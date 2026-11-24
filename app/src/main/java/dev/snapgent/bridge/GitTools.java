package dev.snapgent.bridge;

import android.content.Context;
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
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
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
    private final Context context;
    private final File root;

    GitTools(Context context) throws IOException {
        this.context = context.getApplicationContext();
        this.root = this.context.getFilesDir().getCanonicalFile();
    }

    static boolean isTool(String name) {
        return Arrays.asList("git_init", "git_status", "git_diff", "git_log", "git_branches", "git_add", "git_commit", "git_clone", "git_remotes", "git_remote_add", "git_remote_remove", "git_fetch", "git_pull", "git_push", "git_checkout", "git_branch_create", "git_stash", "git_stash_list", "git_stash_apply").contains(name);
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
        out.put(tool("git_clone", "Clone a GitHub repository into the Android workspace using stored credentials", props("repo_url", "HTTPS GitHub repository URL", "destination", "Workspace-relative destination directory", "branch", "Optional branch to clone"), "repo_url", "destination"));
        out.put(tool("git_remotes", "List repository remotes without exposing embedded credentials", props("repo_path", "Optional repository directory")));
        out.put(tool("git_remote_add", "Add an HTTPS GitHub remote", props("repo_path", "Optional repository directory", "name", "Remote name", "url", "HTTPS GitHub repository URL"), "name", "url"));
        out.put(tool("git_remote_remove", "Remove a configured Git remote", props("repo_path", "Optional repository directory", "name", "Remote name"), "name"));
        out.put(tool("git_fetch", "Fetch refs from a remote using stored GitHub credentials", props("repo_path", "Optional repository directory", "remote", "Remote name, defaults to origin")));
        out.put(tool("git_pull", "Pull changes from a remote branch using stored GitHub credentials", props("repo_path", "Optional repository directory", "remote", "Remote name, defaults to origin", "branch", "Optional branch name")));
        out.put(tool("git_push", "Push the current or selected branch using stored GitHub credentials", props("repo_path", "Optional repository directory", "remote", "Remote name, defaults to origin", "branch", "Optional branch name")));
        out.put(tool("git_checkout", "Checkout an existing local branch", props("repo_path", "Optional repository directory", "branch", "Local branch name"), "branch"));
        out.put(tool("git_branch_create", "Create a local branch", props("repo_path", "Optional repository directory", "branch", "New branch name", "start_point", "Optional starting revision"), "branch"));
        out.put(tool("git_stash", "Stash tracked and untracked working-tree changes", props("repo_path", "Optional repository directory", "message", "Optional stash message")));
        out.put(tool("git_stash_list", "List repository stashes", props("repo_path", "Optional repository directory")));
        out.put(tool("git_stash_apply", "Apply a stash by zero-based index, defaults to the latest stash", props("repo_path", "Optional repository directory", "index", "Zero-based stash index")));
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

    private CredentialsProvider githubCredentials() throws IOException {
        GitHubCredentials.Credentials credentials = GitHubCredentials.get(context);
        if (credentials == null) throw new IOException("GitHub credentials are not configured. Configure them in Settings first.");
        return new UsernamePasswordCredentialsProvider(credentials.username, credentials.token);
    }

    private URIish validateGitHubUrl(String value) throws IOException {
        if (value == null || value.trim().isEmpty() || value.length() > 2048)
            throw new IOException("Invalid GitHub repository URL");
        try {
            URIish uri = new URIish(value.trim());
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUser() != null || uri.getPass() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || path == null
                    || !path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?/?")) {
                throw new IOException("Only public GitHub HTTPS repository URLs are supported; credentials must never be embedded in URLs");
            }
            return uri;
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid GitHub repository URL", e);
        }
    }

    private void validateRemote(Git git, String remoteName) throws IOException {
        if (remoteName == null || !remoteName.matches("[A-Za-z0-9._-]{1,100}"))
            throw new IOException("Invalid remote name");
        String[] urls = git.getRepository().getConfig().getStringList("remote", remoteName, "url");
        if (urls.length == 0) throw new IOException("Remote has no configured URL: " + remoteName);
        for (String url : urls) validateGitHubUrl(url);
    }

    private static String optionalArg(JSONObject args, String key, String fallback) {
        String value = optionalString(args, key);
        return value.isEmpty() ? fallback : value;
    }

    private static String requiredBranch(JSONObject args, String key) {
        String branch = requiredString(args, key).trim();
        if (branch.isEmpty() || branch.startsWith("-") || branch.contains("..")
                || branch.contains(" ") || branch.contains("\\") || branch.contains("~")
                || branch.contains("^") || branch.contains(":") || branch.contains("?")
                || branch.contains("*") || branch.contains("[") || branch.endsWith("/")
                || branch.endsWith(".") || branch.contains("@{"))
            throw new IllegalArgumentException("Invalid branch name");
        return branch;
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
        if ("git_clone".equals(name)) return cloneRepository(args);
        try (Git git = open(repoDir)) {
            switch (name) {
                case "git_status": return status(git).toString(2);
                case "git_diff": return diff(git, optionalString(args, "path"));
                case "git_log": return log(git, integer(args, "limit", 10, 1, 100)).toString(2);
                case "git_branches": return branches(git).toString(2);
                case "git_add": return add(git, args.optJSONArray("paths"));
                case "git_commit": return commit(git, requiredString(args, "message")).toString(2);
                case "git_remotes": return remotes(git).toString(2);
                case "git_remote_add": return remoteAdd(git, requiredString(args, "name"), requiredString(args, "url"));
                case "git_remote_remove": return remoteRemove(git, requiredString(args, "name"));
                case "git_fetch": return fetch(git, optionalArg(args, "remote", "origin")).toString(2);
                case "git_pull": return pull(git, optionalArg(args, "remote", "origin"), optionalString(args, "branch")).toString(2);
                case "git_push": return push(git, optionalArg(args, "remote", "origin"), optionalString(args, "branch")).toString(2);
                case "git_checkout": return checkout(git, requiredBranch(args, "branch"));
                case "git_branch_create": return branchCreate(git, requiredBranch(args, "branch"), optionalString(args, "start_point"));
                case "git_stash": return stash(git, optionalString(args, "message"));
                case "git_stash_list": return stashList(git).toString(2);
                case "git_stash_apply": return stashApply(git, integer(args, "index", 0, 0, 1000));
                default: throw new IllegalArgumentException("Unknown Git tool: " + name);
            }
        } catch (GitAPIException e) {
            throw new IOException("Git operation failed: " + e.getMessage(), e);
        }
    }

    private String cloneRepository(JSONObject args) throws IOException, JSONException {
        URIish uri = validateGitHubUrl(requiredString(args, "repo_url"));
        String destinationPath = requiredString(args, "destination");
        File destination = resolveWorkspace(destinationPath);
        if (destination.equals(root) || destination.exists())
            throw new IOException("Clone destination must be a new, non-root directory");
        String branch = optionalString(args, "branch");
        if (!branch.isEmpty()) {
            try { org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch); }
            catch (Exception e) { throw new IOException("Invalid branch name", e); }
            if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch))
                throw new IOException("Invalid branch name");
        }
        Git cloned = null;
        try {
            org.eclipse.jgit.api.CloneCommand command = Git.cloneRepository()
                    .setURI(uri.toString())
                    .setDirectory(destination)
                    .setCredentialsProvider(githubCredentials());
            if (!branch.isEmpty()) command.setBranch(branch);
            cloned = command.call();
            return new JSONObject().put("cloned", true)
                    .put("repository", relative(destination))
                    .put("remote", "origin")
                    .put("branch", cloned.getRepository().getBranch()).toString(2);
        } catch (GitAPIException e) {
            throw new IOException("GitHub clone failed: " + e.getMessage(), e);
        } finally {
            if (cloned != null) cloned.close();
        }
    }

    private JSONArray remotes(Git git) throws IOException, JSONException {
        JSONArray out = new JSONArray();
        for (String name : git.getRepository().getConfig().getSubsections("remote")) {
            JSONArray urls = new JSONArray();
            for (String value : git.getRepository().getConfig().getStringList("remote", name, "url")) {
                urls.put(validateGitHubUrl(value).toString());
            }
            out.put(new JSONObject().put("name", name).put("urls", urls));
        }
        return out;
    }

    private String remoteAdd(Git git, String name, String url) throws IOException, JSONException {
        if (!name.matches("[A-Za-z0-9._-]{1,100}")) throw new IOException("Invalid remote name");
        URIish uri = validateGitHubUrl(url);
        org.eclipse.jgit.lib.StoredConfig config = git.getRepository().getConfig();
        if (config.getSubsections("remote").contains(name)) throw new IOException("Remote already exists: " + name);
        config.setString("remote", name, "url", uri.toString());
        config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/" + name + "/*");
        config.save();
        return new JSONObject().put("added", true).put("name", name).put("url", uri.toString()).toString(2);
    }

    private String remoteRemove(Git git, String name) throws IOException, JSONException {
        if (!name.matches("[A-Za-z0-9._-]{1,100}")) throw new IOException("Invalid remote name");
        org.eclipse.jgit.lib.StoredConfig config = git.getRepository().getConfig();
        if (!config.getSubsections("remote").contains(name)) throw new IOException("Remote not found: " + name);
        config.unsetSection("remote", name);
        config.save();
        return new JSONObject().put("removed", true).put("name", name).toString(2);
    }

    private JSONObject fetch(Git git, String remote) throws GitAPIException, IOException, JSONException {
        validateRemote(git, remote);
        org.eclipse.jgit.transport.FetchResult result = git.fetch()
                .setRemote(remote).setCredentialsProvider(githubCredentials()).call();
        return new JSONObject().put("fetched", true).put("remote", remote)
                .put("advertised_refs", result.getAdvertisedRefs().size())
                .put("messages", result.getMessages());
    }

    private JSONObject pull(Git git, String remote, String branch) throws GitAPIException, IOException, JSONException {
        validateRemote(git, remote);
        org.eclipse.jgit.api.PullCommand command = git.pull()
                .setRemote(remote).setCredentialsProvider(githubCredentials());
        if (!branch.isEmpty()) {
            if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch))
                throw new IOException("Invalid branch name");
            command.setRemoteBranchName(branch);
        }
        org.eclipse.jgit.api.PullResult result = command.call();
        String mergeStatus = result.getMergeResult() == null ? "NOT_MERGED" : result.getMergeResult().getMergeStatus().name();
        return new JSONObject().put("successful", result.isSuccessful())
                .put("remote", remote).put("branch", branch.isEmpty() ? git.getRepository().getBranch() : branch)
                .put("merge_status", mergeStatus);
    }

    private JSONObject push(Git git, String remote, String branch) throws GitAPIException, IOException, JSONException {
        validateRemote(git, remote);
        String selectedBranch = branch.isEmpty() ? git.getRepository().getBranch() : branch;
        if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + selectedBranch))
            throw new IOException("Invalid branch name");
        org.eclipse.jgit.api.PushCommand command = git.push()
                .setRemote(remote).setCredentialsProvider(githubCredentials())
                .setRefSpecs(new RefSpec("refs/heads/" + selectedBranch + ":refs/heads/" + selectedBranch));
        JSONArray updates = new JSONArray();
        boolean successful = true;
        for (org.eclipse.jgit.transport.PushResult result : command.call()) {
            for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                String status = update.getStatus().name();
                if (!"OK".equals(status) && !"UP_TO_DATE".equals(status)) successful = false;
                updates.put(new JSONObject().put("ref", update.getRemoteName()).put("status", status)
                        .put("message", update.getMessage() == null ? "" : update.getMessage()));
            }
        }
        return new JSONObject().put("successful", successful).put("remote", remote)
                .put("branch", selectedBranch).put("updates", updates);
    }

    private String checkout(Git git, String branch) throws GitAPIException, IOException, JSONException {
        if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch)) throw new IOException("Invalid branch name");
        git.checkout().setName(branch).call();
        return new JSONObject().put("checked_out", true).put("branch", git.getRepository().getBranch()).toString(2);
    }

    private String branchCreate(Git git, String branch, String startPoint) throws GitAPIException, IOException, JSONException {
        if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch)) throw new IOException("Invalid branch name");
        org.eclipse.jgit.api.CreateBranchCommand command = git.branchCreate().setName(branch);
        if (!startPoint.isEmpty()) command.setStartPoint(startPoint);
        org.eclipse.jgit.lib.Ref created = command.call();
        return new JSONObject().put("created", true).put("branch", created.getName()).toString(2);
    }

    private String stash(Git git, String message) throws GitAPIException, JSONException {
        org.eclipse.jgit.api.StashCreateCommand command = git.stashCreate().setIncludeUntracked(true);
        if (!message.isEmpty()) command.setWorkingDirectoryMessage(message);
        RevCommit created = command.call();
        if (created == null) return new JSONObject().put("stashed", false).put("message", "No changes to stash").toString(2);
        return new JSONObject().put("stashed", true).put("hash", created.getName())
                .put("message", created.getShortMessage()).toString(2);
    }

    private JSONArray stashList(Git git) throws GitAPIException, JSONException {
        JSONArray out = new JSONArray();
        int index = 0;
        for (RevCommit commit : git.stashList().call()) {
            out.put(new JSONObject().put("index", index++).put("hash", commit.getName())
                    .put("message", commit.getShortMessage()).put("timestamp", commit.getCommitTime()));
        }
        return out;
    }

    private String stashApply(Git git, int index) throws GitAPIException, JSONException {
        git.stashApply().setStashRef("stash@{" + index + "}").call();
        return new JSONObject().put("applied", true).put("index", index).toString(2);
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
