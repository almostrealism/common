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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
public abstract class GitManagedJob extends EnvironmentManagedJob {

    /** Default maximum file size to commit (1 MB). */
    public static final long DEFAULT_MAX_FILE_SIZE = 1024 * 1024;

    /** File patterns that are always excluded from commits. */
    private static final Set<String> DEFAULT_EXCLUDED_PATTERNS = GitJobConfig.DEFAULT_EXCLUDED_PATTERNS;

    /** Path patterns for test/CI files protected by {@link #protectTestFiles}. */
    private static final Set<String> PROTECTED_PATH_PATTERNS = GitJobConfig.PROTECTED_PATH_PATTERNS;

    /** Default workspace path when /workspace/project does not exist. */
    private static final String FALLBACK_WORKSPACE_DIR =
        System.getProperty("java.io.tmpdir") + "/flowtree-workspaces";

    /**
     * System property key for a server-wide working directory override.
     *
     * <p>When set (via {@code -Dflowtree.workingDirectory=...} or the
     * {@code nodes.workingDirectory} property in the flowtree properties
     * file), this value takes precedence over the working directory
     * assigned by the job factory. This allows the server that
     * <em>executes</em> a job to control where work takes place,
     * independent of the creating server's configuration.</p>
     */
    public static final String WORKING_DIRECTORY_PROPERTY = "flowtree.workingDirectory";

    // ---- Identity and branch configuration ----

    /** Unique identifier for this job, used in log messages and completion events. */
    private String taskId;

    /**
     * The git branch to which changes are committed and pushed.
     * When null or empty, all git operations are skipped.
     */
    private String targetBranch;

    /**
     * The branch used as the starting point when the target branch does not yet
     * exist. Defaults to {@code "master"}.
     */
    private String baseBranch = "master";

    /** The branch that was active when the job started, recorded before any checkout. */
    private String originalBranch;

    // ---- Repository and workspace paths ----

    /**
     * Optional git remote URL. When set and {@link #workingDirectory} is null,
     * the repository is cloned automatically before work begins.
     */
    private String repoUrl;

    /**
     * Absolute path to the local repository root where git commands are executed.
     * Resolved from {@link #repoUrl} if not specified explicitly.
     */
    private String workingDirectory;

    /**
     * Parent directory under which repository checkouts are placed when
     * {@link #repoUrl} is set but no explicit {@link #workingDirectory} is provided.
     */
    private String defaultWorkspacePath;
    /** Dependent repository URLs to clone alongside the primary repo. */
    private List<String> dependentRepos;
    /** Resolved filesystem paths for dependent repos after cloning. */
    private List<String> dependentRepoPaths = new ArrayList<>();

    // ---- Git operation flags ----

    /** When true (default), commits are pushed to the {@code origin} remote. */
    private boolean pushToOrigin = true;

    /** When true (default), the target branch is created if it does not exist. */
    private boolean createBranchIfMissing = true;

    /** When true, git operations are logged but not executed. */
    private boolean dryRun = false;

    /** Set to true after all git operations (stage, commit, push) succeed. */
    private boolean gitOperationsSuccessful = false;

    // ---- File staging configuration ----

    /** Maximum size in bytes for a file to be eligible for staging. */
    private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;

    /** Glob patterns for files that are never staged, seeded from {@link GitJobConfig}. */
    private Set<String> excludedPatterns = new HashSet<>(DEFAULT_EXCLUDED_PATTERNS);

    /** Additional exclusion patterns supplied by the subclass or operator. */
    private Set<String> additionalExcludedPatterns = new HashSet<>();

    /**
     * When true, any file matched by {@link #PROTECTED_PATH_PATTERNS} that also
     * exists on the base branch is blocked from being staged.
     */
    private boolean protectTestFiles = false;

    /** Files that were successfully staged during the current run. */
    private List<String> stagedFiles = new ArrayList<>();

    /** Files that were skipped during staging, with a reason appended to each entry. */
    private List<String> skippedFiles = new ArrayList<>();

    // ---- Commit and PR state ----

    /** Full SHA-1 hash of the commit created by this job, or null if no commit was made. */
    private String commitHash;

    /** URL of an open pull request for the target branch, detected after push. */
    private String pullRequestUrl;

    /** Git author/committer name passed via {@code -c user.name=...} on the commit command. */
    private String gitUserName;

    /** Git author/committer email passed via {@code -c user.email=...} on the commit command. */
    private String gitUserEmail;

    // ---- Merge conflict state ----

    /** True if a merge conflict was detected when synchronizing with the base branch. */
    private boolean mergeConflictsDetected = false;

    /** Paths of files that were in a conflicted state during base-branch synchronization. */
    private List<String> conflictFiles = new ArrayList<>();

    // ---- Git tampering detection state ----

    /** HEAD commit hash recorded just before doWork() starts. */
    private String preWorkHeadHash;

    /** Set of local branch names that existed just before doWork() starts. */
    private Set<String> preWorkBranches;

    /** Whether git tampering was detected after doWork(). */
    private boolean gitTamperingDetected = false;

    /** Human-readable description of what tampering was detected. */
    private String tamperingDescription;

    // ---- Label-based routing requirements ----

    /**
     * Node labels that must be present for a FlowTree node to execute this job.
     * Keys and values are arbitrary strings matched against node label maps.
     */
    private final Map<String, String> requiredLabels = new LinkedHashMap<>();

    /** Receives {@link JobOutput} events streamed from the job while it runs. */
    private Consumer<JobOutput> outputConsumer;

    /** Completed when {@link #run()} returns, allowing callers to await termination. */
    private final CompletableFuture<Void> future = new CompletableFuture<>();

    /**
     * Controller URL used for posting job status events and sending messages.
     * Follows the pattern {@code http://controller/api/workstreams/{id}/jobs/{jobId}}.
     */
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
     * Sets a required label that a Node must have to execute this job.
     *
     * @param key   the label key (e.g. "platform")
     * @param value the required value (e.g. "macos")
     */
    public void setRequiredLabel(String key, String value) {
        requiredLabels.put(key, value);
    }

    /**
     * Returns an unmodifiable view of the labels that a FlowTree node must
     * possess in order to accept and execute this job. Label matching is
     * performed by the node before the job is added to its queue.
     *
     * @return unmodifiable map of required label key-value pairs
     */
    @Override
    public Map<String, String> getRequiredLabels() {
        return Collections.unmodifiableMap(requiredLabels);
    }

    /**
     * Performs the main work of this job.
     * Subclasses must implement this method.
     * Git operations occur after this method completes.
     */
    protected abstract void doWork();

    /**
     * Validates changes made by {@link #doWork()} before git operations.
     * Subclasses can override to implement pre-commit validation logic.
     *
     * @return true to proceed with git operations, false to abort
     * @throws Exception if validation encounters an error
     */
    protected boolean validateChanges() throws Exception {
        return true;
    }

    /**
     * Called when git tampering is detected after {@link #doWork()}.
     *
     * <p>The default implementation logs a warning and returns {@code false}
     * (no retry). Subclasses like {@link ClaudeCodeJob} override this to
     * restart the agent session with a violation warning, giving the agent
     * one chance to redo its work without tampering.</p>
     *
     * <p>When this method returns {@code true}, the caller will re-run
     * tampering detection. If tampering persists, all changes are
     * destroyed with no further retries.</p>
     *
     * @return true if the subclass restarted the work and wants re-evaluation
     */
    protected boolean onGitTampering() {
        warn("Git tampering detected but no restart handler configured -- "
            + "proceeding with reverted state");
        return false;
    }

