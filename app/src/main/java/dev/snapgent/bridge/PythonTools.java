package dev.snapgent.bridge;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Embedded CPython runtime (Chaquopy) exposed as MCP tools.
 *
 * <p>Two tools: {@code run_python} executes a snippet or a script file from the
 * app-private storage, and {@code python_info} describes the interpreter. The
 * interpreter is started lazily because startup costs ~100 ms and is pointless
 * until a tool is actually called.
 *
 * <p>Scope note: this ships the Python standard library plus the packages bound
 * at build time in {@code app/build.gradle} ({@code requests}, {@code Pillow}).
 * There is no runtime {@code pip install} — dependencies are packed into the APK
 * because targetSdk 34 blocks executing anything downloaded to app storage — and
 * the script runs with the app's own UID, the same trust boundary as the other
 * workspace tools.
 */
final class PythonTools {

    private static final int MAX_SOURCE_BYTES = 512 * 1024;
    private static final int MAX_OUTPUT_DEFAULT = 64 * 1024;
    private static final int MAX_OUTPUT_LIMIT = 256 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int MAX_TIMEOUT_MS = 60_000;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    PythonTools(Context context) {
        this.context = context;
    }

    static boolean isTool(String name) {
        return "run_python".equals(name) || "python_info".equals(name);
    }

    JSONArray catalog() throws JSONException {
        JSONArray tools = new JSONArray();

        JSONObject runProps = new JSONObject()
                .put("code", property("string", "Python source to execute. Mutually exclusive with path"))
                .put("path", property("string", "Script file in app-private storage. Mutually exclusive with code"))
                .put("cwd", property("string", "Working directory relative to app-private storage"))
                .put("args", property("array", "Arguments exposed to the script as sys.argv[1:]"));

        JSONObject runSchema = new JSONObject()
                .put("type", "object")
                .put("properties", runProps)
                .put("additionalProperties", false);

        tools.put(new JSONObject()
                .put("name", "run_python")
                .put("description", "Execute Python in the embedded CPython runtime (stdlib + bundled"
                        + " requests/Pillow; no runtime pip)."
                        + " Returns exit_code, output and a truncated flag.")
                .put("inputSchema", runSchema));

        tools.put(new JSONObject()
                .put("name", "python_info")
                .put("description", "Report the embedded Python version, platform and bundled packages")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject())
                        .put("additionalProperties", false)));

        return tools;
    }

    String execute(String name, JSONObject args) throws IOException, JSONException {
        try {
            switch (name) {
                case "python_info":
                    return helper().callAttr("info").toString();
                case "run_python":
                    return runPython(args);
                default:
                    throw new IOException("Unknown Python tool: " + name);
            }
        } catch (IOException | JSONException e) {
            throw e;
        } catch (ExceptionInInitializerError e) {
            throw new IOException("Embedded Python failed to start on this ABI: " + e.getMessage());
        } catch (Throwable t) {
            throw new IOException("Embedded Python error: " + t.getMessage());
        }
    }

    private String runPython(JSONObject args) throws IOException, JSONException {
        boolean hasCode = args.has("code");
        boolean hasPath = args.has("path");
        if (hasCode == hasPath) throw new IOException("run_python needs exactly one of 'code' or 'path'");

        String source;
        String filename;
        if (hasCode) {
            source = args.optString("code", "");
            filename = "<snapgent>";
        } else {
            File file = resolve(args.optString("path", ""));
            if (!file.isFile()) throw new IOException("Script not found: " + args.optString("path", ""));
            if (file.length() > MAX_SOURCE_BYTES) throw new IOException("Script exceeds " + MAX_SOURCE_BYTES + " bytes");
            source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            filename = file.getName();
        }
        if (source.trim().isEmpty()) throw new IOException("Python source is empty");

        // Default to app-private storage: inheriting the process working directory
        // would be "/" on Android, which is read-only and breaks every relative write.
        String cwd = resolve(args.optString("cwd", "")).getAbsolutePath();
        List<String> argv = new ArrayList<>();
        JSONArray rawArgs = args.optJSONArray("args");
        if (rawArgs != null) {
            for (int i = 0; i < rawArgs.length(); i++) argv.add(rawArgs.optString(i, ""));
        }

        final PyObject module = helper();
        final String code = source;
        final String scriptName = filename;
        final String workingDir = cwd;
        final String[] scriptArgs = argv.toArray(new String[0]);
        final int timeoutMs = clamp(args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS), 100, MAX_TIMEOUT_MS);

        Future<String> future = executor.submit(() ->
                module.callAttr("execute", code, scriptName, workingDir, MAX_OUTPUT_LIMIT, scriptArgs).toString());
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Python script exceeded " + timeoutMs
                    + " ms. The interpreter cannot be interrupted mid-exec; it may keep running in the background.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Python execution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Python execution failed: " + cause.getMessage());
        }
    }

    private PyObject helper() throws IOException {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
        return Python.getInstance().getModule("snapgent_runner");
    }

    private File resolve(String relative) throws IOException {
        File root = context.getFilesDir().getCanonicalFile();
        File candidate = new File(root, relative).getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!candidate.getPath().equals(root.getPath()) && !candidate.getPath().startsWith(rootPath)) {
            throw new IOException("Path escapes app-private storage: " + relative);
        }
        return candidate;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static JSONObject property(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }
}
