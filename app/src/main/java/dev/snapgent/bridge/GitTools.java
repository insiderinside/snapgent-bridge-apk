package dev.snapgent.bridge;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Git operations backed by JGit and confined to the app-private workspace. */
final class GitTools {
    private static final int MAX_PATHS = 50;
    private final File root;

    GitTools(File workspaceRoot) throws IOException {
        this.root = workspaceRoot.getCanonicalFile();
    }

    static boolean isTool(String name) {
        return Arrays.asList("git_init", "git_status", "git_diff", "git_log", "git_branches", "git_add", "git_commit").contains(name);
    }

    JSONArray catalog() throws JSONException {
        JSONArray out = new JSONArray();
        out.put(tool("git_init", "Initialize a Git repository inside the Android workspace", props("repo_path", "Optional workspace-relative repository directory")));
        out.put(tool("git_status", "Show Git branch and working-tree status", props("repo_path", "Optional workspace-relative repository directory")));
        out.put(tool("git_diff", "Show unstaged Git diff, optionally filtered by path", props("repo_path", "Optional repository directory", "path", "Optional repository-relative path")));
        out.put(tool("git_log", "Show recent Git commits", props("repo_path", "Optional repository directory", "limit", "Maximum commits from 1 to 100")));
        out.put(tool("git_branches", "List local and remote Git branches", props("repo_path", "Optional repository directory")));
        out.put(tool("git_add", "Stage selected paths in the Git repository", props("repo_path", "Optional repository directory", "paths", "Array of repository-relative paths"), "paths"));
        out.put(tool("git_commit", "Commit already staged Git changes", props("repo_path", "Optional repository directory", "message", "Commit message"), "message"));
        return out;
    }

