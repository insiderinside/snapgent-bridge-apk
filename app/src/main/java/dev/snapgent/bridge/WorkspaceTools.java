package dev.snapgent.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** VS Code-style file tools, confined to the app's own private workspace. */
final class WorkspaceTools {
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_VISITED = 1500;
    private static final int MAX_RESULTS = 120;
    private static final int MAX_COMMAND_OUTPUT = 256 * 1024;
    private final File root;

    WorkspaceTools(Context context) {
        try { root = context.getApplicationContext().getFilesDir().getCanonicalFile(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    JSONArray catalog() throws JSONException {
        JSONArray out = new JSONArray();
        out.put(tool("get_workspace_info", "Get Android app-private workspace information", props()));
        out.put(tool("get_project_info", "Find project manifests in the Android workspace", props()));
        out.put(tool("run_command", "Run an allowlisted Android utility without a shell; paths stay inside the workspace", props("command", "Allowed utility name", "args", "Array of utility arguments", "cwd", "Relative working directory", "timeout_ms", "Timeout from 100 to 30000 ms"), "command"));
        out.put(tool("list_dir", "List one workspace directory", props("path", "Relative directory, defaults to root")));
        out.put(tool("list_tree", "List a bounded workspace directory tree", props("path", "Relative directory", "max_depth", "Depth from 1 to 6")));
        out.put(tool("find_files", "Find files matching a glob, up to 120 results", props("path", "Relative directory", "pattern", "File glob, for example *.md")));
        out.put(tool("search_files", "Search literal text within workspace files", props("path", "Relative directory", "query", "Text to find", "file_pattern", "Optional glob", "case_sensitive", "Case-sensitive match"), "path", "query"));
        out.put(tool("read_files", "Read up to 20 UTF-8 workspace files", props("paths", "Array of relative file paths"), "paths"));
        out.put(tool("read_file_lines", "Read numbered file lines, max 500 lines", props("path", "Relative UTF-8 file", "start_line", "First 1-based line", "end_line", "Last inclusive line"), "path"));
        out.put(tool("get_file_info", "Get file type, size and modification time", props("path", "Relative path"), "path"));
        out.put(tool("get_file_outline", "Find class and function declarations in source code", props("path", "Relative source file"), "path"));
        out.put(tool("create_file", "Create UTF-8 file without overwriting existing files", props("path", "Relative file path", "content", "Optional file content"), "path"));
        out.put(tool("edit_file", "Replace exact text, unique by default", props("path", "Relative file path", "old_text", "Text to replace", "new_text", "Replacement text", "replace_all", "Replace all matches"), "path", "old_text", "new_text"));
        out.put(tool("create_directory", "Create directory in workspace", props("path", "Relative directory"), "path"));
        out.put(tool("move_file", "Move workspace file or directory without overwrite", props("source", "Source path", "destination", "Destination path"), "source", "destination"));
        out.put(tool("copy_file", "Copy workspace file without overwrite", props("source", "Source path", "destination", "Destination path"), "source", "destination"));
        out.put(tool("delete_file", "Delete one file; directory deletion is refused", props("path", "Relative file path"), "path"));
        out.put(tool("apply_patch", "Apply exact unique text edits together, all or none", props("path", "Relative file path", "edits", "Array of old_text/new_text objects"), "path", "edits"));
        return out;
    }

    private static JSONObject props(String... pairs) throws JSONException {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i];
            String type = key.equals("paths") || key.equals("edits") || key.equals("args") ? "array" :
                    key.equals("case_sensitive") || key.equals("replace_all") ? "boolean" :
                    key.equals("max_depth") || key.equals("start_line") || key.equals("end_line") || key.equals("timeout_ms") ? "integer" : "string";
            JSONObject p = new JSONObject().put("type", type).put("description", pairs[i + 1]);
            if (key.equals("paths") || key.equals("args")) p.put("items", new JSONObject().put("type", "string"));
            if (key.equals("edits")) p.put("items", new JSONObject().put("type", "object")
                    .put("properties", props("old_text", "Exact old text", "new_text", "Replacement"))
                    .put("required", new JSONArray().put("old_text").put("new_text")));
            result.put(key, p);
        }
        return result;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required) throws JSONException {
        JSONArray fields = new JSONArray();
        for (String field : required) fields.put(field);
        return new JSONObject().put("name", name).put("description", description)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties)
                        .put("required", fields).put("additionalProperties", false));
    }

    String execute(String name, JSONObject args) throws IOException, JSONException {
        switch (name) {
            case "get_workspace_info": return new JSONObject().put("name", "Android workspace")
                    .put("path", root.getPath()).put("is_git_repository", new File(root, ".git").isDirectory())
                    .put("storage", "app-private only").toString(2);
            case "get_project_info": {
                JSONArray manifests = new JSONArray();
                for (String file : new String[]{"package.json", "pyproject.toml", "Cargo.toml", "go.mod", "build.gradle", "settings.gradle", "pom.xml"})
                    if (resolve(file).isFile()) manifests.put(file);
                return new JSONObject().put("root", root.getPath()).put("manifests", manifests).toString(2);
            }
            case "run_command": return runCommand(args);
            case "list_dir": return listDir(str(args, "path", true)).toString(2);
            case "list_tree": return listTree(str(args, "path", true), number(args, "max_depth", 3, 1, 6)).toString(2);
            case "find_files": return findFiles(str(args, "path", true), str(args, "pattern", true)).toString(2);
            case "search_files": return searchFiles(str(args, "path", true), str(args, "query", false),
                    str(args, "file_pattern", true), args.optBoolean("case_sensitive", false)).toString(2);
            case "read_files": return readFiles(args.optJSONArray("paths")).toString(2);
            case "read_file_lines": return readLines(str(args, "path", false),
                    number(args, "start_line", 1, 1, Integer.MAX_VALUE),
                    number(args, "end_line", -1, -1, Integer.MAX_VALUE));
            case "get_file_info": return info(resolve(str(args, "path", true))).toString(2);
            case "get_file_outline": return outline(str(args, "path", false)).toString(2);
            case "create_file": {
                String path = str(args, "path", false);
                File file = resolve(path);
                if (file.exists()) throw new IOException("Already exists: " + path);
                write(file, str(args, "content", true), true);
                return "Created: " + path;
            }
            case "edit_file": {
                String path = str(args, "path", false);
                String before = read(resolve(path));
                String after = replace(before, str(args, "old_text", false), str(args, "new_text", true),
                        args.optBoolean("replace_all", false));
                write(resolve(path), after, false);
                return "Edited: " + path;
            }
            case "create_directory": {
                String path = str(args, "path", false);
                File dir = resolve(path);
                if (dir.exists() && !dir.isDirectory()) throw new IOException("A file already exists: " + path);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create directory: " + path);
                return "Directory: " + path;
            }
            case "move_file": {
                moveOrCopy(resolve(str(args, "source", false)), resolve(str(args, "destination", false)), false);
                return "Moved";
            }
            case "copy_file": {
                moveOrCopy(resolve(str(args, "source", false)), resolve(str(args, "destination", false)), true);
                return "Copied";
            }
            case "delete_file": {
                File file = resolve(str(args, "path", false));
                if (!file.isFile() || !file.delete()) throw new IOException("Cannot delete file");
                return "Deleted";
            }
            case "apply_patch": {
                String path = str(args, "path", false);
                String content = read(resolve(path));
                JSONArray edits = args.optJSONArray("edits");
                if (edits == null || edits.length() == 0 || edits.length() > 40) throw new IOException("Expected 1-40 edits");
                for (int i = 0; i < edits.length(); i++) {
                    JSONObject item = edits.optJSONObject(i);
                    if (item == null) throw new IOException("Invalid edit at index " + i);
                    content = replace(content, str(item, "old_text", false), str(item, "new_text", true), false);
                }
                write(resolve(path), content, false);
                return "Patched " + edits.length() + " edit(s): " + path;
            }
            default: throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }

    private String runCommand(JSONObject args) throws IOException, JSONException {
        String command = str(args, "command", false).toLowerCase(Locale.ROOT);
        String cwdPath = str(args, "cwd", true);
        File cwd = resolve(cwdPath);
        if (!cwd.isDirectory()) throw new IOException("Working directory is not a directory: " + cwdPath);
        JSONArray input = args.optJSONArray("args");
        List<String> raw = new ArrayList<>();
        if (input != null) {
            if (input.length() > 32) throw new IOException("Maximum 32 command arguments");
            for (int i = 0; i < input.length(); i++) {
                Object value = input.opt(i);
                if (!(value instanceof String) || ((String)value).length() > 4096 || ((String)value).indexOf('\u0000') >= 0)
                    throw new IOException("Invalid command argument at index " + i);
                raw.add((String)value);
            }
        }
        List<String> argv = new ArrayList<>();
        switch (command) {
            case "pwd":
                if (!raw.isEmpty()) throw new IOException("pwd accepts no arguments");
                return new JSONObject().put("command", command).put("cwd", relative(cwd))
                        .put("exit_code", 0).put("output", cwd.getAbsolutePath()).toString(2);
            case "ls": {
                for (String value : raw) {
                    if (value.startsWith("-")) {
                        if (!Arrays.asList("-a", "-l", "-la", "-al", "-1", "-h", "-lh", "-lah", "-alh").contains(value))
                            throw new IOException("Unsupported ls option: " + value);
                        argv.add(value);
                    } else argv.add(commandPath(cwd, value));
                }
                break;
            }
            case "cat":
            case "stat":
            case "sha256sum":
                if (raw.isEmpty()) throw new IOException(command + " requires at least one file path");
                for (String value : raw) argv.add(commandPath(cwd, value));
                break;
            case "head":
            case "tail": {
                int i = 0;
                if (raw.size() >= 2 && "-n".equals(raw.get(0))) {
                    int n;
                    try { n = Integer.parseInt(raw.get(1)); } catch (NumberFormatException e) { throw new IOException("Invalid line count"); }
                    if (n < 1 || n > 5000) throw new IOException("Line count must be 1-5000");
                    argv.add("-n"); argv.add(String.valueOf(n)); i = 2;
                }
                if (i >= raw.size()) throw new IOException(command + " requires a file path");
                for (; i < raw.size(); i++) argv.add(commandPath(cwd, raw.get(i)));
                break;
            }
            case "wc": {
                int i = 0;
                while (i < raw.size() && raw.get(i).startsWith("-")) {
                    if (!Arrays.asList("-l", "-w", "-c", "-lw", "-wl", "-lc", "-cl", "-lwc", "-wlc").contains(raw.get(i)))
                        throw new IOException("Unsupported wc option: " + raw.get(i));
                    argv.add(raw.get(i++));
                }
                if (i >= raw.size()) throw new IOException("wc requires a file path");
                for (; i < raw.size(); i++) argv.add(commandPath(cwd, raw.get(i)));
                break;
            }
            case "grep": {
                int i = 0;
                boolean hasPattern = false;
                while (i < raw.size()) {
                    String value = raw.get(i);
                    if ("-e".equals(value)) {
                        if (i + 1 >= raw.size()) throw new IOException("grep -e requires a pattern");
                        argv.add(value); argv.add(raw.get(i + 1)); i += 2; hasPattern = true; break;
                    }
                    if (value.startsWith("-") && !hasPattern) {
                        if (!Arrays.asList("-n", "-i", "-v", "-E", "-F", "-c", "-l", "-w", "-o").contains(value))
                            throw new IOException("Unsupported grep option: " + value);
                        argv.add(value); i++; continue;
                    }
                    argv.add(value); i++; hasPattern = true; break;
                }
                if (!hasPattern || i >= raw.size()) throw new IOException("grep requires a pattern and at least one file path");
                for (; i < raw.size(); i++) argv.add(commandPath(cwd, raw.get(i)));
                break;
            }
            case "date":
                for (String value : raw) {
                    if (!"-u".equals(value)) throw new IOException("Only date -u is supported");
                    argv.add(value);
                }
                break;
            case "uname":
                for (String value : raw) {
                    if (!Arrays.asList("-a", "-m", "-n", "-r", "-s", "-v").contains(value)) throw new IOException("Unsupported uname option: " + value);
                    argv.add(value);
                }
                break;
            case "df":
                for (String value : raw) {
                    if (!Arrays.asList("-h", "-k").contains(value)) throw new IOException("Unsupported df option: " + value);
                    argv.add(value);
                }
                break;
            default:
                throw new IOException("Command is not allowed: " + command + ". Allowed: pwd, ls, cat, head, tail, wc, grep, stat, sha256sum, date, uname, df. No shell or arbitrary executables.");
        }
        List<String> processArgs = new ArrayList<>();
        processArgs.add("/system/bin/toybox");
        processArgs.add(command);
        processArgs.addAll(argv);
        Process process = new ProcessBuilder(processArgs).directory(cwd).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = stream.read(buffer)) != -1;) {
                    synchronized (captured) {
                        int remaining = MAX_COMMAND_OUTPUT - captured.size();
                        if (remaining > 0) captured.write(buffer, 0, Math.min(remaining, n));
                        if (n > remaining) truncated.set(true);
                    }
                }
            } catch (IOException ignored) { }
        }, "snapgent-command-output");
        reader.setDaemon(true);
        reader.start();
        int timeout = number(args, "timeout_ms", 10000, 100, 30000);
        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            }
            reader.join(1500);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted");
        }
        if (!finished) throw new IOException("Command timed out after " + timeout + " ms");
        String output;
        synchronized (captured) { output = captured.toString(StandardCharsets.UTF_8.name()); }
        return new JSONObject().put("command", command).put("cwd", relative(cwd))
                .put("exit_code", process.exitValue()).put("output", output)
                .put("truncated", truncated.get()).toString(2);
    }

    private String commandPath(File cwd, String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\u0000') >= 0)
            throw new SecurityException("Command paths must be relative to cwd");
        File target = new File(cwd, path).getCanonicalFile();
        if (!target.equals(root) && !target.getPath().startsWith(root.getPath() + File.separator))
            throw new SecurityException("Command path escapes workspace: " + path);
        if (Files.isSymbolicLink(new File(cwd, path).toPath()))
            throw new SecurityException("Symbolic-link command paths are not allowed: " + path);
        return target.getAbsolutePath();
    }

    private static String str(JSONObject args, String key, boolean allowEmpty) {
        Object value = args.opt(key);
        if (value == null && allowEmpty) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string: " + key);
        String s = (String) value;
        if (!allowEmpty && s.isEmpty()) throw new IllegalArgumentException("Empty argument: " + key);
        return s;
    }

    private static int number(JSONObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        Object value = args.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer: " + key);
        int n = ((Number) value).intValue();
        if (n < min || n > max) throw new IllegalArgumentException("Argument out of range: " + key);
        return n;
    }

    private File resolve(String path) throws IOException {
        if (path.startsWith("/") || path.indexOf('\u0000') >= 0)
            throw new SecurityException("Only relative workspace paths are allowed");
        File file = new File(root, path.isEmpty() ? "." : path).getCanonicalFile();
        if (!file.equals(root) && !file.getPath().startsWith(root.getPath() + File.separator))
            throw new SecurityException("Path escapes workspace");
        return file;
    }

    private String relative(File file) {
        return file.equals(root) ? "" : file.getPath().substring(root.getPath().length() + 1).replace(File.separatorChar, '/');
    }

    private JSONObject info(File file) throws JSONException {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return new JSONObject().put("name", name).put("path", relative(file))
                .put("type", file.isDirectory() ? "directory" : file.isFile() ? "file" : "missing")
                .put("extension", dot > 0 ? name.substring(dot + 1) : "")
                .put("bytes", file.isFile() ? file.length() : 0)
                .put("modified_ms", file.lastModified());
    }

    private static File[] sorted(File dir) throws IOException {
        File[] entries = dir.listFiles();
        if (entries == null) throw new IOException("Cannot list directory");
        Arrays.sort(entries, Comparator.comparing(File::getName));
        return entries;
    }

    private JSONArray listDir(String path) throws IOException, JSONException {
        File dir = resolve(path);
        if (!dir.isDirectory()) throw new IOException("Not a directory: " + path);
        JSONArray out = new JSONArray();
        for (File entry : sorted(dir)) out.put(info(entry));
        return out;
    }

    private List<File> visit(File start, int maxDepth) throws IOException {
        List<File> result = new ArrayList<>();
        traverse(start, maxDepth, 0, new int[]{0}, result);
        return result;
    }

    private void traverse(File file, int limit, int depth, int[] count, List<File> result) throws IOException {
        if (++count[0] > MAX_VISITED) throw new IOException("Traversal exceeded 1500 entries");
        if (Files.isSymbolicLink(file.toPath())) return;
        result.add(file);
        if (file.isDirectory() && depth < limit)
            for (File child : sorted(file)) traverse(child, limit, depth + 1, count, result);
    }

    private JSONArray listTree(String path, int depth) throws IOException, JSONException {
        File dir = resolve(path);
        if (!dir.isDirectory()) throw new IOException("Not a directory: " + path);
        JSONArray out = new JSONArray();
        for (File file : visit(dir, depth)) {
            if (!file.equals(dir)) out.put(info(file));
            if (out.length() >= MAX_RESULTS) break;
        }
        return out;
    }

    private static boolean glob(String pattern, String relative) {
        if (pattern.isEmpty() || pattern.equals("*") || pattern.equals("**/*")) return true;
        String p = pattern.replace('\\', '/');
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') {
                if (i + 1 < p.length() && p.charAt(i + 1) == '*') { regex.append(".*"); i++; }
                else regex.append("[^/]*");
            } else if (c == '?') regex.append("[^/]");
            else { if (".()[]{}+$^|".indexOf(c) >= 0) regex.append('\\'); regex.append(c); }
        }
        regex.append('$');
        String target = p.contains("/") ? relative : new File(relative).getName();
        return Pattern.compile(regex.toString()).matcher(target).matches();
    }

    private JSONArray findFiles(String path, String pattern) throws IOException, JSONException {
        File dir = resolve(path);
        if (!dir.isDirectory()) throw new IOException("Not a directory: " + path);
        JSONArray out = new JSONArray();
        for (File file : visit(dir, 6)) {
            if (file.isFile() && glob(pattern, relative(file))) out.put(relative(file));
            if (out.length() >= MAX_RESULTS) break;
        }
        return out;
    }

    private JSONArray searchFiles(String path, String query, String pattern, boolean sensitive) throws IOException, JSONException {
        File start = resolve(path);
        if (!start.exists()) throw new IOException("Path missing: " + path);
        List<File> files = start.isFile() ? Collections.singletonList(start) : visit(start, 6);
        String needle = sensitive ? query : query.toLowerCase(Locale.ROOT);
        JSONArray out = new JSONArray();
        for (File file : files) {
            if (!file.isFile() || file.length() > MAX_BYTES || !glob(pattern, relative(file))) continue;
            String[] lines = read(file).split("\\r?\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String haystack = sensitive ? lines[i] : lines[i].toLowerCase(Locale.ROOT);
                if (haystack.contains(needle)) {
                    out.put(new JSONObject().put("path", relative(file)).put("line", i + 1)
                            .put("text", lines[i].length() > 300 ? lines[i].substring(0, 300) : lines[i]));
                    if (out.length() >= MAX_RESULTS) return out;
                }
            }
        }
        return out;
    }

    private JSONArray readFiles(JSONArray paths) throws IOException, JSONException {
        if (paths == null || paths.length() < 1 || paths.length() > 20) throw new IOException("Expected 1-20 paths");
        JSONArray out = new JSONArray();
        int total = 0;
        for (int i = 0; i < paths.length(); i++) {
            String path = paths.optString(i, null);
            if (path == null) throw new IOException("Invalid path at index " + i);
            String text = read(resolve(path));
            total += text.getBytes(StandardCharsets.UTF_8).length;
            if (total > 1024 * 1024) throw new IOException("Combined read exceeds 1 MiB");
            out.put(new JSONObject().put("path", path).put("content", text));
        }
        return out;
    }

    private String readLines(String path, int start, int end) throws IOException {
        String[] lines = read(resolve(path)).split("\\r?\\n", -1);
        if (end < 0) end = Math.min(lines.length, start + 199);
        if (end < start || end - start >= 500 || start > lines.length)
            throw new IOException("Invalid line range, maximum 500 lines");
        StringBuilder out = new StringBuilder();
        for (int n = start; n <= end && n <= lines.length; n++) out.append(n).append(": ").append(lines[n - 1]).append('\n');
        return out.toString();
    }

    private JSONArray outline(String path) throws IOException, JSONException {
        String[] lines = read(resolve(path)).split("\\r?\\n", -1);
        Pattern declaration = Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|async|export|abstract|override|open)\\s+)*(?:class|interface|enum|def|function|fun)\\s+([\\w$]+)");
        Pattern method = Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|async|export|override)\\s+)*(?:[\\w<>\\[\\],?]+\\s+)?([\\w$]+)\\s*\\([^)]*\\)\\s*(?:\\{|:|throws|=>)");
        JSONArray out = new JSONArray();
        for (int i = 0; i < lines.length && out.length() < MAX_RESULTS; i++) {
            Matcher m = declaration.matcher(lines[i]);
            if (!m.find()) m = method.matcher(lines[i]);
            if (m.find(0)) out.put(new JSONObject().put("line", i + 1).put("name", m.group(1)).put("signature", lines[i].trim()));
        }
        return out;
    }

    private String read(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Not a file: " + relative(file));
        if (file.length() > MAX_BYTES) throw new IOException("File exceeds 512 KiB");
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) != -1;) {
                if (out.size() + n > MAX_BYTES) throw new IOException("File exceeds 512 KiB");
                out.write(chunk, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String replace(String source, String oldText, String newText, boolean all) throws IOException {
        int first = source.indexOf(oldText);
        if (first < 0) throw new IOException("Exact old_text not found");
        if (!all && source.indexOf(oldText, first + oldText.length()) >= 0)
            throw new IOException("old_text occurs more than once");
        return all ? source.replace(oldText, newText)
                : source.substring(0, first) + newText + source.substring(first + oldText.length());
    }

    private void write(File file, String text, boolean createOnly) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Content exceeds 512 KiB");
        if (file.equals(root) || file.isDirectory()) throw new IOException("Not a writable file");
        if (createOnly && file.exists()) throw new IOException("File already exists");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create parent directory");
        File temporary = File.createTempFile(".snapgent-", ".tmp", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (createOnly) Files.move(temporary.toPath(), file.toPath());
            else Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }

    private void moveOrCopy(File source, File destination, boolean copy) throws IOException {
        if (source.equals(root) || !source.exists() || Files.isSymbolicLink(source.toPath()))
            throw new IOException("Invalid source");
        if (destination.equals(root) || destination.exists()) throw new IOException("Destination exists");
        if (destination.getPath().startsWith(source.getPath() + File.separator))
            throw new IOException("Cannot move into itself");
        if (copy && !source.isFile()) throw new IOException("Only files can be copied");
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create destination directory");
        if (copy) Files.copy(source.toPath(), destination.toPath());
        else Files.move(source.toPath(), destination.toPath());
    }
}
