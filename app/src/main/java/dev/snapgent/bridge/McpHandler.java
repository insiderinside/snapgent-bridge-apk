package dev.snapgent.bridge;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Minimal MCP JSON-RPC 2.0 server. Filesystem tools are restricted to this app's private files. */
public final class McpHandler {
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private final Context context;
    private final WorkspaceTools workspace;
    private final GitTools git;
    private final String appVersion;

    public McpHandler(Context context) {
        this.context = context.getApplicationContext();
        this.workspace = new WorkspaceTools(this.context);
        this.appVersion = BridgeConfig.appVersion(this.context);
        try { this.git = new GitTools(this.context.getFilesDir()); }
        catch (IOException e) { throw new IllegalStateException("Cannot initialize Git tools", e); }
    }

    /** Version name of the installed APK, so every report matches the build. */
    public String appVersion() {
        return appVersion;
    }

    public String handle(String message) {
        JSONObject request;
        try {
            request = new JSONObject(message);
        } catch (JSONException e) {
            return error(JSONObject.NULL, -32700, "Invalid JSON").toString();
        }
        Object id = request.opt("id");
        boolean notification = !request.has("id");
        try {
            if (!"2.0".equals(request.optString("jsonrpc")) || !(request.opt("method") instanceof String)) {
                return error(notification ? JSONObject.NULL : id, -32600, "Invalid JSON-RPC request").toString();
            }
            String method = request.getString("method");
            if (notification) return null;
            JSONObject result;
            switch (method) {
                case "initialize": result = initialize(request.optJSONObject("params")); break;
                case "ping": result = new JSONObject(); break;
                case "tools/list": result = listTools(); break;
                case "tools/call": result = callTool(request.optJSONObject("params")); break;
                default: return error(id, -32601, "Method not found: " + method).toString();
            }
            return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id).put("result", result).toString();
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage()).toString();
        } catch (Exception e) {
            return error(id, -32603, "Internal error: " + e.getClass().getSimpleName()).toString();
        }
    }

    private JSONObject initialize(JSONObject params) throws JSONException {
        String requested = params == null ? "" : params.optString("protocolVersion", "");
        String version = requested.equals("2024-11-05") || requested.equals("2025-03-26") || requested.equals(PROTOCOL_VERSION)
                ? requested : PROTOCOL_VERSION;
        return new JSONObject()
                .put("protocolVersion", version)
                .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)))
                .put("serverInfo", new JSONObject().put("name", "snapgent-android-bridge").put("version", appVersion));
    }

    private JSONObject listTools() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("device_info", "Get Android device information and bridge status", new JSONObject()));
        tools.put(tool("list_files", "List files in the Snapgent Bridge app-private storage", new JSONObject()
                .put("path", stringProperty("Relative directory path, or empty for the root"))));
        tools.put(tool("read_file", "Read a UTF-8 file from Snapgent Bridge app-private storage (max 512 KiB)", new JSONObject()
                .put("path", stringProperty("Relative file path")), "path"));
        tools.put(tool("write_file", "Create or replace a UTF-8 file in Snapgent Bridge app-private storage (max 512 KiB)", new JSONObject()
                .put("path", stringProperty("Relative file path"))
                .put("content", stringProperty("UTF-8 text content")), "path", "content"));
        JSONArray workspaceTools = workspace.catalog();
        for (int i = 0; i < workspaceTools.length(); i++) tools.put(workspaceTools.getJSONObject(i));
        JSONArray gitTools = git.catalog();
        for (int i = 0; i < gitTools.length(); i++) tools.put(gitTools.getJSONObject(i));
        return new JSONObject().put("tools", tools);
    }

    private JSONObject tool(String name, String description, JSONObject properties, String... required) throws JSONException {
        JSONArray requiredFields = new JSONArray();
        for (String field : required) requiredFields.put(field);
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties).put("required", requiredFields).put("additionalProperties", false);
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    private JSONObject stringProperty(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private JSONObject callTool(JSONObject params) throws JSONException {
        if (params == null || !(params.opt("name") instanceof String)) throw new IllegalArgumentException("Missing tool name");
        String name = params.optString("name");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        try {
            switch (name) {
                case "device_info": return toolResult(deviceInfo().toString(2), false);
                case "list_files": return toolResult(listFiles(requireString(args, "path", true)).toString(2), false);
                case "read_file": return toolResult(readFile(requireString(args, "path", false)), false);
                case "write_file": {
                    String path = requireString(args, "path", false);
                    String content = requireString(args, "content", true);
                    writeFile(path, content);
                    return toolResult("Saved: " + path, false);
                }
                default: return toolResult(GitTools.isTool(name) ? git.execute(name, args) : workspace.execute(name, args), false);
            }
        } catch (IOException | SecurityException e) {
            return toolResult("Tool error: " + e.getMessage(), true);
        }
    }

    private JSONObject toolResult(String text, boolean isError) throws JSONException {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject()
                .put("type", "text").put("text", text))).put("isError", isError);
    }

    private String requireString(JSONObject args, String name, boolean allowEmpty) {
        Object value = args.opt(name);
        if (allowEmpty && value == null && "path".equals(name)) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string argument: " + name);
        String result = (String) value;
        if (!allowEmpty && result.isEmpty()) throw new IllegalArgumentException("Argument cannot be empty: " + name);
        return result;
    }

    private JSONObject deviceInfo() throws JSONException {
        return new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("bridgeVersion", appVersion)
                .put("bridgeRunning", BridgeService.running)
                .put("websocketHost", "127.0.0.1")
                .put("websocketPort", BridgeConfig.port(context))
                .put("storage", "app-private only")
                .put("appFilesFreeBytes", context.getFilesDir().getUsableSpace());
    }

    private File resolve(String relativePath) throws IOException {
        if (relativePath.startsWith("/") || relativePath.indexOf('\u0000') >= 0) {
            throw new SecurityException("Only relative paths inside app storage are allowed");
        }
        File root = context.getFilesDir().getCanonicalFile();
        File target = new File(root, relativePath.isEmpty() ? "." : relativePath).getCanonicalFile();
        if (!target.equals(root) && !target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new SecurityException("Path escapes app-private storage");
        }
        return target;
    }

    private JSONArray listFiles(String relativePath) throws IOException, JSONException {
        File directory = resolve(relativePath);
        if (!directory.isDirectory()) throw new IOException("Not a directory: " + relativePath);
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot list directory");
        Arrays.sort(entries, Comparator.comparing(File::getName));
        JSONArray result = new JSONArray();
        for (File entry : entries) {
            result.put(new JSONObject()
                    .put("name", entry.getName())
                    .put("type", entry.isDirectory() ? "directory" : "file")
                    .put("bytes", entry.length()));
        }
        return result;
    }

    private String readFile(String relativePath) throws IOException {
        File file = resolve(relativePath);
        if (!file.isFile()) throw new IOException("File not found: " + relativePath);
        if (file.length() > MAX_FILE_BYTES) throw new IOException("File exceeds 512 KiB");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStreamEx output = new ByteArrayOutputStreamEx()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_FILE_BYTES) throw new IOException("File exceeds 512 KiB");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void writeFile(String relativePath, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Content exceeds 512 KiB");
        File file = resolve(relativePath);
        if (file.equals(context.getFilesDir().getCanonicalFile()) || file.isDirectory()) throw new IOException("Destination is a directory");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create parent directory");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static final class ByteArrayOutputStreamEx extends java.io.ByteArrayOutputStream {}

    private JSONObject error(Object id, int code, String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0")
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("error", new JSONObject().put("code", code).put("message", message == null ? "Unknown error" : message));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }
}
