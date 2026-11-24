package dev.snapgent.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Storage for user-defined tool definitions: one JSON file per tool under
 * {@code <filesDir>/custom-tools/}.
 *
 * <p>Definitions used to live as a single JSON string inside the
 * {@code snapgent_bridge} preferences file. That survived app updates too, but a
 * single value meant one bad write lost every tool at once, and nothing was
 * visible outside the app. A folder fixes both: each tool is its own file, an
 * unreadable file only loses that tool, and the directory sits inside the
 * workspace root so the Files page (and its copy/export path) can see it.
 *
 * <p>Files are written atomically (temporary file then rename), so an interrupted
 * write cannot leave a half-written definition behind.
 */
final class CustomToolStore {

    /** Folder name under the app files directory; also what the Files page shows. */
    static final String DIR = "custom-tools";

    private static final String TEMP_SUFFIX = ".tmp";
    private static final String JSON_SUFFIX = ".json";

    private CustomToolStore() {
    }

    static File directory(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    /** Every stored definition, sorted by name so the catalog is stable between runs. */
    static JSONArray load(Context context) {
        JSONArray tools = new JSONArray();
        File dir = directory(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return tools;

        migrateLegacyPreferences(context, dir);

        File[] files = dir.listFiles();
        if (files == null) return tools;
        List<File> ordered = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(JSON_SUFFIX)) ordered.add(file);
        }
        Collections.sort(ordered, (left, right) -> left.getName().compareTo(right.getName()));

        for (File file : ordered) {
            JSONObject definition = read(file);
            if (definition == null) {
                BridgeLog.warn("CustomToolStore", "Skipping unreadable tool file: " + file.getName());
                continue;
            }
            if (definition.optString("name", "").trim().isEmpty()) {
                BridgeLog.warn("CustomToolStore", "Skipping tool file without a name: " + file.getName());
                continue;
            }
            tools.put(definition);
        }
        return tools;
    }

    /**
     * Writes every definition in {@code tools} and removes files for tools that are
     * no longer present, so the folder always mirrors the catalog exactly.
     */
    static void save(Context context, JSONArray tools) throws IOException {
        File dir = directory(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + DIR + " directory");
        }
        List<String> keep = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject definition = tools.optJSONObject(i);
            if (definition == null) continue;
            String name = definition.optString("name", "").trim();
            if (name.isEmpty()) continue;
            write(dir, name, definition);
            keep.add(name + JSON_SUFFIX);
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            String fileName = file.getName();
            if (fileName.endsWith(TEMP_SUFFIX)) {
                // Left behind by an interrupted write; never part of the catalog.
                file.delete();
                continue;
            }
            if (fileName.endsWith(JSON_SUFFIX) && !keep.contains(fileName)) file.delete();
        }
    }

    /** Removes the file for {@code name}; returns false when there was nothing to remove. */
    static boolean delete(Context context, String name) {
        return new File(directory(context), name + JSON_SUFFIX).delete();
    }

    /** Where a tool's definition lives, for error messages and the Files page. */
    static String pathFor(Context context, String name) {
        return DIR + "/" + name + JSON_SUFFIX;
    }

    private static void write(File dir, String name, JSONObject definition) throws IOException {
        File target = new File(dir, name + JSON_SUFFIX);
        File temporary = new File(dir, name + JSON_SUFFIX + TEMP_SUFFIX);
        String text;
        try {
            text = definition.toString(2);
        } catch (JSONException e) {
            throw new IOException("Tool definition is not valid JSON: " + name);
        }
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(text);
            writer.write("\n");
            writer.flush();
            out.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cannot save " + name + JSON_SUFFIX);
        }
    }

    private static JSONObject read(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * Moves definitions written by older builds out of the preferences file.
     *
     * <p>Runs only when the folder holds no tool yet, so deleting every tool on
     * purpose cannot resurrect old ones; the legacy value is removed once its
     * tools are safely on disk.
     */
    private static void migrateLegacyPreferences(Context context, File dir) {
        SharedPreferences prefs = context.getSharedPreferences(ToolSettings.PREFS, Context.MODE_PRIVATE);
        String legacy = prefs.getString(ToolSettings.LEGACY_CUSTOM_KEY, null);
        if (legacy == null || legacy.trim().isEmpty()) return;

        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File file : existing) {
                if (file.isFile() && file.getName().endsWith(JSON_SUFFIX)) return;
            }
        }

        JSONArray definitions;
        try {
            definitions = new JSONArray(legacy);
        } catch (JSONException e) {
            BridgeLog.warn("CustomToolStore", "Legacy custom tool list is not valid JSON; leaving it alone");
            return;
        }
        try {
            save(context, definitions);
        } catch (IOException e) {
            BridgeLog.warn("CustomToolStore", "Cannot migrate custom tools: " + e.getMessage());
            return;
        }
        prefs.edit().remove(ToolSettings.LEGACY_CUSTOM_KEY).apply();
        BridgeLog.info("CustomToolStore", "Moved " + definitions.length()
                + " custom tool(s) into " + DIR + "/");
    }

    /** Names of the files currently stored, for diagnostics in {@code tool_list}. */
    static List<String> fileNames(Context context) {
        File[] files = directory(context).listFiles();
        if (files == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(JSON_SUFFIX)) {
                names.add(file.getName().substring(0, file.getName().length() - JSON_SUFFIX.length()));
            }
        }
        Collections.sort(names);
        return names;
    }
}
