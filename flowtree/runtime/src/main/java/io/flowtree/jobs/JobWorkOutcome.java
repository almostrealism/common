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
     * @return {@code true} when every changed file was skipped and nothing
     *         was staged or committed
     */
    boolean allChangesDropped() {
        // A non-empty skip list is itself proof the commit handler ran.
        return !job.getSkippedFiles().isEmpty()
                && job.getStagedFiles().isEmpty()
                && job.getCommitHash() == null;
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
     * @return the failure description, or {@code null} when nothing was orphaned
     */
    String describeUnpublishedWork() {
        if (job.getTargetBranch() == null || job.getTargetBranch().isEmpty()) return null;
        if (!job.performsGitOperations()) return null;
        if (job.isDryRun() || job.hasAgentCommitted()) return null;
        String commit = job.getCommitHash();
        if (commit != null && !commit.isEmpty()) return null;

        boolean changesRemain = job.hasUncommittedChanges();
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
