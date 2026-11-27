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

import io.flowtree.github.ReviewActivity;

/**
 * The controller's one per-PR remediation record, keyed by workstream (one
 * workstream is one branch is one pull request). It is the single piece of
 * state the remediation loop reads: the number of auto-review/auto-resolve
 * passes dispatched for the PR since the count last reset, which caps how many
 * times a PR may be auto-remediated before a person is pinged, and the last
 * commit a remediation pass produced, so the controller can tell a commit the
 * loop produced from one it did not.
 *
 * <p>Every dispatched remediation pass counts, whether or not it commits and
 * whatever review comments are open — the count is incremented when a pass is
 * dispatched ({@link #afterDispatch}). A commit the loop did <em>not</em>
 * produce (a person's push, or a manually submitted job) resets the count
 * ({@link #afterForeignCommitReset}): the next pass is triggered by that commit,
 * and because it is not the last commit a remediation pass produced
 * ({@link #wasProducedByRemediationPass}), the budget starts over.</p>
 *
 * <p>It also records what the last dispatched pass ran against: the commit that
 * triggered it and the {@link ReviewActivity} on the pull request at that
 * moment. A pass of the same kind re-triggered on that commit, with no review
 * thread opened since, has nothing new to look at ({@link #isRepeatOf}). The record is immutable; {@link RemediationStateStore}
 * returns a fresh one on each change.</p>
 *
 * @author Michael Murray
 * @see RemediationStateStore
 */
public final class RemediationState {

    /** The workstream (branch / PR) this record belongs to. */
    private final String workstreamId;

    /** Dispatched remediation passes since the count last reset. */
    private final int passCount;

    /** The commit SHA the most recent remediation pass produced, or {@code null}. */
    private final String lastProducedCommit;

    /** Whether a person has already been notified that the cap is reached. */
    private final boolean notified;

    /** The commit that triggered the last dispatched pass, or {@code null}. */
    private final String lastDispatchedCommit;

    /** The review activity the last dispatched pass ran against, or {@code null}. */
    private final ReviewActivity lastReviewed;

    /**
     * Creates a remediation record.
     *
     * @param workstreamId       the workstream (branch / PR) key
     * @param passCount          dispatched passes since the last reset, never negative
     * @param lastProducedCommit the last remediation pass's SHA, or {@code null}
     * @param notified           whether the cap-reached ping was already sent
     */
    public RemediationState(String workstreamId, int passCount,
                            String lastProducedCommit, boolean notified) {
        this(workstreamId, passCount, lastProducedCommit, notified, null, null);
    }

