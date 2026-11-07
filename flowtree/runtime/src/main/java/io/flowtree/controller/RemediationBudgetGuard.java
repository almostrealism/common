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

import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.BiPredicate;

/**
 * The submit-time gate for the per-PR remediation loop. It reads the one
 * {@link RemediationState} record for a workstream and decides whether a
 * remediation-pass submission should be dispatched or skipped, so the
 * controller stops re-dispatching {@code auto-review}/{@code auto-resolve} on
 * every CI run once a PR has stopped making progress — a loop that has charged a
 * single PR dozens of jobs.
 *
 * <p>It mirrors the shape of the other submit-time gates
 * ({@code TestExecutionLimitsSubmissionValidator}): {@link #skipReason} returns
 * a human-readable reason to skip, or {@code null} to dispatch. The endpoint
 * turns a non-null reason into the same {@code skipped} response the
 * stale-submission guard returns, so a skip is a successful,
 * non-dispatching outcome.</p>
 *
 * <p>Two skips: the <em>budget</em> — after {@code budget} consecutive passes
 * that changed nothing, stop and ping a person once — and the
 * <em>own-commit</em> skip — a review triggered only by the previous pass's own
 * commit would re-enter the self-triggered loop, so it is not run; any other
 * commit still triggers one.</p>
 *
 * @author Michael Murray
 * @see RemediationState
 * @see RemediationStateStore
 */
public final class RemediationBudgetGuard implements ConsoleFeatures {

    /** Environment variable that overrides the default per-PR budget. */
    public static final String BUDGET_ENV = "FLOWTREE_REMEDIATION_BUDGET";

    /**
     * Default per-PR budget: after this many consecutive passes that changed
     * nothing, auto-dispatch stops and a person is pinged. Chosen so routine
     * PRs (which draw a handful of no-op passes) are never interrupted while
     * the pathological tail — dozens of jobs on a single PR — is capped far
     * below it. Override with {@link #BUDGET_ENV}.
     */
    public static final int DEFAULT_BUDGET = 5;

    /** The per-PR remediation record store, or {@code null} to disable the gate. */
    private final RemediationStateStore store;

    /** Consecutive no-progress passes allowed before auto-dispatch stops. */
    private final int budget;

    /** Pings a person: given (workstreamId, message), returns whether it was delivered. */
    private final BiPredicate<String, String> notifier;

    /**
     * Creates a gate over {@code store}.
     *
     * @param store    the remediation record store, or {@code null} to disable
     * @param budget   the per-PR budget; non-positive disables the budget skip
     * @param notifier called once with (workstreamId, message) when a budget is
     *                 first found spent, to ping a person; returns whether the
     *                 ping was delivered, so an undelivered one is retried
     */
    public RemediationBudgetGuard(RemediationStateStore store, int budget,
                                  BiPredicate<String, String> notifier) {
        this.store = store;
        this.budget = budget;
        this.notifier = notifier;
    }

    /**
     * Builds a guard whose person-ping posts to the PR's workstream Slack
     * channel, with the budget from {@link #budgetFromEnv()}. This is the
     * controller's production wiring; tests use the constructor with a
     * recording {@link BiPredicate} instead. The ping counts as delivered only
     * when the workstream has a channel and notifier and the post succeeds.
     *
     * @param store     the remediation record store
     * @param notifiers the registry resolving a workstream's channel and notifier
     * @return a guard that pings the workstream channel on budget exhaustion
     */
    public static RemediationBudgetGuard forChannel(RemediationStateStore store,
                                                    NotifierRegistry notifiers) {
        return new RemediationBudgetGuard(store, budgetFromEnv(), (workstreamId, message) -> {
            Workstream ws = notifiers.workstreamFor(workstreamId);
            SlackNotifier notifier = notifiers.notifierFor(workstreamId);
            return ws != null && ws.getChannelId() != null && notifier != null
                    && notifier.postMessage(ws.getChannelId(), message) != null;
        });
    }