    private static JSONObject props(String... pairs) throws JSONException {
        JSONObject out = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i];
            JSONObject p = new JSONObject().put("type", key.equals("paths") ? "array" : key.equals("limit") ? "integer" : "string")
                    .put("description", pairs[i + 1]);
            if (key.equals("paths")) p.put("items", new JSONObject().put("type", "string"));
            out.put(key, p);
        }
        return out;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required) throws JSONException {
        JSONArray fields = new JSONArray();
        for (String field : required) fields.put(field);
        return new JSONObject().put("name", name).put("description", description)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties)
                        .put("required", fields).put("additionalProperties", false));
    }

    String execute(String name, JSONObject args) throws IOException, JSONException {
        File repoDir = resolveWorkspace(optionalString(args, "repo_path"));
        if ("git_init".equals(name)) {
            if (!repoDir.isDirectory()) throw new IOException("Repository path is not a directory");
            if (new File(repoDir, ".git").exists()) throw new IOException("Git metadata already exists");
            try (Git ignored = Git.init().setDirectory(repoDir).call()) {
                return new JSONObject().put("initialized", true).put("path", relative(repoDir)).put("branch", "master").toString(2);
            } catch (GitAPIException e) { throw new IOException("Git init failed: " + e.getMessage(), e); }
        }
        try (Git git = open(repoDir)) {
            switch (name) {
                case "git_status": return status(git).toString(2);
                case "git_diff": return diff(git, optionalString(args, "path"));
                case "git_log": return log(git, integer(args, "limit", 10, 1, 100)).toString(2);
                case "git_branches": return branches(git).toString(2);
                case "git_add": return add(git, args.optJSONArray("paths"));
                case "git_commit": return commit(git, requiredString(args, "message")).toString(2);
                default: throw new IllegalArgumentException("Unknown Git tool: " + name);
            }
        } catch (GitAPIException e) {
            throw new IOException("Git operation failed: " + e.getMessage(), e);
        }
    }

    private Git open(File dir) throws IOException {
        try { return Git.open(dir); }
        catch (IOException e) { throw new IOException("Not a Git repository: " + relative(dir) + ". Run git_init first.", e); }
    }

    private JSONObject status(Git git) throws GitAPIException, JSONException, IOException {
        Status s = git.status().call();
        return new JSONObject().put("repository", relative(git.getRepository().getWorkTree()))
                .put("branch", git.getRepository().getBranch()).put("is_clean", s.isClean())
                .put("added", strings(s.getAdded())).put("changed", strings(s.getChanged()))
                .put("modified", strings(s.getModified())).put("removed", strings(s.getRemoved()))
                .put("missing", strings(s.getMissing())).put("untracked", strings(s.getUntracked()))
                .put("untracked_folders", strings(s.getUntrackedFolders())).put("conflicting", strings(s.getConflicting()));
    }

    private String diff(Git git, String path) throws IOException, GitAPIException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setRepository(git.getRepository());
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            org.eclipse.jgit.api.DiffCommand command = git.diff();
            if (!path.isEmpty() && !".".equals(path)) command.setPathFilter(PathFilter.create(validateGitPath(git.getRepository(), path)));
            List<DiffEntry> entries = command.call();
            for (DiffEntry entry : entries) {
                formatter.format(entry);
                if (output.size() > 512 * 1024) break;
            }
            formatter.flush();
        }
        byte[] bytes = output.toByteArray();
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return text.length() > 512 * 1024 ? text.substring(0, 512 * 1024) + "\n[diff truncated]" : text;
    }

    private JSONArray log(Git git, int limit) throws GitAPIException, JSONException {
        JSONArray out = new JSONArray();
        try {
            for (RevCommit c : git.log().setMaxCount(limit).call()) {
                PersonIdent author = c.getAuthorIdent();
                out.put(new JSONObject().put("hash", c.getName()).put("short_hash", c.abbreviate(8).name())
                        .put("message", c.getShortMessage()).put("author", author == null ? "" : author.getName())
                        .put("email", author == null ? "" : author.getEmailAddress())
                        .put("timestamp", c.getCommitTime()));
            }
        } catch (NoHeadException ignored) { }
        return out;
    }

    private JSONArray branches(Git git) throws GitAPIException, JSONException, IOException {
        JSONArray out = new JSONArray();
        String current = git.getRepository().getFullBranch();
        for (Ref ref : git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL).call()) {
            String name = ref.getName();
            out.put(new JSONObject().put("name", name).put("current", name.equals(current))
                    .put("type", name.startsWith("refs/remotes/") ? "remote" : "local"));
        }
        return out;
    }

    private String add(Git git, JSONArray paths) throws IOException, JSONException, GitAPIException {
        if (paths == null || paths.length() < 1 || paths.length() > MAX_PATHS) throw new IOException("Expected 1-50 paths");
        org.eclipse.jgit.api.AddCommand command = git.add();
        List<String> staged = new ArrayList<>();
        for (int i = 0; i < paths.length(); i++) {
            String path = paths.optString(i, null);
            if (path == null || path.isEmpty()) throw new IOException("Invalid path at index " + i);
            String clean = validateGitPath(git.getRepository(), path);
            command.addFilepattern(clean);
            staged.add(clean);
        }
        command.call();
        return new JSONObject().put("staged", new JSONArray(staged)).toString(2);
    }

    private JSONObject commit(Git git, String message) throws IOException, JSONException, GitAPIException {
        if (message.trim().isEmpty() || message.length() > 10000) throw new IOException("Commit message must contain 1-10000 characters");
        String name = git.getRepository().getConfig().getString("user", null, "name");
        String email = git.getRepository().getConfig().getString("user", null, "email");
        if (name == null || name.trim().isEmpty()) name = "Snapgent Android";
        if (email == null || email.trim().isEmpty()) email = "snapgent@localhost";
        PersonIdent ident = new PersonIdent(name, email);
        RevCommit result = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
        return new JSONObject().put("committed", true).put("hash", result.getName())
                .put("short_hash", result.abbreviate(8).name()).put("message", result.getShortMessage())
                .put("author", name).put("email", email);
    }

    private String validateGitPath(Repository repo, String value) throws IOException {
        String path = value.replace('\\', '/');
        if (path.startsWith("/") || path.indexOf('\u0000') >= 0) throw new SecurityException("Git paths must be relative");
        for (String part : path.split("/")) if ("..".equals(part) || ".git".equals(part)) throw new SecurityException("Git path escapes or targets metadata: " + value);
        File workTree = repo.getWorkTree().getCanonicalFile();
        File target = new File(workTree, path).getCanonicalFile();
        if (!target.equals(workTree) && !target.getPath().startsWith(workTree.getPath() + File.separator))
            throw new SecurityException("Git path escapes repository: " + value);
        if (Files.isSymbolicLink(new File(workTree, path).toPath())) throw new SecurityException("Symbolic-link Git paths are not allowed");
        String relative = workTree.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/');
        return relative.isEmpty() ? "." : relative;
    }

    private File resolveWorkspace(String path) throws IOException {
        if (path.startsWith("/") || path.indexOf('\u0000') >= 0) throw new SecurityException("Repository path must be workspace-relative");
        for (String part : path.split("/")) if ("..".equals(part)) throw new SecurityException("Repository path escapes workspace");
        File dir = new File(root, path.isEmpty() ? "." : path).getCanonicalFile();
        if (!dir.equals(root) && !dir.getPath().startsWith(root.getPath() + File.separator)) throw new SecurityException("Repository path escapes workspace");
        if (Files.isSymbolicLink(new File(root, path.isEmpty() ? "." : path).toPath())) throw new SecurityException("Symbolic-link repository paths are not allowed");
        return dir;
    }

    private String relative(File file) {
        File canonical;
        try { canonical = file.getCanonicalFile(); } catch (IOException e) { canonical = file; }
        return canonical.equals(root) ? "" : canonical.getPath().substring(root.getPath().length() + 1).replace(File.separatorChar, '/');
    }

    private static JSONArray strings(Collection<String> values) throws JSONException {
        JSONArray out = new JSONArray();
        for (String value : new TreeSet<>(values)) out.put(value);
        return out;
    }

    private static String optionalString(JSONObject args, String key) {
        Object value = args.opt(key);
        if (value == null) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string: " + key);
        return (String)value;
    }

    private static String requiredString(JSONObject args, String key) {
        String value = optionalString(args, key);
        if (value.isEmpty()) throw new IllegalArgumentException("Missing string: " + key);
        return value;
    }

    private static int integer(JSONObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        Object value = args.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer: " + key);
        int result = ((Number)value).intValue();
        if (result < min || result > max) throw new IllegalArgumentException("Argument out of range: " + key);
        return result;
    }
}
