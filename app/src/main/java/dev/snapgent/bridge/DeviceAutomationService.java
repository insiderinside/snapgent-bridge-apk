package dev.snapgent.bridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Accessibility service that gives connected agents the device-control half of
 * adb: gestures, hardware keys, text input, a UI hierarchy dump and screenshots.
 *
 * <p>Everything here runs through the platform accessibility APIs, so no root,
 * no USB and no external adb server are involved. The service only acts when a
 * tool asks it to; it does not scrape or report anything on its own beyond the
 * foreground package name.
 */
public final class DeviceAutomationService extends AccessibilityService {
    private static final String TAG = "DeviceAutomation";
    /** Screenshots closer together than this are rejected by the framework. */
    private static final long SCREENSHOT_MIN_GAP_MS = 450;
    private static final int MAX_DUMP_NODES = 800;
    private static final int MAX_DUMP_DEPTH = 60;

    private static volatile DeviceAutomationService instance;
    private static volatile String foregroundPackage = "";
    private static volatile long lastScreenshotAt = 0L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();

    static boolean isReady() {
        return instance != null;
    }

    static DeviceAutomationService get() {
        return instance;
    }

    static String foregroundPackage() {
        return foregroundPackage == null ? "" : foregroundPackage;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        BridgeLog.info(TAG, "Accessibility service connected; device control tools are live");
        // If this process was reborn after an eviction, the socket is still
        // down; Auto-start brings it back so the extension can reconnect.
        BridgeService.startIfEnabled(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        BridgeLog.warn(TAG, "Accessibility service unbound; device control tools are offline");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        screenshotExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence name = event.getPackageName();
        if (name != null && name.length() > 0) foregroundPackage = name.toString();
    }

    @Override
    public void onInterrupt() {
        // Nothing to cancel: gestures are short and dispatched per request.
    }

    /** One-finger tap at a screen coordinate. */
    boolean tap(float x, float y) {
        return gesture(x, y, x, y, 60L, 3000L);
    }

    /** Long press at a screen coordinate. */
    boolean longPress(float x, float y, long durationMs) {
        return gesture(x, y, x, y, Math.max(400L, Math.min(durationMs, 10000L)), 12000L);
    }

    /** Drag from one point to another. */
    boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        return gesture(x1, y1, x2, y2, Math.max(60L, Math.min(durationMs, 10000L)), 15000L);
    }

