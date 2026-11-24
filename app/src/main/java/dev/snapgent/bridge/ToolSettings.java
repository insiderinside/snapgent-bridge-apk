package dev.snapgent.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Persisted per-tool switches, plus a compatibility bridge to the custom tool store.
 *
 * <p>The enable/disable set still lives in the {@code snapgent_bridge} preferences
 * file, because it is a handful of names and always travels with the rest of the
 * settings. Tool <em>definitions</em> moved to {@link CustomToolStore}: one JSON
 * file per tool under {@code custom-tools/}, so an app update, a settings reset or
 * one unreadable file can no longer take every custom tool down with it.
 */
final class ToolSettings {

    static final String PREFS = "snapgent_bridge";
    private static final String KEY_DISABLED = "tools.disabled";

    /** Key older builds used for the whole custom tool list; only read to migrate. */
    static final String LEGACY_CUSTOM_KEY = "tools.custom";

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
        return CustomToolStore.load(context);
    }

    static void saveCustomTools(Context context, JSONArray tools) throws IOException {
        CustomToolStore.save(context, tools);
    }

    /** Kept for callers that only need the old JSON parse, without touching disk order. */
    static JSONArray parse(String raw) {
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
