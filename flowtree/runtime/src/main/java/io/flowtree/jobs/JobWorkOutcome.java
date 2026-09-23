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

import java.io.IOException;
import java.util.function.Predicate;

/**
 * Answers what became of the work a {@link GitManagedJob} produced: whether it
 * was committed, dropped by a staging guardrail, or left behind entirely.
 *
 * <p>These questions decide the job's completion status, and getting them
 * wrong is the failure this class exists to prevent — a job that produced real
 * changes, published none of them, and reported success, leaving the work in a
 * working directory on an agent host where nobody looks. Every path that drops
 * a job's output does so quietly, so the checks here are deliberately
 * suspicious: a question that could not be answered is answered as though work
 * were lost, never as though none existed.</p>
 *
 * <p>Holds no state of its own — it is a view onto its job, reading the
 * job's commit results and working tree through the job's own accessors.</p>
 *
 * @author Michael Murray
 */
class JobWorkOutcome {

    /** The job whose work this reports on. */
    private final GitManagedJob job;

    /** Whether {@link #capture(boolean)} has run; {@link #capturedDescription} is authoritative once it has. */
    private boolean captured;

    /** The outcome recorded by {@link #capture(boolean)}; {@code null} means nothing was orphaned. */
    private String capturedDescription;

    /**
     * Creates an outcome view of {@code job}.
     *
     * @param job the job to report on
     */
    JobWorkOutcome(GitManagedJob job) {
        this.job = job;
    }

    /**
     * Returns whether every file the agent changed was dropped by a staging
     * guardrail: the working tree had changes to stage, but none of them
     * survived {@link FileStager}'s guardrails, so nothing was committed.
     *
     * <p>Different from "nothing to do" (a session that made no changes at
     * all): here the job DID produce a change and it was discarded, so the job
     * must not report success.</p>
     *
     * <p>Publication is asked across every repository, not just the primary
     * one. A job whose primary files were all skipped may still have committed
     * a dependent repo, and that job did not have all its changes dropped.</p>
     *
     * @return {@code true} when every changed file was skipped and nothing
     *         was committed anywhere
     */
    boolean allChangesDropped() {
        // A non-empty skip list is itself proof the commit handler ran.
        return !job.getSkippedFiles().isEmpty()
                && job.getStagedFiles().isEmpty()
                && !job.hasPublishedCommit();
    }

    /**
     * Returns a description of work the job produced but never published, or
     * {@code null} when its output and its commit agree.
     *
     * <p>A git-managed job that finishes without a commit published nothing.
     * That is correct for a session that deliberately changed nothing — a
     * review that found no defect, an investigation that only reported — and
     * wrong for one that produced something. The two are told apart by what
     * the session left behind: uncommitted changes to non-excluded files in a
     * working tree, or an
     * {@linkplain GitManagedJob#authoredCommitMessage() authored commit
     * message}.</p>
     *
     * <p>Either one with no commit behind it means the job's output was
     * dropped on the way out, and every path that drops it does so quietly: a
     * {@link GitManagedJob#validateChanges()} that returned false, staging
     * guardrails that passed no file, a phase that threw after the work was
     * already made. Reporting success is what keeps those paths invisible.</p>
     *
     * <p>A tampering revert is checked first, and against the primary commit
     * hash alone rather than {@link GitManagedJob#hasPublishedCommit()}. The
     * revert destroyed primary-tree work, and only a primary commit from the
     * restart replaces it; a dependent-repository commit is different work and
     * would otherwise mask the loss. The reported reason speaks of the agent's
     * changes rather than its commits, because
     * {@link GitTamperingDetector#revert()} also runs for a branch switch,
     * where the reset discarded uncommitted state and no commit ever
     * existed.</p>
     *
     * @return the failure description, or {@code null} when nothing was orphaned
     */
    String describeUnpublishedWork() {
        if (captured) return capturedDescription;
        return computeUnpublishedWork();
    }

    /**
     * Records the outcome while the caller still holds the workspace lock.
     *
     * <p>{@link GitManagedJob#run()} releases that lock in its {@code finally}
     * block before the completion event is built, and the working-tree query
     * below reads a directory that the next job for the same workspace may
     * already have started editing. Asking afterwards can therefore report
     * another job's changes as this one's unpublished work. Asking here, at
     * the end of the locked region, reads the tree this job actually left.</p>
     *
     * <p>{@code locked} says whether that region was in fact exclusive.
     * {@link WorkspaceLock#acquire(String)} can fail — an unwritable parent
     * directory, an I/O error — and lets the job continue unlocked, in which
     * case this reading has the same race it was added to remove. It is still
     * the best reading available, and taken here rather than later because
     * later is strictly worse, but the caller is told so it can say the
     * outcome was not observed under exclusion.</p>
     *
     * @param locked whether the workspace lock was held for this job
     */
    void capture(boolean locked) {
        capturedDescription = computeUnpublishedWork();
        captured = true;
        if (!locked) {
            job.warn("Workspace lock was not held; the completion snapshot of "
                    + job.getWorkingDirectory() + " may not reflect this job alone");
        }
    }