    /**
     * Creates a remediation record that also knows what the last dispatched pass
     * ran against.
     *
     * @param workstreamId         the workstream (branch / PR) key
     * @param passCount            dispatched passes since the last reset, never negative
     * @param lastProducedCommit   the last remediation pass's SHA, or {@code null}
     * @param notified             whether the cap-reached ping was already sent
     * @param lastDispatchedCommit the commit that triggered the last dispatched pass, or {@code null}
     * @param lastReviewed         the review activity it ran against, or {@code null}
     */
    public RemediationState(String workstreamId, int passCount, String lastProducedCommit,
                            boolean notified, String lastDispatchedCommit, ReviewActivity lastReviewed) {
        this.workstreamId = workstreamId;
        this.passCount = Math.max(0, passCount);
        this.lastProducedCommit = lastProducedCommit;
        this.notified = notified;
        this.lastDispatchedCommit = lastDispatchedCommit;
        this.lastReviewed = lastReviewed;
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

    /** Returns the count of dispatched remediation passes since the last reset. */
    public int getPassCount() { return passCount; }

    /** Returns the last remediation pass's SHA, or {@code null} when none has committed. */
    public String getLastProducedCommit() { return lastProducedCommit; }

    /** Returns whether the cap-reached notification has already been sent. */
    public boolean isNotified() { return notified; }

    /** Returns the commit that triggered the last dispatched pass, or {@code null}. */
    public String getLastDispatchedCommit() { return lastDispatchedCommit; }

    /** Returns the review activity the last dispatched pass ran against, or {@code null}. */
    public ReviewActivity getLastReviewed() { return lastReviewed; }

    /**
     * Returns whether a pass triggered on {@code commit} against {@code activity}
     * would repeat the last dispatched pass: the same commit, and the same kind of
     * pass with no review thread opened since ({@link ReviewActivity#hasNothingNewSince}).
     * An unknown commit or activity, on either side, is never a repeat.
     *
     * @param commit   the commit that triggered the new submission, or {@code null}
     * @param activity the review activity it would run against, or {@code null}
     * @return {@code true} when the new pass would find nothing new
     */
    public boolean isRepeatOf(String commit, ReviewActivity activity) {
        return commit != null && !commit.isEmpty() && activity != null
                && commit.equals(lastDispatchedCommit) && activity.hasNothingNewSince(lastReviewed);
    }

    /**
     * Returns whether the dispatched-pass count has reached the cap.
     *
     * @param budget the per-PR cap; a non-positive value disables it
     * @return {@code true} when auto-dispatch should stop and a person be pinged
     */
    public boolean budgetExhausted(int budget) {
        return budget > 0 && passCount >= budget;
    }

    /**
     * Returns whether {@code commit} is exactly the commit the last remediation
     * pass produced. The controller uses this to tell a commit the loop produced
     * (so the next pass triggered by it continues counting toward the cap) from a
     * commit it did not produce (which resets the count). Any commit that is not
     * the last produced — a person's push, a manually submitted job's commit, or
     * {@code null} — is treated as not the loop's own.
     *
     * @param commit the commit that triggered a new submission, or {@code null}
     * @return {@code true} when the commit is the last remediation pass's own
     */
    public boolean wasProducedByRemediationPass(String commit) {
        return commit != null && !commit.isEmpty()
                && commit.equals(lastProducedCommit);
    }

    /**
     * Returns the record left by dispatching one remediation pass: the
     * dispatched-pass count increments by one. The last-produced commit and the
     * notified flag carry over unchanged — a pass is counted when it is
     * dispatched, independently of whether it later commits.
     *
     * @return the updated record
     */
    public RemediationState afterDispatch() {
        return afterDispatch(lastDispatchedCommit, lastReviewed);
    }

    /**
     * Returns the record left by dispatching one remediation pass triggered on
     * {@code commit} against {@code activity}: the count increments and the pass
     * is recorded as the last one dispatched, so a re-trigger with nothing new can
     * be recognised ({@link #isRepeatOf}).
     *
     * @param commit   the commit that triggered the pass, or {@code null}
     * @param activity the review activity it ran against, or {@code null}
     * @return the updated record
     */
    public RemediationState afterDispatch(String commit, ReviewActivity activity) {
        return new RemediationState(workstreamId, passCount + 1, lastProducedCommit, notified,
                commit, activity);
    }

    /**
     * Returns the record left by a commit the remediation loop did not produce —
     * a person's push, or a manually submitted job's commit — appearing on the
     * branch. That commit is progress a person made, so the dispatched-pass count
     * resets to zero and the notified flag clears so a later cap-reached pings
     * again. The last-produced commit is unchanged: it still records the last
     * commit a remediation pass produced, and a foreign commit is not one.
     *
     * @return the updated record
     */
    public RemediationState afterForeignCommitReset() {
        return new RemediationState(workstreamId, 0, lastProducedCommit, false,
                lastDispatchedCommit, lastReviewed);
    }

    /**
     * Returns the record with {@code commit} recorded as the last commit a
     * remediation pass produced. The dispatched-pass count and the notified flag
     * are unchanged — a pass's own commit does not reset the count; only a commit
     * the loop did not produce does ({@link #afterForeignCommitReset}).
     *
     * @param commit the commit SHA this remediation pass produced
     * @return the updated record
     */
    public RemediationState withProducedCommit(String commit) {
        return new RemediationState(workstreamId, passCount, commit, notified,
                lastDispatchedCommit, lastReviewed);
    }

    /**
     * Returns the record with its cap-reached notification marked sent, so a
     * person is pinged once per exhaustion rather than on every refused
     * submission. The count and last-produced commit are unchanged.
     *
     * @return the updated record
     */
    public RemediationState afterNotified() {
        return new RemediationState(workstreamId, passCount, lastProducedCommit, true,
                lastDispatchedCommit, lastReviewed);
    }

    /**
     * Returns the record with its cap-reached notification marked unsent, for a
     * claimed ping that could not be delivered: the next refused submission
     * claims it again, so the person is still pinged once delivery is possible.
     * The count and last-produced commit are unchanged.
     *
     * @return the updated record
     */
    public RemediationState afterNotificationUndelivered() {
        return new RemediationState(workstreamId, passCount, lastProducedCommit, false,
                lastDispatchedCommit, lastReviewed);
    }
}
