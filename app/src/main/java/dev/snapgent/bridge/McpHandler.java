package dev.snapgent.bridge;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;
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
    private static final int MAX_BINARY_BYTES = 4 * 1024 * 1024;
    private final Context context;
    private final WorkspaceTools workspace;
    private final GitTools git;
    private final PythonTools pythonTools;
    private final RenderTools renderTools;
    private final DeviceTools deviceTools;
    private final CustomTools customTools;
    private final String appVersion;

    public McpHandler(Context context) {
        this.context = context.getApplicationContext();
        this.workspace = new WorkspaceTools(this.context);
        this.pythonTools = new PythonTools(this.context);
        this.renderTools = new RenderTools(this.context);
        this.deviceTools = new DeviceTools(this.context);
        this.customTools = new CustomTools(this.context, this.pythonTools);
        this.appVersion = BridgeConfig.appVersion(this.context);
        try { this.git = new GitTools(this.context); }
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

    /** Catalog of the built-in tools, before the user's on/off switches are applied. */
    private JSONArray builtinCatalog() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("device_info", "Get Android device information and bridge status", new JSONObject()));
        tools.put(tool("list_files", "List files in the Snapgent Bridge app-private storage", new JSONObject()
                .put("path", stringProperty("Relative directory path, or empty for the root"))));
        tools.put(tool("read_file", "Read a UTF-8 file from Snapgent Bridge app-private storage (max 512 KiB)", new JSONObject()
                .put("path", stringProperty("Relative file path")), "path"));
        tools.put(tool("write_file", "Create or replace a UTF-8 file in Snapgent Bridge app-private storage (max 512 KiB)", new JSONObject()
                .put("path", stringProperty("Relative file path"))
                .put("content", stringProperty("UTF-8 text content")), "path", "content"));
        tools.put(tool("write_file_base64", "Upload binary data (audio, images, video) into app-private storage."
                + " Base64-encoded, at most 4 MiB per call; pass append=true to continue a larger file.", new JSONObject()
                .put("path", stringProperty("Relative file path"))
                .put("data", stringProperty("Base64-encoded bytes"))
                .put("append", boolProperty("Append instead of replacing the file")), "path", "data"));
        tools.put(tool("read_file_base64", "Read a binary file (max 4 MiB) as base64, to pull renders or voice-overs off the device", new JSONObject()
                .put("path", stringProperty("Relative file path")), "path"));
        JSONArray workspaceTools = workspace.catalog();
        for (int i = 0; i < workspaceTools.length(); i++) tools.put(workspaceTools.getJSONObject(i));
        JSONArray gitTools = git.catalog();
        for (int i = 0; i < gitTools.length(); i++) tools.put(gitTools.getJSONObject(i));
        JSONArray pythonToolCatalog = pythonTools.catalog();
        for (int i = 0; i < pythonToolCatalog.length(); i++) tools.put(pythonToolCatalog.getJSONObject(i));
        JSONArray renderToolCatalog = renderTools.catalog();
        for (int i = 0; i < renderToolCatalog.length(); i++) tools.put(renderToolCatalog.getJSONObject(i));
        JSONArray deviceToolCatalog = deviceTools.catalog();
        for (int i = 0; i < deviceToolCatalog.length(); i++) tools.put(deviceToolCatalog.getJSONObject(i));
        return tools;
    }

    private JSONObject listTools() throws JSONException {
        JSONArray tools = new JSONArray();
        JSONArray builtin = builtinCatalog();
        for (int i = 0; i < builtin.length(); i++) {
            JSONObject entry = builtin.getJSONObject(i);
            if (ToolSettings.isEnabled(context, entry.optString("name"))) tools.put(entry);
        }
        JSONArray custom = customTools.catalog();
        for (int i = 0; i < custom.length(); i++) {
            JSONObject entry = custom.getJSONObject(i);
            if (ToolSettings.isEnabled(context, entry.optString("name"))) tools.put(entry);
        }
        JSONArray management = customTools.managementCatalog();
        for (int i = 0; i < management.length(); i++) tools.put(management.getJSONObject(i));
        return new JSONObject().put("tools", tools);
    }

    /**
     * Every tool with its switch state, for the Settings screen. A tool the user
     * switched off stays listed here even though {@code tools/list} hides it.
     */
    public JSONArray toolSummaries() throws JSONException {
        JSONArray result = new JSONArray();
        JSONArray builtin = builtinCatalog();
        for (int i = 0; i < builtin.length(); i++) {
            JSONObject entry = builtin.getJSONObject(i);
            String name = entry.optString("name");
            result.put(new JSONObject().put("name", name).put("kind", "builtin")
                    .put("description", entry.optString("description"))
                    .put("enabled", ToolSettings.isEnabled(context, name)));
        }
        JSONArray custom = customTools.catalog();
        for (int i = 0; i < custom.length(); i++) {
            JSONObject entry = custom.getJSONObject(i);
            String name = entry.optString("name");
            result.put(new JSONObject().put("name", name).put("kind", "custom")
                    .put("description", entry.optString("description"))
                    .put("enabled", ToolSettings.isEnabled(context, name)));
        }
        // Management tools cannot be switched off: they are how a client creates and
        // toggles everything else, so they are always listed as enabled.
        result.put(new JSONObject().put("name", "tool_create").put("kind", "management")
                .put("description", "Create or replace a custom tool").put("enabled", true));
        result.put(new JSONObject().put("name", "tool_delete").put("kind", "management")
                .put("description", "Delete a custom tool").put("enabled", true));
        result.put(new JSONObject().put("name", "tool_list").put("kind", "management")
                .put("description", "List every tool and its switch state").put("enabled", true));
        result.put(new JSONObject().put("name", "tool_set_enabled").put("kind", "management")
                .put("description", "Enable or disable a tool").put("enabled", true));
        return result;
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

    private JSONObject boolProperty(String description) throws JSONException {
        return new JSONObject().put("type", "boolean").put("description", description);
    }

    private JSONObject callTool(JSONObject params) throws JSONException {
        if (params == null || !(params.opt("name") instanceof String)) throw new IllegalArgumentException("Missing tool name");
        String name = params.optString("name");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        if (!ToolSettings.isEnabled(context, name)) {
            return toolResult("Tool is switched off on the device: " + name
                    + ". Enable it in the Snapgent Bridge settings or with tool_set_enabled.", true);
        }
        try {
            switch (name) {
                case "device_info": return toolResult(deviceInfo().toString(2), false);
                case "list_files": return toolResult(listFiles(requireString(args, "path", true)).toString(2), false);
                case "read_file": return toolResult(readFile(requireString(args, "path", false)), false);
                case "write_file": {
                    String path = requireString(args, "path", false);
                    String content = requireString(args, "content", true);
                    writeBytes(path, content.getBytes(StandardCharsets.UTF_8), false);
                    return toolResult("Saved: " + path, false);
                }
                case "write_file_base64": {
                    String path = requireString(args, "path", false);
                    byte[] decoded;
                    try {
                        decoded = Base64.decode(requireString(args, "data", false), Base64.DEFAULT);
                    } catch (IllegalArgumentException e) {
                        throw new IOException("data is not valid base64");
                    }
                    if (decoded.length > MAX_BINARY_BYTES) throw new IOException("Payload exceeds 4 MiB; split it with append=true");
                    writeBytes(path, decoded, args.optBoolean("append", false));
                    return toolResult(new JSONObject().put("saved", path)
                            .put("bytes", decoded.length)
                            .put("totalBytes", resolve(path).length())
                            .put("append", args.optBoolean("append", false)).toString(), false);
                }
                case "read_file_base64": {
                    String path = requireString(args, "path", false);
                    File file = resolve(path);
                    if (!file.isFile()) throw new IOException("File not found: " + path);
                    if (file.length() > MAX_BINARY_BYTES) throw new IOException("File exceeds 4 MiB: " + file.length() + " bytes");
                    byte[] bytes = new byte[(int) file.length()];
                    try (FileInputStream input = new FileInputStream(file)) {
                        int offset = 0;
                        while (offset < bytes.length) {
                            int count = input.read(bytes, offset, bytes.length - offset);
                            if (count < 0) break;
                            offset += count;
                        }
                    }
                    return toolResult(new JSONObject()
                            .put("path", path)
                            .put("bytes", bytes.length)
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString(), false);
                }
                default:
                    if (CustomTools.isManagementTool(name)) {
                        return toolResult(customTools.execute(name, args, CustomTools.nameSet(builtinCatalog())), false);
                    }
                    if (customTools.isCustom(name)) return toolResult(customTools.execute(name, args, CustomTools.nameSet(builtinCatalog())), false);
                    if (PythonTools.isTool(name)) return toolResult(pythonTools.execute(name, args), false);
                    if (RenderTools.isTool(name)) return toolResult(renderTools.execute(name, args), false);
                    if (DeviceTools.isTool(name)) return toolResult(deviceTools.execute(name, args), false);
                    return toolResult(GitTools.isTool(name) ? git.execute(name, args) : workspace.execute(name, args), false);
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

    private void writeBytes(String relativePath, byte[] bytes, boolean append) throws IOException {
        if (bytes.length > MAX_FILE_BYTES && !append) {
            // Text writes stay capped at 512 KiB; binary uploads are checked by the caller.
            if (bytes.length > MAX_BINARY_BYTES) throw new IOException("Content exceeds 4 MiB");
        }
        File file = resolve(relativePath);
        if (file.equals(context.getFilesDir().getCanonicalFile()) || file.isDirectory()) throw new IOException("Destination is a directory");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create parent directory");
        try (FileOutputStream output = new FileOutputStream(file, append)) {
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
