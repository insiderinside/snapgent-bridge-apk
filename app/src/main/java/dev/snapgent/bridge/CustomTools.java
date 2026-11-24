package dev.snapgent.bridge;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * User-defined MCP tools that live on the device.
 *
 * <p>A definition is small JSON stored in {@link ToolSettings}: either a
 * {@code python} snippet executed by the embedded interpreter, or an
 * {@code http} request whose URL/body/headers may reference arguments as
 * {@code {{name}}}. Both kinds become ordinary MCP tools in {@code tools/list},
 * so a client on the Mac sees them exactly like the built-in ones.
 *
 * <p>The management tools ({@code tool_create}, {@code tool_delete},
 * {@code tool_list}, {@code tool_set_enabled}) are always available, including
 * over the WebSocket endpoint, so a remote client can build its own toolset
 * without touching the phone UI.
 */
final class CustomTools {

    static final int MAX_CODE_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int MAX_TIMEOUT_MS = 60_000;
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{1,40}$");

    private final Context context;
    private final PythonTools python;

    CustomTools(Context context, PythonTools python) {
        this.context = context;
        this.python = python;
    }

    /** Names owned by this class; they are also the tools an MCP client uses to manage definitions. */
    static boolean isManagementTool(String name) {
        return "tool_create".equals(name) || "tool_delete".equals(name)
                || "tool_list".equals(name) || "tool_set_enabled".equals(name);
    }

    JSONArray managementCatalog() throws JSONException {
        JSONArray tools = new JSONArray();

        JSONObject paramSchema = new JSONObject()
                .put("type", "array")
                .put("description", "Arguments exposed to the tool: [{name, description, required, type}]");

        JSONObject create = new JSONObject()
                .put("name", "tool_create")
                .put("description", "Create or replace a custom MCP tool on this device."
                        + " kind=python runs a snippet in the embedded interpreter;"
                        + " kind=http sends a request with {{arg}} substitution."
                        + " The tool appears immediately in tools/list.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("name", property("string", "Tool name: lowercase letters, digits and underscores"))
                                .put("description", property("string", "What the tool does, shown to the client"))
                                .put("kind", property("string", "python or http"))
                                .put("code", property("string", "Python source (kind=python). Arguments are available as the dict ARGS"))
                                .put("url", property("string", "Request URL (kind=http), may contain {{arg}}"))
                                .put("method", property("string", "HTTP method for kind=http, default GET"))
                                .put("headers", property("object", "HTTP headers for kind=http"))
                                .put("body", property("string", "Request body for kind=http, may contain {{arg}}"))
                                .put("params", paramSchema)
                                .put("timeout_ms", property("integer", "Timeout in milliseconds (max 60000)")))
                        .put("required", new JSONArray().put("name").put("description").put("kind"))
                        .put("additionalProperties", false));
        tools.put(create);