    /**
     * Returns the commit message for changes made by this job.
     * Subclasses should override to provide a descriptive message.
     *
     * @return the commit message
     */
    protected String getCommitMessage() {
        return "Changes from job: " + getTaskString();
    }

    /**
     * Executes the full job lifecycle: environment setup, optional repo clone,
     * working-directory preparation, pre-work HEAD snapshot, the subclass work
     * ({@link #doWork()}), git-tampering detection and remediation,
     * change validation, and finally the git operations (stage, commit, push).
     *
     * <p>This method is {@code final}: subclasses customise behaviour by
     * overriding {@link #doWork()}, {@link #validateChanges()},
     * {@link #onGitTampering()}, and {@link #getCommitMessage()}.</p>
     *
     * <p>Regardless of success or failure, {@link #fireJobCompleted(Exception)}
     * is called in the {@code finally} block and the {@link #future} is
     * completed so that waiters are always unblocked.</p>
     */
    @Override
    public final void run() {
        Exception error = null;

        try {
            // Apply server-wide workspace override if configured.
            // This sets the parent directory under which repos are cloned,
            // allowing the executing server to control where work takes
            // place regardless of the factory-assigned configuration.
            String serverWorkDir = System.getProperty(WORKING_DIRECTORY_PROPERTY);
            if (serverWorkDir != null && !serverWorkDir.isEmpty()) {
                log("Overriding default workspace path with server property: " + serverWorkDir);
                defaultWorkspacePath = serverWorkDir;
                workingDirectory = null;
            }

            // Resolve working directory from repoUrl if needed.
            // This clones the repo if a repoUrl is specified but no
            // working directory is set (or the directory is empty).
            if (repoUrl != null && !repoUrl.isEmpty()) {
                resolveAndCloneRepository();
            }

            // Clone/sync dependent repos alongside the primary repo
            prepareDependentRepos();

            // Prepare working directory: verify clean state, checkout branch,
            // pull latest. This must happen before doWork() so the agent
            // operates on the current remote state of the target branch.
            if (targetBranch != null && !targetBranch.isEmpty()) {
                originalBranch = getCurrentBranch();
                prepareWorkingDirectory();
                prepareDependentReposBranches();
            }

            // Prepare execution environment (Python venv, etc.)
            prepareEnvironment();

            // Record pre-work HEAD so we can detect unauthorized commits
            if (targetBranch != null && !targetBranch.isEmpty()) {
                preWorkHeadHash = executeGitWithOutput("rev-parse", "HEAD").trim();
                log("Pre-work HEAD: " + preWorkHeadHash.substring(0, Math.min(7, preWorkHeadHash.length())));
                preWorkBranches = new HashSet<>(Arrays.asList(
                        executeGitWithOutput("branch", "--list", "--format=%(refname:short)").split("\n")));
            }

            // Prepare execution environment (Python venv, etc.)
            prepareEnvironment();

            // Record pre-work HEAD so we can detect unauthorized commits
            if (targetBranch != null && !targetBranch.isEmpty()) {
                preWorkHeadHash = executeGitWithOutput("rev-parse", "HEAD").trim();
                log("Pre-work HEAD: " + preWorkHeadHash.substring(0, Math.min(7, preWorkHeadHash.length())));
                preWorkBranches = new HashSet<>(Arrays.asList(
                        executeGitWithOutput("branch", "--list", "--format=%(refname:short)").split("\n")));
            }

            // Perform the actual work
            doWork();

            // Handle git operations if a target branch is specified
            if (targetBranch != null && !targetBranch.isEmpty()) {
                // Detect and handle git tampering.  If the agent switched
                // branches, created commits, or otherwise mutated the repo,
                // revert to the pre-work state and allow the subclass to
                // retry the session with a stern warning.
                detectGitTampering();

                if (gitTamperingDetected) {
                    revertGitTampering();

                    // Give the subclass a chance to restart.  onGitTampering()
                    // may call doWork() again (with an amended prompt) and
                    // will return true if it wants us to re-evaluate.
                    if (onGitTampering()) {
                        // Re-check after the restart
                        resetTamperingState();
                        detectGitTampering();
                        if (gitTamperingDetected) {
                            revertGitTampering();
                            warn("Git tampering persisted after restart -- "
                                + "all agent changes destroyed");
                        }
                    }
                }

                if (validateChanges()) {
                    handleGitOperations();
                    handleDependentRepoGitOperations();
                } else {
                    warn("Change validation failed - skipping git operations");
                }
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
        // 1. Check for ANY uncommitted changes -- even files matching the
        //    exclusion list must be stashed because their presence on disk
        //    can prevent git from switching branches.
        List<String> allDirtyFiles = getAllDirtyFiles();
        if (!allDirtyFiles.isEmpty()) {
            List<String> nonExcludedFiles = filterExcluded(allDirtyFiles);

            // Build a descriptive file list for the stash message, preferring
            // non-excluded files but falling back to excluded ones.
            List<String> labelFiles = nonExcludedFiles.isEmpty()
                ? allDirtyFiles : nonExcludedFiles;
            String fileList = labelFiles.size() <= 5
                ? String.join(", ", labelFiles)
                : String.join(", ", labelFiles.subList(0, 5))
                    + " (+" + (labelFiles.size() - 5) + " more)";
            if (!nonExcludedFiles.isEmpty()) {
                warn("Uncommitted changes found: " + fileList + " -- stashing");
            } else {
                log("Only excluded files are dirty (" + fileList
                    + ") -- stashing to allow branch switch");
            }

            String stashMessage = "flowtree: interrupted job residue before "
                + taskId + " [" + fileList + "]";
            // --include-untracked captures new files as well as modifications
            if (executeGit("stash", "push", "--include-untracked", "-m", stashMessage) != 0) {
                throw new RuntimeException(
                    "Failed to stash uncommitted changes: " + fileList);
            }
            log("Working directory cleaned (changes stashed)");
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

        // 5. Synchronize with the base branch (e.g., origin/master) so our
        //    working branch incorporates any changes that have landed on the
        //    base since the branch was created.  This is critical because
        //    the long-term goal is to merge back into the base branch, and
        //    staying current reduces merge conflicts at PR time.
        synchronizeWithBaseBranch();
    }

    /**
     * Merges the latest remote base branch into the current working branch.
     *
     * <p>If the base branch is the same as the target branch, or if the
     * remote base branch does not exist, this is a no-op.</p>
     *
     * <p>When merge conflicts occur, they are recorded via
     * {@link #mergeConflictsDetected} and {@link #conflictFiles} so that
     * subclasses (e.g., {@code ClaudeCodeJob}) can adjust the agent prompt
     * to include conflict resolution instructions. The merge is left in
     * a conflicted state so the coding agent can resolve the conflicts.</p>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private void synchronizeWithBaseBranch() throws IOException, InterruptedException {
        if (baseBranch == null || baseBranch.isEmpty()) {
            return;
        }

        // No need to merge if target and base are the same branch
        if (baseBranch.equals(targetBranch)) {
            return;
        }

        String remoteBase = "origin/" + baseBranch;
        boolean remoteBaseExists = executeGit(
            "show-ref", "--verify", "--quiet",
            "refs/remotes/" + remoteBase) == 0;
        if (!remoteBaseExists) {
            log("Remote base branch " + remoteBase + " does not exist -- skipping sync");
            return;
        }

        // Check if there are new commits on the base branch that we don't have
        String mergeBase = executeGitWithOutput("merge-base", "HEAD", remoteBase).trim();
        String baseHead = executeGitWithOutput("rev-parse", remoteBase).trim();

        if (mergeBase.equals(baseHead)) {
            log("Already up to date with " + remoteBase);
            return;
        }

        log("Synchronizing with " + remoteBase + " (merge-base: "
            + mergeBase.substring(0, Math.min(7, mergeBase.length()))
            + ", base HEAD: "
            + baseHead.substring(0, Math.min(7, baseHead.length())) + ")...");

        int mergeResult = executeGit("merge", remoteBase,
            "--no-edit", "-m", "Merge " + remoteBase + " into " + targetBranch);

        if (mergeResult == 0) {
            String headHash = executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
            log("Successfully merged " + remoteBase + " (now at " + headHash + ")");
        } else {
            // Merge conflict detected -- identify conflicted files
            log("Merge conflict detected while synchronizing with " + remoteBase);
            mergeConflictsDetected = true;

            String statusOutput = executeGitWithOutput("status", "--porcelain");
            for (String line : statusOutput.split("\n")) {
                if (line.startsWith("UU ") || line.startsWith("AA ")
                        || line.startsWith("DD ") || line.startsWith("AU ")
                        || line.startsWith("UA ") || line.startsWith("DU ")
                        || line.startsWith("UD ")) {
                    String file = line.substring(3).trim();
                    if (!file.isEmpty()) {
                        conflictFiles.add(file);
                    }
                }
            }

            log("Conflicted files (" + conflictFiles.size() + "): "
                + (conflictFiles.size() <= 10
                    ? String.join(", ", conflictFiles)
                    : String.join(", ", conflictFiles.subList(0, 10))
                        + " (+" + (conflictFiles.size() - 10) + " more)"));

            // Abort the merge so the working directory is clean for the agent.
            // The agent will be told about the conflicts and instructed to
            // perform the merge itself after understanding the changes.
            executeGit("merge", "--abort");
            log("Merge aborted -- agent will be instructed to resolve conflicts");
        }
    }

    /**
     * Resolves the working directory from a {@link #repoUrl} and clones
     * the repository if needed.
     *
     * <p>When {@code repoUrl} is set but {@code workingDirectory} is null,
     * the directory is resolved using the following priority:</p>
     * <ol>
     *   <li>{@link #defaultWorkspacePath} from the global YAML configuration</li>
     *   <li>{@code /workspace/project} if that directory exists</li>
     *   <li>{@code <tmpdir>/flowtree-workspaces/<repo-name>} as a last resort</li>
     * </ol>
     *
     * <p>If the resolved directory already contains a {@code .git} directory,
     * the clone step is skipped (the repo is already present). Otherwise,
     * the repo is cloned into that directory.</p>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private void resolveAndCloneRepository() throws IOException, InterruptedException {
        // If workingDirectory is already set, just ensure the repo is there
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            File workDir = new File(workingDirectory);
            if (!new File(workDir, ".git").exists() && !workDir.exists()) {
                log("Working directory does not exist, cloning " + repoUrl + " into " + workingDirectory);
                cloneRepository(workingDirectory);
            }
            return;
        }

        // Resolve the workspace path
        String resolvedPath = resolveWorkspacePath();
        log("Resolved workspace path: " + resolvedPath);

        File resolvedDir = new File(resolvedPath);
        if (new File(resolvedDir, ".git").exists()) {
            log("Repository already exists at " + resolvedPath);
            workingDirectory = resolvedPath;
            return;
        }

        // Clone the repository
        cloneRepository(resolvedPath);
        workingDirectory = resolvedPath;
    }

    /**
     * Resolves the workspace path for a repo URL checkout.
     *
     * <p>The resolved path is always a repo-specific subdirectory.
     * The parent directory is chosen using the following priority:</p>
     * <ol>
     *   <li>{@link #defaultWorkspacePath} if explicitly configured</li>
     *   <li>{@code /workspace/project} if the directory exists</li>
     *   <li>{@code <tmpdir>/flowtree-workspaces} as fallback</li>
     * </ol>
     *
     * <p>In all cases, the repository name (derived from {@link #repoUrl})
     * is appended to form the final path, e.g.
     * {@code /workspace/project/owner-repo}.</p>
     *
     * @return the resolved absolute path for the workspace
     */
    private String resolveWorkspacePath() {
        String repoName = extractRepoName(repoUrl);

        // 1. Use configured default workspace path as parent
        if (defaultWorkspacePath != null && !defaultWorkspacePath.isEmpty()) {
            return defaultWorkspacePath + "/" + repoName;
        }

        // 2. Check if /workspace/project exists as parent
        File defaultDir = new File("/workspace/project");
        if (defaultDir.exists() && defaultDir.isDirectory()) {
            return "/workspace/project/" + repoName;
        }

        // 3. Fall back to system temp dir with a repo-derived name
        return FALLBACK_WORKSPACE_DIR + "/" + repoName;
    }

    /**
     * Extracts a filesystem-safe repository name from a git URL.
     *
     * <p>Handles SSH ({@code git@github.com:owner/repo.git}) and
     * HTTPS ({@code https://github.com/owner/repo.git}) formats.
     * Falls back to a hash-based name if parsing fails.</p>
     *
     * @param url the git remote URL
     * @return a filesystem-safe name derived from the repo
     */
    private static String extractRepoName(String url) {
        return WorkspaceResolver.extractRepoName(url);
    }

    /**
     * Clones the {@link #repoUrl} into the specified directory.
     *
     * <p>Creates parent directories as needed. The clone is performed
     * with {@code git clone} into the target path.</p>
     *
     * @param targetPath the directory to clone into
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private void cloneRepository(String targetPath) throws IOException, InterruptedException {
        GitOperations gitOps = new GitOperations(workingDirectory, taskId);
        gitOps.cloneRepository(repoUrl, targetPath);
    }

    /**
     * Clones or syncs dependent repositories alongside the primary repo.
     *
     * <p>Each dependent repo is cloned as a sibling directory of the
     * primary working directory. If a repo is already cloned, this
     * method ensures it is up-to-date with origin.</p>
     */
    private void prepareDependentRepos() throws IOException, InterruptedException {
        if (dependentRepos == null || dependentRepos.isEmpty()) {
            return;
        }

        String parentDir = resolveParentDirectory();
        if (parentDir == null) {
            warn("Cannot resolve parent directory for dependent repos");
            return;
        }

        for (String depRepoUrl : dependentRepos) {
            String repoName = WorkspaceResolver.extractRepoName(depRepoUrl);
            String depPath = parentDir + "/" + repoName;

            File depDir = new File(depPath);
            if (new File(depDir, ".git").exists()) {
                log("Dependent repo already cloned: " + depPath);
            } else {
                log("Cloning dependent repo: " + depRepoUrl + " into " + depPath);
                GitOperations gitOps = new GitOperations(parentDir, taskId);
                gitOps.cloneRepository(depRepoUrl, depPath);
            }

            dependentRepoPaths.add(depPath);
        }
    }

    /**
     * Prepares branch state for all dependent repos: fetch, checkout
     * the target branch (creating it from the base branch if needed),
     * and pull latest changes.
     */
    private void prepareDependentReposBranches() throws IOException, InterruptedException {
        for (String depPath : dependentRepoPaths) {
            log("Preparing dependent repo branch: " + depPath);

            // Fetch latest
            executeGitInDir(depPath, "fetch", "origin");

            // Check out target branch or create it
            String currentBranch = executeGitWithOutputInDir(depPath,
                "rev-parse", "--abbrev-ref", "HEAD").trim();
            if (!targetBranch.equals(currentBranch)) {
                boolean exists = executeGitInDir(depPath, "rev-parse",
                    "--verify", targetBranch) == 0
                    || executeGitInDir(depPath, "rev-parse",
                        "--verify", "origin/" + targetBranch) == 0;

                if (exists) {
                    executeGitInDir(depPath, "checkout", targetBranch);
                } else {
                    String startPoint = "origin/" + baseBranch;
                    log("Creating branch " + targetBranch + " from "
                        + startPoint + " in " + depPath);
                    executeGitInDir(depPath, "checkout", "-b",
                        targetBranch, "--no-track", startPoint);
                }
            }

            // Pull latest (fast-forward only, ignore failure for new branches)
            executeGitInDir(depPath, "pull", "--ff-only", "origin", targetBranch);
        }
    }

    /**
     * Handles git operations (stage, commit, push) for all dependent
     * repos. Uses the same commit message pattern as the primary repo,
     * reading {@code commit.txt} from the primary working directory if
     * it exists.
     */
    private void handleDependentRepoGitOperations() throws IOException, InterruptedException {
        for (String depPath : dependentRepoPaths) {
            // Check for changes
            String statusOutput = executeGitWithOutputInDir(depPath,
                "status", "--porcelain");
            List<String> changedFiles = new ArrayList<>();
            for (String line : statusOutput.split("\n")) {
                if (line.length() > 3) {
                    String file = line.substring(3).trim();
                    if (file.contains(" -> ")) {
                        file = file.split(" -> ")[1];
                    }
                    if (!file.isEmpty()) {
                        changedFiles.add(file);
                    }
                }
            }

            if (changedFiles.isEmpty()) {
                log("No changes in dependent repo: " + depPath);
                continue;
            }

            log("Committing " + changedFiles.size() + " changes in dependent repo: " + depPath);

            // Stage all changes (apply same size guardrail)
            for (String file : changedFiles) {
                File f = new File(depPath, file);
                if (f.exists() && f.length() > maxFileSizeBytes) {
                    log("SKIP (size) in dependent repo: " + file);
                    continue;
                }
                executeGitInDir(depPath, "add", file);
            }

            // Read commit message from primary working directory's commit.txt
            String commitMessage = getCommitMessage();
            File commitFile = resolveFile("commit.txt");
            if (commitFile != null && commitFile.exists()) {
                try {
                    String content = Files.readString(commitFile.toPath()).trim();
                    if (!content.isEmpty()) {
                        commitMessage = content;
                    }
                } catch (IOException e) {
                    log("Could not read commit.txt for dependent repo: " + e.getMessage());
                }
            }

            // Commit with identity
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
            args.add(commitMessage);

            executeGitInDir(depPath, args.toArray(new String[0]));

            // Push
            if (pushToOrigin && !dryRun) {
                String refspec = targetBranch + ":" + targetBranch;
                executeGitInDir(depPath, "push", "-u", "origin", refspec);
                log("Pushed dependent repo: " + depPath);
            }
        }
    }

    /**
     * Resolves the parent directory of the primary working directory.
     * Dependent repos are cloned as siblings of the primary repo.
     */
    private String resolveParentDirectory() {
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            return new File(workingDirectory).getParent();
        }
        if (defaultWorkspacePath != null && !defaultWorkspacePath.isEmpty()) {
            return defaultWorkspacePath;
        }
        return null;
    }

    /**
     * Executes a git command in a specific directory.
     */
    private int executeGitInDir(String dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir);
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log("[dep-git] " + line);
            }
        }
        return process.waitFor();
    }

    /**
     * Executes a git command in a specific directory and returns stdout.
     */
    private String executeGitWithOutputInDir(String dir, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir);
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
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

    /**
     * Checks the working directory for uncommitted changes, excluding files
     * that match the job's excluded patterns (e.g., claude-output, .claude
     * settings, build artifacts).  Unlike {@link #getAllDirtyFiles()}, this
     * only returns files that would NOT normally be excluded.
     *
     * @return list of dirty file paths that are NOT excluded
     */
    private List<String> checkForUncommittedChanges() throws IOException, InterruptedException {
        return filterExcluded(getAllDirtyFiles());
    }

    /**
     * Returns every file reported as dirty by {@code git status --porcelain},
     * with no exclusion filtering applied.
     *
     * @return all dirty file paths
     */
    private List<String> getAllDirtyFiles() throws IOException, InterruptedException {
        String statusOutput = executeGitWithOutput("status", "--porcelain");
        List<String> dirtyFiles = new ArrayList<>();

        for (String line : statusOutput.split("\n")) {
            if (line.length() > 3) {
                String file = line.substring(3).trim();
                if (file.contains(" -> ")) {
                    file = file.split(" -> ")[1];
                }
                if (!file.isEmpty()) {
                    dirtyFiles.add(file);
                }
            }
        }

        return dirtyFiles;
    }

    /**
     * Filters out files that match the job's excluded patterns.
     *
     * @param files list of file paths
     * @return files that do NOT match any excluded pattern
     */
    private List<String> filterExcluded(List<String> files) {
        Set<String> allExcluded = new HashSet<>(excludedPatterns);
        allExcluded.addAll(additionalExcludedPatterns);

        List<String> result = new ArrayList<>();
        for (String file : files) {
            if (!matchesAnyPattern(file, allExcluded)) {
                result.add(file);
            }
        }
        return result;
    }

    /**
     * Fires the job completed event by POSTing to the workstream URL.
     * The controller's notification system receives this event and
     * formats an appropriate message, so no separate message
     * is sent from here.
     */
    protected void fireJobCompleted(Exception error) {
        JobCompletionEvent event = createEvent(error);

        event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles,
            gitOperationsSuccessful && pushToOrigin && !stagedFiles.isEmpty());
        if (pullRequestUrl != null) {
            event.withPullRequestUrl(pullRequestUrl);
        }
        populateEventDetails(event);
        postStatusEvent(event);
    }

    /**
     * Creates the completion event for this job.
     *
     * <p>Subclasses can override to return a more specific event type.
     * For example, {@link ClaudeCodeJob} returns {@link ClaudeCodeJobEvent}.</p>
     *
     * @param error the exception if the job failed, or null on success
     * @return the event to fire
     */
    protected JobCompletionEvent createEvent(Exception error) {
        if (error != null) {
            return JobCompletionEvent.failed(
                taskId, getTaskString(),
                error.getMessage(), error
            );
        } else {
            return JobCompletionEvent.success(taskId, getTaskString());
        }
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
     * Detects whether the coding agent tampered with the git repository
     * during its session.
     *
     * <p>Tampering is defined as any of the following:
     * <ul>
     *   <li>Switching to a different branch than the target branch</li>
     *   <li>Creating new commits (HEAD has advanced beyond the pre-work hash)</li>
     *   <li>Creating new local branches that did not exist before</li>
     * </ul>
     *
     * <p>When tampering is detected, {@link #gitTamperingDetected} is set to
     * {@code true} and {@link #tamperingDescription} is populated with details.</p>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private void detectGitTampering() throws IOException, InterruptedException {
        if (preWorkHeadHash == null) {
            return;
        }

        StringBuilder violations = new StringBuilder();

        // Check 1: Has the branch changed?
        String currentBranch = getCurrentBranch();
        if (!targetBranch.equals(currentBranch)) {
            violations.append("Branch switched from '").append(targetBranch)
                .append("' to '").append(currentBranch).append("'. ");
        }

        // Check 2: Were any commits created on the current branch?
        // Count commits reachable from HEAD but not from preWorkHeadHash and
        // not already on origin/targetBranch.  Excluding origin means commits
        // the agent pulled from remote (pre-existing history) are not flagged —
        // only commits the agent created locally that aren't on the remote count.
        executeGit("fetch", "origin", "--quiet");
        boolean remoteRefExists = executeGit("show-ref", "--verify", "--quiet",
                "refs/remotes/origin/" + targetBranch) == 0;
        String newCommits;
        if (remoteRefExists) {
            newCommits = executeGitWithOutput("rev-list", "HEAD",
                "--not", preWorkHeadHash, "refs/remotes/origin/" + targetBranch,
                "--count").trim();
        } else {
            newCommits = executeGitWithOutput(
                "rev-list", preWorkHeadHash + "..HEAD", "--count").trim();
        }
        int commitCount = 0;
        try {
            commitCount = Integer.parseInt(newCommits);
        } catch (NumberFormatException ignored) {
            // If we can't parse, check whether HEAD moved at all
            String currentHead = executeGitWithOutput("rev-parse", "HEAD").trim();
            if (!currentHead.equals(preWorkHeadHash)) {
                commitCount = -1; // unknown but HEAD moved
            }
        }

        if (commitCount != 0) {
            if (commitCount > 0) {
                violations.append("Agent created ").append(commitCount)
                    .append(" unauthorized commit(s) on ").append(currentBranch).append(". ");
            } else {
                violations.append("HEAD moved from pre-work position (unknown commit count). ");
            }
        }

        // Check 3: Were any new local branches created during the session?
        // Compare against the pre-work snapshot so pre-existing branches
        // checked out before the job started are not flagged.
        String branchOutput = executeGitWithOutput("branch", "--list", "--format=%(refname:short)");
        List<String> newBranches = new ArrayList<>();
        for (String branch : branchOutput.split("\n")) {
            String trimmed = branch.trim();
            if (!trimmed.isEmpty() && !preWorkBranches.contains(trimmed)) {
                newBranches.add(trimmed);
            }
        }

        if (!newBranches.isEmpty()) {
            // Only flag as tampering if there are also other violations;
            // extra local branches alone may be artifacts from prior jobs.
            if (violations.length() > 0) {
                violations.append("Unexpected local branches found: ")
                    .append(String.join(", ", newBranches)).append(". ");
            }
        }

        if (violations.length() > 0) {
            gitTamperingDetected = true;
            tamperingDescription = violations.toString().trim();
            warn("Git tampering detected: " + tamperingDescription);
        }
    }

    /**
     * Reverts all git changes made by the agent that constitute tampering.
     *
     * <p>This method:
     * <ol>
     *   <li>Discards all uncommitted changes (staged and unstaged)</li>
     *   <li>Removes all untracked files</li>
     *   <li>Switches back to the target branch</li>
     *   <li>Resets HEAD to the pre-work commit hash, undoing any commits
     *       the agent made</li>
     * </ol>
     *
     * <p>After this method completes, the working directory is in exactly
     * the state it was in just before {@link #doWork()} was called.</p>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private void revertGitTampering() throws IOException, InterruptedException {
        log("Reverting git tampering -- restoring pre-work state...");

        // 1. Discard all uncommitted changes and untracked files on
        //    whatever branch we're currently on.
        executeGit("reset", "--hard");
        executeGit("clean", "-fd");

        // 2. Switch back to the target branch if needed
        String currentBranch = getCurrentBranch();
        if (!targetBranch.equals(currentBranch)) {
            log("Switching from '" + currentBranch + "' back to '" + targetBranch + "'");
            if (executeGit("checkout", targetBranch) != 0) {
                // Target branch may have been deleted or never existed locally
                // if the agent was creating branches.  Force-create it from the
                // pre-work hash.
                warn("Could not checkout " + targetBranch + " -- recreating from pre-work HEAD");
                executeGit("checkout", "-B", targetBranch, preWorkHeadHash);
            }
        }

        // 3. Reset to the pre-work HEAD, undoing any commits the agent made
        String headBefore = executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
        if (executeGit("reset", "--hard", preWorkHeadHash) != 0) {
            throw new RuntimeException(
                "Failed to reset to pre-work HEAD " + preWorkHeadHash
                    + " -- manual intervention required");
        }

        // 4. Clean again in case the reset left untracked files
        executeGit("clean", "-fd");

        String headAfter = executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
        log("Tampering reverted: HEAD was " + headBefore + ", now restored to " + headAfter);
    }

    /**
     * Returns whether git tampering was detected after the last
     * {@link #doWork()} execution.
     *
     * @return true if the agent tampered with the git repository
     */
    protected boolean isGitTamperingDetected() {
        return gitTamperingDetected;
    }

    /**
     * Returns a human-readable description of the git tampering that
     * was detected, or null if no tampering occurred.
     *
     * @return the tampering description, or null
     */
    protected String getTamperingDescription() {
        return tamperingDescription;
    }

    /**
     * Resets the git tampering detection state.
     * Called before each doWork() invocation when retrying.
     */
    protected void resetTamperingState() {
        gitTamperingDetected = false;
        tamperingDescription = null;
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

        // At this point, detectGitTampering()/revertGitTampering() have already
        // run.  We should be on the target branch with a clean HEAD (any
        // unauthorized commits have been reset).  If the branch still doesn't
        // match, something unexpected happened — fail loudly.
        if (!ensureOnTargetBranch()) {
            String msg = "Failed to switch to target branch '" + targetBranch + "'" +
                " (working directory: " + (workingDirectory != null ? workingDirectory : System.getProperty("user.dir")) + ")";
            throw new RuntimeException(msg);
        }

        // Unstage everything the agent may have staged via `git add`.
        // This ensures ALL files pass through our guardrails below before
        // being committed — the agent's own staging is not trusted.
        executeGit("reset", "HEAD");

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
     * Checks out the target branch, creating it from the base branch if it does
     * not yet exist and {@link #createBranchIfMissing} is {@code true}.
     *
     * <p>If {@link #dryRun} is {@code true}, the checkout is only logged.
     * New branches are created with {@code --no-track} so that the upstream
     * is set explicitly to {@code origin/<targetBranch>} by {@link #pushToOrigin()}
     * rather than inheriting the base branch's upstream.</p>
     *
     * @return true if we are (or would be in dry-run mode) on the target branch
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
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
     * Returns all files reported as changed by {@code git status --porcelain},
     * including modified, added, deleted, and untracked files.
     *
     * <p>For renamed files the post-rename path is returned (the part after
     * {@code " -> "}).</p>
     *
     * @return list of changed file paths relative to the working directory
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
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
     * Evaluates each changed file against the staging guardrails and adds
     * passing files to the git index via {@code git add}.
     *
     * <p>The guardrails are applied in order:
     * <ol>
     *   <li>Excluded-pattern check (see {@link #excludedPatterns} and
     *       {@link #additionalExcludedPatterns})</li>
     *   <li>Test-file protection (when {@link #protectTestFiles} is enabled):
     *       files matching {@link #PROTECTED_PATH_PATTERNS} that exist on the
     *       base branch are blocked</li>
     *   <li>File-size check: files larger than {@link #maxFileSizeBytes} are
     *       skipped</li>
     *   <li>Binary detection: files with more than 10 % null bytes are skipped</li>
     * </ol>
     *
     * <p>Files that pass all guardrails are added with {@code git add} and
     * recorded in {@link #stagedFiles}; rejected files are recorded in
     * {@link #skippedFiles} with a reason suffix.</p>
     *
     * @param changedFiles the candidate file paths to evaluate
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
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

            // Guardrail 1.5: Protect test/CI files that exist on the base branch
            if (protectTestFiles && matchesAnyPattern(file, PROTECTED_PATH_PATTERNS)) {
                if (existsOnBaseBranch(file)) {
                    log("BLOCKED (protected - exists on " + baseBranch + "): " + file);
                    skippedFiles.add(file + " (protected - exists on base branch)");
                    continue;
                } else {
                    log("ALLOWED (branch-new file): " + file);
                }
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
     * Commits the staged changes with the message from {@link #getCommitMessage()}.
     *
     * <p>If {@link #gitUserName} and/or {@link #gitUserEmail} are set,
     * they are passed via {@code git -c user.name=... -c user.email=...}
     * directly on the command line, which is the most reliable way to
     * override identity regardless of container or SSH environment.</p>
     *
     * <p>On success, the resulting commit hash is stored in {@link #commitHash}
     * and any leftover {@code commit.txt} scratch file is deleted.</p>
     *
     * @return true if the commit succeeded (exit code 0)
     * @throws IOException if the git process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
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
     * Pushes the current branch to the {@code origin} remote.
     *
     * <p>Uses an explicit refspec ({@code targetBranch:targetBranch})
     * so the push always targets the correct remote branch, regardless
     * of any inherited upstream tracking configuration. The {@code -u}
     * flag also sets the upstream to {@code origin/<targetBranch>}.</p>
     *
     * @return true if the push succeeded (exit code 0)
     * @throws IOException if the git process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
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
     * Uses the controller's GitHub proxy endpoint, which authenticates
     * with per-org tokens from workstreams.yaml.</p>
     *
     * @return the PR URL, or null if no PR was found
     */
    private String detectPullRequestUrl() {
        try {
            String remoteUrl = executeGitWithOutput("remote", "get-url", "origin").trim();
            PullRequestDetector detector = new PullRequestDetector();
            return detector.detect(remoteUrl, targetBranch, workstreamUrl).orElse(null);
        } catch (Exception e) {
            log("Could not detect PR URL: " + e.getMessage());
            return null;
        }
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
        return PullRequestDetector.extractOwnerRepo(remoteUrl);
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

    /**
     * Returns the short name of the currently checked-out branch.
     *
     * @return the current branch name (e.g. {@code "feature/my-work"})
     * @throws IOException if the git command fails to execute
     * @throws InterruptedException if the git command is interrupted
     */
    private String getCurrentBranch() throws IOException, InterruptedException {
        return executeGitWithOutput("rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /**
     * Returns whether the given branch exists locally or as a remote-tracking
     * ref under {@code origin/}.
     *
     * @param branch the branch name to check
     * @return true if the branch exists locally or at {@code origin/<branch>}
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private boolean branchExists(String branch) throws IOException, InterruptedException {
        // Check local branches
        int result = executeGit("show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        if (result == 0) return true;

        // Check remote branches
        result = executeGit("show-ref", "--verify", "--quiet", "refs/remotes/origin/" + branch);
        return result == 0;
    }

    /**
     * Checks if a file exists on the base branch.
     * Branch-new files (not present on the base branch) return {@code false}.
     * Fails safe: returns {@code true} (protected) if the check errors out.
     *
     * @param file the file path to check
     * @return true if the file exists on the base branch
     */
    private boolean existsOnBaseBranch(String file) {
        try {
            String ref = "origin/" + (baseBranch != null ? baseBranch : "master");
            return executeGit("cat-file", "-e", ref + ":" + file) == 0;
        } catch (Exception e) {
            warn("Could not check base branch for " + file + ": " + e.getMessage());
            return true; // Fail safe: protect if uncertain
        }
    }

    /**
     * Executes a git sub-command in the {@link #workingDirectory} and returns
     * its exit code.
     *
     * <p>Standard error is merged into standard output so the full output is
     * captured. If the exit code is non-zero, the output is logged as a
     * warning. SSH host-key prompts are suppressed via
     * {@code GIT_SSH_COMMAND}. Git identity environment variables are
     * injected via {@link #applyGitIdentity(ProcessBuilder)}.</p>
     *
     * @param args git sub-command and its arguments (e.g. {@code "commit", "-m", "msg"})
     * @return the process exit code (0 on success)
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private int executeGit(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(GitOperations.resolveGitCommand());
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);

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

    /**
     * Executes a git sub-command in the {@link #workingDirectory} and returns
     * its combined standard-output and standard-error as a string.
     *
     * <p>Unlike {@link #executeGit(String...)}, the exit code is not checked;
     * callers that need to detect failure should inspect the returned string
     * or use {@link #executeGit(String...)} instead.</p>
     *
     * @param args git sub-command and its arguments
     * @return the full output of the command
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private String executeGitWithOutput(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(GitOperations.resolveGitCommand());
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);

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
        GitOperations.augmentPath(pb);

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

    /**
     * Resolves a relative file path against the {@link #workingDirectory}.
     * When no working directory is configured, the path is treated as relative
     * to the JVM's current working directory.
     *
     * @param path the relative file path to resolve
     * @return a {@link File} object for the resolved path
     */
    private File resolveFile(String path) {
        if (workingDirectory != null) {
            return new File(workingDirectory, path);
        }
        return new File(path);
    }

    /**
     * Returns whether the given file path matches any pattern in the provided set.
     * Delegates to {@link FileStager#matchesAnyPattern(String, Set)} which
     * supports both exact-suffix and glob matching.
     *
     * @param path     the file path to test
     * @param patterns the set of patterns to match against
     * @return true if any pattern matches the path
     */
    private boolean matchesAnyPattern(String path, Set<String> patterns) {
        return FileStager.matchesAnyPattern(path, patterns);
    }

    /**
     * Returns whether a file path matches a single glob pattern.
     * Delegates to {@link FileStager#matchesGlobPattern(String, String)}.
     *
     * @param path    the file path to test
     * @param pattern the glob pattern to match against
     * @return true if the pattern matches the path
     */
    private boolean matchesGlobPattern(String path, String pattern) {
        return FileStager.matchesGlobPattern(path, pattern);
    }

    /**
     * Heuristically determines whether the given file is binary by scanning
     * up to the first 8,000 bytes for null bytes.  A file is considered
     * binary if more than 10 % of the inspected bytes are null ({@code 0x00}).
     *
     * <p>If the file does not exist, is a directory, or cannot be read,
     * it is assumed to be binary (safe default for staging).</p>
     *
     * @param file the file to inspect
     * @return true if the file appears to be binary
     */
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

    /**
     * Formats a byte count as a human-readable string in B, KB, or MB units.
     *
     * @param bytes the number of bytes to format
     * @return a formatted string such as {@code "512 B"}, {@code "1.5 KB"},
     *         or {@code "2.3 MB"}
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ==================== Job Interface ====================

    /**
     * Formats a log message by prepending the simple class name and, when
     * available, the task ID enclosed in square brackets.
     *
     * <p>Example outputs:
     * <ul>
     *   <li>With task ID: {@code "ClaudeCodeJob [abc-123]: message text"}</li>
     *   <li>Without task ID: {@code "ClaudeCodeJob: message text"}</li>
     * </ul>
     *
     * @param msg the raw message text
     * @return the formatted log string
     */
    @Override
    public String formatMessage(String msg) {
        String prefix = getLogClass().getSimpleName();
        String id = getTaskId();
        if (id != null && !id.isEmpty()) {
            return prefix + " [" + id + "]: " + msg;
        }
        return prefix + ": " + msg;
    }

    /**
     * Returns the task identifier for this job, used in log messages and
     * completion events.
     *
     * @return the task ID, or null if none was assigned
     */
    @Override
    public String getTaskId() {
        return taskId;
    }

    /**
     * Sets the task identifier for this job.
     *
     * @param taskId the task ID string
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Returns a {@link CompletableFuture} that is completed when {@link #run()}
     * finishes, whether successfully or with an exception.  Callers can use
     * this to await job termination without blocking the FlowTree thread pool.
     *
     * @return the future that completes on job termination
     */
    @Override
    public CompletableFuture<Void> getCompletableFuture() {
        return future;
    }

    /**
     * Registers a consumer that receives {@link JobOutput} events while
     * the job is running.
     *
     * @param outputConsumer the consumer to notify with output events
     */
    @Override
    public void setOutputConsumer(Consumer<JobOutput> outputConsumer) {
        this.outputConsumer = outputConsumer;
    }

    /**
     * Returns the currently registered output consumer, or null if none
     * has been set.
     *
     * @return the output consumer, or null
     */
    protected Consumer<JobOutput> getOutputConsumer() {
        return outputConsumer;
    }

    // ==================== Configuration ====================

    /**
     * Returns the git branch to which this job's changes are committed.
     * Returns null if no target branch has been set, in which case git
     * operations are skipped entirely.
     *
     * @return the target branch name, or null
     */
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

    /**
     * Returns the absolute path of the local repository root used for all
     * git operations and file resolution.
     *
     * @return the working directory path, or null if not yet resolved
     */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Provides the working directory to the parent {@link EnvironmentManagedJob}
     * so that environment preparation (Python venv, etc.) targets the same
     * directory as git operations.
     *
     * @return the working directory path, or null
     */
    @Override
    protected String getEnvironmentWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Sets the absolute path of the local repository root where git commands
     * are executed and files are resolved.
     *
     * @param workingDirectory the absolute path to the repository root
     */
    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Returns the git repository URL for automatic checkout.
     */
    public String getRepoUrl() {
        return repoUrl;
    }

    /**
     * Sets the git repository URL. When set and no
     * {@link #workingDirectory} is specified, the repo is cloned
     * into a resolved workspace path before the job starts.
     *
     * @param repoUrl the git clone URL (e.g., "https://github.com/owner/repo.git")
     */
    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    /**
     * Returns the default workspace path for repo checkouts.
     */
    public String getDefaultWorkspacePath() {
        return defaultWorkspacePath;
    }

    /**
     * Sets the default workspace path used when a repo URL is specified
     * but no explicit working directory is provided.
     *
     * @param defaultWorkspacePath the absolute path for repo checkouts
     */
    public void setDefaultWorkspacePath(String defaultWorkspacePath) {
        this.defaultWorkspacePath = defaultWorkspacePath;
    }

    /**
     * Returns the list of dependent repository URLs that should be
     * checked out alongside the primary repo.
     */
    public List<String> getDependentRepos() {
        return dependentRepos;
    }

    /**
     * Sets the dependent repository URLs. Each repo is cloned as a
     * sibling directory of the primary working directory and managed
     * with the same branch and commit lifecycle.
     *
     * @param dependentRepos list of git clone URLs
     */
    public void setDependentRepos(List<String> dependentRepos) {
        this.dependentRepos = dependentRepos;
    }

    /**
     * Returns the resolved filesystem paths for dependent repos that
     * were cloned during job preparation. Available after
     * {@link #run()} has completed the preparation phase.
     */
    public List<String> getDependentRepoPaths() {
        return new ArrayList<>(dependentRepoPaths);
    }

    /**
     * Returns the maximum file size in bytes that will be staged and committed.
     * Files larger than this threshold are skipped with a size-exceeded reason.
     *
     * @return the maximum file size in bytes
     */
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

    /**
     * Returns whether commits will be pushed to the {@code origin} remote.
     *
     * @return true if push-to-origin is enabled (default)
     */
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

    /**
     * Returns whether the target branch will be created when it does not exist.
     *
     * @return true if automatic branch creation is enabled (default)
     */
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

    /**
     * Returns whether this job is running in dry-run mode.
     * In dry-run mode all git operations are logged but not executed.
     *
     * @return true if dry-run mode is enabled
     */
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
     * Returns whether test file protection is enabled.
     *
     * <p>When enabled, test and CI files that exist on the base branch
     * cannot be staged. This prevents agents from hiding test failures
     * by modifying existing tests instead of fixing production code.</p>
     */
    public boolean isProtectTestFiles() {
        return protectTestFiles;
    }

    /**
     * Sets whether to protect test files that exist on the base branch.
     *
     * @param protectTestFiles true to block staging of existing test/CI files
     */
    public void setProtectTestFiles(boolean protectTestFiles) {
        this.protectTestFiles = protectTestFiles;
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
     * {@code /messages} sends messages.
     */
    public String getWorkstreamUrl() {
        return workstreamUrl;
    }

    /**
     * Sets the workstream URL for this job.
     *
     * <p>The URL follows the pattern
     * {@code http://controller/api/workstreams/{id}/jobs/{jobId}} for job-level
     * communication (messages go to the job's thread) or
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

    /**
     * Returns whether merge conflicts were detected when synchronizing
     * with the base branch during {@link #prepareWorkingDirectory()}.
     *
     * <p>Subclasses can use this to modify their behavior, for example
     * by adding conflict resolution instructions to a coding agent's prompt.</p>
     */
    protected boolean hasMergeConflicts() {
        return mergeConflictsDetected;
    }

    /**
     * Returns the list of files with merge conflicts, detected during
     * base branch synchronization.
     *
     * @return unmodifiable list of conflicted file paths, empty if no conflicts
     */
    protected List<String> getMergeConflictFiles() {
        return new ArrayList<>(conflictFiles);
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

    /** Shared Jackson mapper used to serialize {@link JobCompletionEvent} instances to JSON. */
    private static final ObjectMapper eventMapper = new ObjectMapper();

    /**
     * Serializes a {@link JobCompletionEvent} to a JSON string using Jackson.
     *
     * @param event the event to serialize
     * @return JSON string representation
     */
    private String buildEventJson(JobCompletionEvent event) {
        ObjectNode root = eventMapper.createObjectNode();
        root.put("jobId", event.getJobId());
        root.put("status", event.getStatus().name());
        root.put("description", event.getDescription());
        root.put("targetBranch", event.getTargetBranch());
        root.put("commitHash", event.getCommitHash());
        root.put("pushed", event.isPushed());

        ArrayNode stagedArray = root.putArray("stagedFiles");
        for (String f : event.getStagedFiles()) stagedArray.add(f);

        ArrayNode skippedArray = root.putArray("skippedFiles");
        for (String f : event.getSkippedFiles()) skippedArray.add(f);

        root.put("pullRequestUrl", event.getPullRequestUrl());
        root.put("errorMessage", event.getErrorMessage());

        // Claude Code specific (base class returns defaults)
        root.put("prompt", event.getPrompt());
        root.put("sessionId", event.getSessionId());
        root.put("exitCode", event.getExitCode());

        // Timing information
        root.put("durationMs", event.getDurationMs());
        root.put("durationApiMs", event.getDurationApiMs());
        root.put("costUsd", event.getCostUsd());
        root.put("numTurns", event.getNumTurns());

        // Session details
        root.put("subtype", event.getSubtype());
        root.put("sessionIsError", event.isSessionError());
        root.put("permissionDenials", event.getPermissionDenials());

        ArrayNode deniedArray = root.putArray("deniedToolNames");
        for (String t : event.getDeniedToolNames()) deniedArray.add(t);

        try {
            return eventMapper.writeValueAsString(root);
        } catch (Exception e) {
            warn("Failed to serialize event JSON: " + e.getMessage());
            return "{}";
        }
    }

    // ==================== Encoding ====================

    /**
     * Encodes a string as Base64 for safe embedding in the {@link #encode()} wire format.
     * Returns an empty string when the input is null so null fields are safely
     * round-tripped through the protocol.
     *
     * @param s the string to encode, or null
     * @return the Base64-encoded string, or {@code ""} if {@code s} is null
     */
    protected static String base64Encode(String s) {
        return s == null ? "" : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a Base64-encoded string that was produced by {@link #base64Encode(String)}.
     * Returns null when the input is null or empty so that optional fields
     * remain null after deserialization.
     *
     * @param s the Base64-encoded string, or null/empty
     * @return the decoded string, or null if the input was null or empty
     */
    protected static String base64Decode(String s) {
        return s == null || s.isEmpty() ? null : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /**
     * Serializes this job to the FlowTree key-value wire format used to
     * transmit jobs between nodes.
     *
     * <p>The encoding is a {@code ::}-delimited string of {@code key:=value}
     * pairs prefixed with the fully-qualified class name. String values are
     * Base64-encoded to avoid delimiter collisions. The encoded form is
     * consumed by {@link #set(String, String)} on the receiving side.</p>
     *
     * <p>Only non-default values are emitted to keep the string compact.
     * Subclasses that add additional fields must override this method and
     * call {@code super.encode()} to include the base fields.</p>
     *
     * @return the encoded job string
     */
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
        if (repoUrl != null) {
            sb.append("::repoUrl:=").append(base64Encode(repoUrl));
        }
        if (defaultWorkspacePath != null) {
            sb.append("::defaultWsPath:=").append(base64Encode(defaultWorkspacePath));
        }
        sb.append("::maxFileSize:=").append(maxFileSizeBytes);
        sb.append("::push:=").append(pushToOrigin);
        sb.append("::createBranch:=").append(createBranchIfMissing);
        sb.append("::dryRun:=").append(dryRun);
        sb.append("::protectTests:=").append(protectTestFiles);
        if (gitUserName != null) {
            sb.append("::gitUserName:=").append(base64Encode(gitUserName));
        }
        if (gitUserEmail != null) {
            sb.append("::gitUserEmail:=").append(base64Encode(gitUserEmail));
        }
        if (workstreamUrl != null) {
            sb.append("::workstreamUrl:=").append(base64Encode(workstreamUrl));
        }
        for (Map.Entry<String, String> entry : requiredLabels.entrySet()) {
            sb.append("::req.").append(entry.getKey()).append(":=").append(entry.getValue());
        }
        sb.append(encodeEnvironmentProperties());
        return sb.toString();
    }

    /**
     * Deserializes a single key-value pair from the FlowTree wire format,
     * populating the corresponding field on this job.
     *
     * <p>This method is called once per {@code key:=value} token produced by
     * {@link #encode()}. String values must be Base64-decoded with
     * {@link #base64Decode(String)} before use.  Keys that start with
     * {@code "req."} are interpreted as required-label entries.  Unrecognized
     * keys are forwarded to {@link #setEnvironmentProperty(String, String)}
     * so that environment configuration is also handled transparently.</p>
     *
     * @param key   the property key from the encoded string
     * @param value the property value (Base64-encoded for string fields)
     */
    @Override
    public void set(String key, String value) {
        switch (key) {
            case "taskId":
                this.taskId = value;
                break;
            case "workDir":
                this.workingDirectory = base64Decode(value);
                break;
            case "repoUrl":
                this.repoUrl = base64Decode(value);
                break;
            case "defaultWsPath":
                this.defaultWorkspacePath = base64Decode(value);
                break;
            case "branch":
                this.targetBranch = base64Decode(value);
                break;
            case "baseBranch":
                this.baseBranch = base64Decode(value);
                break;
            case "push":
                this.pushToOrigin = Boolean.parseBoolean(value);
                break;
            case "workstreamUrl":
                this.workstreamUrl = base64Decode(value);
                break;
            case "gitUserName":
                this.gitUserName = base64Decode(value);
                break;
            case "gitUserEmail":
                this.gitUserEmail = base64Decode(value);
                break;
            case "protectTests":
                this.protectTestFiles = Boolean.parseBoolean(value);
                break;
            case "maxFileSize":
                this.maxFileSizeBytes = Long.parseLong(value);
                break;
            case "createBranch":
                this.createBranchIfMissing = Boolean.parseBoolean(value);
                break;
            case "dryRun":
                this.dryRun = Boolean.parseBoolean(value);
                break;
            default:
                if (key.startsWith("req.")) {
                    requiredLabels.put(key.substring(4), value);
                } else {
                    setEnvironmentProperty(key, value);
                }
                break;
        }
    }
}
