package dev.snapgent.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashSet;
import java.util.Set;

/**
 * Persisted per-tool switches plus the user-defined tool definitions.
 *
 * <p>Both live in the same preferences file as the bridge config, so a single
 * "reset settings" clears them together. Nothing here touches app-private files:
 * a tool definition is small JSON, and keeping it in preferences means the tool
 * list survives a workspace reset.
 */
final class ToolSettings {

    static final String PREFS = "snapgent_bridge";
    private static final String KEY_DISABLED = "tools.disabled";
    private static final String KEY_CUSTOM = "tools.custom";

    private ToolSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> disabled(Context context) {
        // getStringSet returns a set the framework owns; callers must not mutate it.
        return new HashSet<>(prefs(context).getStringSet(KEY_DISABLED, new HashSet<>()));
    }

    static boolean isEnabled(Context context, String tool) {
        return !disabled(context).contains(tool);
    }

    static void setEnabled(Context context, String tool, boolean enabled) {
        Set<String> next = disabled(context);
        if (enabled) next.remove(tool); else next.add(tool);
        prefs(context).edit().putStringSet(KEY_DISABLED, next).apply();
    }

    static void setAllEnabled(Context context, boolean enabled) {
        if (enabled) prefs(context).edit().remove(KEY_DISABLED).apply();
    }

    static JSONArray customTools(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY_CUSTOM, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void saveCustomTools(Context context, JSONArray tools) {
        prefs(context).edit().putString(KEY_CUSTOM, tools.toString()).apply();
    }
}
