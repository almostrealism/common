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

package io.flowtree.controller;

/**
 * The controller's one per-PR remediation record, keyed by workstream (one
 * workstream is one branch is one pull request). It is the single piece of
 * state the two halves of the remediation loop read: the count of consecutive
 * passes that changed nothing, which bounds how many times a PR may be
 * auto-remediated before a person is pinged, and the last commit a pass produced, so a review
 * re-triggered only by the previous review's own commit is not run again.
 *
 * <p>A "pass" is a completed remediation job on the workstream. A pass that
 * produced a commit made progress and resets the count; one that produced no
 * commit changed nothing and increments it. (The controller cannot observe
 * whether a committing pass flipped a CI quality gate red→green, so a commit is
 * taken as progress — see {@link RemediationStateStore#recordPass}.) The record
 * is immutable; {@link RemediationStateStore} returns a fresh one on each
 * change.</p>
 *
 * @author Michael Murray
 * @see RemediationStateStore
 */
public final class RemediationState {

    /** The workstream (branch / PR) this record belongs to. */
    private final String workstreamId;

    /** Consecutive completed passes that produced no commit. */
    private final int consecutiveNoProgress;

    /** The commit SHA the most recent committing pass produced, or {@code null}. */
    private final String lastProducedCommit;

    /** Whether a person has already been notified that the budget is spent. */
    private final boolean notified;

    /**
     * Creates a remediation record.
     *
     * @param workstreamId          the workstream (branch / PR) key
     * @param consecutiveNoProgress consecutive no-commit passes, never negative
     * @param lastProducedCommit    the last committing pass's SHA, or {@code null}
     * @param notified              whether the budget-spent ping was already sent
     */
    public RemediationState(String workstreamId, int consecutiveNoProgress,
                            String lastProducedCommit, boolean notified) {
        this.workstreamId = workstreamId;
        this.consecutiveNoProgress = Math.max(0, consecutiveNoProgress);
        this.lastProducedCommit = lastProducedCommit;
        this.notified = notified;
    }

    /**
     * Returns the empty record for a workstream with no remediation history.
     *
     * @param workstreamId the workstream key
     * @return a zero-count record with no produced commit and no notification
     */
    public static RemediationState empty(String workstreamId) {
        return new RemediationState(workstreamId, 0, null, false);
    }

    /** Returns the workstream (branch / PR) key. */
    public String getWorkstreamId() { return workstreamId; }

    /** Returns the count of consecutive completed passes that produced no commit. */
    public int getConsecutiveNoProgress() { return consecutiveNoProgress; }

    /** Returns the last committing pass's SHA, or {@code null} when none has committed. */
    public String getLastProducedCommit() { return lastProducedCommit; }

    /** Returns whether the budget-spent notification has already been sent. */
    public boolean isNotified() { return notified; }

    /**
     * Returns whether the consecutive-no-progress count has reached the budget.
     *
     * @param budget the per-PR budget; a non-positive value disables the cap
     * @return {@code true} when auto-dispatch should stop and a person be pinged
     */
    public boolean budgetExhausted(int budget) {
        return budget > 0 && consecutiveNoProgress >= budget;
    }

    /**
     * Returns whether {@code triggerCommit} is exactly the commit the last pass
     * produced — i.e. the only new commit on the PR is the previous pass's own,
     * so re-reviewing it would re-enter the self-triggered loop. Any other
     * commit (a person's, or a later pass's) does not match and still triggers.
     *
     * @param triggerCommit the commit that triggered a new submission, or {@code null}
     * @return {@code true} when the triggering commit is the last pass's own
     */
    public boolean isLastProducedCommit(String triggerCommit) {
        return triggerCommit != null && !triggerCommit.isEmpty()
                && triggerCommit.equals(lastProducedCommit);
    }

    /**
     * Returns the record left by completing one remediation pass. A pass that
     * produced a commit made progress: the consecutive-no-progress count resets
     * to zero, the commit becomes the last produced, and the notified flag
     * clears so a later budget exhaustion pings again. A pass that produced no
     * commit changed nothing: the count increments and the last-produced commit
     * and the flag carry over unchanged.
     *
     * <p>This is the record's own evolution rule, co-located with the fields it
     * governs; {@link RemediationStateStore#recordPass} persists the result.</p>
     *
     * @param producedCommit the commit SHA this pass produced, or {@code null}
     *                       or empty when it changed nothing
     * @return the updated record
     */
    public RemediationState afterPass(String producedCommit) {
        boolean progress = producedCommit != null && !producedCommit.isEmpty();
        if (progress) {
            return new RemediationState(workstreamId, 0, producedCommit, false);
        }
        return new RemediationState(workstreamId, consecutiveNoProgress + 1,
                lastProducedCommit, notified);
    }

    /**
     * Returns the record left by a commit that a job other than a remediation
     * pass produced on the branch — a person's own job, for example. That
     * commit is progress, so the consecutive-no-progress count resets and the
     * notified flag clears; it is not a remediation pass's own commit, so the
     * last-produced commit is unchanged and a review of the new commit still
     * runs.
     *
     * @return the updated record
     */
    public RemediationState afterExternalCommit() {
        return new RemediationState(workstreamId, 0, lastProducedCommit, false);
    }

    /**
     * Returns the record with its budget-spent notification marked sent, so a
     * person is pinged once per exhaustion rather than on every refused
     * submission. The count and last-produced commit are unchanged.
     *
     * @return the updated record
     */
    public RemediationState afterNotified() {
        return new RemediationState(workstreamId, consecutiveNoProgress,
                lastProducedCommit, true);
    }
}
