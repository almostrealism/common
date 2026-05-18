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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects and reverts unauthorized git repository mutations made by a coding
 * agent during its {@code doWork()} session.
 *
 * <p>Before the agent runs, call {@link #recordPreWorkState()} to snapshot
 * the current HEAD hash and the set of local branch names. After the agent
 * returns, call {@link #detect()} to identify any of the following violations:
 * <ul>
 *   <li>The active branch changed away from the target branch.</li>
 *   <li>The agent created new commits that are not already on the remote
 *       {@code origin/<targetBranch>}.</li>
 *   <li>New local branches were created during the session (only flagged
 *       when combined with other violations).</li>
 * </ul>
 *
 * <p>If {@link #isDetected()} returns {@code true}, call {@link #revert()} to
 * restore the repository to the exact state captured by
 * {@link #recordPreWorkState()}. After handling the violation (e.g., retrying
 * with a corrected prompt), call {@link #reset()} before the next
 * {@link #detect()} invocation.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * GitTamperingDetector detector = new GitTamperingDetector(job, "feature/my-work");
 * detector.recordPreWorkState();
 * job.doWork();
 * detector.detect();
 * if (detector.isDetected()) {
 *     detector.revert();
 *     // optionally retry ...
 *     detector.reset();
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see GitManagedJob
 */
class GitTamperingDetector implements ConsoleFeatures {

    /** The job whose git repository is being monitored. */
    private final GitManagedJob job;

    /** The branch the agent is expected to remain on. */
    private final String targetBranch;

    /** HEAD commit hash captured just before {@code doWork()} runs. */
    private String preWorkHeadHash;

    /** Local branch names that existed just before {@code doWork()} runs. */
    private Set<String> preWorkBranches;

    /** Whether tampering was detected by the most recent {@link #detect()} call. */
    private boolean detected;

    /** Human-readable summary of the violations detected, or {@code null} if none. */
    private String description;

    /**
     * Creates a new detector for the given job and target branch.
     *
     * @param job          the job whose repository is being monitored
     * @param targetBranch the branch the agent must stay on
     */
    GitTamperingDetector(GitManagedJob job, String targetBranch) {
        this.job = job;
        this.targetBranch = targetBranch;
    }

    /**
     * Snapshots the repository state immediately before the agent session begins.
     *
     * <p>Records the HEAD commit hash and the set of local branch names so
     * that {@link #detect()} can compare against these baselines later.</p>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    void recordPreWorkState() throws IOException, InterruptedException {
        preWorkHeadHash = job.executeGitWithOutput("rev-parse", "HEAD").trim();
        log("Pre-work HEAD: " + preWorkHeadHash.substring(0, Math.min(7, preWorkHeadHash.length())));
        preWorkBranches = new HashSet<>(Arrays.asList(
                job.executeGitWithOutput("branch", "--list", "--format=%(refname:short)").split("\n")));
    }

    /**
     * Returns {@code true} if the agent created at least one git commit since
     * {@link #recordPreWorkState()} was called.
     *
     * <p>Returns {@code false} when {@link #recordPreWorkState()} has not been
     * called yet, or when the HEAD comparison cannot be performed (errors are
     * swallowed so callers need not declare checked exceptions).</p>
     *
     * @return true if HEAD has advanced beyond the pre-work snapshot
     */
    boolean hasCommitted() {
        if (preWorkHeadHash == null) {
            return false;
        }
        try {
            return !job.executeGitWithOutput("rev-parse", "HEAD").trim().equals(preWorkHeadHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Inspects the repository for unauthorized mutations made during the agent session.
     *
     * <p>Three checks are performed in sequence:
     * <ol>
     *   <li><b>Branch switch</b> — the currently active branch must equal
     *       {@link #targetBranch}.</li>
     *   <li><b>Unauthorized commits</b> — commits reachable from HEAD that
     *       are not already at {@link #preWorkHeadHash} and not on
     *       {@code origin/<targetBranch>} are counted. When the remote ref
     *       does not exist, the count is taken from
     *       {@code preWorkHeadHash..HEAD}.</li>
     *   <li><b>New local branches</b> — any branch name not present in the
     *       pre-work snapshot is recorded. This check only contributes to a
     *       violation when combined with at least one of the checks above.</li>
     * </ol>
     *
     * <p>When violations are found, {@link #detected} is set to {@code true}
     * and {@link #description} is populated with a human-readable summary.</p>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    void detect() throws IOException, InterruptedException {
        if (preWorkHeadHash == null) {
            return;
        }

        StringBuilder violations = new StringBuilder();

        // Check 1: Has the branch changed away from the target?
        String currentBranch = job.executeGitWithOutput("rev-parse", "--abbrev-ref", "HEAD").trim();
        if (!targetBranch.equals(currentBranch)) {
            violations.append("Branch switched from '").append(targetBranch)
                    .append("' to '").append(currentBranch).append("'. ");
        }

        // Check 2: Were any unauthorized commits created?
        // Fetch first so origin/<targetBranch> is up to date; exclude it so
        // pre-existing remote history is not flagged — only local-only commits count.
        job.executeGit("fetch", "origin", "--quiet");
        boolean remoteRefExists = job.executeGit("show-ref", "--verify", "--quiet",
                "refs/remotes/origin/" + targetBranch) == 0;

        String newCommits;
        if (remoteRefExists) {
            newCommits = job.executeGitWithOutput("rev-list", "HEAD",
                    "--not", preWorkHeadHash, "refs/remotes/origin/" + targetBranch,
                    "--count").trim();
        } else {
            newCommits = job.executeGitWithOutput(
                    "rev-list", preWorkHeadHash + "..HEAD", "--count").trim();
        }

        int commitCount = 0;
        try {
            commitCount = Integer.parseInt(newCommits);
        } catch (NumberFormatException ignored) {
            // Unable to parse count — check whether HEAD moved at all.
            String currentHead = job.executeGitWithOutput("rev-parse", "HEAD").trim();
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

        // Check 3: Were new local branches created?
        // Only flag this when combined with other violations — extra local branches
        // alone may be harmless artifacts from prior jobs.
        String branchOutput = job.executeGitWithOutput("branch", "--list", "--format=%(refname:short)");
        List<String> newBranches = new ArrayList<>();
        for (String branch : branchOutput.split("\n")) {
            String trimmed = branch.trim();
            if (!trimmed.isEmpty() && !preWorkBranches.contains(trimmed)) {
                newBranches.add(trimmed);
            }
        }

        if (!newBranches.isEmpty() && violations.length() > 0) {
            violations.append("Unexpected local branches found: ")
                    .append(String.join(", ", newBranches)).append(". ");
        }

        if (violations.length() > 0) {
            detected = true;
            description = violations.toString().trim();
            warn("Git tampering detected: " + description);
        }
    }

    /**
     * Restores the repository to the exact state captured by {@link #recordPreWorkState()}.
     *
     * <p>The revert sequence is:
     * <ol>
     *   <li>Discard all uncommitted changes ({@code git reset --hard}).</li>
     *   <li>Remove all untracked files ({@code git clean -fd}).</li>
     *   <li>Switch back to {@link #targetBranch} if the agent changed branches.
     *       Falls back to force-creating the branch from the pre-work hash when
     *       the branch cannot be checked out normally.</li>
     *   <li>Reset HEAD to {@link #preWorkHeadHash}, undoing any commits the agent made.</li>
     *   <li>Clean again in case the reset left untracked files.</li>
     * </ol>
     *
     * @throws IOException if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException if the hard reset to {@link #preWorkHeadHash} fails
     */
    void revert() throws IOException, InterruptedException {
        log("Reverting git tampering -- restoring pre-work state...");

        // 1. Discard all uncommitted changes and untracked files on the
        //    current branch (which may differ from targetBranch).
        job.executeGit("reset", "--hard");
        job.executeGit("clean", "-fd");

        // 2. Switch back to the target branch if needed.
        String currentBranch = job.executeGitWithOutput("rev-parse", "--abbrev-ref", "HEAD").trim();
        if (!targetBranch.equals(currentBranch)) {
            log("Switching from '" + currentBranch + "' back to '" + targetBranch + "'");
            if (job.executeGit("checkout", targetBranch) != 0) {
                // Target branch may have been deleted or was never local.
                // Force-create it from the pre-work hash so we have a safe landing point.
                warn("Could not checkout " + targetBranch + " -- recreating from pre-work HEAD");
                job.executeGit("checkout", "-B", targetBranch, preWorkHeadHash);
            }
        }

        // 3. Reset HEAD to the pre-work commit, undoing any agent commits.
        String headBefore = job.executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
        if (job.executeGit("reset", "--hard", preWorkHeadHash) != 0) {
            throw new RuntimeException(
                    "Failed to reset to pre-work HEAD " + preWorkHeadHash
                            + " -- manual intervention required");
        }

        // 4. Clean again in case the reset left untracked files.
        job.executeGit("clean", "-fd");

        String headAfter = job.executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
        log("Tampering reverted: HEAD was " + headBefore + ", now restored to " + headAfter);
    }

    /**
     * Returns whether tampering was detected by the most recent {@link #detect()} call.
     *
     * @return true if tampering was detected
     */
    boolean isDetected() {
        return detected;
    }

    /**
     * Returns a human-readable description of the tampering violations detected,
     * or {@code null} if no tampering was found.
     *
     * @return the tampering description, or null
     */
    String getDescription() {
        return description;
    }

    /**
     * Clears the detection state so this instance can be reused for a
     * subsequent {@link #detect()} call.
     *
     * <p>The pre-work snapshot ({@link #preWorkHeadHash} and
     * {@link #preWorkBranches}) is intentionally preserved, because after a
     * revert the repository is back to the same pre-work state — there is no
     * need to re-snapshot.</p>
     */
    void reset() {
        detected = false;
        description = null;
    }

    /**
     * Formats a log message with the {@code "GitTamperingDetector"} prefix and,
     * when available, the task ID from the associated job.
     *
     * @param msg the raw message text
     * @return the formatted log string
     */
    @Override
    public String formatMessage(String msg) {
        String taskId = job.getTaskId();
        if (taskId != null && !taskId.isEmpty()) {
            return "GitTamperingDetector [" + taskId + "]: " + msg;
        }
        return "GitTamperingDetector: " + msg;
    }
}