        tools.put(new JSONObject()
                .put("name", "tool_delete")
                .put("description", "Delete a custom tool by name")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("name", property("string", "Custom tool name")))
                        .put("required", new JSONArray().put("name"))
                        .put("additionalProperties", false)));

        tools.put(new JSONObject()
                .put("name", "tool_list")
                .put("description", "List every tool with its enabled state and, for custom tools, the full definition")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject())
                        .put("additionalProperties", false)));

        tools.put(new JSONObject()
                .put("name", "tool_set_enabled")
                .put("description", "Enable or disable a tool. A disabled tool disappears from tools/list and refuses calls.")
                .put("inputSchema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("name", property("string", "Tool name"))
                                .put("enabled", property("boolean", "true to enable, false to disable")))
                        .put("required", new JSONArray().put("name").put("enabled"))
                        .put("additionalProperties", false)));

        return tools;
    }

    /** Catalog entries for every stored custom definition. */
    JSONArray catalog() throws JSONException {
        JSONArray tools = new JSONArray();
        JSONArray definitions = ToolSettings.customTools(context);
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.optJSONObject(i);
            if (definition == null) continue;
            tools.put(new JSONObject()
                    .put("name", definition.optString("name"))
                    .put("description", definition.optString("description", "Custom tool"))
                    .put("inputSchema", schemaFor(definition)));
        }
        return tools;
    }

    private JSONObject schemaFor(JSONObject definition) throws JSONException {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        JSONArray params = definition.optJSONArray("params");
        if (params != null) {
            for (int i = 0; i < params.length(); i++) {
                JSONObject param = params.optJSONObject(i);
                if (param == null) continue;
                String name = param.optString("name", "").trim();
                if (name.isEmpty()) continue;
                properties.put(name, new JSONObject()
                        .put("type", param.optString("type", "string"))
                        .put("description", param.optString("description", "")));
                if (param.optBoolean("required", false)) required.put(name);
            }
        }
        return new JSONObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
    }

    /** True when {@code name} is a stored custom definition. */
    boolean isCustom(String name) {
        JSONArray definitions = ToolSettings.customTools(context);
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.optJSONObject(i);
            if (definition != null && name.equals(definition.optString("name"))) return true;
        }
        return false;
    }

    /**
     * Runs a management or custom tool.
     *
     * @param reserved built-in tool names, so a definition cannot shadow one
     */
    String execute(String name, JSONObject args, Set<String> reserved) throws IOException, JSONException {
        switch (name) {
            case "tool_create": return create(args, reserved);
            case "tool_delete": return delete(args);
            case "tool_list": return list(reserved);
            case "tool_set_enabled": {
                String tool = requireString(args, "name");
                if (!args.has("enabled")) throw new IOException("Missing argument: enabled");
                boolean enabled = args.optBoolean("enabled", true);
                ToolSettings.setEnabled(context, tool, enabled);
                return new JSONObject().put("name", tool).put("enabled", enabled).toString();
            }
            default: return runDefinition(name, args);
        }
    }

    private String create(JSONObject args, Set<String> reserved) throws IOException, JSONException {
        String name = requireString(args, "name").trim().toLowerCase(Locale.US);
        if (!NAME.matcher(name).matches()) {
            throw new IOException("Invalid tool name: use lowercase letters, digits and underscores, starting with a letter");
        }
        if (reserved.contains(name) || isManagementTool(name)) {
            throw new IOException("Tool name is reserved by a built-in tool: " + name);
        }
        String description = requireString(args, "description").trim();
        String kind = requireString(args, "kind").trim().toLowerCase(Locale.US);
        if (!"python".equals(kind) && !"http".equals(kind)) throw new IOException("kind must be python or http");

        JSONObject definition = new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("kind", kind);
        if (args.has("timeout_ms")) definition.put("timeout_ms", clamp(args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS)));
        JSONArray params = args.optJSONArray("params");
        if (params != null) definition.put("params", params);

        if ("python".equals(kind)) {
            String code = requireString(args, "code");
            if (code.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
                throw new IOException("Python source exceeds " + MAX_CODE_BYTES + " bytes");
            }
            definition.put("code", code);
        } else {
            String url = requireString(args, "url").trim();
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new IOException("url must start with http:// or https://");
            }
            definition.put("url", url);
            definition.put("method", args.optString("method", "GET").trim().toUpperCase(Locale.US));
            JSONObject headers = args.optJSONObject("headers");
            if (headers != null) definition.put("headers", headers);
            if (args.has("body")) definition.put("body", args.optString("body", ""));
        }

        JSONArray definitions = ToolSettings.customTools(context);
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject existing = definitions.optJSONObject(i);
            if (existing == null) continue;
            if (name.equals(existing.optString("name"))) { next.put(definition); replaced = true; }
            else next.put(existing);
        }
        if (!replaced) next.put(definition);
        ToolSettings.saveCustomTools(context, next);
        // A freshly created tool is enabled unless it was switched off before.
        return new JSONObject().put("created", !replaced).put("replaced", replaced)
                .put("tool", definition).toString();
    }

    private String delete(JSONObject args) throws IOException, JSONException {
        String name = requireString(args, "name").trim();
        JSONArray definitions = ToolSettings.customTools(context);
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject existing = definitions.optJSONObject(i);
            if (existing == null) continue;
            if (name.equals(existing.optString("name"))) removed = true;
            else next.put(existing);
        }
        if (!removed) throw new IOException("No custom tool named " + name);
        ToolSettings.saveCustomTools(context, next);
        ToolSettings.setEnabled(context, name, true);
        return new JSONObject().put("deleted", name).toString();
    }

    private String list(Set<String> reserved) throws IOException, JSONException {
        JSONArray tools = new JSONArray();
        for (String name : reserved) {
            tools.put(new JSONObject().put("name", name).put("kind", "builtin")
                    .put("enabled", ToolSettings.isEnabled(context, name)));
        }
        // The management tools are always available, so they are reported as always enabled.
        for (String name : new String[]{"tool_create", "tool_delete", "tool_list", "tool_set_enabled"}) {
            tools.put(new JSONObject().put("name", name).put("kind", "management").put("enabled", true));
        }
        JSONArray definitions = ToolSettings.customTools(context);
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.optJSONObject(i);
            if (definition == null) continue;
            String name = definition.optString("name");
            tools.put(new JSONObject().put("name", name).put("kind", "custom:" + definition.optString("kind"))
                    .put("enabled", ToolSettings.isEnabled(context, name))
                    .put("description", definition.optString("description")));
        }
        return new JSONObject().put("count", tools.length()).put("tools", tools).toString();
    }

    private String runDefinition(String name, JSONObject args) throws IOException, JSONException {
        JSONObject definition = null;
        JSONArray definitions = ToolSettings.customTools(context);
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject candidate = definitions.optJSONObject(i);
            if (candidate != null && name.equals(candidate.optString("name"))) { definition = candidate; break; }
        }
        if (definition == null) throw new IOException("Unknown tool: " + name);
        int timeout = clamp(definition.optInt("timeout_ms", DEFAULT_TIMEOUT_MS));
        if ("python".equals(definition.optString("kind"))) return runPython(definition, args, timeout);
        return runHttp(definition, args, timeout);
    }

    private String runPython(JSONObject definition, JSONObject args, int timeoutMs) throws IOException, JSONException {
        String encoded = Base64.encodeToString(args.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String prelude = "import base64, json\n"
                + "ARGS = json.loads(base64.b64decode('" + encoded + "').decode('utf-8'))\n";
        JSONObject call = new JSONObject()
                .put("code", prelude + definition.optString("code", ""))
                .put("timeout_ms", timeoutMs);
        return python.execute("run_python", call);
    }

    private String runHttp(JSONObject definition, JSONObject args, int timeoutMs) throws IOException, JSONException {
        String method = definition.optString("method", "GET").toUpperCase(Locale.US);
        String url = substitute(definition.optString("url", ""), args);
        String body = substitute(definition.optString("body", ""), args);
        JSONObject headers = definition.optJSONObject("headers");

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(Math.min(timeoutMs, 15_000));
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    connection.setRequestProperty(key, substitute(headers.optString(key, ""), args));
                }
            }
            boolean sendsBody = body != null && !body.isEmpty()
                    && !"GET".equals(method) && !"HEAD".equals(method);
            if (sendsBody) {
                connection.setDoOutput(true);
                if (connection.getRequestProperty("Content-Type") == null) {
                    connection.setRequestProperty("Content-Type", "application/json");
                }
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : read(stream);
            return new JSONObject()
                    .put("status", status)
                    .put("contentType", String.valueOf(connection.getContentType()))
                    .put("body", text).toString();
        } catch (IOException e) {
            throw new IOException("HTTP tool failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String read(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) break;
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String substitute(String template, JSONObject args) {
        if (template == null || template.isEmpty()) return "";
        String result = template;
        Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = args.opt(key);
            result = result.replace("{{" + key + "}}", value == null ? "" : String.valueOf(value));
        }
        return result;
    }

    private static String requireString(JSONObject args, String name) throws IOException {
        Object value = args.opt(name);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IOException("Missing argument: " + name);
        }
        return (String) value;
    }

    private static int clamp(int value) {
        return Math.max(100, Math.min(MAX_TIMEOUT_MS, value));
    }

    private static JSONObject property(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }

    static Set<String> nameSet(JSONArray tools) {
        Set<String> names = new HashSet<>();
        if (tools == null) return names;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool != null) names.add(tool.optString("name"));
        }
        return names;
    }
}
