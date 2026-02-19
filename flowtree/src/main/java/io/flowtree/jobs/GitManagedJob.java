/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.job.Job;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.JobOutput;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Abstract base class for jobs that manage their changes via git.
 *
 * <p>After the main work completes (via {@link #doWork()}), this class handles:
 * <ul>
 *   <li>Creating/switching to the target branch (if specified)</li>
 *   <li>Staging changed files (with guardrails)</li>
 *   <li>Committing with a descriptive message</li>
 *   <li>Pushing to origin</li>
 * </ul>
 *
 * <h2>Guardrails</h2>
 * <ul>
 *   <li>Files exceeding {@link #maxFileSizeBytes} are not committed (default: 1MB)</li>
 *   <li>Binary files matching common patterns are excluded</li>
 *   <li>Sensitive files (.env, credentials, keys) are excluded</li>
 *   <li>Generated/build directories are excluded</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyJob extends GitManagedJob {
 *     public MyJob(String taskId) {
 *         super(taskId);
 *         setTargetBranch("feature/my-work");
 *     }
 *
 *     @Override
 *     protected void doWork() {
 *         // Perform the actual work here
 *     }
 *
 *     @Override
 *     protected String getCommitMessage() {
 *         return "Completed my task";
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 */
public abstract class GitManagedJob implements Job, ConsoleFeatures {

    /** Default maximum file size to commit (1 MB). */
    public static final long DEFAULT_MAX_FILE_SIZE = 1024 * 1024;

    /** File patterns that are always excluded from commits. */
    private static final Set<String> DEFAULT_EXCLUDED_PATTERNS = new HashSet<>(Arrays.asList(
        // Secrets and credentials
        ".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx",
        "credentials.json", "secrets.json", "**/secrets/**",

        // Build outputs and dependencies
        "target/**", "build/**", "dist/**", "out/**",
        "node_modules/**", ".gradle/**", ".m2/**",
        "*.class", "*.jar", "*.war", "*.ear",

        // IDE and OS files
        ".idea/**", ".vscode/**", "*.iml",
        ".DS_Store", "Thumbs.db",

        // Binary and media files
        "*.exe", "*.dll", "*.so", "*.dylib",
        "*.zip", "*.tar", "*.gz", "*.rar", "*.7z",
        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.ico",
        "*.mp3", "*.mp4", "*.wav", "*.avi", "*.mov",
        "*.pdf", "*.doc", "*.docx", "*.xls", "*.xlsx",

        // Database and logs
        "*.db", "*.sqlite", "*.log",

        // Hardware acceleration outputs (AR-specific)
        "Extensions/**", "*.cl", "*.metal",

        // Claude Code agent outputs and settings
        "claude-output/**", "commit.txt",
        ".claude/**", "settings.local.json"
    ));

    private String taskId;
    private String targetBranch;
    private String baseBranch = "master";
    private String workingDirectory;
    private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;
    private Set<String> excludedPatterns = new HashSet<>(DEFAULT_EXCLUDED_PATTERNS);
    private Set<String> additionalExcludedPatterns = new HashSet<>();
    private boolean pushToOrigin = true;
    private boolean createBranchIfMissing = true;
    private boolean dryRun = false;
    private String gitUserName;
    private String gitUserEmail;

    private String originalBranch;
    private List<String> stagedFiles = new ArrayList<>();
    private List<String> skippedFiles = new ArrayList<>();
    private String commitHash;
    private String pullRequestUrl;
    private boolean gitOperationsSuccessful = false;

    private Consumer<JobOutput> outputConsumer;
    private final CompletableFuture<Void> future = new CompletableFuture<>();

    private String workstreamUrl;

    /**
     * Default constructor for deserialization.
     */
    protected GitManagedJob() {
    }

    /**
     * Creates a new GitManagedJob with the specified task ID.
     *
     * @param taskId the task ID for tracking
     */
    protected GitManagedJob(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Performs the main work of this job.
     * Subclasses must implement this method.
     * Git operations occur after this method completes.
     */
    protected abstract void doWork();

    /**
     * Returns the commit message for changes made by this job.
     * Subclasses should override to provide a descriptive message.
     *
     * @return the commit message
     */
    protected String getCommitMessage() {
        return "Changes from job: " + getTaskString();
    }

    @Override
    public final void run() {
        Exception error = null;

        try {
            // Prepare working directory: verify clean state, checkout branch,
            // pull latest. This must happen before doWork() so the agent
            // operates on the current remote state of the target branch.
            if (targetBranch != null && !targetBranch.isEmpty()) {
                originalBranch = getCurrentBranch();
                prepareWorkingDirectory();
            }

            // Perform the actual work
            doWork();

            // Handle git operations if a target branch is specified
            if (targetBranch != null && !targetBranch.isEmpty()) {
                handleGitOperations();
            }

        } catch (Exception e) {
            warn("Error: " + e.getMessage(), e);
            error = e;
        } finally {
            // Fire completion event
            fireJobCompleted(error);
            future.complete(null);
        }
    }

    /**
     * Prepares the working directory before the agent starts work.
     *
     * <p>This method ensures the repo is in a clean, up-to-date state:
     * <ol>
     *   <li>Checks for uncommitted changes (excluding ignored files like
     *       claude-output, .claude settings, etc.). Fails if any are found.</li>
     *   <li>Fetches latest refs from origin.</li>
     *   <li>Checks out the target branch (creating it if needed).</li>
     *   <li>Pulls the latest changes from origin (fast-forward only).
     *       Fails if the local branch has diverged.</li>
     * </ol>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException if any pre-flight check fails
     */
    private void prepareWorkingDirectory() throws IOException, InterruptedException {
        // 1. Check for uncommitted changes (excluding ignored files)
        List<String> dirtyFiles = checkForUncommittedChanges();
        if (!dirtyFiles.isEmpty()) {
            // Discard uncommitted changes left by a previous job so the
            // working directory can be synced cleanly.  This is safe because
            // agent workers should never have manual edits; any uncommitted
            // changes are residue from a prior (likely failed) job run.
            String fileList = dirtyFiles.size() <= 5
                ? String.join(", ", dirtyFiles)
                : String.join(", ", dirtyFiles.subList(0, 5)) + " (+" + (dirtyFiles.size() - 5) + " more)";
            warn("Uncommitted changes found: " + fileList + " -- resetting to clean state");
            if (executeGit("checkout", ".") != 0) {
                throw new RuntimeException(
                    "Failed to discard uncommitted changes: " + fileList);
            }
            // Also remove untracked files that are not in .gitignore
            executeGit("clean", "-fd");
            log("Working directory cleaned");
        }

        // 2. Fetch latest from origin
        log("Fetching latest from origin...");
        if (executeGit("fetch", "origin") != 0) {
            throw new RuntimeException("Failed to fetch from origin");
        }

        // 3. Checkout target branch
        if (!ensureOnTargetBranch()) {
            throw new RuntimeException("Failed to switch to target branch: " + targetBranch);
        }

        // 4. Sync with remote if remote branch exists
        boolean remoteBranchExists = executeGit(
            "show-ref", "--verify", "--quiet",
            "refs/remotes/origin/" + targetBranch) == 0;
        if (remoteBranchExists) {
            log("Syncing with origin/" + targetBranch + "...");
            int pullResult = executeGit("pull", "--ff-only", "origin", targetBranch);
            if (pullResult != 0) {
                // Fast-forward failed, likely because the local branch diverged
                // (e.g., a previous job's commit was force-pushed or rebased).
                // Since we already verified there are no uncommitted changes,
                // reset to match the remote exactly. This ensures tool server
                // files (MCP Python scripts, etc.) are always up to date.
                log("Fast-forward pull failed; resetting to origin/" + targetBranch);
                int resetResult = executeGit("reset", "--hard", "origin/" + targetBranch);
                if (resetResult != 0) {
                    throw new RuntimeException(
                        "Failed to sync with origin/" + targetBranch +
                        " -- both pull and reset failed");
                }
            }
            String headHash = executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
            log("Working directory is up to date with origin/" + targetBranch + " at " + headHash);
        } else {
            log("No remote branch origin/" + targetBranch + " -- skipping pull");
        }
    }

    /**
     * Checks the working directory for uncommitted changes, excluding files
     * that match the job's excluded patterns (e.g., claude-output, .claude
     * settings, build artifacts).
     *
     * @return list of dirty file paths that are NOT excluded
     */
    private List<String> checkForUncommittedChanges() throws IOException, InterruptedException {
        String statusOutput = executeGitWithOutput("status", "--porcelain");
        List<String> dirtyFiles = new ArrayList<>();

        Set<String> allExcluded = new HashSet<>(excludedPatterns);
        allExcluded.addAll(additionalExcludedPatterns);

        for (String line : statusOutput.split("\n")) {
            if (line.length() > 3) {
                String file = line.substring(3).trim();
                if (file.contains(" -> ")) {
                    file = file.split(" -> ")[1];
                }
                if (!file.isEmpty() && !matchesAnyPattern(file, allExcluded)) {
                    dirtyFiles.add(file);
                }
            }
        }

        if (!dirtyFiles.isEmpty()) {
            warn("Found " + dirtyFiles.size() + " uncommitted file(s) not in exclude list");
        } else {
            log("Working directory is clean (excluding ignored files)");
        }

        return dirtyFiles;
    }

    /**
     * Fires the job completed event by POSTing to the workstream URL.
     * The controller's {@code SlackNotifier} receives this event and
     * formats an appropriate Slack message, so no separate Slack message
     * is sent from here.
     */
    protected void fireJobCompleted(Exception error) {
        JobCompletionEvent event;

        if (error != null) {
            event = JobCompletionEvent.failed(
                taskId, getTaskString(),
                error.getMessage(), error
            );
        } else {
            event = JobCompletionEvent.success(taskId, getTaskString());
        }

        event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles,
            gitOperationsSuccessful && pushToOrigin && !stagedFiles.isEmpty());
        if (pullRequestUrl != null) {
            event.withPullRequestUrl(pullRequestUrl);
        }
        populateEventDetails(event);
        postStatusEvent(event);
    }

    /**
     * Subclasses can override to add additional details to completion events.
     *
     * @param event the event to populate
     */
    protected void populateEventDetails(JobCompletionEvent event) {
        // Default implementation does nothing
        // Subclasses like ClaudeCodeJob override to add prompt, sessionId, etc.
    }

    /**
     * Handles all git operations: branch management, staging, committing, and pushing.
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException if a git operation (branch switch, commit, push) fails
     */
    private void handleGitOperations() throws IOException, InterruptedException {
        log("Starting git operations...");
        log("Target branch: " + targetBranch);

        // Ensure we're on the target branch
        if (!ensureOnTargetBranch()) {
            String msg = "Failed to switch to target branch '" + targetBranch + "'" +
                " (working directory: " + (workingDirectory != null ? workingDirectory : System.getProperty("user.dir")) + ")";
            throw new RuntimeException(msg);
        }

        // Find and filter changed files
        List<String> changedFiles = findChangedFiles();
        if (changedFiles.isEmpty()) {
            log("No changes to commit");
            gitOperationsSuccessful = true;
            return;
        }

        // Stage files (with guardrails)
        stageFiles(changedFiles);
        if (stagedFiles.isEmpty()) {
            log("No files passed guardrails, nothing to commit");
            gitOperationsSuccessful = true;
            return;
        }

        // Commit
        if (!commit()) {
            throw new RuntimeException("Git commit failed after staging " + stagedFiles.size() + " files");
        }

        // Push to origin
        if (pushToOrigin && !dryRun) {
            if (!pushToOrigin()) {
                throw new RuntimeException("Git push to origin/" + targetBranch + " failed");
            }
        }

        // Detect open PR for the target branch (if remote is GitHub)
        pullRequestUrl = detectPullRequestUrl();
        if (pullRequestUrl != null) {
            log("Open PR: " + pullRequestUrl);
        }

        gitOperationsSuccessful = true;
        log("Git operations completed successfully");
    }

    /**
     * Ensures we're on the target branch, creating it if necessary.
     */
    private boolean ensureOnTargetBranch() throws IOException, InterruptedException {
        String currentBranch = getCurrentBranch();

        if (targetBranch.equals(currentBranch)) {
            log("Already on target branch: " + targetBranch);
            return true;
        }

        // Check if target branch exists
        boolean branchExists = branchExists(targetBranch);

        if (!branchExists && !createBranchIfMissing) {
            warn("Target branch does not exist and createBranchIfMissing=false");
            return false;
        }

        if (dryRun) {
            log("DRY RUN: Would " +
                (branchExists ? "checkout" : "create and checkout") + " branch: " + targetBranch);
            return true;
        }

        if (branchExists) {
            // Checkout existing branch
            return executeGit("checkout", targetBranch) == 0;
        } else {
            // Create new branch from the configured base branch.
            // Use --no-track so the new branch does NOT inherit
            // origin/<baseBranch> as its upstream; pushToOrigin() will
            // set the upstream to origin/<targetBranch> explicitly.
            String startPoint = "origin/" + baseBranch;
            log("Creating new branch: " + targetBranch + " from " + startPoint);
            return executeGit("checkout", "-b", targetBranch, "--no-track", startPoint) == 0;
        }
    }

    /**
     * Finds all changed files (modified, added, deleted, untracked).
     */
    private List<String> findChangedFiles() throws IOException, InterruptedException {
        List<String> files = new ArrayList<>();

        // Get modified/deleted/added (tracked)
        String statusOutput = executeGitWithOutput("status", "--porcelain");
        for (String line : statusOutput.split("\n")) {
            if (line.length() > 3) {
                String file = line.substring(3).trim();
                // Handle renamed files (old -> new)
                if (file.contains(" -> ")) {
                    file = file.split(" -> ")[1];
                }
                if (!file.isEmpty()) {
                    files.add(file);
                }
            }
        }

        log("Found " + files.size() + " changed files");
        return files;
    }

    /**
     * Stages files that pass all guardrails.
     */
    private void stageFiles(List<String> changedFiles) throws IOException, InterruptedException {
        Set<String> allExcluded = new HashSet<>(excludedPatterns);
        allExcluded.addAll(additionalExcludedPatterns);

        for (String file : changedFiles) {
            File f = resolveFile(file);

            // Check if file was deleted
            boolean isDeleted = !f.exists();

            // Guardrail 1: Check excluded patterns
            if (matchesAnyPattern(file, allExcluded)) {
                log("SKIP (pattern): " + file);
                skippedFiles.add(file + " (excluded pattern)");
                continue;
            }

            // Guardrail 2: Check file size (only for existing files)
            if (!isDeleted && f.length() > maxFileSizeBytes) {
                log("SKIP (size " + formatSize(f.length()) + "): " + file);
                skippedFiles.add(file + " (exceeds " + formatSize(maxFileSizeBytes) + ")");
                continue;
            }

            // Guardrail 3: Check if binary (only for existing files)
            if (!isDeleted && isBinaryFile(f)) {
                log("SKIP (binary): " + file);
                skippedFiles.add(file + " (binary file)");
                continue;
            }

            // File passed all guardrails
            if (dryRun) {
                log("DRY RUN: Would stage: " + file);
            } else {
                if (executeGit("add", file) == 0) {
                    stagedFiles.add(file);
                    log("Staged: " + file);
                } else {
                    warn("Failed to stage: " + file);
                }
            }
        }

        log("Staged " + stagedFiles.size() + " files, skipped " + skippedFiles.size());
    }

    /**
     * Commits the staged changes.
     *
     * <p>If {@link #gitUserName} and/or {@link #gitUserEmail} are set,
     * they are passed via {@code git -c user.name=... -c user.email=...}
     * directly on the command line, which is the most reliable way to
     * override identity regardless of container or SSH environment.</p>
     */
    private boolean commit() throws IOException, InterruptedException {
        String message = getCommitMessage();

        if (dryRun) {
            log("DRY RUN: Would commit with message: " + message);
            return true;
        }

        // Build command with identity config flags for reliability.
        // The -c flags must appear before the subcommand ("commit").
        List<String> args = new ArrayList<>();
        if (gitUserName != null && !gitUserName.isEmpty()) {
            args.add("-c");
            args.add("user.name=" + gitUserName);
        }
        if (gitUserEmail != null && !gitUserEmail.isEmpty()) {
            args.add("-c");
            args.add("user.email=" + gitUserEmail);
        }
        args.add("commit");
        args.add("-m");
        args.add(message);

        log("Committing as: " +
            (gitUserName != null ? gitUserName : "(default)") + " <" +
            (gitUserEmail != null ? gitUserEmail : "(default)") + ">");

        int result = executeGit(args.toArray(new String[0]));
        if (result == 0) {
            // Get the commit hash
            commitHash = executeGitWithOutput("rev-parse", "HEAD").trim();
            log("Committed: " + commitHash);

            // Clean up commit.txt so it is not reused by a subsequent run
            File commitFile = resolveFile("commit.txt");
            if (commitFile.exists()) {
                if (commitFile.delete()) {
                    log("Cleaned up commit.txt");
                } else {
                    log("Warning: could not delete commit.txt");
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Pushes changes to origin.
     *
     * <p>Uses an explicit refspec ({@code targetBranch:targetBranch})
     * so the push always targets the correct remote branch, regardless
     * of any inherited upstream tracking configuration.</p>
     */
    private boolean pushToOrigin() throws IOException, InterruptedException {
        log("Pushing to origin/" + targetBranch + "...");

        // Use explicit refspec so the push always targets the correct
        // remote branch, even if the local branch was created from a
        // different base (e.g., origin/master).  The -u flag sets the
        // upstream to origin/<targetBranch> for subsequent operations.
        String refspec = targetBranch + ":" + targetBranch;
        int result = executeGit("push", "-u", "origin", refspec);
        if (result == 0) {
            log("Pushed to origin/" + targetBranch);
            return true;
        }

        return false;
    }

    // ==================== PR Detection ====================

    /**
     * Detects an open pull request URL for the target branch.
     *
     * <p>Only attempts detection if the origin remote points to GitHub.
     * Uses the GitHub REST API directly (via {@link HttpURLConnection})
     * to query for an open PR, authenticated with a {@code GITHUB_TOKEN}
     * or {@code GH_TOKEN} environment variable.</p>
     *
     * @return the PR URL, or null if no PR was found
     */
    private String detectPullRequestUrl() {
        try {
            // Check if remote is GitHub
            String remoteUrl = executeGitWithOutput("remote", "get-url", "origin").trim();
            if (!remoteUrl.contains("github.com")) {
                return null;
            }

            // Extract owner/repo from remote URL
            // Handles both SSH (git@github.com:owner/repo.git) and
            // HTTPS (https://github.com/owner/repo.git) formats
            String ownerRepo = extractOwnerRepo(remoteUrl);
            if (ownerRepo == null) {
                log("Could not extract owner/repo from remote URL: " + remoteUrl);
                return null;
            }

            // Look for GITHUB_TOKEN or GH_TOKEN
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isEmpty()) {
                token = System.getenv("GH_TOKEN");
            }
            if (token == null || token.isEmpty()) {
                log("No GITHUB_TOKEN or GH_TOKEN set, cannot query GitHub API for PR");
                return null;
            }

            // Query GitHub API for open PRs on this branch
            String apiUrl = "https://api.github.com/repos/" + ownerRepo +
                "/pulls?head=" + ownerRepo.split("/")[0] + ":" + targetBranch +
                "&state=open&per_page=1";

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log("GitHub API returned " + responseCode + " for PR query");
                return null;
            }

            // Parse the JSON array response with Jackson
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(conn.getInputStream());
            if (root.isArray() && root.size() > 0) {
                JsonNode firstPr = root.get(0);
                JsonNode htmlUrlNode = firstPr.get("html_url");
                if (htmlUrlNode != null && htmlUrlNode.isTextual()) {
                    String prUrl = htmlUrlNode.asText();
                    if (prUrl.startsWith("https://github.com/") && prUrl.contains("/pull/")) {
                        return prUrl;
                    }
                }
            }
        } catch (Exception e) {
            log("Could not detect PR URL: " + e.getMessage());
        }

        return null;
    }

    /**
     * Extracts the {@code owner/repo} string from a GitHub remote URL.
     *
     * <p>Supports both SSH ({@code git@github.com:owner/repo.git}) and
     * HTTPS ({@code https://github.com/owner/repo.git}) formats.</p>
     *
     * @param remoteUrl the git remote URL
     * @return the owner/repo string, or null if not parseable
     */
    private static String extractOwnerRepo(String remoteUrl) {
        // SSH format: git@github.com:owner/repo.git
        if (remoteUrl.contains("git@github.com:")) {
            String path = remoteUrl.substring(remoteUrl.indexOf("git@github.com:") + 15);
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String validated = validateOwnerRepo(path);
            if (validated != null) return validated;
        }

        // HTTPS format: https://github.com/owner/repo.git
        if (remoteUrl.contains("github.com/")) {
            String path = remoteUrl.substring(remoteUrl.indexOf("github.com/") + 11);
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String validated = validateOwnerRepo(path);
            if (validated != null) return validated;
        }

        return null;
    }

    /**
     * Validates that a path is exactly {@code owner/repo} -- two non-empty
     * parts separated by a single slash.
     *
     * @param path the candidate owner/repo string
     * @return the path if valid, or null
     */
    private static String validateOwnerRepo(String path) {
        String[] parts = path.split("/");
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return path;
        }
        return null;
    }

    // ==================== Git Utilities ====================

    /**
     * Applies git identity environment variables to a {@link ProcessBuilder}.
     *
     * <p>Uses {@code GIT_AUTHOR_NAME}, {@code GIT_AUTHOR_EMAIL},
     * {@code GIT_COMMITTER_NAME}, and {@code GIT_COMMITTER_EMAIL} so the
     * identity is scoped to the process and never persisted in the repo's
     * local config.</p>
     */
    private void applyGitIdentity(ProcessBuilder pb) {
        if (gitUserName != null && !gitUserName.isEmpty()) {
            pb.environment().put("GIT_AUTHOR_NAME", gitUserName);
            pb.environment().put("GIT_COMMITTER_NAME", gitUserName);
        }
        if (gitUserEmail != null && !gitUserEmail.isEmpty()) {
            pb.environment().put("GIT_AUTHOR_EMAIL", gitUserEmail);
            pb.environment().put("GIT_COMMITTER_EMAIL", gitUserEmail);
        }
    }

    private String getCurrentBranch() throws IOException, InterruptedException {
        return executeGitWithOutput("rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    private boolean branchExists(String branch) throws IOException, InterruptedException {
        // Check local branches
        int result = executeGit("show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        if (result == 0) return true;

        // Check remote branches
        result = executeGit("show-ref", "--verify", "--quiet", "refs/remotes/origin/" + branch);
        return result == 0;
    }

    private int executeGit(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);

        // Prevent SSH from hanging on unknown host keys (no TTY available)
        pb.environment().put("GIT_SSH_COMMAND",
                "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes");
        applyGitIdentity(pb);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            warn("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output.toString().trim());
        }

        return exitCode;
    }

    private String executeGitWithOutput(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);

        // Prevent SSH from hanging on unknown host keys (no TTY available)
        pb.environment().put("GIT_SSH_COMMAND",
                "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes");
        applyGitIdentity(pb);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor();
        return output.toString();
    }

    /**
     * Executes an arbitrary command and returns its output.
     * Used for non-git commands like {@code gh}.
     */
    private String executeCommandWithOutput(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor();
        return output.toString();
    }

    // ==================== File Utilities ====================

    private File resolveFile(String path) {
        if (workingDirectory != null) {
            return new File(workingDirectory, path);
        }
        return new File(path);
    }

    private boolean matchesAnyPattern(String path, Set<String> patterns) {
        for (String pattern : patterns) {
            if (matchesGlobPattern(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesGlobPattern(String path, String pattern) {
        // Convert glob pattern to regex by processing tokens so that
        // replacing '*' does not corrupt the '.*' produced by '**'.
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                // "**/" matches zero or more directories
                if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') {
                    regex.append("(.*/)?");
                    i += 3;
                } else {
                    // trailing "**" matches everything
                    regex.append(".*");
                    i += 2;
                }
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (c == '.') {
                regex.append("\\.");
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }

        String r = regex.toString();
        return Pattern.matches(r, path) ||
               Pattern.matches(".*/" + r, path) ||
               path.endsWith("/" + pattern) ||
               path.equals(pattern);
    }

    private boolean isBinaryFile(File file) {
        if (!file.exists() || file.isDirectory()) {
            return false;
        }

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            int checkLength = Math.min(bytes.length, 8000);

            int nullCount = 0;
            for (int i = 0; i < checkLength; i++) {
                if (bytes[i] == 0) {
                    nullCount++;
                    if (nullCount > checkLength / 10) {
                        return true; // More than 10% null bytes suggests binary
                    }
                }
            }
            return false;
        } catch (IOException e) {
            // If we can't read it, assume it might be binary
            return true;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ==================== Job Interface ====================

    /** {@inheritDoc} */
    @Override
    public String formatMessage(String msg) {
        String prefix = getLogClass().getSimpleName();
        String id = getTaskId();
        if (id != null && !id.isEmpty()) {
            return prefix + " [" + id + "]: " + msg;
        }
        return prefix + ": " + msg;
    }

    @Override
    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    @Override
    public CompletableFuture<Void> getCompletableFuture() {
        return future;
    }

    @Override
    public void setOutputConsumer(Consumer<JobOutput> outputConsumer) {
        this.outputConsumer = outputConsumer;
    }

    protected Consumer<JobOutput> getOutputConsumer() {
        return outputConsumer;
    }

    // ==================== Configuration ====================

    public String getTargetBranch() {
        return targetBranch;
    }

    /**
     * Sets the target branch for commits.
     * If null or empty, git operations are skipped.
     */
    public void setTargetBranch(String targetBranch) {
        this.targetBranch = targetBranch;
    }

    /**
     * Returns the base branch used as the starting point when creating
     * a new target branch.
     */
    public String getBaseBranch() {
        return baseBranch;
    }

    /**
     * Sets the base branch used as the starting point when creating
     * a new target branch. Defaults to {@code "master"}.
     *
     * <p>When the target branch does not exist, the job creates it from
     * {@code origin/<baseBranch>} (after fetching) rather than from
     * whatever HEAD happens to be checked out.</p>
     *
     * @param baseBranch the branch name to base new branches on
     */
    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    /**
     * Sets the maximum file size that will be committed.
     * Files larger than this are skipped.
     *
     * @param maxFileSizeBytes maximum size in bytes (default: 1MB)
     */
    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public boolean isPushToOrigin() {
        return pushToOrigin;
    }

    /**
     * Sets whether to push commits to origin after committing.
     *
     * @param pushToOrigin true to push (default), false to only commit locally
     */
    public void setPushToOrigin(boolean pushToOrigin) {
        this.pushToOrigin = pushToOrigin;
    }

    public boolean isCreateBranchIfMissing() {
        return createBranchIfMissing;
    }

    /**
     * Sets whether to create the target branch if it doesn't exist.
     *
     * @param createBranchIfMissing true to create (default), false to fail if missing
     */
    public void setCreateBranchIfMissing(boolean createBranchIfMissing) {
        this.createBranchIfMissing = createBranchIfMissing;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Sets dry run mode. When true, git operations are logged but not executed.
     *
     * @param dryRun true to enable dry run mode
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Returns the git user name for commits.
     */
    public String getGitUserName() {
        return gitUserName;
    }

    /**
     * Sets the git user name for commits made by this job.
     * When set, the name is passed via {@code git -c user.name=...}
     * on the commit command line.
     *
     * @param gitUserName the name to use in git commits
     */
    public void setGitUserName(String gitUserName) {
        this.gitUserName = gitUserName;
    }

    /**
     * Returns the git user email for commits.
     */
    public String getGitUserEmail() {
        return gitUserEmail;
    }

    /**
     * Sets the git user email for commits made by this job.
     * When set, the email is passed via {@code git -c user.email=...}
     * on the commit command line.
     *
     * @param gitUserEmail the email to use in git commits
     */
    public void setGitUserEmail(String gitUserEmail) {
        this.gitUserEmail = gitUserEmail;
    }

    /**
     * Adds additional patterns to exclude from commits.
     *
     * @param patterns glob patterns to exclude
     */
    public void addExcludedPatterns(String... patterns) {
        additionalExcludedPatterns.addAll(Arrays.asList(patterns));
    }

    /**
     * Clears all default excluded patterns.
     * Use with caution - this removes safety guardrails.
     */
    public void clearDefaultExcludedPatterns() {
        excludedPatterns.clear();
    }

    /**
     * Returns the workstream URL for this job.
     * This URL serves as a prefix for controller interactions:
     * POST to the URL itself sends status events, appending
     * {@code /messages} sends Slack messages.
     */
    public String getWorkstreamUrl() {
        return workstreamUrl;
    }

    /**
     * Sets the workstream URL for this job.
     *
     * <p>The URL follows the pattern
     * {@code http://controller/api/workstreams/{id}/jobs/{jobId}} for job-level
     * communication (messages go to the job's Slack thread) or
     * {@code http://controller/api/workstreams/{id}} for workstream-level
     * communication (messages go to the channel).</p>
     *
     * @param workstreamUrl the controller URL for this job's workstream
     */
    public void setWorkstreamUrl(String workstreamUrl) {
        this.workstreamUrl = workstreamUrl;
    }

    // ==================== Results ====================

    /**
     * Returns the list of files that were staged.
     */
    public List<String> getStagedFiles() {
        return new ArrayList<>(stagedFiles);
    }

    /**
     * Returns the list of files that were skipped (with reasons).
     */
    public List<String> getSkippedFiles() {
        return new ArrayList<>(skippedFiles);
    }

    /**
     * Returns the commit hash if a commit was made.
     */
    public String getCommitHash() {
        return commitHash;
    }

    /**
     * Returns the pull request URL if one was detected after push.
     */
    public String getPullRequestUrl() {
        return pullRequestUrl;
    }

    /**
     * Returns whether git operations completed successfully.
     */
    public boolean isGitOperationsSuccessful() {
        return gitOperationsSuccessful;
    }

    /**
     * Returns the original branch before any checkout occurred.
     */
    public String getOriginalBranch() {
        return originalBranch;
    }

    // ==================== Status Reporting ====================

    /**
     * POSTs a job status event as JSON to the {@link #workstreamUrl}.
     * Fire-and-forget: errors are logged but do not fail the job.
     *
     * @param event the event to report
     */
    private void postStatusEvent(JobCompletionEvent event) {
        if (workstreamUrl == null || workstreamUrl.isEmpty()) {
            return;
        }

        String baseUrl = resolveWorkstreamUrl();
        String json = buildEventJson(event);

        log("Posting status event (" + event.getStatus() + ") to " + baseUrl);
        postJson(baseUrl, json);
    }

    /**
     * Resolves the workstream URL, replacing the {@code 0.0.0.0} placeholder
     * with the controller's actual address from {@code FLOWTREE_ROOT_HOST}.
     *
     * <p>Subclasses should use this method when passing the workstream URL
     * to external processes (e.g., environment variables for MCP tools).</p>
     *
     * @return the resolved URL, or the original URL if no resolution is needed
     */
    protected String resolveWorkstreamUrl() {
        String url = workstreamUrl;

        String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
        if (rootHost != null && !rootHost.isEmpty() && url.contains("0.0.0.0")) {
            url = url.replace("0.0.0.0", rootHost);
        }

        return url;
    }

    /**
     * POSTs a JSON string to the given URL.
     * Fire-and-forget: errors are logged but do not propagate.
     */
    private void postJson(String url, String json) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                warn("POST to " + url + " returned " + responseCode);
            }
        } catch (Exception e) {
            warn("Failed to POST to " + url + ": " + e.getMessage());
        }
    }

    /**
     * Serializes a {@link JobCompletionEvent} to a JSON string.
     * Called after {@link #populateEventDetails(JobCompletionEvent)} so
     * subclass fields (prompt, sessionId, exitCode) are included.
     *
     * @param event the event to serialize
     * @return JSON string representation
     */
    private String buildEventJson(JobCompletionEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "jobId", event.getJobId(), true);
        appendJsonField(sb, "status", event.getStatus().name(), false);
        appendJsonField(sb, "description", event.getDescription(), false);
        appendJsonField(sb, "targetBranch", event.getTargetBranch(), false);
        appendJsonField(sb, "commitHash", event.getCommitHash(), false);
        sb.append(",\"pushed\":").append(event.isPushed());

        // Staged files
        sb.append(",\"stagedFiles\":[");
        List<String> staged = event.getStagedFiles();
        for (int i = 0; i < staged.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(staged.get(i))).append("\"");
        }
        sb.append("]");

        // Skipped files
        sb.append(",\"skippedFiles\":[");
        List<String> skipped = event.getSkippedFiles();
        for (int i = 0; i < skipped.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(skipped.get(i))).append("\"");
        }
        sb.append("]");

        // PR URL
        appendJsonField(sb, "pullRequestUrl", event.getPullRequestUrl(), false);

        // Error info
        appendJsonField(sb, "errorMessage", event.getErrorMessage(), false);

        // Claude Code specific
        appendJsonField(sb, "prompt", event.getPrompt(), false);
        appendJsonField(sb, "sessionId", event.getSessionId(), false);
        sb.append(",\"exitCode\":").append(event.getExitCode());

        // Timing information
        sb.append(",\"durationMs\":").append(event.getDurationMs());
        sb.append(",\"durationApiMs\":").append(event.getDurationApiMs());
        sb.append(",\"costUsd\":").append(event.getCostUsd());
        sb.append(",\"numTurns\":").append(event.getNumTurns());

        // Session details
        appendJsonField(sb, "subtype", event.getSubtype(), false);
        sb.append(",\"sessionIsError\":").append(event.isSessionError());
        sb.append(",\"permissionDenials\":").append(event.getPermissionDenials());

        sb.append("}");
        return sb.toString();
    }

    private void appendJsonField(StringBuilder sb, String name, String value, boolean first) {
        if (!first) sb.append(",");
        sb.append("\"").append(name).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeJson(value)).append("\"");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ==================== Encoding ====================

    /** Performs the base64Encode operation. */
    protected static String base64Encode(String s) {
        return s == null ? "" : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Performs the base64Decode operation. */
    protected static String base64Decode(String s) {
        return s == null || s.isEmpty() ? null : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    @Override
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getName());
        sb.append("::taskId:=").append(taskId);
        if (targetBranch != null) {
            sb.append("::branch:=").append(base64Encode(targetBranch));
        }
        if (baseBranch != null && !"master".equals(baseBranch)) {
            sb.append("::baseBranch:=").append(base64Encode(baseBranch));
        }
        if (workingDirectory != null) {
            sb.append("::workDir:=").append(base64Encode(workingDirectory));
        }
        sb.append("::maxFileSize:=").append(maxFileSizeBytes);
        sb.append("::push:=").append(pushToOrigin);
        sb.append("::createBranch:=").append(createBranchIfMissing);
        sb.append("::dryRun:=").append(dryRun);
        if (gitUserName != null) {
            sb.append("::gitUserName:=").append(base64Encode(gitUserName));
        }
        if (gitUserEmail != null) {
            sb.append("::gitUserEmail:=").append(base64Encode(gitUserEmail));
        }
        if (workstreamUrl != null) {
            sb.append("::workstreamUrl:=").append(base64Encode(workstreamUrl));
        }
        return sb.toString();
    }

    @Override
    public void set(String key, String value) {
        switch (key) {
            case "taskId":
                this.taskId = value;
                break;
            case "branch":
                this.targetBranch = base64Decode(value);
                break;
            case "baseBranch":
                this.baseBranch = base64Decode(value);
                break;
            case "workDir":
                this.workingDirectory = base64Decode(value);
                break;
            case "maxFileSize":
                this.maxFileSizeBytes = Long.parseLong(value);
                break;
            case "push":
                this.pushToOrigin = Boolean.parseBoolean(value);
                break;
            case "createBranch":
                this.createBranchIfMissing = Boolean.parseBoolean(value);
                break;
            case "dryRun":
                this.dryRun = Boolean.parseBoolean(value);
                break;
            case "gitUserName":
                this.gitUserName = base64Decode(value);
                break;
            case "gitUserEmail":
                this.gitUserEmail = base64Decode(value);
                break;
            case "workstreamUrl":
                this.workstreamUrl = base64Decode(value);
                break;
        }
    }
}