    /**
     * Computes the unpublished-work description from the job's current state.
     *
     * @return the failure description, or {@code null} when nothing was orphaned
     */
    private String computeUnpublishedWork() {
        if (job.getTargetBranch() == null || job.getTargetBranch().isEmpty()) return null;
        if (!job.performsGitOperations()) return null;
        if (job.isDryRun()) return null;

        String primaryCommit = job.getCommitHash();
        boolean primaryCommitted = primaryCommit != null && !primaryCommit.isEmpty();
        if (job.hasRevertedAgentWork() && !primaryCommitted) {
            return "Nothing was committed to the primary repository, and the agent's own changes"
                    + " to it were reverted as git tampering. Whatever they held was destroyed"
                    + " and no restart replaced it.";
        }

        if (job.hasPublishedCommit()) return null;
        if (job.hasAgentCommitted()) return null;

        boolean changesRemain = mayHaveUncommittedChanges();
        String authored = job.authoredCommitMessage();
        boolean messageAuthored = authored != null && !authored.trim().isEmpty();
        if (!changesRemain && !messageAuthored) return null;

        String produced;
        if (changesRemain && messageAuthored) {
            produced = "the working tree still holds uncommitted changes and the agent wrote a"
                    + " commit message for them";
        } else if (changesRemain) {
            produced = "the working tree still holds uncommitted changes";
        } else {
            produced = "the agent wrote a commit message, so it had changes to describe, yet none"
                    + " of them reached the tree";
        }
        return "Nothing was committed or pushed, but the job produced work: " + produced
                + ". The work is still in the working directory on the agent host, unpublished.";
    }

    /**
     * Returns whether any of the job's repositories holds uncommitted changes
     * that {@code git status} actually reported.
     *
     * <p>A query that could not run reads as "no changes" here. That is the
     * right answer for callers deciding whether there is work to act on — a
     * review session, a change-enforcement retry — because acting on a tree
     * nobody could read produces nothing. Callers deciding whether work may
     * have been LOST want {@link #mayHaveUncommittedChanges()} instead.</p>
     *
     * @return {@code true} when changes were observed in any repository
     */
    boolean observedUncommittedChanges() {
        return anyRepository(GitOperations::hasUncommittedChanges);
    }

    /**
     * Returns whether any of the job's repositories holds uncommitted changes,
     * treating a tree that could not be read as one that might.
     *
     * <p>This is the fail-closed form, for the questions where a wrong "no"
     * loses work: whether the job published everything it produced, and
     * whether it still owes a commit message. See
     * {@link #hasUncommittedChanges(String)}.</p>
     *
     * @return {@code true} when changes remain or a tree could not be read
     */
    boolean mayHaveUncommittedChanges() {
        return anyRepository(this::hasUncommittedChanges);
    }

    /**
     * Applies {@code test} to the primary working directory and every
     * dependent repository, stopping at the first {@code true}.
     *
     * @param test the per-repository question to ask
     * @return {@code true} when any repository answers {@code true}
     */
    private boolean anyRepository(Predicate<String> test) {
        if (test.test(job.getWorkingDirectory())) return true;
        for (String depPath : job.getDependentRepoPaths()) {
            if (test.test(depPath)) return true;
        }
        return false;
    }

    /**
     * Returns whether {@code repoPath} holds uncommitted changes to files
     * outside the standard exclusion set (see
     * {@link GitOperations#isExcludedPath(String)}), so harness artifacts such
     * as {@code commit.txt} do not read as work.
     *
     * <p>Fails closed. {@link GitOperations#getChangedFiles(String)} returns an
     * empty list when {@code git status} cannot run at all — an inaccessible
     * worktree, a missing git executable, an interrupted query — which is
     * indistinguishable from a clean tree. Every caller reads "no changes" as
     * permission to skip work, so a query that could not answer is treated as
     * "changes may exist" rather than "there are none".</p>
     *
     * @param repoPath the repository root to inspect
     * @return {@code true} when changes remain or the tree could not be read
     */
    boolean hasUncommittedChanges(String repoPath) {
        try {
            for (String file : GitOperations.requireChangedFiles(repoPath)) {
                if (!GitOperations.isExcludedPath(file)) return true;
            }
            return false;
        } catch (IOException e) {
            job.warn("Could not determine whether " + repoPath + " has uncommitted changes;"
                    + " assuming it does: " + e.getMessage());
            return true;
        }
    }
}
