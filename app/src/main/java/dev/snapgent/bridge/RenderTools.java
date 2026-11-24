package dev.snapgent.bridge;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-device rendering without Termux, Playwright or FFmpeg.
 *
 * <p>Still frames come from an off-screen {@link WebView}: HTML/Canvas/Three.js is
 * loaded into a software-layered WebView laid out at the requested size, then
 * drawn into a {@link Bitmap} and written as PNG. Movies are encoded with
 * {@link MediaCodec} (H.264) and {@link MediaMuxer} (MP4), optionally muxing a
 * 16-bit PCM WAV soundtrack encoded to AAC.
 *
 * <p>This replaces the two gaps in the Rekam Teknik production table: "render
 * still via Playwright" and "render MP4 via FFmpeg". Rendering is deterministic
 * when the page exposes {@code window.__snapgentSeek(ms)}; otherwise the page is
 * simply given time to settle before each capture.
 */
final class RenderTools {

    private static final int MAX_DIMENSION = 3840;
    private static final int MAX_FRAMES = 1800;
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;
    private static final int LOAD_TIMEOUT_MS = 30_000;
    private static final int CAPTURE_TIMEOUT_MS = 30_000;
    private static final int MAX_HTML_BYTES = 8 * 1024 * 1024;

    /**
     * Capture path. {@code gpu} keeps hardware acceleration on so WebGL/Three.js is
     * rendered by the GPU, and reads the frame back through {@link RenderNode} (or a
     * virtual display); {@code software} is the pre-0.1.8 path that forced
     * {@link View#LAYER_TYPE_SOFTWARE} and drew the WebView into a software bitmap,
     * which silently disables WebGL.
     */
    private static final String MODE_GPU = "gpu";
    private static final String MODE_SOFTWARE = "software";

    private static final String MODE_PROPERTY = "Capture path: 'gpu' keeps WebGL on the GPU (default), "
            + "'software' forces the legacy software layer";

    private final Context context;

    RenderTools(Context context) {
        this.context = context;
    }

    static boolean isTool(String name) {
        return "render_still".equals(name)
                || "render_video".equals(name)
                || "encode_frames".equals(name)
                || "render_info".equals(name);
    }

    JSONArray catalog() throws JSONException {
        JSONArray tools = new JSONArray();

        JSONObject stillProps = new JSONObject()
                .put("path", property("string", "HTML file in app-private storage (mutually exclusive with html)"))
                .put("html", property("string", "Inline HTML document (mutually exclusive with path)"))
                .put("width", property("integer", "Viewport width in pixels (default 1920)"))
                .put("height", property("integer", "Viewport height in pixels (default 1080)"))
                .put("seek_ms", property("integer", "Value passed to window.__snapgentSeek(ms) before capture"))
                .put("wait_ms", property("integer", "Settle time after load/seek before the frame is drawn (default 350)"))
                .put("render_mode", property("string", MODE_PROPERTY))
                .put("output", property("string", "PNG path relative to app-private storage (default render/still.png)"));

        tools.put(new JSONObject()
                .put("name", "render_still")
                .put("description", "Render one HTML/Canvas/Three.js frame in an off-screen WebView and save it as PNG. "
                        + "render_mode=gpu (default) keeps WebGL on the GPU and reads the frame back through RenderNode "
                        + "or a virtual display; render_mode=software falls back to the legacy software layer. "
                        + "Replaces Playwright screenshots on device.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", stillProps).put("additionalProperties", false)));

        JSONObject videoProps = new JSONObject()
                .put("path", property("string", "HTML file in app-private storage (mutually exclusive with html)"))
                .put("html", property("string", "Inline HTML document (mutually exclusive with path)"))
                .put("width", property("integer", "Frame width in pixels (default 1920)"))
                .put("height", property("integer", "Frame height in pixels (default 1080)"))
                .put("fps", property("integer", "Frames per second (default 30, max 60)"))
                .put("duration_ms", property("integer", "Total duration in milliseconds (default 3000)"))
                .put("wait_ms", property("integer", "Settle time after each seek (default 40)"))
                .put("render_mode", property("string", MODE_PROPERTY))
                .put("bitrate", property("integer", "Video bitrate in bits per second (default 8000000)"))
                .put("audio_path", property("string", "Optional 16-bit PCM WAV file in app-private storage to mux as AAC"))
                .put("output", property("string", "MP4 path relative to app-private storage (default render/video.mp4)"));