    private boolean gesture(float x1, float y1, float x2, float y2, long durationMs, long timeoutMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, durationMs);
        GestureDescription description = new GestureDescription.Builder().addStroke(stroke).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean(false);
        boolean dispatched = dispatchGesture(description, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                completed.set(true);
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, main);
        if (!dispatched) return false;
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return completed.get();
    }

    /** Global hardware/navigation actions: back, home, recents, shade, settings. */
    boolean globalAction(String key) {
        int action;
        switch (key) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock": action = GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot": action = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            default: return false;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        main.post(() -> {
            try {
                ok.set(performGlobalAction(action));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(3000L, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok.get();
    }

    /**
     * Type into the focused text field.
     *
     * @return the strategy that worked, or {@code null} when the field refused both
     *         ACTION_SET_TEXT and ACTION_PASTE.
     */
    String setText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null) {
            target = firstEditable(root);
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        if (target == null) return null;
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return "set_text";

        // Some custom widgets ignore ACTION_SET_TEXT but accept a paste.
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("snapgent", text));
            if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return "paste";
        }
        return null;
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_DUMP_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node == null) continue;
            if (node.isEditable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    /** Flat JSON dump of the visible hierarchy, top-left origin coordinates. */
    JSONObject dumpTree(String filter, boolean clickableOnly) throws JSONException {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IllegalArgumentException("No active window to dump");
        JSONArray nodes = new JSONArray();
        ArrayDeque<Row> queue = new ArrayDeque<>();
        queue.add(new Row(root, 0));
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_DUMP_NODES) {
            Row row = queue.removeFirst();
            AccessibilityNodeInfo node = row.node;
            if (node == null) continue;
            JSONObject entry = describe(node, row.depth);
            String haystack = (entry.optString("text") + " " + entry.optString("description")
                    + " " + entry.optString("id")).toLowerCase();
            boolean matches = filter == null || filter.isEmpty() || haystack.contains(filter.toLowerCase());
            boolean clickable = entry.optBoolean("clickable");
            if (matches && (!clickableOnly || clickable) && entry.length() > 2) nodes.put(entry);
            if (row.depth >= MAX_DUMP_DEPTH) continue;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(new Row(child, row.depth + 1));
            }
        }
        return new JSONObject()
                .put("package", foregroundPackage)
                .put("nodes", nodes)
                .put("count", nodes.length());
    }

    private JSONObject describe(AccessibilityNodeInfo node, int depth) throws JSONException {
        String text = trim(node.getText());
        String description = trim(node.getContentDescription());
        String viewId = node.getViewIdResourceName();
        if (viewId != null && viewId.indexOf('/') >= 0) viewId = viewId.substring(viewId.indexOf('/') + 1);
        String className = node.getClassName() == null ? "" : node.getClassName().toString();
        if (className.indexOf('.') >= 0) className = className.substring(className.lastIndexOf('.') + 1);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONObject entry = new JSONObject()
                .put("text", text)
                .put("description", description)
                .put("class", className)
                .put("id", viewId == null ? "" : viewId)
                .put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom)
                .put("center", ((bounds.left + bounds.right) / 2) + "," + ((bounds.top + bounds.bottom) / 2))
                .put("clickable", node.isClickable())
                .put("editable", node.isEditable())
                .put("scrollable", node.isScrollable())
                .put("focused", node.isFocused())
                .put("checked", node.isCheckable() && node.isChecked());
        // Move the most useful keys to the front for readable logs.
        return entry;
    }

    private static String trim(CharSequence value) {
        if (value == null) return "";
        String text = value.toString().replace('\n', ' ').trim();
        return text.length() > 180 ? text.substring(0, 180) + "…" : text;
    }

    /**
     * Click the first node whose text or content description contains the needle.
     *
     * @return "action" when a clickable node accepted ACTION_CLICK, "tap" when the
     *         gesture fallback hit its centre, {@code null} when nothing matched.
     */
    String tapText(String needle, boolean exact) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        Match match = findMatch(root, needle, exact);
        if (match == null) return null;
        if (clickNode(match.node)) return "action";
        Rect bounds = new Rect();
        match.node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return null;
        tap(bounds.exactCenterX(), bounds.exactCenterY());
        return "tap";
    }

    /** Wait until a node with the given text appears; returns the matched text or null. */
    String waitForText(String needle, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(500L, Math.min(timeoutMs, 30000L));
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                Match match = findMatch(root, needle, false);
                if (match != null) return match.label;
            }
            Thread.sleep(250L);
        }
        return null;
    }

    private static final class Match {
        final AccessibilityNodeInfo node;
        final String label;

        Match(AccessibilityNodeInfo node, String label) {
            this.node = node;
            this.label = label;
        }
    }

    private Match findMatch(AccessibilityNodeInfo root, String needle, boolean exact) {
        String wanted = needle.toLowerCase();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        Match fallback = null;
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_DUMP_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node == null) continue;
            String text = trim(node.getText());
            String description = trim(node.getContentDescription());
            for (String candidate : new String[]{text, description}) {
                if (candidate.isEmpty()) continue;
                String lower = candidate.toLowerCase();
                boolean hit = exact ? lower.equals(wanted) : lower.contains(wanted);
                if (!hit) continue;
                if (candidate.equalsIgnoreCase(needle) || labelClickable(node)) {
                    return new Match(node, candidate);
                }
                if (fallback == null) fallback = new Match(node, candidate);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return fallback;
    }

    private boolean labelClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable()) return true;
            current = current.getParent();
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return false;
    }

    /** Capture the current screen into a PNG file. Requires Android 11 or newer. */
    JSONObject screenshot(File target) throws IOException, JSONException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IOException("Screenshots need Android 11 (API 30) or newer; this device reports API "
                    + Build.VERSION.SDK_INT);
        }
        long wait = SCREENSHOT_MIN_GAP_MS - (System.currentTimeMillis() - lastScreenshotAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Bitmap bitmap = null;
        IOException failure = null;
        for (int attempt = 0; attempt < 3 && bitmap == null; attempt++) {
            try {
                bitmap = captureBitmap();
            } catch (IOException e) {
                failure = e;
                if (!e.getMessage().contains("interval")) break;
                try {
                    Thread.sleep(SCREENSHOT_MIN_GAP_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (bitmap == null) {
            throw failure == null ? new IOException("Screenshot capture returned no bitmap") : failure;
        }
        lastScreenshotAt = System.currentTimeMillis();
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create screenshot directory");
            }
            try (FileOutputStream output = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Bitmap could not be encoded as PNG");
                }
                output.getFD().sync();
            }
            return new JSONObject()
                    .put("width", bitmap.getWidth())
                    .put("height", bitmap.getHeight())
                    .put("bytes", target.length());
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap captureBitmap() throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        final AtomicReference<IOException> error = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                try {
                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (wrapped == null) {
                        error.set(new IOException("Screenshot buffer could not be wrapped"));
                        return;
                    }
                    Bitmap copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) error.set(new IOException("Screenshot copy failed"));
                    else bitmap.set(copy);
                } finally {
                    buffer.close();
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(int code) {
                error.set(new IOException("Screenshot failed (code " + code + ")"
                        + (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? " interval" : "")));
                latch.countDown();
            }
        });
        try {
            if (!latch.await(8000L, TimeUnit.MILLISECONDS)) {
                throw new IOException("Screenshot timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Screenshot interrupted");
        }
        if (error.get() != null && bitmap.get() == null) throw error.get();
        Bitmap result = bitmap.get();
        if (result == null) throw new IOException("Screenshot returned no pixels");
        return result;
    }

    private static final class Row {
        final AccessibilityNodeInfo node;
        final int depth;

        Row(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    /** Visible windows, so an agent can tell which app it is driving. */
    List<String> windowPackages() {
        List<String> packages = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (android.view.accessibility.AccessibilityWindowInfo window : getWindows()) {
                if (window.getRoot() == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                CharSequence name = root.getPackageName();
                if (name != null && !packages.contains(name.toString())) packages.add(name.toString());
            }
        }
        return packages;
    }
}
