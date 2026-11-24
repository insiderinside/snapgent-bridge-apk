package dev.snapgent.bridge;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for {@code asr-ctl}, the small control server that runs as the shell user.
 *
 * <p>The app cannot start or stop a daemon itself: it runs as an app uid, and Android
 * refuses to exec binaries that live outside the APK. The service daemons must run as
 * the shell user, so starting and stopping them is delegated to {@code asr-ctl}, which
 * lives on the device as the shell user and listens on loopback.
 *
 * <p>Everything here is fire-and-forget over HTTP on {@code 127.0.0.1:18124}; the
 * Services page only needs "did it work, and what does it say". When the controller is
 * not running the page says so instead of pretending a disabled button is a bug.
 */
final class ServiceControl {

    static final int PORT = 18124;
    private static final String HOST = "127.0.0.1";
    private static final int CHECK_TIMEOUT_MS = 700;
    private static final int ACTION_TIMEOUT_MS = 20_000;
    private static final long CACHE_TTL_MS = 5_000L;

    /** null means "not checked yet", which is different from "not running". */
    private static volatile Boolean AVAILABLE;
    private static volatile long CHECKED_AT;
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);

    private ServiceControl() {
    }

    /** The last known answer, or null when the controller has not been probed yet. */
    static Boolean available() {
        return AVAILABLE;
    }

    static boolean needsCheck() {
        return AVAILABLE == null || System.currentTimeMillis() - CHECKED_AT > CACHE_TTL_MS;
    }

    /** Result of one start/stop call. */
    interface Reply {
        void onReply(boolean ok, String message);
    }

    /**
     * Asks the controller whether it is there, then posts {@code done} to the main
     * thread. Concurrent calls collapse into the running check.
     */
    static void checkAsync(Runnable done) {
        if (!CHECKING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                String body = request("/status", CHECK_TIMEOUT_MS);
                AVAILABLE = body != null && body.contains("\"asr-ctl\"");
            } finally {
                CHECKED_AT = System.currentTimeMillis();
                CHECKING.set(false);
            }
            if (done != null) new Handler(Looper.getMainLooper()).post(done);
        }, "snapgent-service-control-check");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Calls {@code /start?name=…} or {@code /stop?name=…} on a background thread and
     * answers on the main thread with a sentence fit for a toast.
     */
    static void actAsync(String action, String name, Reply reply) {
        Thread worker = new Thread(() -> {
            boolean ok = false;
            String message;
            try {
                String body = request("/" + action + "?name=" + name, ACTION_TIMEOUT_MS);
                JSONObject json = body == null ? null : new JSONObject(body);
                if (json == null) {
                    message = "No answer from the service controller";
                } else {
                    ok = json.optBoolean("ok", false);
                    String error = json.optString("error", "");
                    if (!ok) {
                        message = error.isEmpty() ? name + ": " + action + " failed" : error;
                    } else if ("start".equals(action)) {
                        message = json.optBoolean("already", false)
                                ? name + " is already running"
                                : json.optBoolean("ready", false)
                                ? name + " started and answers"
                                : name + " started but is not answering yet";
                    } else {
                        message = json.optBoolean("already", false)
                                ? name + " was not running"
                                : name + " stopped";
                    }
                }
            } catch (Exception e) {
                message = "Service controller is not reachable";
                BridgeLog.warn("Services", action + " " + name + " failed: " + e);
            }
            final boolean done = ok;
            final String text = message;
            if (reply != null) {
                new Handler(Looper.getMainLooper()).post(() -> reply.onReply(done, text));
            }
        }, "snapgent-service-control-" + action);
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * One request/response over loopback; null when the controller is not there.
     *
     * <p>The body length comes from the reply's own {@code Content-Length}, not from the
     * connection closing. A controller that has just spawned a daemon can leave the
     * socket open (the child inherited it), and waiting for EOF then turned a perfectly
     * good answer into a 20-second "no answer" — the Start button looked broken while
     * the daemon was already up.
     */
    private static String request(String path, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), Math.min(timeoutMs, CHECK_TIMEOUT_MS));
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: 127.0.0.1:" + PORT + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[2048];
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            String headers = "";
            int split;
            int read;
            while ((split = headers.indexOf("\r\n\r\n")) < 0 && (read = in.read(chunk)) > 0) {
                head.write(chunk, 0, read);
                headers = head.toString("UTF-8");
                if (head.size() > 8 * 1024) break;
            }
            split = headers.indexOf("\r\n\r\n");
            if (split < 0) return headers.isEmpty() ? null : headers;

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] already = headers.substring(split + 4).getBytes(StandardCharsets.UTF_8);
            body.write(already, 0, already.length);
            int length = contentLength(headers.substring(0, split));
            if (length >= 0) {
                while (body.size() < length && (read = in.read(chunk)) > 0) {
                    body.write(chunk, 0, Math.min(read, length - body.size()));
                }
            } else {
                while ((read = in.read(chunk)) > 0) body.write(chunk, 0, read);
            }
            return body.toString("UTF-8");
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** {@code Content-Length} from the reply headers, or -1 when it is not declared. */
    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (!line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) continue;
            try {
                return Integer.parseInt(line.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
