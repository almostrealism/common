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

import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the file-staging, committing, pushing and pull-request-detection
 * phases of a {@link GitManagedJob} run.
 *
 * <p>This class encapsulates the logic that was previously spread across
 * {@code GitManagedJob.handleGitOperations()}, {@code findChangedFiles()},
 * {@code stageFiles()}, {@code commit()}, {@code pushToOrigin()}, and
 * {@code detectPullRequestUrl()}, making each concern independently testable
 * and easier to read.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Construct with a {@link GitManagedJob} reference.</li>
 *   <li>Call {@link #handle(boolean)} after tampering detection and validation
 *       have completed successfully.</li>
 *   <li>Inspect results via the getters ({@link #getStagedFiles()},
 *       {@link #getCommitHash()}, {@link #getPullRequestUrl()}, etc.).</li>
 * </ol>
 *
 * <h2>Merge-in-progress path</h2>
 * <p>When {@code synchronizeWithBaseBranch()} left the repository with a
 * {@code MERGE_HEAD} present (conflict left for the agent to resolve),
 * {@link #handle(boolean)} detects this and takes a special path: it verifies
 * all conflicts are resolved, stages any additional agent changes, and
 * produces a merge commit rather than an ordinary commit.</p>
 *
 * @author Michael Murray
 * @see GitManagedJob
 * @see FileStager
 * @see PullRequestDetector
 */
class GitCommitHandler implements ConsoleFeatures {

    /** The job that owns this handler. */
    private final GitManagedJob job;

    /** Files that passed all staging guardrails and were added to the index. */
    private final List<String> stagedFiles = new ArrayList<>();

    /** Files that were rejected by a guardrail, with a reason appended to each entry. */
    private final List<String> skippedFiles = new ArrayList<>();

    /** Full SHA-1 hash of the commit created by this handler, or {@code null}. */
    private String commitHash;

    /** URL of an open pull request detected after push, or {@code null}. */
    private String pullRequestUrl;

    /** Set to {@code true} when all git operations complete without error. */
    private boolean successful;

    /**
     * Creates a new handler for the given job.
     *
     * @param job the job that this handler operates on behalf of
     */
    GitCommitHandler(GitManagedJob job) {
        this.job = job;
    }

    /**
     * Executes all git operations: branch validation, file staging, commit,
     * optional push, and pull-request detection.
     *
     * <p>The full sequence is:
     * <ol>
     *   <li>Verify (or switch to) the target branch via
     *       {@link GitManagedJob#ensureOnTargetBranch()}, throwing if it fails.</li>
     *   <li>Detect whether a merge is in progress ({@code MERGE_HEAD} present).</li>
     *   <li><b>Merge path</b>: verify no unresolved conflicts remain, stage any
     *       additional agent changes, and skip commit if the index is already
     *       clean (nothing for us to add).</li>
     *   <li><b>Normal path</b>: unstage anything the agent staged directly
     *       ({@code git reset HEAD}), discover changed files, and stage them
     *       through the {@link FileStager} guardrails.</li>
     *   <li>Commit (as a merge commit when {@code MERGE_HEAD} is present).</li>
     *   <li>Push to {@code origin} if enabled and not in dry-run mode.</li>
     *   <li>Detect an open pull request for the target branch.</li>
     * </ol>
     *
     * @param mergeConflictsDetected {@code true} when the base-branch sync
     *        detected conflicts that the agent was asked to resolve
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException if the branch switch, commit, or push fails
     */
    void handle(boolean mergeConflictsDetected) throws IOException, InterruptedException {
        log("Starting git operations...");
        log("Target branch: " + job.getTargetBranch());

        // Step 1: Ensure we are on the target branch.
        if (!job.ensureOnTargetBranch()) {
            throw new RuntimeException("Failed to switch to target branch '"
                    + job.getTargetBranch() + "'");
        }

        // Step 2: Detect whether a merge is in progress.
        boolean mergeInProgress = !job.executeGitWithOutput(
                "rev-parse", "--verify", "--quiet", "MERGE_HEAD").trim().isEmpty();

        if (mergeInProgress) {
            log("Merge in progress -- will commit as merge commit");

            // Verify the agent resolved every conflict marker.
            String unmerged = job.executeGitWithOutput("ls-files", "--unmerged").trim();
            if (!unmerged.isEmpty()) {
                throw new RuntimeException(
                        "Unresolved merge conflicts remain after agent run -- cannot commit:\n"
                                + unmerged);
            }

            // Stage any additional changes the agent made (through guardrails),
            // but do NOT reset HEAD — that would abort the in-progress merge.
            List<String> changedFiles = findChangedFiles();
            if (!changedFiles.isEmpty()) {
                stageFiles(changedFiles);
            }

            // There may already be staged merge content even if the agent made
            // no extra working-tree changes.  If the index is entirely empty,
            // there is nothing for us to commit.
            if (job.executeGitWithOutput("diff", "--name-only", "--cached").trim().isEmpty()) {
                log("No changes to commit");
                successful = true;
                return;
            }

        } else {
            // Normal (non-merge) path.
            // Unstage everything the agent may have staged via `git add` so
            // every file passes through the guardrails below.
            job.executeGit("reset", "HEAD");

            List<String> changedFiles = findChangedFiles();
            if (changedFiles.isEmpty()) {
                log("No changes to commit");
                successful = true;
                return;
            }

            stageFiles(changedFiles);

            if (stagedFiles.isEmpty()) {
                log("No files passed guardrails, nothing to commit");
                successful = true;
                return;
            }
        }

        // Step 5: Commit.
        commit();

        // Step 6: Push if enabled.
        if (job.isPushToOrigin() && !job.isDryRun()) {
            push();
        }

        // Step 7: Detect open pull request.
        pullRequestUrl = detectPullRequestUrl();
        if (pullRequestUrl != null) {
            log("Open PR: " + pullRequestUrl);
        }

        successful = true;
        log("Git operations completed successfully");
    }

    /**
     * Returns all files reported as changed by {@code git status --porcelain}.
     *
     * <p>Includes modified, added, deleted, and untracked files. For renamed
     * files, the post-rename path is returned (the part after {@code " -> "}).</p>
     *
     * @return list of changed file paths relative to the working directory
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private List<String> findChangedFiles() throws IOException, InterruptedException {
        List<String> files = new ArrayList<>();

        String statusOutput = job.executeGitWithOutput("status", "--porcelain");
        for (String line : statusOutput.split("\n")) {
            if (line.length() > 3) {
                String file = line.substring(3).trim();
                // Handle renamed files: "old/path -> new/path" — take the new path.
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
     * Evaluates each candidate file through the {@link FileStager} guardrails
     * and issues {@code git add} for each file that passes.
     *
     * <p>The guardrails applied (in order) are: excluded-pattern check, test-file
     * protection, file-size limit, and binary detection. Files that pass are
     * recorded in {@link #stagedFiles}; rejected files are recorded in
     * {@link #skippedFiles} with a reason suffix.</p>
     *
     * @param files the candidate file paths to evaluate and stage
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private void stageFiles(List<String> files) throws IOException, InterruptedException {
        FileStagingConfig config = FileStagingConfig.builder()
                .excludedPatterns(job.getAllExcludedPatterns())
                .protectedPathPatterns(GitJobConfig.PROTECTED_PATH_PATTERNS)
                .protectTestFiles(job.isProtectTestFiles())
                .baseBranch(job.getBaseBranch())
                .maxFileSizeBytes(job.getMaxFileSizeBytes())
                .build();

        FileStager stager = new FileStager();
        File workDir = job.getWorkingDirectory() != null
                ? new File(job.getWorkingDirectory())
                : new File(".");

        StagingResult result = stager.evaluateFiles(files, config, workDir, job::executeGit);

        // Stage each approved file.
        for (String file : result.getStagedFiles()) {
            if (job.isDryRun()) {
                log("DRY RUN: Would stage: " + file);
                stagedFiles.add(file);
            } else {
                if (job.executeGit("add", file) == 0) {
                    stagedFiles.add(file);
                    log("Staged: " + file);
                } else {
                    warn("Failed to stage: " + file);
                }
            }
        }

        skippedFiles.addAll(result.getSkippedFiles());
        log("Staged " + stagedFiles.size() + " files, skipped " + skippedFiles.size());
    }

    /**
     * Commits the staged changes using the message from
     * {@link GitManagedJob#getCommitMessage()}.
     *
     * <p>When {@link GitManagedJob#getGitUserName()} and/or
     * {@link GitManagedJob#getGitUserEmail()} are set, they are forwarded as
     * {@code git -c user.name=...} and {@code git -c user.email=...} flags so
     * the identity is scoped to this process invocation only.</p>
     *
     * <p>On success, the resulting commit hash is stored in {@link #commitHash}.</p>
     *
     * @throws IOException if the git process cannot be started
     * @throws InterruptedException if the calling thread is interrupted
     * @throws RuntimeException if the commit fails (non-zero exit code)
     */
    private void commit() throws IOException, InterruptedException {
        String message = job.getCommitMessage();

        if (job.isDryRun()) {
            log("DRY RUN: Would commit with message: " + message);
            return;
        }

        // Build the argument list, inserting identity -c flags before "commit".
        List<String> args = new ArrayList<>();
        String gitUserName = job.getGitUserName();
        String gitUserEmail = job.getGitUserEmail();

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

        log("Committing as: "
                + (gitUserName != null ? gitUserName : "(default)") + " <"
                + (gitUserEmail != null ? gitUserEmail : "(default)") + ">");

        int result = job.executeGit(args.toArray(new String[0]));
        if (result != 0) {
            throw new RuntimeException(
                    "Git commit failed after staging " + stagedFiles.size() + " files");
        }

        commitHash = job.executeGitWithOutput("rev-parse", "HEAD").trim();
        log("Committed: " + commitHash);
    }

    /**
     * Pushes the current branch to the {@code origin} remote.
     *
     * <p>An explicit refspec ({@code targetBranch:targetBranch}) is used so the
     * push always targets the correct remote branch regardless of inherited
     * upstream tracking configuration. The {@code -u} flag sets the upstream to
     * {@code origin/<targetBranch>} for subsequent operations.</p>
     *
     * @throws IOException if the git process cannot be started
     * @throws InterruptedException if the calling thread is interrupted
     * @throws RuntimeException if the push fails (non-zero exit code)
     */
    private void push() throws IOException, InterruptedException {
        String targetBranch = job.getTargetBranch();
        log("Pushing to origin/" + targetBranch + "...");

        String refspec = targetBranch + ":" + targetBranch;
        int result = job.executeGit("push", "-u", "origin", refspec);
        if (result != 0) {
            throw new RuntimeException(
                    "Git push to origin/" + targetBranch + " failed");
        }

        log("Pushed to origin/" + targetBranch);
    }

    /**
     * Detects an open pull request for the target branch using
     * {@link PullRequestDetector}.
     *
     * <p>The remote URL is resolved from {@code git remote get-url origin}.
     * Detection is a best-effort operation: any exception is swallowed and
     * {@code null} is returned.</p>
     *
     * @return the PR {@code html_url}, or {@code null} if none was found or
     *         detection failed
     */
    private String detectPullRequestUrl() {
        try {
            String remoteUrl = job.executeGitWithOutput("remote", "get-url", "origin").trim();
            PullRequestDetector detector = new PullRequestDetector();
            return detector.detect(remoteUrl, job.getTargetBranch(), job.getWorkstreamUrl())
                    .orElse(null);
        } catch (Exception e) {
            log("Could not detect PR URL: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the files that passed all staging guardrails and were added to
     * the git index.
     *
     * @return an unmodifiable view of the staged file list
     */
    List<String> getStagedFiles() {
        return new ArrayList<>(stagedFiles);
    }

    /**
     * Returns the files that were rejected by a staging guardrail, with a
     * reason suffix appended to each entry (e.g., {@code "file.jar (binary file)"}).
     *
     * @return an unmodifiable view of the skipped file list
     */
    List<String> getSkippedFiles() {
        return new ArrayList<>(skippedFiles);
    }

    /**
     * Returns the full SHA-1 hash of the commit created by this handler,
     * or {@code null} if no commit was made (e.g., dry run or no changes).
     *
     * @return the commit hash, or null
     */
    String getCommitHash() {
        return commitHash;
    }

    /**
     * Returns the URL of an open pull request detected after push, or
     * {@code null} if no PR was found or PR detection was not attempted.
     *
     * @return the pull request URL, or null
     */
    String getPullRequestUrl() {
        return pullRequestUrl;
    }

    /**
     * Returns {@code true} when all git operations (stage, commit, optional push)
     * completed without throwing an exception.
     *
     * @return true if git operations succeeded
     */
    boolean isSuccessful() {
        return successful;
    }

    /**
     * Handles git operations (stage, commit, push) for all dependent repos.
     * Uses the same commit message as the primary repo. No-op when {@code depPaths} is empty.
     *
     * @param depPaths resolved filesystem paths of dependent repositories
     * @throws IOException if a git operation fails
     * @throws InterruptedException if a command is interrupted
     */
    void handleDependentRepos(List<String> depPaths) throws IOException, InterruptedException {
        for (String depPath : depPaths) {
            GitOperations gitOps = new GitOperations(depPath, job.getTaskId());
            String name = job.getGitUserName();
            String email = job.getGitUserEmail();
            if (name != null && !name.isEmpty()) gitOps.setGitUserName(name);
            if (email != null && !email.isEmpty()) gitOps.setGitUserEmail(email);

            String statusOutput = gitOps.executeWithOutput("status", "--porcelain");
            List<String> changedFiles = new ArrayList<>();
            for (String line : statusOutput.split("\n")) {
                if (line.length() > 3) {
                    String file = line.substring(3).trim();
                    if (file.contains(" -> ")) file = file.split(" -> ")[1];
                    if (!file.isEmpty()) changedFiles.add(file);
                }
            }
            if (changedFiles.isEmpty()) {
                log("No changes in dependent repo: " + depPath);
                continue;
            }
            log("Committing " + changedFiles.size() + " changes in dependent repo: " + depPath);

            boolean anyStagedInDep = false;
            for (String file : changedFiles) {
                File f = new File(depPath, file);
                if (f.exists() && f.length() > job.getMaxFileSizeBytes()) {
                    log("SKIP (size) in dependent repo: " + file);
                    continue;
                }
                gitOps.execute("add", file);
                anyStagedInDep = true;
            }
            if (!anyStagedInDep) {
                log("No files staged in dependent repo (all skipped): " + depPath);
                continue;
            }

            int commitExitCode = gitOps.execute("commit", "-m", job.getCommitMessage());
            if (commitExitCode != 0) {
                log("Commit failed in dependent repo: " + depPath + " (exit code " + commitExitCode + ")");
                continue;
            }

            if (job.isPushToOrigin() && !job.isDryRun()) {
                String refspec = job.getTargetBranch() + ":" + job.getTargetBranch();
                int pushExitCode = gitOps.execute("push", "-u", "origin", refspec);
                if (pushExitCode == 0) {
                    log("Pushed dependent repo: " + depPath);
                } else {
                    throw new IOException("Git push failed for dependent repo: " + depPath);
                }
            }
        }
    }

    /**
     * Formats a log message with the {@code "GitCommitHandler"} prefix and,
     * when available, the task ID from the associated job.
     *
     * @param msg the raw message text
     * @return the formatted log string
     */
    @Override
    public String formatMessage(String msg) {
        String taskId = job.getTaskId();
        if (taskId != null && !taskId.isEmpty()) {
            return "GitCommitHandler [" + taskId + "]: " + msg;
        }
        return "GitCommitHandler: " + msg;
    }
}