    /**
     * Reads the per-PR budget from {@link #BUDGET_ENV}, falling back to
     * {@link #DEFAULT_BUDGET} when it is unset or not a positive integer.
     *
     * @return the configured budget
     */
    public static int budgetFromEnv() {
        String raw = System.getenv(BUDGET_ENV);
        if (raw != null && !raw.isEmpty()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // Fall through to the default.
            }
        }
        return DEFAULT_BUDGET;
    }

    /**
     * Returns why a remediation-pass submission should be skipped, or
     * {@code null} to dispatch it. Only an {@code auto-review}/{@code auto-resolve}
     * pass is gated; any other submission — a person's work, or an automated job
     * that is the primary work being reviewed (a QA round, a planning job, an
     * implementation) — is never skipped. Whether a submission is a remediation
     * pass is stated explicitly by the caller (CI's remediation submitters),
     * never inferred from the generic {@code automated} flag, which every CI
     * submission sets.
     *
     * <p>Pings a person — once — when it first finds the budget spent, and
     * marks the record so a later refused submission on the same PR is silent
     * until a commit resets the count. A ping that cannot be delivered (no
     * channel or notifier yet) leaves the record unmarked, so a later refused
     * submission tries again. Every skip is logged with its reason.</p>
     *
     * @param workstreamId   the workstream (branch / PR) key
     * @param remediationPass whether this is a remediation-pass submission
     *                       (an {@code auto-review}/{@code auto-resolve} pass)
     * @param triggerCommit  the commit that triggered the submission, or
     *                       {@code null} when the caller did not supply one
     * @return a skip reason, or {@code null} to dispatch
     */
    public String skipReason(String workstreamId, boolean remediationPass, String triggerCommit) {
        return skipReason(workstreamId, remediationPass, triggerCommit, Boolean.FALSE, null);
    }

    /**
     * Returns why a remediation-pass submission should be skipped, taking into
     * account whether the pull request still has reviewer feedback outstanding,
     * or {@code null} to dispatch it.
     *
     * <p>This is the feedback-aware form the controller's submit path uses. The
     * own-commit skip — "the only new commit is the previous pass's own" — now
     * applies <em>only</em> when nothing is outstanding ({@code feedbackOutstanding}
     * is {@link Boolean#FALSE}). Feedback that arrived on, or is still open at,
     * the pass's own commit (a reviewer reviewing it) is new work, so the pass
     * is dispatched. When the caller cannot establish that nothing is
     * outstanding ({@code feedbackOutstanding} is {@code null}), the submission
     * is dispatched rather than skipped — a review finding left unanswered must
     * never be stranded by a skip. The {@link #skipReason(String, boolean, String)}
     * form, for callers that do not report feedback state, keeps the original
     * own-commit skip by passing {@link Boolean#FALSE}.</p>
     *
     * @param workstreamId      the workstream (branch / PR) key
     * @param remediationPass   whether this is a remediation-pass submission
     * @param triggerCommit     the commit that triggered the submission, or {@code null}
     * @param feedbackOutstanding {@link Boolean#TRUE} when the PR has a finding not
     *                          yet accounted for, {@link Boolean#FALSE} when every
     *                          finding is accounted for, or {@code null} when it
     *                          could not be determined
     * @param feedbackFindings  a human-readable list of the outstanding findings
     *                          (one per line), included in the budget-pause ping,
     *                          or {@code null}
     * @return a skip reason, or {@code null} to dispatch
     */
    public String skipReason(String workstreamId, boolean remediationPass, String triggerCommit,
                             Boolean feedbackOutstanding, String feedbackFindings) {
        if (store == null || !remediationPass || workstreamId == null || workstreamId.isEmpty()) {
            return null;
        }
        String reason = evaluate(workstreamId, triggerCommit, feedbackOutstanding, feedbackFindings);
        if (reason != null) {
            log("Skipping job submission for workstream " + workstreamId + ": " + reason);
        }
        return reason;
    }

    /**
     * Decides the skip for a remediation-pass submission on a known workstream,
     * sending the one budget-spent ping when this call claims it and releasing
     * the claim when the ping is not delivered.
     *
     * @param workstreamId      the workstream (branch / PR) key
     * @param triggerCommit     the commit that triggered the submission, or {@code null}
     * @param feedbackOutstanding tri-state outstanding-feedback flag; see
     *                          {@link #skipReason(String, boolean, String, Boolean, String)}
     * @param feedbackFindings  the outstanding findings for the ping, or {@code null}
     * @return a skip reason, or {@code null} to dispatch
     */
    private String evaluate(String workstreamId, String triggerCommit,
                            Boolean feedbackOutstanding, String feedbackFindings) {
        // TODO(review): a direct push the controller never sees does not reset exhaustion.
        RemediationStateStore.Decision decision =
                store.evaluateSubmission(workstreamId, budget, triggerCommit);
        if (decision == RemediationStateStore.Decision.BUDGET_SPENT_NOTIFY
                && !notifyBudgetSpent(workstreamId, feedbackFindings)) {
            warn("Budget-spent ping for workstream " + workstreamId
                + " was not delivered; a later refused submission will retry it");
            store.releaseNotification(workstreamId);
        }
        switch (decision) {
            case BUDGET_SPENT_NOTIFY:
            case BUDGET_SPENT_SILENT:
                return "Per-PR remediation budget of " + budget
                    + " consecutive no-progress passes reached; auto-dispatch paused pending a person";
            case OWN_COMMIT:
                // Skip only when nothing is outstanding; see skipReason's javadoc.
                if (Boolean.FALSE.equals(feedbackOutstanding)) {
                    return "The only new commit is the previous remediation pass's own ("
                        + triggerCommit + ") and no reviewer feedback is outstanding; not re-reviewing it";
                }
                return null;
            case UNAVAILABLE:
                return "Remediation state could not be read; refusing automated submission "
                    + "until it can be read again";
            default:
                return null;
        }
    }

    /**
     * Pings a person that the per-PR budget is spent, listing any reviewer
     * findings still outstanding so the person knows exactly what remains.
     *
     * @param workstreamId     the workstream (branch / PR) key
     * @param feedbackFindings outstanding findings (one per line), or {@code null}
     * @return {@code true} when the ping was delivered
     */
    private boolean notifyBudgetSpent(String workstreamId, String feedbackFindings) {
        String message = "Per-PR remediation budget reached on this branch: " + budget
                + " consecutive auto-review/auto-resolve passes changed nothing. "
                + "Auto-dispatch is paused for this PR until a commit lands — someone should "
                + "look at why it is not converging.";
        if (feedbackFindings != null && !feedbackFindings.isBlank()) {
            message += "\n\nReviewer feedback still outstanding:\n" + feedbackFindings.strip();
        }
        return notifier != null && notifier.test(workstreamId, message);
    }

    /**
     * Records that a submission this guard let through was dispatched as job
     * {@code jobId}. A remediation pass ({@code auto-review}/{@code auto-resolve})
     * is registered as a remediation job, so its terminal event counts against
     * the per-PR budget ({@link RemediationStateStore#completeJob}) and its
     * produced commit becomes the last-produced commit the own-commit skip
     * compares against; any other submission — a person's work, or an automated
     * job that is the primary work being reviewed — is not registered, so it
     * never moves the budget and its commit is never taken as a review's own.
     *
     * @param jobId           the dispatched job's id
     * @param workstreamId    the workstream (branch / PR) key
     * @param remediationPass whether the submission was a remediation pass
     */
    public void dispatched(String jobId, String workstreamId, boolean remediationPass) {
        if (store == null || !remediationPass) return;
        store.registerJob(jobId, workstreamId);
    }
}
