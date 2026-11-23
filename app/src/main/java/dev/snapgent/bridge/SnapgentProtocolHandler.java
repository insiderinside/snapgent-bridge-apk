package dev.snapgent.bridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Adapter for the snapgentV3 extension's native WebSocket message protocol. */
public final class SnapgentProtocolHandler {
    private static final String SERVER_ID = "android";
    private final McpHandler mcp;
    private final JSONArray catalog;

    public SnapgentProtocolHandler(McpHandler mcp) {
        this.mcp = mcp;
        try {
            JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list");
            JSONArray nativeTools = new JSONObject(mcp.handle(request.toString())).getJSONObject("result").getJSONArray("tools");
            catalog = new JSONArray();
            for (int i = 0; i < nativeTools.length(); i++) {
                JSONObject source = nativeTools.getJSONObject(i);
                catalog.put(new JSONObject()
                        .put("name", source.getString("name"))
                        .put("server", SERVER_ID)
                        .put("description", source.optString("description"))
                        .put("inputSchema", source.getJSONObject("inputSchema")));
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to construct snapgentV3 tool catalog", e);
        }
    }

    public JSONObject connected() throws JSONException {
        return new JSONObject()
                .put("type", "connected")
                .put("bridge_version", "android-0.2.0")
                .put("mcp_alive", true)
                .put("tools", catalog)
                .put("servers", new JSONArray().put(new JSONObject()
                        .put("id", SERVER_ID)
                        .put("alive", true)
                        .put("tools", catalog.length())));
    }

    public String handle(String raw) {
        JSONObject request;
        try {
            request = new JSONObject(raw);
        } catch (JSONException e) {
            return error(JSONObject.NULL, "Invalid JSON request").toString();
        }
        Object id = request.has("id") ? request.opt("id") : JSONObject.NULL;
        String type = request.optString("type", "");
        try {
            switch (type) {
                case "ping":
                    return new JSONObject().put("type", "pong").put("id", id).toString();
                case "studio_status":
                    return new JSONObject().put("type", "studio_status").put("id", id)
                            .put("studio", JSONObject.NULL).put("studio_app", JSONObject.NULL)
                            .put("studio_proc", JSONObject.NULL).toString();
                case "list_tools":
                    return connected().put("type", "tools").put("id", id).toString();
                case "call_tool":
                    return callTool(request, id).toString();
                case "restart_mcp":
                    return new JSONObject().put("type", "mcp_status").put("id", id)
                            .put("ok", true).put("alive", true)
                            .put("tools", catalog).put("servers", connected().getJSONArray("servers")).toString();
                case "stream":
                case "progress":
                    return null; // Fire-and-forget telemetry frames.
                default:
                    return error(id, "Unsupported Android bridge request: " + type).toString();
            }
        } catch (JSONException e) {
            return error(id, "Invalid bridge request").toString();
        }
    }

    private JSONObject callTool(JSONObject request, Object id) throws JSONException {
        String requestedServer = request.optString("server", SERVER_ID);
        String name = request.optString("name", "");
        if (name.startsWith(SERVER_ID + "/")) name = name.substring(SERVER_ID.length() + 1);
        if (!SERVER_ID.equals(requestedServer) || name.isEmpty()) {
            return toolError(id, "Tool is not available on the selected Android server");
        }
        JSONObject arguments = request.optJSONObject("arguments");
        if (arguments == null) arguments = new JSONObject();
        JSONObject rpc = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", new JSONObject().put("name", name).put("arguments", arguments));
        String raw = mcp.handle(rpc.toString());
        if (raw == null) return toolError(id, "No MCP tool response");
        JSONObject response = new JSONObject(raw);
        if (response.has("error")) {
            return toolError(id, response.getJSONObject("error").optString("message", "Unknown MCP error"));
        }
        JSONObject result = response.optJSONObject("result");
        if (result == null) return toolError(id, "Empty MCP result");
        JSONArray parts = result.optJSONArray("content");
        StringBuilder text = new StringBuilder();
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && "text".equals(part.optString("type"))) {
                if (text.length() > 0) text.append('\n');
                text.append(part.optString("text"));
            }
        }
        if (result.optBoolean("isError", false)) return toolError(id, text.toString());
        return new JSONObject().put("type", "tool_result").put("id", id)
                .put("ok", true).put("text", text.toString()).put("images", new JSONArray());
    }

    private JSONObject toolError(Object id, String message) throws JSONException {
        return new JSONObject().put("type", "tool_result").put("id", id)
                .put("ok", false).put("kind", "tool_error").put("error", message);
    }

    private JSONObject error(Object id, String message) {
        try {
            return new JSONObject().put("type", "error").put("id", id).put("error", message);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
