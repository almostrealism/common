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
import io.flowtree.job.Job;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.HostFingerprint;
import org.almostrealism.io.JobOutput;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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

    /** Jackson mapper used to construct JSON event payloads posted to the workstream messages endpoint. */
    private static final ObjectMapper eventMapper = new ObjectMapper();


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

    // ---- Git operation flags ----

    /** When true (default), commits are pushed to the {@code origin} remote. */
    private boolean pushToOrigin = true;

    /** When true (default), the target branch is created if it does not exist. */
    private boolean createBranchIfMissing = true;

    /** When true, git operations are logged but not executed. */
    private boolean dryRun = false;

    // ---- File staging configuration ----

    /** Maximum size in bytes for a file to be eligible for staging. */
    private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;

    /** Glob patterns for files that are never staged, seeded from {@link GitJobConfig}. */
    private Set<String> excludedPatterns = new HashSet<>(DEFAULT_EXCLUDED_PATTERNS);

    /** Additional exclusion patterns supplied by the subclass or operator. */
    private Set<String> additionalExcludedPatterns = new HashSet<>();

    /**
     * When true, any file matched by {@link GitJobConfig#PROTECTED_PATH_PATTERNS} that also
     * exists on the base branch is blocked from being staged.
     */
    private boolean protectTestFiles = false;

    // ---- Git author identity ----

    /** Git author/committer name passed via {@code -c user.name=...} on the commit command. */
    private String gitUserName;

    /** Git author/committer email passed via {@code -c user.email=...} on the commit command. */
    private String gitUserEmail;

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
     * Channel held open for the duration of the job to back {@link #workspaceLock}.
     * Closed unconditionally in the {@code finally} block of {@link #run()}.
     */
    private FileChannel workspaceLockChannel;

    /**
     * Exclusive OS-level lock on {@code <parent>/.flowtree-locks/<repoName>.lock},
     * placed outside the git working tree so {@code git stash --include-untracked}
     * cannot unlink it mid-job (see {@code FLOWTREE_COLLISIONS.md}). Prevents
     * concurrent {@link GitManagedJob} instances on the same working directory.
     */
    private FileLock workspaceLock;

    // ---- Per-run helper instances ----

    /**
     * Handles repository cloning, branch preparation, and base-branch sync.
     * Created at the start of {@link #run()} when a target branch is configured.
     */
    private GitRepositorySetup repoSetup;

    /**
     * Detects and reverts unauthorized git mutations made by the coding agent.
     * Created after {@link #prepareEnvironment()} completes, before {@link #doWork()}.
     */
    private GitTamperingDetector tampering;

    /**
     * Handles file staging, committing, pushing, and PR detection.
     * Created after tampering detection and change validation pass.
     */
    private GitCommitHandler commitHandler;

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
            if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
                postJson(resolveWorkstreamUrl() + "/messages",
                        eventMapper.createObjectNode().put("text", HostFingerprint.describe()).toString());
            }

            // Apply server-wide workspace override if configured.
            String serverWorkDir = System.getProperty(WORKING_DIRECTORY_PROPERTY);
            if (serverWorkDir != null && !serverWorkDir.isEmpty()) {
                log("Overriding default workspace path with server property: " + serverWorkDir);
                defaultWorkspacePath = serverWorkDir;
                workingDirectory = null;
            }

            // Resolve working directory from repoUrl if needed (clone if absent).
            // Acquire a filesystem lock BEFORE cloning so that two jobs targeting
            // the same repo — whether in this JVM or in a sibling container sharing
            // the same bind-mounted host path — cannot operate concurrently.
            if (repoUrl != null && !repoUrl.isEmpty()) {
                String lockTarget = workingDirectory != null && !workingDirectory.isEmpty()
                    ? workingDirectory
                    : WorkspaceResolver.resolve(defaultWorkspacePath, repoUrl);
                acquireWorkspaceLock(lockTarget);
                repoSetup = new GitRepositorySetup(this);
                workingDirectory = repoSetup.resolveAndClone();
            }

            // Clone/sync dependent repos alongside the primary repo
            if (dependentRepos != null && !dependentRepos.isEmpty()) {
                if (repoSetup == null) repoSetup = new GitRepositorySetup(this);
                repoSetup.prepareDependentRepos();
            }

            // Prepare working directory: stash dirty files, fetch, checkout branch,
            // pull latest, and merge base branch.
            if (targetBranch != null && !targetBranch.isEmpty()) {
                originalBranch = getCurrentBranch();
                if (repoSetup == null) repoSetup = new GitRepositorySetup(this);
                repoSetup.prepare();
                repoSetup.prepareDependentReposBranches();
            }

            // Prepare execution environment (Python venv, etc.)
            prepareEnvironment();

            // Record pre-work state so tampering can be detected after doWork().
            if (targetBranch != null && !targetBranch.isEmpty()) {
                tampering = new GitTamperingDetector(this, targetBranch);
                tampering.recordPreWorkState();
            }

            // Perform the actual work.
            doWork();

            // Handle git operations if a target branch is specified.
            if (targetBranch != null && !targetBranch.isEmpty()) {
                // Detect and handle git tampering.  If the agent switched branches,
                // created commits, or otherwise mutated the repo, revert to the
                // pre-work state and allow the subclass to retry the session.
                tampering.detect();

                if (tampering.isDetected()) {
                    tampering.revert();

                    // Give the subclass a chance to restart.  onGitTampering() may
                    // call doWork() again (with an amended prompt) and will return
                    // true if it wants us to re-evaluate.
                    if (onGitTampering()) {
                        tampering.reset();
                        tampering.detect();
                        if (tampering.isDetected()) {
                            tampering.revert();
                            warn("Git tampering persisted after restart -- "
                                + "all agent changes destroyed");
                        }
                    }
                }

                if (validateChanges()) {
                    commitHandler = new GitCommitHandler(this);
                    commitHandler.handle(repoSetup.hasMergeConflicts());
                    List<String> depPaths = repoSetup.getDependentRepoPaths();
                    commitHandler.handleDependentRepos(depPaths);
                } else {
                    warn("Change validation failed - skipping git operations");
                }
            }

        } catch (Exception e) {
            warn("Error: " + e.getMessage(), e);
            error = e;
        } finally {
            releaseWorkspaceLock();
            fireJobCompleted(error);
            future.complete(null);
        }
    }

    /**
     * Acquires an exclusive OS-level lock on
     * {@code <parent>/.flowtree-locks/<repoName>.lock}. The lock file is
     * placed outside the git working tree so {@code git stash
     * --include-untracked} cannot unlink it mid-job — POSIX advisory locks
     * ({@link FileLock}) are per-inode, and unlink-recreate breaks them
     * silently (see {@code FLOWTREE_COLLISIONS.md}). On shared filesystems
     * the lock serialises sibling containers targeting the same repository.
     * Blocks until available; failures are logged but do not abort the job.
     *
     * @param workspacePath the git repository root
     */
    private void acquireWorkspaceLock(String workspacePath) {
        try {
            Path repoRoot = Paths.get(workspacePath);
            Path parentDir = repoRoot.getParent();
            if (parentDir == null) {
                warn("Cannot resolve parent of workspace " + workspacePath
                        + " -- workspace lock skipped");
                return;
            }
            Path repoNamePath = repoRoot.getFileName();
            String repoName = repoNamePath != null ? repoNamePath.toString() : "unknown";
            Path lockDir = parentDir.resolve(".flowtree-locks");
            Files.createDirectories(lockDir);
            Path lockFile = lockDir.resolve(repoName + ".lock");
            workspaceLockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            String host = hostname();
            log("[" + host + "] Acquiring workspace lock: " + lockFile
                    + " (job=" + taskId + ", repo=" + repoName + ")");
            workspaceLock = workspaceLockChannel.lock();
            log("[" + host + "] Workspace lock acquired: " + lockFile);
        } catch (IOException e) {
            warn("Failed to acquire workspace lock for " + workspacePath + ": " + e.getMessage());
        }
    }

    /** Returns the local hostname for log diagnostics, or {@code "unknown"} on failure. */
    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * Releases the workspace lock acquired by {@link #acquireWorkspaceLock(String)}.
     * Safe to call when no lock is held.
     */
    private void releaseWorkspaceLock() {
        if (workspaceLock != null) {
            try {
                workspaceLock.release();
                log("[" + hostname() + "] Workspace lock released (job=" + taskId + ")");
            } catch (IOException e) {
                warn("Failed to release workspace lock: " + e.getMessage());
            }
            workspaceLock = null;
        }
        if (workspaceLockChannel != null) {
            try {
                workspaceLockChannel.close();
            } catch (IOException e) {
                warn("Failed to close workspace lock channel: " + e.getMessage());
            }
            workspaceLockChannel = null;
        }
    }

    /**
     * Fires the job completed event by POSTing to the workstream URL.
     * The controller's notification system receives this event and
     * formats an appropriate message, so no separate message
     * is sent from here.
     */
    protected void fireJobCompleted(Exception error) {
        JobCompletionEvent event = createEvent(error);

        List<String> staged = commitHandler != null ? commitHandler.getStagedFiles() : new ArrayList<>();
        List<String> skipped = commitHandler != null ? commitHandler.getSkippedFiles() : new ArrayList<>();
        String commit = commitHandler != null ? commitHandler.getCommitHash() : null;
        boolean pushed = commitHandler != null && commitHandler.isSuccessful()
                && pushToOrigin && !staged.isEmpty();
        String prUrl = commitHandler != null ? commitHandler.getPullRequestUrl() : null;

        event.withGitInfo(targetBranch, commit, staged, skipped, pushed);
        if (prUrl != null) {
            event.withPullRequestUrl(prUrl);
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
     * Returns {@code true} if the agent created git commits since {@code doWork()} started.
     *
     * <p>A moved HEAD means the agent violated the "do not commit" rule.  Subclasses
     * use this to skip unrelated enforcement logic (e.g., CI-failure retry loops) when
     * a git integrity violation is already present — the two concerns must never interact.</p>
     *
     * <p>Returns {@code false} if the tampering detector has not been initialized or if
     * the HEAD comparison cannot be performed.</p>
     */
    protected boolean hasAgentCommitted() {
        return tampering != null && tampering.hasCommitted();
    }

    /**
     * Returns whether git tampering was detected after the last
     * {@link #doWork()} execution.
     *
     * @return true if the agent tampered with the git repository
     */
    protected boolean isGitTamperingDetected() {
        return tampering != null && tampering.isDetected();
    }

    /**
     * Returns a human-readable description of the git tampering that
     * was detected, or null if no tampering occurred.
     *
     * @return the tampering description, or null
     */
    protected String getTamperingDescription() {
        return tampering != null ? tampering.getDescription() : null;
    }

    /**
     * Resets the git tampering detection state.
     * Called before each doWork() invocation when retrying.
     */
    protected void resetTamperingState() {
        if (tampering != null) tampering.reset();
    }

    /**
     * Returns whether merge conflicts were detected when synchronizing
     * with the base branch during repository preparation.
     *
     * <p>Subclasses can use this to modify their behavior, for example
     * by adding conflict resolution instructions to a coding agent's prompt.</p>
     */
    protected boolean hasMergeConflicts() {
        return repoSetup != null && repoSetup.hasMergeConflicts();
    }

    /**
     * Returns the list of files with merge conflicts, detected during
     * base branch synchronization.
     *
     * @return list of conflicted file paths, empty if no conflicts
     */
    protected List<String> getMergeConflictFiles() {
        if (repoSetup == null) return new ArrayList<>();
        return repoSetup.getConflictFiles();
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
        return repoSetup != null ? repoSetup.getDependentRepoPaths() : new ArrayList<>();
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
     * Returns the union of the base and additional excluded patterns.
     *
     * <p>This is the complete set of glob patterns that the staging guardrails
     * will use to reject files. It combines {@code excludedPatterns}
     * (seeded from {@link GitJobConfig#DEFAULT_EXCLUDED_PATTERNS}) with any
     * patterns added via {@link #addExcludedPatterns(String...)}.</p>
     *
     * @return an unmodifiable set containing all exclusion patterns
     */
    Set<String> getAllExcludedPatterns() {
        Set<String> all = new HashSet<>(excludedPatterns);
        all.addAll(additionalExcludedPatterns);
        return Collections.unmodifiableSet(all);
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
        return commitHandler != null ? commitHandler.getStagedFiles() : new ArrayList<>();
    }

    /**
     * Returns the list of files that were skipped (with reasons).
     */
    public List<String> getSkippedFiles() {
        return commitHandler != null ? commitHandler.getSkippedFiles() : new ArrayList<>();
    }

    /**
     * Returns the commit hash if a commit was made.
     */
    public String getCommitHash() {
        return commitHandler != null ? commitHandler.getCommitHash() : null;
    }

    /**
     * Returns the pull request URL if one was detected after push.
     */
    public String getPullRequestUrl() {
        return commitHandler != null ? commitHandler.getPullRequestUrl() : null;
    }

    /**
     * Returns whether git operations completed successfully.
     */
    public boolean isGitOperationsSuccessful() {
        return commitHandler != null && commitHandler.isSuccessful();
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
        String json = event.toJson();

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
    protected void postJson(String url, String json) {
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
    String getCurrentBranch() throws IOException, InterruptedException {
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
    boolean branchExists(String branch) throws IOException, InterruptedException {
        // Check local branches
        int result = executeGit("show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        if (result == 0) return true;

        // Check remote branches
        result = executeGit("show-ref", "--verify", "--quiet", "refs/remotes/origin/" + branch);
        return result == 0;
    }

    /**
     * Checks out the target branch, creating it from the base branch if it does
     * not yet exist and {@link #createBranchIfMissing} is {@code true}.
     *
     * <p>If {@link #dryRun} is {@code true}, the checkout is only logged.
     * New branches are created with {@code --no-track} so that the upstream
     * is set explicitly to {@code origin/<targetBranch>} by the push operation
     * rather than inheriting the base branch's upstream.</p>
     *
     * @return true if we are (or would be in dry-run mode) on the target branch
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    boolean ensureOnTargetBranch() throws IOException, InterruptedException {
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
    int executeGit(String... args) throws IOException, InterruptedException {
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
    String executeGitWithOutput(String... args) throws IOException, InterruptedException {
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
     *
     * @param command the command and its arguments
     * @return the combined stdout and stderr of the command
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    String executeCommandWithOutput(String... command) throws IOException, InterruptedException {
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
        if (dependentRepos != null && !dependentRepos.isEmpty()) {
            sb.append("::depRepos:=").append(base64Encode(String.join(",", dependentRepos)));
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
            case "depRepos":
                String decodedRepos = base64Decode(value);
                List<String> repoList = new ArrayList<>();
                for (String r : decodedRepos.split(",")) {
                    String trimmed = r.trim();
                    if (!trimmed.isEmpty()) {
                        repoList.add(trimmed);
                    }
                }
                this.dependentRepos = repoList;
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