        tools.put(new JSONObject()
                .put("name", "render_video")
                .put("description", "Render an HTML/Canvas/Three.js animation to an H.264 MP4 on device using MediaCodec. "
                        + "Calls window.__snapgentSeek(ms) when the page defines it; render_mode=gpu (default) captures "
                        + "through the GPU so WebGL survives; optional WAV soundtrack is muxed as AAC.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", videoProps).put("additionalProperties", false)));

        JSONObject framesProps = new JSONObject()
                .put("dir", property("string", "Directory in app-private storage that holds the PNG frames"))
                .put("prefix", property("string", "File-name prefix of the frames (default \"frame-\")"))
                .put("suffix", property("string", "File-name suffix (default \".png\")"))
                .put("fps", property("integer", "Frames per second (default 30, max 60)"))
                .put("bitrate", property("integer", "Video bitrate in bits per second (default 8000000)"))
                .put("audio_path", property("string", "Optional 16-bit PCM WAV file to mux as AAC"))
                .put("output", property("string", "MP4 path relative to app-private storage (default render/from-frames.mp4)"));

        tools.put(new JSONObject()
                .put("name", "encode_frames")
                .put("description", "Encode an existing PNG sequence from app-private storage into an H.264 MP4 "
                        + "(MediaCodec + MediaMuxer, optional WAV soundtrack).")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", framesProps).put("additionalProperties", false)));

        tools.put(new JSONObject()
                .put("name", "render_info")
                .put("description", "Report the on-device rendering capabilities: WebView version, H.264/AAC encoders, "
                        + "supported frame sizes and output formats")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()).put("additionalProperties", false)));

        return tools;
    }

    String execute(String name, JSONObject args) throws IOException, JSONException {
        switch (name) {
            case "render_info":
                return renderInfo();
            case "render_still":
                return renderStill(args);
            case "render_video":
                return renderVideo(args);
            case "encode_frames":
                return encodeFrames(args);
            default:
                throw new IOException("Unknown render tool: " + name);
        }
    }

    // ------------------------------------------------------------------ tools

    private String renderInfo() throws JSONException {
        JSONObject info = new JSONObject();
        info.put("webview", WebView.getCurrentWebViewPackage() == null
                ? "unknown"
                : WebView.getCurrentWebViewPackage().versionName + " (" + WebView.getCurrentWebViewPackage().packageName + ")");

        JSONArray encoders = new JSONArray();
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = list.getCodecInfos();
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) continue;
            for (String type : codecInfo.getSupportedTypes()) {
                if (!"video/avc".equals(type) && !"audio/mp4a-latm".equals(type)) continue;
                JSONObject entry = new JSONObject().put("name", codecInfo.getName()).put("type", type);
                JSONArray sizes = new JSONArray();
                if ("video/avc".equals(type)) {
                    MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(type);
                    int maxW = 0;
                    int maxH = 0;
                    for (MediaCodecInfo.VideoCapabilities vc : caps.getVideoCapabilities() == null
                            ? new MediaCodecInfo.VideoCapabilities[0]
                            : new MediaCodecInfo.VideoCapabilities[]{caps.getVideoCapabilities()}) {
                        maxW = Math.max(maxW, vc.getSupportedWidths().getUpper());
                        maxH = Math.max(maxH, vc.getSupportedHeights().getUpper());
                    }
                    sizes.put(maxW).put(maxH);
                }
                entry.put("maxSize", sizes);
                encoders.put(entry);
            }
        }
        info.put("encoders", encoders);
        info.put("muxer", "MP4 (H.264 + AAC)");
        info.put("stillFormat", "PNG");
        info.put("renderModes", new JSONArray().put(MODE_GPU).put(MODE_SOFTWARE));
        info.put("overlayAllowed", Settings.canDrawOverlays(context));
        info.put("capturePaths", new JSONArray()
                .put("gpu: RenderNode + HardwareRenderer + ImageReader (API 29+)")
                .put("gpu: VirtualDisplay + Presentation + ImageReader")
                .put("software: forced LAYER_TYPE_SOFTWARE + View.draw"));
        info.put("webgl", probeWebGl());
        info.put("note", "render_mode=gpu keeps hardware acceleration on, so WebGL runs on the GPU; the frame is "
                + "read back from a RenderNode or a virtual display. render_mode=software forces the legacy "
                + "software layer, which disables WebGL. WebView (Chromium) is the same engine family as "
                + "Playwright but not the desktop build. Animation is deterministic when the page exposes "
                + "window.__snapgentSeek(ms).");
        return info.toString(2);
    }

    /** WebGL renderer string from a throwaway GPU-mode WebView ("none" when WebGL is unavailable). */
    private String probeWebGl() {
        WebSession session = null;
        try {
            WebDocument blank = new WebDocument(
                    "<!doctype html><html><body style=\"margin:0;background:#000\"></body></html>",
                    "file://" + context.getFilesDir().getAbsolutePath() + "/");
            session = WebSession.open(context, blank, 64, 64, MODE_GPU);
            return session.webgl == null ? "unknown" : session.webgl;
        } catch (Throwable t) {
            return "unavailable: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        } finally {
            if (session != null) session.close();
        }
    }

    private String renderStill(JSONObject args) throws IOException, JSONException {
        WebDocument doc = WebDocument.from(context, args);
        int width = dim(args.optInt("width", DEFAULT_WIDTH));
        int height = dim(args.optInt("height", DEFAULT_HEIGHT));
        int waitMs = clamp(args.optInt("wait_ms", 350), 0, 30_000);
        int seekMs = args.optInt("seek_ms", -1);
        String mode = renderMode(args);
        String output = args.optString("output", "render/still.png");
        File out = resolve(output);
        ensureParent(out);

        WebSession session = WebSession.open(context, doc, width, height, mode);
        try {
            Bitmap bitmap = session.capture(seekMs, waitMs);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("PNG encoding failed");
                }
            } finally {
                bitmap.recycle();
            }
        } finally {
            session.close();
        }

        return new JSONObject()
                .put("output", relative(out))
                .put("width", width)
                .put("height", height)
                .put("bytes", out.length())
                .put("format", "png")
                .put("render_mode", session.modeUsed)
                .put("requested_mode", mode)
                .put("webgl", session.webgl)
                .toString(2);
    }

    private String renderVideo(JSONObject args) throws IOException, JSONException {
        WebDocument doc = WebDocument.from(context, args);
        int width = dim(args.optInt("width", DEFAULT_WIDTH));
        int height = dim(args.optInt("height", DEFAULT_HEIGHT));
        int fps = clamp(args.optInt("fps", 30), 1, 60);
        int durationMs = clamp(args.optInt("duration_ms", 3000), 50, 600_000);
        int waitMs = clamp(args.optInt("wait_ms", 40), 0, 5_000);
        int bitrate = clamp(args.optInt("bitrate", 8_000_000), 200_000, 50_000_000);
        String mode = renderMode(args);
        String output = args.optString("output", "render/video.mp4");
        File out = resolve(output);
        ensureParent(out);
        File audio = args.has("audio_path") ? resolve(args.optString("audio_path")) : null;

        int frameCount = Math.max(1, (int) Math.round(durationMs / 1000.0 * fps));
        if (frameCount > MAX_FRAMES) {
            throw new IOException("Requested " + frameCount + " frames; the on-device limit is " + MAX_FRAMES);
        }

        WebSession session = WebSession.open(context, doc, width, height, mode);
        VideoEncoder encoder = new VideoEncoder(width, height, fps, bitrate, out, audio);
        try {
            for (int i = 0; i < frameCount; i++) {
                long frameMs = Math.round(i * 1000.0 / fps);
                Bitmap frame = session.capture((int) frameMs, waitMs);
                try {
                    encoder.writeFrame(frame, Math.round(frameMs * 1000.0));
                } finally {
                    frame.recycle();
                }
            }
            encoder.finish();
        } catch (IOException | RuntimeException e) {
            encoder.abort();
            throw e;
        } finally {
            session.close();
        }

        return new JSONObject()
                .put("output", relative(out))
                .put("width", width)
                .put("height", height)
                .put("fps", fps)
                .put("frames", frameCount)
                .put("duration_ms", durationMs)
                .put("audio", audio != null)
                .put("bytes", out.length())
                .put("format", "mp4")
                .put("render_mode", session.modeUsed)
                .put("requested_mode", mode)
                .put("webgl", session.webgl)
                .toString(2);
    }

    private String encodeFrames(JSONObject args) throws IOException, JSONException {
        File dir = resolve(args.optString("dir", "render"));
        if (!dir.isDirectory()) throw new IOException("Frame directory not found: " + args.optString("dir", "render"));
        String prefix = args.optString("prefix", "frame-");
        String suffix = args.optString("suffix", ".png");
        int fps = clamp(args.optInt("fps", 30), 1, 60);
        int bitrate = clamp(args.optInt("bitrate", 8_000_000), 200_000, 50_000_000);
        String output = args.optString("output", "render/from-frames.mp4");
        File out = resolve(output);
        ensureParent(out);
        File audio = args.has("audio_path") ? resolve(args.optString("audio_path")) : null;

        File[] files = dir.listFiles(file -> file.isFile()
                && file.getName().startsWith(prefix)
                && file.getName().endsWith(suffix));
        if (files == null || files.length == 0) {
            throw new IOException("No *" + suffix + " files with prefix '" + prefix + "' in " + args.optString("dir", "render"));
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        if (files.length > MAX_FRAMES) {
            throw new IOException("Found " + files.length + " frames; the on-device limit is " + MAX_FRAMES);
        }

        Bitmap probe = android.graphics.BitmapFactory.decodeFile(files[0].getAbsolutePath());
        if (probe == null) throw new IOException("Cannot decode " + files[0].getName());
        int width = probe.getWidth();
        int height = probe.getHeight();
        probe.recycle();

        VideoEncoder encoder = new VideoEncoder(width, height, fps, bitrate, out, audio);
        try {
            for (int i = 0; i < files.length; i++) {
                Bitmap frame = android.graphics.BitmapFactory.decodeFile(files[i].getAbsolutePath());
                if (frame == null) throw new IOException("Cannot decode " + files[i].getName());
                if (frame.getWidth() != width || frame.getHeight() != height) {
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
                    frame.recycle();
                    frame = scaled;
                }
                try {
                    encoder.writeFrame(frame, Math.round(i * 1_000_000.0 / fps));
                } finally {
                    frame.recycle();
                }
            }
            encoder.finish();
        } catch (IOException | RuntimeException e) {
            encoder.abort();
            throw e;
        }

        return new JSONObject()
                .put("output", relative(out))
                .put("width", width)
                .put("height", height)
                .put("fps", fps)
                .put("frames", files.length)
                .put("audio", audio != null)
                .put("bytes", out.length())
                .put("format", "mp4")
                .toString(2);
    }

    // ------------------------------------------------------------- web view

    /** HTML document plus the base URL used to resolve relative assets. */
    private static final class WebDocument {
        final String html;
        final String baseUrl;

        private WebDocument(String html, String baseUrl) {
            this.html = html;
            this.baseUrl = baseUrl;
        }

        static WebDocument from(Context context, JSONObject args) throws IOException, JSONException {
            boolean hasPath = args.has("path");
            boolean hasHtml = args.has("html");
            if (hasPath == hasHtml) throw new IOException("Provide exactly one of 'path' or 'html'");
            if (hasHtml) {
                String html = args.optString("html", "");
                if (html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
                    throw new IOException("Inline HTML exceeds " + MAX_HTML_BYTES + " bytes");
                }
                return new WebDocument(html, "file://" + context.getFilesDir().getAbsolutePath() + "/");
            }
            File file = resolveStatic(context, args.optString("path", ""));
            if (!file.isFile()) throw new IOException("HTML file not found: " + args.optString("path", ""));
            if (file.length() > MAX_HTML_BYTES) throw new IOException("HTML file exceeds " + MAX_HTML_BYTES + " bytes");
            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return new WebDocument(html, "file://" + file.getParentFile().getAbsolutePath() + "/");
        }
    }

    /** Off-screen WebView driven from the main looper. */
    private static final class WebSession {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());

        private final Context context;
        private final int width;
        private final int height;
        private final String mode;
        private WebView webView;
        private WindowManager windowManager;
        private boolean windowAttached;
        private ImageReader displayReader;
        private VirtualDisplay virtualDisplay;
        private Presentation presentation;
        private boolean softwareCommitted;

        /** Capture path actually used by the last frame: gpu, gpu-virtualdisplay or software. */
        private String modeUsed = MODE_GPU;
        /** WebGL renderer string reported by the page's context, e.g. "Adreno (TM) 730". */
        private String webgl = "unknown";

        private WebSession(Context context, int width, int height, String mode) {
            this.context = context;
            this.width = width;
            this.height = height;
            this.mode = mode;
        }

        static WebSession open(Context context, WebDocument doc, int width, int height, String mode)
                throws IOException {
            WebSession session = new WebSession(context, width, height, mode);
            session.create(doc);
            return session;
        }

        private void create(WebDocument doc) throws IOException {
            final CountDownLatch ready = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();

            MAIN.post(() -> {
                try {
                    // Render at 1 CSS pixel per output pixel. With the phone's own density
                    // (often 440 dpi) a 1920x1080 request laid the page out as 1920 CSS px
                    // -- about 5300 device pixels -- so only the top-left corner was
                    // captured and Chromium dropped tiles for lack of memory.
                    Context renderContext = pixelContext(context, width, height);
                    WebView view = new WebView(renderContext);
                    WebSettings settings = view.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true);
                    settings.setAllowFileAccess(true);
                    try {
                        settings.setAllowFileAccessFromFileURLs(true);
                        settings.setAllowUniversalAccessFromFileURLs(true);
                    } catch (Throwable ignored) {
                        // Deprecated in newer WebView builds; file access still works for simple documents.
                    }
                    settings.setMediaPlaybackRequiresUserGesture(false);
                    view.setBackgroundColor(Color.TRANSPARENT);
                    if (MODE_SOFTWARE.equals(mode)) {
                        // Legacy path: the software layer is what let View.draw() read the
                        // frame, and it is also what turned WebGL off.
                        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                    }
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    view.layout(0, 0, width, height);
                    view.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView v, String url) {
                            ready.countDown();
                        }

                        @Override
                        public void onReceivedError(WebView v, int errorCode, String description, String failingUrl) {
                            failure.compareAndSet(null, new IOException("WebView failed to load " + failingUrl + ": " + description));
                            ready.countDown();
                        }
                    });
                    webView = view;
                    if (MODE_GPU.equals(mode)) {
                        try {
                            attachWindow(view);
                        } catch (Throwable t) {
                            BridgeLog.warn("RenderTools", "No GPU window for this render (" + describe(t)
                                    + "); falling back to the software capture path");
                        }
                    }
                    view.loadDataWithBaseURL(doc.baseUrl, doc.html, "text/html", "UTF-8", null);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    ready.countDown();
                }
            });

            try {
                if (!ready.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("WebView did not finish loading within " + LOAD_TIMEOUT_MS + " ms");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while loading the page");
            }
            if (failure.get() != null) {
                Throwable t = failure.get();
                throw new IOException("WebView error: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
            }
            if (MODE_GPU.equals(mode)) probeWebGl();
        }

        /**
         * Puts the WebView in a real window so Chromium's compositor has a surface to
         * draw WebGL into. The window sits off-screen: nothing is visible to the user,
         * and the pixels are read back through a RenderNode or a virtual display.
         */
        /**
         * A context whose density makes one CSS pixel one output pixel, so a
         * {@code width x height} request lays the page out exactly that size.
         */
        private static Context pixelContext(Context base, int width, int height) {
            android.content.res.Configuration configuration =
                    new android.content.res.Configuration(base.getResources().getConfiguration());
            configuration.densityDpi = DisplayMetrics.DENSITY_MEDIUM;
            configuration.screenWidthDp = width;
            configuration.screenHeightDp = height;
            configuration.smallestScreenWidthDp = Math.min(width, height);
            return base.createConfigurationContext(configuration);
        }

        private void attachWindow(WebView view) throws IOException {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                throw new IOException("overlay windows need Android 8.0 or newer");
            }
            if (!Settings.canDrawOverlays(context)) {
                throw new IOException("the 'Display over other apps' grant is missing");
            }
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) throw new IOException("no window manager");
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = -(width + 64);
            params.y = 0;
            params.setTitle("snapgent-render");
            manager.addView(view, params);
            windowManager = manager;
            windowAttached = true;
        }

        Bitmap capture(int seekMs, int waitMs) throws IOException {
            if (MODE_GPU.equals(mode) && !softwareCommitted) {
                Bitmap gpu = grabAfterDelay(seekMs, waitMs, this::grabHardwareNode);
                if (gpu != null) {
                    modeUsed = "gpu";
                    return gpu;
                }
                Bitmap virtual = grabAfterDelay(seekMs, waitMs, this::grabVirtualDisplay);
                if (virtual != null) {
                    modeUsed = "gpu-virtualdisplay";
                    return virtual;
                }
                BridgeLog.warn("RenderTools", "GPU capture read back no usable frame; continuing on the software path");
                softwareCommitted = true;
            }
            Bitmap software = grabAfterDelay(seekMs, waitMs, this::grabSoftware);
            if (software == null) throw new IOException("Frame capture produced no bitmap");
            modeUsed = MODE_SOFTWARE;
            return software;
        }

        private interface FrameSource {
            Bitmap grab() throws Exception;
        }

        /** Seeks, waits on the main looper, then runs one grab; null means "that path failed". */
        private Bitmap grabAfterDelay(int seekMs, int waitMs, FrameSource source) throws IOException {
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<Bitmap> shot = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();

            MAIN.post(() -> {
                try {
                    if (seekMs >= 0) {
                        String js = "(function(){try{if(typeof window.__snapgentSeek==='function')"
                                + "window.__snapgentSeek(" + seekMs + ");}catch(e){}})();";
                        webView.evaluateJavascript(js, null);
                    }
                    MAIN.postDelayed(() -> {
                        try {
                            shot.set(source.grab());
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                        } finally {
                            done.countDown();
                        }
                    }, Math.max(0, waitMs));
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    done.countDown();
                }
            });

            try {
                if (!done.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("Frame capture timed out after " + CAPTURE_TIMEOUT_MS + " ms");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while capturing a frame");
            }
            if (failure.get() != null) {
                throw new IOException("Frame capture failed: " + describe(failure.get()));
            }
            return shot.get();
        }

        /** GPU path 1: record the WebView into a RenderNode and replay it on the GPU. */
        private Bitmap grabHardwareNode() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || webView == null) return null;
            ImageReader reader = null;
            HardwareRenderer renderer = null;
            try {
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
                renderer = new HardwareRenderer();
                renderer.setSurface(reader.getSurface());
                RenderNode node = new RenderNode("snapgent-frame");
                node.setPosition(0, 0, width, height);
                RecordingCanvas canvas = node.beginRecording();
                canvas.drawColor(Color.BLACK);
                webView.draw(canvas);
                node.endRecording();
                renderer.setContentRoot(node);
                renderer.createRenderRequest().syncAndDraw();

                Image image = nextImage(reader, 1_000);
                if (image == null) return null;
                Bitmap bitmap;
                try {
                    bitmap = imageToBitmap(image);
                } finally {
                    image.close();
                }
                if (bitmap != null && isUniform(bitmap)) {
                    bitmap.recycle();
                    return null;
                }
                return bitmap;
            } catch (Throwable t) {
                BridgeLog.warn("RenderTools", "RenderNode capture unavailable: " + describe(t));
                return null;
            } finally {
                if (renderer != null) {
                    try {
                        renderer.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        /** GPU path 2: give the WebView its own virtual display and read the composed frames. */
        private Bitmap grabVirtualDisplay() {
            try {
                ensureVirtualDisplay();
                if (displayReader == null) return null;
                Image image = nextImage(displayReader, 1_500);
                if (image == null) return null;
                Bitmap bitmap;
                try {
                    bitmap = imageToBitmap(image);
                } finally {
                    image.close();
                }
                if (bitmap != null && isUniform(bitmap)) {
                    bitmap.recycle();
                    return null;
                }
                return bitmap;
            } catch (Throwable t) {
                BridgeLog.warn("RenderTools", "Virtual display capture unavailable: " + describe(t));
                return null;
            }
        }

        private void ensureVirtualDisplay() throws Exception {
            if (displayReader != null) return;
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displays == null) throw new IOException("no display manager");
            ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            VirtualDisplay display = displays.createVirtualDisplay("snapgent-render", width, height,
                    DisplayMetrics.DENSITY_MEDIUM, reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (display == null) {
                reader.close();
                throw new IOException("cannot create a virtual display");
            }
            Context displayContext = context.createDisplayContext(display.getDisplay());
            Presentation window = new Presentation(displayContext, display.getDisplay());
            FrameLayout root = new FrameLayout(displayContext);
            root.setBackgroundColor(Color.BLACK);
            window.setContentView(root);
            window.show();
            if (windowAttached && windowManager != null && webView != null) {
                try {
                    windowManager.removeView(webView);
                } catch (Throwable ignored) {
                }
                windowAttached = false;
            }
            root.addView(webView, new FrameLayout.LayoutParams(width, height));
            displayReader = reader;
            virtualDisplay = display;
            presentation = window;
            Thread.sleep(150); // let the private display compose its first frame
        }

        private Bitmap grabSoftware() throws IOException {
            if (webView == null) throw new IOException("WebView is gone");
            // The software layer is the only way View.draw() can read pixels out of the
            // WebView tree, and it is exactly what turns WebGL off.
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            webView.draw(canvas);
            return bitmap;
        }

        private static Image nextImage(ImageReader reader, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                Image image = reader.acquireLatestImage();
                if (image != null) return image;
                if (System.currentTimeMillis() >= deadline) return null;
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        private static Bitmap imageToBitmap(Image image) {
            if (image.getPlanes().length == 0) return null;
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride() != 4) return null;
            int w = image.getWidth();
            int h = image.getHeight();
            int rowStride = plane.getRowStride();
            ByteBuffer buffer = plane.getBuffer();
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (rowStride == w * 4) {
                buffer.rewind();
                bitmap.copyPixelsFromBuffer(buffer);
                return bitmap;
            }
            byte[] row = new byte[rowStride];
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                buffer.position(y * rowStride);
                int length = Math.min(rowStride, buffer.remaining());
                if (length <= 0) break;
                buffer.get(row, 0, length);
                for (int x = 0; x < w; x++) {
                    int i = x * 4;
                    if (i + 3 >= length) break;
                    pixels[y * w + x] = ((row[i + 3] & 0xFF) << 24)
                            | ((row[i] & 0xFF) << 16)
                            | ((row[i + 1] & 0xFF) << 8)
                            | (row[i + 2] & 0xFF);
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        }

        /** A frame whose pixels are all identical means the read-back produced nothing. */
        private static boolean isUniform(Bitmap bitmap) {
            int first = bitmap.getPixel(0, 0);
            int stepX = Math.max(1, bitmap.getWidth() / 24);
            int stepY = Math.max(1, bitmap.getHeight() / 24);
            for (int y = 0; y < bitmap.getHeight(); y += stepY) {
                for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                    if (bitmap.getPixel(x, y) != first) return false;
                }
            }
            return true;
        }

        /** Asks the page which renderer backs its WebGL context: a GPU name, or SwiftShader. */
        private void probeWebGl() {
            final CountDownLatch done = new CountDownLatch(1);
            MAIN.post(() -> {
                try {
                    String js = "(function(){try{var c=document.createElement('canvas');"
                            + "var g=c.getContext('webgl2')||c.getContext('webgl')||c.getContext('experimental-webgl');"
                            + "if(!g)return 'none';var e=g.getExtension('WEBGL_debug_renderer_info');"
                            + "return e?String(g.getParameter(e.UNMASKED_RENDERER_WEBGL)):String(g.getParameter(g.RENDERER));"
                            + "}catch(err){return 'error:'+err.message;}})()";
                    webView.evaluateJavascript(js, value -> {
                        webgl = unquote(value);
                        done.countDown();
                    });
                } catch (Throwable t) {
                    webgl = "unavailable: " + describe(t);
                    done.countDown();
                }
            });
            try {
                done.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static String unquote(String value) {
            if (value == null) return "unknown";
            String text = value.trim();
            if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length() - 1);
            }
            return text.replace("\\u003C", "<")
                    .replace("\\u003E", ">")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }

        void close() {
            MAIN.post(() -> {
                if (presentation != null) {
                    try {
                        presentation.dismiss();
                    } catch (Throwable ignored) {
                    }
                    presentation = null;
                }
                if (virtualDisplay != null) {
                    try {
                        virtualDisplay.release();
                    } catch (Throwable ignored) {
                    }
                    virtualDisplay = null;
                }
                if (displayReader != null) {
                    try {
                        displayReader.close();
                    } catch (Throwable ignored) {
                    }
                    displayReader = null;
                }
                if (windowAttached && windowManager != null && webView != null) {
                    try {
                        windowManager.removeView(webView);
                    } catch (Throwable ignored) {
                    }
                    windowAttached = false;
                }
                if (webView != null) {
                    try {
                        webView.stopLoading();
                        webView.destroy();
                    } catch (Throwable ignored) {
                    }
                    webView = null;
                }
            });
        }
    }

    // ------------------------------------------------------------- encoding

    /** H.264 video encoder with optional AAC audio muxed into one MP4. */
    private static final class VideoEncoder {
        private final int width;
        private final int height;
        private final int fps;
        private final MediaMuxer muxer;
        private final MediaCodec video;
        private final MediaCodec audio;
        private final List<AudioChunk> audioChunks = new ArrayList<>();
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final File output;

        private int videoTrack = -1;
        private int audioTrack = -1;
        private boolean started;
        private boolean finished;
        private int audioCursor;

        VideoEncoder(int width, int height, int fps, int bitrate, File output, File wav) throws IOException {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.output = output;

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            MediaCodec videoCodec;
            try {
                videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable t) {
                throw new IOException("No usable H.264 encoder on this device: " + t.getMessage());
            }
            this.video = videoCodec;
            this.video.start();

            MediaCodec audioCodec = null;
            try {
                this.muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                this.muxer.setOrientationHint(0);
            } catch (IOException e) {
                video.release();
                throw e;
            }

            if (wav != null) {
                audioCodec = prepareAudio(wav);
            }
            this.audio = audioCodec;
        }

        private MediaCodec prepareAudio(File wav) throws IOException {
            WavData data = WavData.read(wav);
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, data.sampleRate, data.channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024);

            MediaCodec codec;
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
            } catch (Throwable t) {
                throw new IOException("No usable AAC encoder for the soundtrack: " + t.getMessage());
            }

            // Encode the whole soundtrack up front; the chunks are interleaved into the
            // muxer as video frames advance so the MP4 keeps a sane sample order.
            int bytesPerFrame = 1024 * data.channels * 2;
            long totalSamples = 0;
            long presentationUs = 0;
            ByteBuffer pcm = data.pcm;
            while (pcm.hasRemaining()) {
                int chunkSize = Math.min(bytesPerFrame, pcm.remaining());
                int inputIndex = -1;
                long deadline = System.currentTimeMillis() + 5_000;
                while (inputIndex < 0 && System.currentTimeMillis() < deadline) {
                    inputIndex = codec.dequeueInputBuffer(10_000);
                }
                if (inputIndex < 0) throw new IOException("AAC encoder did not accept input");

                ByteBuffer input = codec.getInputBuffer(inputIndex);
                if (input == null) throw new IOException("AAC encoder input buffer unavailable");
                input.clear();
                byte[] chunk = new byte[chunkSize];
                pcm.get(chunk);
                input.put(chunk);
                codec.queueInputBuffer(inputIndex, 0, chunkSize, presentationUs, 0);

                int frameSamples = chunkSize / (data.channels * 2);
                totalSamples += frameSamples;
                presentationUs = totalSamples * 1_000_000L / data.sampleRate;

                drainAudio(codec, false);
            }

            int inputIndex;
            long deadline = System.currentTimeMillis() + 5_000;
            do {
                inputIndex = codec.dequeueInputBuffer(10_000);
            } while (inputIndex < 0 && System.currentTimeMillis() < deadline);
            if (inputIndex < 0) throw new IOException("AAC encoder did not accept end-of-stream");
            codec.queueInputBuffer(inputIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainAudio(codec, true);

            MediaFormat outputFormat = codec.getOutputFormat();
            audioTrack = muxer.addTrack(outputFormat);
            return codec;
        }

        private void drainAudio(MediaCodec codec, boolean endOfStream) throws IOException {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (endOfStream) return;
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (index < 0) continue;

                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    byte[] payload = new byte[info.size];
                    buffer.position(info.offset);
                    buffer.get(payload, 0, info.size);
                    audioChunks.add(new AudioChunk(info.presentationTimeUs, payload));
                }
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        void writeFrame(Bitmap bitmap, long presentationUs) throws IOException {
            if (finished) throw new IOException("Encoder already finished");

            boolean retried = false;
            while (true) {
                int inputIndex = video.dequeueInputBuffer(20_000);
                if (inputIndex < 0) {
                    if (retried) throw new IOException("H.264 encoder is not accepting input");
                    retried = true;
                    drainVideo(false);
                    continue;
                }

                Image image = video.getInputImage(inputIndex);
                if (image == null) throw new IOException("H.264 encoder did not expose an input image");
                fillYuv(image, bitmap);
                int size = 0;
                ByteBuffer raw = video.getInputBuffer(inputIndex);
                if (raw != null) size = raw.capacity();
                video.queueInputBuffer(inputIndex, 0, size, presentationUs, 0);
                break;
            }

            drainVideo(false);
            muxAudioUpTo(presentationUs);
        }

        void finish() throws IOException {
            int inputIndex;
            long deadline = System.currentTimeMillis() + 10_000;
            do {
                inputIndex = video.dequeueInputBuffer(20_000);
            } while (inputIndex < 0 && System.currentTimeMillis() < deadline);
            if (inputIndex < 0) throw new IOException("H.264 encoder did not accept end-of-stream");
            video.queueInputBuffer(inputIndex, 0, 0,
                    Math.round(1_000_000.0 / fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainVideo(true);

            muxAudioUpTo(Long.MAX_VALUE );

            if (audio != null) {
                audio.stop();
                audio.release();
            }
            video.stop();
            video.release();
            if (started) muxer.stop();
            muxer.release();
            finished = true;
        }

        void abort() {
            try {
                if (audio != null) {
                    audio.stop();
                    audio.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                video.stop();
                video.release();
            } catch (Throwable ignored) {
            }
            try {
                muxer.release();
            } catch (Throwable ignored) {
            }
            try {
                if (output.exists() && !output.delete()) {
                    // best effort
                }
            } catch (Throwable ignored) {
            }
            finished = true;
        }

        private void drainVideo(boolean endOfStream) throws IOException {
            // Encoders such as OMX.qcom.video.encoder.avc emit nothing until several
            // frames have been queued. Draining must therefore be non-blocking while
            // frames are still arriving: spinning here would starve the encoder of the
            // very input it waits for and deadlock the whole render. Only the final
            // drain (after EOS) may wait, and even then with a deadline.
            long deadline = System.currentTimeMillis() + (endOfStream ? 10_000 : 0);
            while (true) {
                int index = video.dequeueOutputBuffer(info, 20_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return;
                    if (System.currentTimeMillis() >= deadline) return;
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(video.getOutputFormat());
                    maybeStart();
                    continue;
                }
                if (index < 0) continue;

                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (info.size > 0 && !isConfig) {
                    ByteBuffer buffer = video.getOutputBuffer(index);
                    if (buffer != null) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrack, buffer, info);
                    }
                }
                video.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        private void maybeStart() {
            if (started) return;
            if (videoTrack < 0) return;
            if (audio != null && audioTrack < 0) return;
            muxer.start();
            started = true;
        }

        private void muxAudioUpTo(long presentationUs) throws IOException {
            if (audio == null || !started) return;
            while (audioCursor < audioChunks.size()) {
                AudioChunk chunk = audioChunks.get(audioCursor);
                if (chunk.presentationUs > presentationUs) return;
                MediaCodec.BufferInfo chunkInfo = new MediaCodec.BufferInfo();
                chunkInfo.presentationTimeUs = chunk.presentationUs;
                chunkInfo.size = chunk.payload.length;
                chunkInfo.offset = 0;
                chunkInfo.flags = 0;
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(chunk.payload), chunkInfo);
                audioCursor++;
            }
        }

        private static final class AudioChunk {
            final long presentationUs;
            final byte[] payload;

            AudioChunk(long presentationUs, byte[] payload) {
                this.presentationUs = presentationUs;
                this.payload = payload;
            }
        }
    }

    // ----------------------------------------------------------------- yuv

    private static void fillYuv(Image image, Bitmap bitmap) throws IOException {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w != image.getWidth() || h != image.getHeight()) {
            throw new IOException("Frame size " + w + "x" + h + " does not match encoder input "
                    + image.getWidth() + "x" + image.getHeight());
        }
        int[] argb = new int[w * h];
        bitmap.getPixels(argb, 0, w, 0, 0, w, h);

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int uRow = planes[1].getRowStride();
        int vRow = planes[2].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uPix = planes[1].getPixelStride();
        int vPix = planes[2].getPixelStride();

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = argb[row + x];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int luma = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yBuffer.put(y * yRow + x * yPix, (byte) clampByte(luma));
            }
        }

        int chromaWidth = (w + 1) / 2;
        int chromaHeight = (h + 1) / 2;
        for (int cy = 0; cy < chromaHeight; cy++) {
            int y0 = Math.min(cy * 2, h - 1);
            int y1 = Math.min(cy * 2 + 1, h - 1);
            for (int cx = 0; cx < chromaWidth; cx++) {
                int x0 = Math.min(cx * 2, w - 1);
                int x1 = Math.min(cx * 2 + 1, w - 1);
                long rSum = 0;
                long gSum = 0;
                long bSum = 0;
                int[] offsets = {y0 * w + x0, y0 * w + x1, y1 * w + x0, y1 * w + x1};
                for (int offset : offsets) {
                    int c = argb[offset];
                    rSum += (c >> 16) & 0xff;
                    gSum += (c >> 8) & 0xff;
                    bSum += c & 0xff;
                }
                int r = (int) (rSum / 4);
                int g = (int) (gSum / 4);
                int b = (int) (bSum / 4);
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                uBuffer.put(cy * uRow + cx * uPix, (byte) clampByte(u));
                vBuffer.put(cy * vRow + cx * vPix, (byte) clampByte(v));
            }
        }
    }

    private static int clampByte(int value) {
        return value < 0 ? 0 : (Math.min(value, 255));
    }

    // ----------------------------------------------------------------- wav

    /** Minimal RIFF/WAVE reader for 16-bit PCM soundtracks. */
    private static final class WavData {
        final int sampleRate;
        final int channels;
        final ByteBuffer pcm;

        private WavData(int sampleRate, int channels, ByteBuffer pcm) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.pcm = pcm;
        }

        static WavData read(File file) throws IOException {
            if (!file.isFile()) throw new IOException("WAV file not found: " + file.getName());
            if (file.length() > 64L * 1024 * 1024) throw new IOException("WAV exceeds 64 MB");
            ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.capacity() < 44) throw new IOException("Not a WAV file: " + file.getName());
            byte[] riff = new byte[4];
            buffer.get(riff);
            if (!"RIFF".equals(new String(riff, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a RIFF file: " + file.getName());
            }
            buffer.getInt();
            byte[] wave = new byte[4];
            buffer.get(wave);
            if (!"WAVE".equals(new String(wave, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a WAVE file: " + file.getName());
            }

            int channels = 0;
            int sampleRate = 0;
            int bits = 0;
            int dataOffset = -1;
            int dataLength = 0;

            while (buffer.remaining() >= 8) {
                byte[] idBytes = new byte[4];
                buffer.get(idBytes);
                String id = new String(idBytes, StandardCharsets.US_ASCII);
                int size = buffer.getInt();
                if ("fmt ".equals(id)) {
                    if (size < 16 || buffer.remaining() < size) throw new IOException("Malformed WAV header");
                    int format = buffer.getShort() & 0xffff;
                    channels = buffer.getShort() & 0xffff;
                    sampleRate = buffer.getInt();
                    buffer.getInt(); // byte rate
                    buffer.getShort(); // block align
                    bits = buffer.getShort() & 0xffff;
                    int skip = size - 16;
                    if (skip > 0) buffer.position(buffer.position() + skip);
                    if (format != 1 || bits != 16) {
                        throw new IOException("Only 16-bit PCM WAV is supported (format=" + format + ", bits=" + bits + ")");
                    }
                } else if ("data".equals(id)) {
                    dataOffset = buffer.position();
                    dataLength = Math.min(size, buffer.remaining());
                    break;
                } else {
                    if (size < 0 || size > buffer.remaining()) break;
                    buffer.position(buffer.position() + size);
                }
            }

            if (channels <= 0 || sampleRate <= 0 || dataOffset < 0 || dataLength <= 0) {
                throw new IOException("WAV is missing fmt/data chunks: " + file.getName());
            }
            byte[] data = new byte[dataLength];
            buffer.position(dataOffset);
            buffer.get(data);
            return new WavData(sampleRate, channels, ByteBuffer.wrap(data));
        }
    }

    // ------------------------------------------------------------- helpers

    private static JSONObject property(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }

    /** Reads {@code render_mode}; gpu is the default so WebGL keeps working. */
    private static String renderMode(JSONObject args) throws IOException {
        String mode = args.optString("render_mode", MODE_GPU).trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty()) return MODE_GPU;
        if (!MODE_GPU.equals(mode) && !MODE_SOFTWARE.equals(mode)) {
            throw new IOException("render_mode must be '" + MODE_GPU + "' or '" + MODE_SOFTWARE
                    + "', not '" + mode + "'");
        }
        return mode;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }

    private static int dim(int value) throws IOException {
        if (value < 16 || value > MAX_DIMENSION) {
            throw new IOException("Dimension must be between 16 and " + MAX_DIMENSION + ": " + value);
        }
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private File resolve(String relative) throws IOException {
        return resolveStatic(context, relative);
    }

    private static File resolveStatic(Context context, String relative) throws IOException {
        if (context == null) throw new IOException("Render context is not available");
        File root = context.getFilesDir().getCanonicalFile();
        File candidate = new File(root, relative).getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!candidate.getPath().equals(root.getPath()) && !candidate.getPath().startsWith(rootPath)) {
            throw new IOException("Path escapes app-private storage: " + relative);
        }
        return candidate;
    }

    private String relative(File file) throws IOException {
        File root = context.getFilesDir().getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        String path = file.getCanonicalPath();
        return path.startsWith(rootPath) ? path.substring(rootPath.length()) : path;
    }

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent.getAbsolutePath());
        }
    }
}
