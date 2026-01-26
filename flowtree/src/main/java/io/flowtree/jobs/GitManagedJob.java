/*
 * Copyright 2025 Michael Murray
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

import io.flowtree.job.Job;
import org.almostrealism.io.JobOutput;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
public abstract class GitManagedJob implements Job {

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
        "Extensions/**", "*.cl", "*.metal"
    ));

    /** Global list of completion listeners. */
    private static final List<JobCompletionListener> completionListeners = new CopyOnWriteArrayList<>();

    /**
     * Registers a global listener for job completion events.
     * Listeners are notified when any GitManagedJob completes.
     *
     * @param listener the listener to register
     */
    public static void addCompletionListener(JobCompletionListener listener) {
        completionListeners.add(listener);
    }

    /**
     * Removes a previously registered completion listener.
     *
     * @param listener the listener to remove
     */
    public static void removeCompletionListener(JobCompletionListener listener) {
        completionListeners.remove(listener);
    }

    private String taskId;
    private String targetBranch;
    private String workingDirectory;
    private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;
    private Set<String> excludedPatterns = new HashSet<>(DEFAULT_EXCLUDED_PATTERNS);
    private Set<String> additionalExcludedPatterns = new HashSet<>();
    private boolean pushToOrigin = true;
    private boolean createBranchIfMissing = true;
    private boolean dryRun = false;

    private String originalBranch;
    private List<String> stagedFiles = new ArrayList<>();
    private List<String> skippedFiles = new ArrayList<>();
    private String commitHash;
    private boolean gitOperationsSuccessful = false;

    private Consumer<JobOutput> outputConsumer;
    private final CompletableFuture<Void> future = new CompletableFuture<>();

    private String workstreamId;
    private JobCompletionListener instanceListener;

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
            // Fire started event
            fireJobStarted();

            // Capture original branch before work begins
            if (targetBranch != null) {
                originalBranch = getCurrentBranch();
            }

            // Perform the actual work
            doWork();

            // Handle git operations if a target branch is specified
            if (targetBranch != null && !targetBranch.isEmpty()) {
                handleGitOperations();
            }

        } catch (Exception e) {
            System.err.println("[GitManagedJob] Error: " + e.getMessage());
            e.printStackTrace();
            error = e;
        } finally {
            // Fire completion event
            fireJobCompleted(error);
            future.complete(null);
        }
    }

    /**
     * Fires the job started event to all registered listeners.
     */
    protected void fireJobStarted() {
        JobCompletionEvent event = JobCompletionEvent.started(
            taskId, workstreamId, getTaskString()
        );
        event.withGitInfo(targetBranch, null, null, null, false);
        populateEventDetails(event);

        for (JobCompletionListener listener : completionListeners) {
            try {
                listener.onJobStarted(event);
            } catch (Exception e) {
                System.err.println("[GitManagedJob] Listener error: " + e.getMessage());
            }
        }

        if (instanceListener != null) {
            try {
                instanceListener.onJobStarted(event);
            } catch (Exception e) {
                System.err.println("[GitManagedJob] Instance listener error: " + e.getMessage());
            }
        }
    }

    /**
     * Fires the job completed event to all registered listeners.
     */
    protected void fireJobCompleted(Exception error) {
        JobCompletionEvent event;

        if (error != null) {
            event = JobCompletionEvent.failed(
                taskId, workstreamId, getTaskString(),
                error.getMessage(), error
            );
        } else {
            event = JobCompletionEvent.success(
                taskId, workstreamId, getTaskString()
            );
        }

        event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles, gitOperationsSuccessful && pushToOrigin);
        populateEventDetails(event);

        for (JobCompletionListener listener : completionListeners) {
            try {
                listener.onJobCompleted(event);
            } catch (Exception e) {
                System.err.println("[GitManagedJob] Listener error: " + e.getMessage());
            }
        }

        if (instanceListener != null) {
            try {
                instanceListener.onJobCompleted(event);
            } catch (Exception e) {
                System.err.println("[GitManagedJob] Instance listener error: " + e.getMessage());
            }
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
     * Handles all git operations: branch management, staging, committing, and pushing.
     */
    private void handleGitOperations() {
        System.out.println("[GitManagedJob] Starting git operations...");
        System.out.println("[GitManagedJob] Target branch: " + targetBranch);

        try {
            // Step 1: Ensure we're on the target branch
            if (!ensureOnTargetBranch()) {
                System.err.println("[GitManagedJob] Failed to switch to target branch");
                return;
            }

            // Step 2: Find and filter changed files
            List<String> changedFiles = findChangedFiles();
            if (changedFiles.isEmpty()) {
                System.out.println("[GitManagedJob] No changes to commit");
                gitOperationsSuccessful = true;
                return;
            }

            // Step 3: Stage files (with guardrails)
            stageFiles(changedFiles);
            if (stagedFiles.isEmpty()) {
                System.out.println("[GitManagedJob] No files passed guardrails, nothing to commit");
                gitOperationsSuccessful = true;
                return;
            }

            // Step 4: Commit
            if (!commit()) {
                System.err.println("[GitManagedJob] Commit failed");
                return;
            }

            // Step 5: Push to origin
            if (pushToOrigin && !dryRun) {
                if (!pushToOrigin()) {
                    System.err.println("[GitManagedJob] Push failed");
                    return;
                }
            }

            gitOperationsSuccessful = true;
            System.out.println("[GitManagedJob] Git operations completed successfully");

        } catch (Exception e) {
            System.err.println("[GitManagedJob] Git operations failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Ensures we're on the target branch, creating it if necessary.
     */
    private boolean ensureOnTargetBranch() throws IOException, InterruptedException {
        String currentBranch = getCurrentBranch();

        if (targetBranch.equals(currentBranch)) {
            System.out.println("[GitManagedJob] Already on target branch: " + targetBranch);
            return true;
        }

        // Check if target branch exists
        boolean branchExists = branchExists(targetBranch);

        if (!branchExists && !createBranchIfMissing) {
            System.err.println("[GitManagedJob] Target branch does not exist and createBranchIfMissing=false");
            return false;
        }

        if (dryRun) {
            System.out.println("[GitManagedJob] DRY RUN: Would " +
                (branchExists ? "checkout" : "create and checkout") + " branch: " + targetBranch);
            return true;
        }

        if (branchExists) {
            // Checkout existing branch
            return executeGit("checkout", targetBranch) == 0;
        } else {
            // Create new branch from current HEAD
            System.out.println("[GitManagedJob] Creating new branch: " + targetBranch);
            return executeGit("checkout", "-b", targetBranch) == 0;
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

        System.out.println("[GitManagedJob] Found " + files.size() + " changed files");
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
                System.out.println("[GitManagedJob] SKIP (pattern): " + file);
                skippedFiles.add(file + " (excluded pattern)");
                continue;
            }

            // Guardrail 2: Check file size (only for existing files)
            if (!isDeleted && f.length() > maxFileSizeBytes) {
                System.out.println("[GitManagedJob] SKIP (size " + formatSize(f.length()) + "): " + file);
                skippedFiles.add(file + " (exceeds " + formatSize(maxFileSizeBytes) + ")");
                continue;
            }

            // Guardrail 3: Check if binary (only for existing files)
            if (!isDeleted && isBinaryFile(f)) {
                System.out.println("[GitManagedJob] SKIP (binary): " + file);
                skippedFiles.add(file + " (binary file)");
                continue;
            }

            // File passed all guardrails
            if (dryRun) {
                System.out.println("[GitManagedJob] DRY RUN: Would stage: " + file);
            } else {
                if (executeGit("add", file) == 0) {
                    stagedFiles.add(file);
                    System.out.println("[GitManagedJob] Staged: " + file);
                } else {
                    System.err.println("[GitManagedJob] Failed to stage: " + file);
                }
            }
        }

        System.out.println("[GitManagedJob] Staged " + stagedFiles.size() + " files, skipped " + skippedFiles.size());
    }

    /**
     * Commits the staged changes.
     */
    private boolean commit() throws IOException, InterruptedException {
        String message = getCommitMessage();

        if (dryRun) {
            System.out.println("[GitManagedJob] DRY RUN: Would commit with message: " + message);
            return true;
        }

        int result = executeGit("commit", "-m", message);
        if (result == 0) {
            // Get the commit hash
            commitHash = executeGitWithOutput("rev-parse", "HEAD").trim();
            System.out.println("[GitManagedJob] Committed: " + commitHash);
            return true;
        }

        return false;
    }

    /**
     * Pushes changes to origin.
     */
    private boolean pushToOrigin() throws IOException, InterruptedException {
        System.out.println("[GitManagedJob] Pushing to origin...");

        // Push with -u to set upstream if this is a new branch
        int result = executeGit("push", "-u", "origin", targetBranch);
        if (result == 0) {
            System.out.println("[GitManagedJob] Pushed to origin/" + targetBranch);
            return true;
        }

        return false;
    }

    // ==================== Git Utilities ====================

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

        Process process = pb.start();
        // Consume output to prevent blocking
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) { /* consume */ }
        }

        return process.waitFor();
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
        // Convert glob pattern to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("**/", "(.*/)?")
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .replace("?", ".");

        return Pattern.matches(regex, path) ||
               Pattern.matches(".*/" + regex, path) ||
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

    public String getWorkstreamId() {
        return workstreamId;
    }

    /**
     * Sets the workstream ID for this job.
     * Used for routing completion events to the correct Slack channel.
     *
     * @param workstreamId the workstream identifier
     */
    public void setWorkstreamId(String workstreamId) {
        this.workstreamId = workstreamId;
    }

    /**
     * Sets an instance-level completion listener for this job.
     * This listener is called in addition to any global listeners.
     *
     * @param listener the listener to notify on completion
     */
    public void setCompletionListener(JobCompletionListener listener) {
        this.instanceListener = listener;
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

    // ==================== Encoding ====================

    protected static String base64Encode(String s) {
        return s == null ? "" : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

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
        if (workingDirectory != null) {
            sb.append("::workDir:=").append(base64Encode(workingDirectory));
        }
        sb.append("::maxFileSize:=").append(maxFileSizeBytes);
        sb.append("::push:=").append(pushToOrigin);
        sb.append("::createBranch:=").append(createBranchIfMissing);
        sb.append("::dryRun:=").append(dryRun);
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
        }
    }
}
