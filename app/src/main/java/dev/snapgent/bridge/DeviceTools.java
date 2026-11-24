package dev.snapgent.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.PowerManager;
import android.util.Base64;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * adb-style device control over the accessibility service: inspect the screen,
 * tap, swipe, type, press keys, launch apps and take screenshots.
 *
 * <p>These tools replace the parts of {@code adb shell input}/{@code screencap}/
 * {@code uiautomator dump} an agent normally needs, without a USB link.
 */
final class DeviceTools {
    private static final int MAX_INLINE_SCREENSHOT_BYTES = 1536 * 1024;
    private final Context context;
    private final File root;

    DeviceTools(Context context) {
        this.context = context.getApplicationContext();
        try {
            this.root = this.context.getFilesDir().getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    JSONArray catalog() throws JSONException {
        JSONArray out = new JSONArray();
        out.put(tool("device_status", "Screen size, orientation, screen state, foreground app and whether device control is enabled",
                props()));
        out.put(tool("screen_dump", "Dump the on-screen accessibility hierarchy (adb uiautomator dump equivalent) with text, ids and tap coordinates",
                props("filter", "Only keep nodes whose text, description or id contains this (case-insensitive)",
                        "clickable_only", "Keep only clickable nodes")));
        out.put(tool("screen_screenshot", "Capture the screen as PNG into the workspace (adb screencap equivalent)",
                props("path", "Target PNG path, defaults to screenshots/screen-<timestamp>.png",
                        "inline", "Also return the PNG as base64 (only when smaller than 1.5 MiB)")));
        out.put(tool("screen_tap", "Tap a screen coordinate (adb shell input tap)",
                props("x", "X coordinate in pixels", "y", "Y coordinate in pixels"), "x", "y"));
        out.put(tool("screen_tap_text", "Tap the element whose text or description matches, using ACTION_CLICK then a coordinate fallback",
                props("text", "Text or content description to find",
                        "exact", "Match the whole label instead of a substring"), "text"));
        out.put(tool("screen_swipe", "Swipe or scroll between two points (adb shell input swipe)",
                props("x1", "Start X", "y1", "Start Y", "x2", "End X", "y2", "End Y",
                        "duration_ms", "Gesture duration in milliseconds, default 300"), "x1", "y1", "x2", "y2"));
        out.put(tool("screen_long_press", "Press and hold a coordinate",
                props("x", "X coordinate", "y", "Y coordinate",
                        "duration_ms", "Hold duration in milliseconds, default 700"), "x", "y"));
        out.put(tool("screen_key", "Press a global navigation or hardware key",
                props("key", "One of back, home, recents, notifications, quick_settings, lock, screenshot"), "key"));
        out.put(tool("screen_text", "Type text into the focused input field",
                props("text", "Text to enter"), "text"));
        out.put(tool("screen_wait", "Wait until an element with the given text appears on screen",
                props("text", "Text to wait for",
                        "timeout_ms", "Timeout from 500 to 30000 ms, default 5000"), "text"));
        out.put(tool("app_list", "List launchable applications",
                props("include_system", "Include system apps, default false")));
        out.put(tool("app_launch", "Launch an installed application by package name",
                props("package", "Android package name, for example com.android.settings"), "package"));
        return out;
    }

    static boolean isTool(String name) {
        switch (name) {
            case "device_status":
            case "screen_dump":
            case "screen_screenshot":
            case "screen_tap":
            case "screen_tap_text":
            case "screen_swipe":
            case "screen_long_press":
            case "screen_key":
            case "screen_text":
            case "screen_wait":
            case "app_list":
            case "app_launch":
                return true;
            default:
                return false;
        }
    }

    String execute(String name, JSONObject args) throws IOException, JSONException {
        switch (name) {
            case "device_status": return deviceStatus().toString();
            case "screen_dump": {
                DeviceAutomationService service = require();
                try {
                    return service.dumpTree(args.optString("filter", ""), args.optBoolean("clickable_only", false)).toString();
                } catch (IllegalArgumentException e) {
                    throw new IOException(e.getMessage());
                }
            }
            case "screen_screenshot": return screenshot(args).toString();
            case "screen_tap": {
                DeviceAutomationService service = require();
                if (!service.tap((float) number(args, "x"), (float) number(args, "y"))) {
                    throw new IOException("Tap was not delivered; the gesture was cancelled or timed out");
                }
                return new JSONObject().put("tapped", args.optDouble("x")).put("y", args.optDouble("y")).toString();
            }
            case "screen_tap_text": {
                DeviceAutomationService service = require();
                String label = args.optString("text", "");
                if (label.isEmpty()) throw new IOException("Argument cannot be empty: text");
                String how = service.tapText(label, args.optBoolean("exact", false));
                if (how == null) throw new IOException("No element on screen matches: " + label);
                return new JSONObject().put("matched", label).put("method", how).toString();
            }
            case "screen_swipe": {
                DeviceAutomationService service = require();
                long duration = args.optLong("duration_ms", 300L);
                if (!service.swipe((float) number(args, "x1"), (float) number(args, "y1"),
                        (float) number(args, "x2"), (float) number(args, "y2"), duration)) {
                    throw new IOException("Swipe was not delivered; the gesture was cancelled or timed out");
                }
                return new JSONObject().put("swiped", true).put("durationMs", duration).toString();
            }
            case "screen_long_press": {
                DeviceAutomationService service = require();
                long duration = args.optLong("duration_ms", 700L);
                if (!service.longPress((float) number(args, "x"), (float) number(args, "y"), duration)) {
                    throw new IOException("Long press was not delivered; the gesture was cancelled or timed out");
                }
                return new JSONObject().put("longPressed", true).put("durationMs", duration).toString();
            }
            case "screen_key": {
                DeviceAutomationService service = require();
                String key = args.optString("key", "");
                if (key.isEmpty()) throw new IOException("Argument cannot be empty: key");
                if (!service.globalAction(key)) throw new IOException("Unsupported key or action refused: " + key);
                return new JSONObject().put("key", key).put("sent", true).toString();
            }
            case "screen_text": {
                DeviceAutomationService service = require();
                String text = args.optString("text", "");
                if (text.isEmpty()) throw new IOException("Argument cannot be empty: text");
                String method = service.setText(text);
                if (method == null) {
                    throw new IOException("No focused editable field accepted the text; tap the field first");
                }
                return new JSONObject().put("typed", text.length()).put("method", method).toString();
            }
            case "screen_wait": {
                DeviceAutomationService service = require();
                String text = args.optString("text", "");
                if (text.isEmpty()) throw new IOException("Argument cannot be empty: text");
                long timeout = args.optLong("timeout_ms", 5000L);
                String matched;
                try {
                    matched = service.waitForText(text, timeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Wait interrupted");
                }
                if (matched == null) throw new IOException("Timed out after " + timeout + " ms waiting for: " + text);
                return new JSONObject().put("matched", matched).put("timeoutMs", timeout).toString();
            }
            case "app_list": return appList(args.optBoolean("include_system", false)).toString();
            case "app_launch": {
                String packageName = args.optString("package", "");
                if (packageName.isEmpty()) throw new IOException("Argument cannot be empty: package");
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch == null) throw new IOException("No launchable activity for package: " + packageName);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Android blocks activity starts from a background app. A visible
                // 1x1 overlay is the exemption an ordinary app can grant itself.
                boolean allowed = LaunchOverlay.isAllowed(context);
                LaunchOverlay.ensure(context);
                if (allowed) sleepQuietly(300L);
                context.startActivity(launch);
                boolean foreground = false;
                for (int attempt = 0; attempt < 8 && !foreground; attempt++) {
                    sleepQuietly(250L);
                    DeviceAutomationService service = DeviceAutomationService.get();
                    foreground = service != null && service.windowPackages().contains(packageName);
                }
                JSONObject result = new JSONObject().put("launched", packageName).put("foreground", foreground);
                if (!foreground && !allowed) {
                    result.put("blocked", "Android blocks activity starts from the background")
                            .put("fix", "Grant Display over other apps to Snapgent Bridge"
                                    + " (Settings > Device control) so the app can be brought to the front");
                }
                return result.toString();
            }
            default: throw new IOException("Unknown device tool: " + name);
        }
    }

    private DeviceAutomationService require() throws IOException {
        DeviceAutomationService service = DeviceAutomationService.get();
        if (service == null) {
            throw new IOException("Device control is off. Enable Snapgent Bridge in"
                    + " Settings > Accessibility (the bridge Settings page has a shortcut), then retry.");
        }
        return service;
    }

    private JSONObject deviceStatus() throws JSONException {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean interactive = power != null && power.isInteractive();
        int orientation = context.getResources().getConfiguration().orientation;
        JSONObject result = new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("screenWidth", metrics.widthPixels)
                .put("screenHeight", metrics.heightPixels)
                .put("densityDpi", metrics.densityDpi)
                .put("orientation", orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait")
                .put("screenOn", interactive)
                .put("deviceControlReady", DeviceAutomationService.isReady())
                .put("backgroundLaunchAllowed", LaunchOverlay.isAllowed(context))
                .put("foregroundPackage", DeviceAutomationService.foregroundPackage())
                .put("screenshotSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        DeviceAutomationService service = DeviceAutomationService.get();
        if (service != null) {
            JSONArray windows = new JSONArray();
            for (String name : service.windowPackages()) windows.put(name);
            result.put("visibleWindows", windows);
        }
        return result;
    }

    private JSONObject screenshot(JSONObject args) throws IOException, JSONException {
        DeviceAutomationService service = require();
        String requested = args.optString("path", "").trim();
        if (requested.isEmpty()) {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            requested = "screenshots/screen-" + stamp + ".png";
        }
        File target = resolve(requested);
        JSONObject result = service.screenshot(target);
        result.put("path", relative(target));
        if (args.optBoolean("inline", false)) {
            long length = target.length();
            if (length > MAX_INLINE_SCREENSHOT_BYTES) {
                result.put("inline", false)
                        .put("inlineSkipped", "PNG is " + length + " bytes, read it with read_file_base64");
            } else {
                byte[] bytes = new byte[(int) length];
                try (java.io.FileInputStream input = new java.io.FileInputStream(target)) {
                    int offset = 0;
                    while (offset < bytes.length) {
                        int count = input.read(bytes, offset, bytes.length - offset);
                        if (count < 0) break;
                        offset += count;
                    }
                }
                result.put("inline", true).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            }
        }
        return result;
    }

    private JSONArray appList(boolean includeSystem) throws JSONException {
        PackageManager manager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = manager.queryIntentActivities(launcher, 0);
        List<JSONObject> entries = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            ApplicationInfo application = info.activityInfo.applicationInfo;
            boolean system = (application.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !includeSystem) continue;
            entries.add(new JSONObject()
                    .put("package", info.activityInfo.packageName)
                    .put("label", String.valueOf(info.loadLabel(manager)))
                    .put("system", system));
        }
        Collections.sort(entries, (left, right) -> left.optString("label").compareToIgnoreCase(right.optString("label")));
        JSONArray result = new JSONArray();
        for (JSONObject entry : entries) result.put(entry);
        return result;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double number(JSONObject args, String name) throws IOException {
        Object value = args.opt(name);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                // fall through to the shared error
            }
        }
        throw new IOException("Expected a number for argument: " + name);
    }

    private File resolve(String relativePath) throws IOException {
        if (relativePath.startsWith("/") || relativePath.indexOf('\u0000') >= 0) {
            throw new SecurityException("Only relative paths inside app storage are allowed");
        }
        File target = new File(root, relativePath).getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new SecurityException("Path escapes app-private storage");
        }
        return target;
    }

    private String relative(File file) {
        String prefix = root.getPath() + File.separator;
        String path = file.getPath();
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static JSONObject props(String... pairs) throws JSONException {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i];
            String type = key.equals("inline") || key.equals("exact") || key.equals("clickable_only")
                    || key.equals("include_system") ? "boolean"
                    : key.equals("x") || key.equals("y") || key.equals("x1") || key.equals("y1")
                    || key.equals("x2") || key.equals("y2") || key.equals("duration_ms")
                    || key.equals("timeout_ms") ? "number" : "string";
            result.put(key, new JSONObject().put("type", type).put("description", pairs[i + 1]));
        }
        return result;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required) throws JSONException {
        JSONArray fields = new JSONArray();
        for (String field : required) fields.put(field);
        return new JSONObject().put("name", name).put("description", description)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties)
                        .put("required", fields).put("additionalProperties", false));
    }
}
