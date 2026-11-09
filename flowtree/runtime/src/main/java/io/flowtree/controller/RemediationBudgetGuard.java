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
import java.util.function.Function;

/**
 * The submit-time gate for the per-PR remediation loop. It reads the one
 * {@link RemediationState} record for a workstream and decides whether a
 * remediation-pass submission should be dispatched or skipped, so the
 * controller stops re-dispatching {@code auto-review}/{@code auto-resolve} once
 * a PR has drawn the per-PR cap of passes — a loop that has charged a single PR
 * dozens of jobs.
 *
 * <p>It mirrors the shape of the other submit-time gates
 * ({@code TestExecutionLimitsSubmissionValidator}): {@link #skipReason} returns
 * a human-readable reason to skip, or {@code null} to dispatch. The endpoint
 * turns a non-null reason into the same {@code skipped} response the
 * stale-submission guard returns, so a skip is a successful, non-dispatching
 * outcome.</p>
 *
 * <p>There is one skip: the <em>cap</em>. Every dispatched remediation pass
 * counts, whether or not it commits and whatever review comments are open;
 * after {@code budget} of them, auto-dispatch stops and a person is pinged once.
 * A commit the loop did not produce — a person's push, or a manually submitted
 * job's commit — resets the count, so the cap bounds the self-sustaining loop
 * (a pass commits, its commit triggers the next pass) without ever interrupting
 * a PR a person is actively pushing to. A review triggered by a review pass's
 * own commit is <em>not</em> skipped: it is dispatched and counts toward the
 * cap, which is what ends the loop on its own (a pass that finds nothing commits
 * nothing, so nothing triggers another pass).</p>
 *
 * @author Michael Murray
 * @see RemediationState
 * @see RemediationStateStore
 */
public final class RemediationBudgetGuard implements ConsoleFeatures {

    /** Environment variable that overrides the default per-PR cap. */
    public static final String BUDGET_ENV = "FLOWTREE_REMEDIATION_BUDGET";

    /**
     * Default per-PR cap: after this many dispatched remediation passes,
     * auto-dispatch stops and a person is pinged. Override with
     * {@link #BUDGET_ENV}.
     */
    public static final int DEFAULT_BUDGET = 8;

    /** The per-PR remediation record store, or {@code null} to disable the gate. */
    private final RemediationStateStore store;

    /** Dispatched remediation passes allowed before auto-dispatch stops. */
    private final int budget;

    /** Pings a person: given (workstreamId, message), returns whether it was delivered. */
    private final BiPredicate<String, String> notifier;

    /** Builds the cap-reached ping text for a workstream. */
    private final Function<String, String> escalationMessage;

    /**
     * Creates a gate over {@code store} whose cap-reached ping is the plain
     * message of {@link #capReachedMessage(int)}. Used where no PR context is
     * available to enrich the ping (and by tests).
     *
     * @param store    the remediation record store, or {@code null} to disable
     * @param budget   the per-PR cap; non-positive disables the cap skip
     * @param notifier called once with (workstreamId, message) when the cap is
     *                 first reached, to ping a person; returns whether the ping
     *                 was delivered, so an undelivered one is retried
     */
    public RemediationBudgetGuard(RemediationStateStore store, int budget,
                                  BiPredicate<String, String> notifier) {
        this(store, budget, notifier, workstreamId -> capReachedMessage(budget));
    }

    /**
     * Creates a gate over {@code store} with an explicit cap-reached ping
     * builder, so the ping can list the PR's unresolved review threads and the
     * TODO lines the branch added.
     *
     * @param store             the remediation record store, or {@code null} to disable
     * @param budget            the per-PR cap; non-positive disables the cap skip
     * @param notifier          posts (workstreamId, message), returning delivery
     * @param escalationMessage builds the ping text for a workstream
     */
    public RemediationBudgetGuard(RemediationStateStore store, int budget,
                                  BiPredicate<String, String> notifier,
                                  Function<String, String> escalationMessage) {
        this.store = store;
        this.budget = budget;
        this.notifier = notifier;
        this.escalationMessage = escalationMessage;
    }

    /**
     * Builds a guard whose person-ping posts to the PR's workstream Slack
     * channel, with the cap from {@link #budgetFromEnv()} and a ping that lists
     * the PR's unresolved review threads and the TODO lines the branch added
     * ({@link RemediationEscalation}). This is the controller's production
     * wiring; tests use a constructor with a recording {@link BiPredicate}
     * instead. The ping counts as delivered only when the workstream has a
     * channel and notifier and the post succeeds.
     *
     * @param store      the remediation record store
     * @param notifiers  the registry resolving a workstream's channel and notifier
     * @param escalation builds the enriched ping body for a workstream's PR
     * @return a guard that pings the workstream channel when the cap is reached
     */
    public static RemediationBudgetGuard forChannel(RemediationStateStore store,
                                                    NotifierRegistry notifiers,
                                                    RemediationEscalation escalation) {
        int budget = budgetFromEnv();
        BiPredicate<String, String> notifier = (workstreamId, message) -> {
            Workstream ws = notifiers.workstreamFor(workstreamId);
            SlackNotifier slack = notifiers.notifierFor(workstreamId);
            return ws != null && ws.getChannelId() != null && slack != null
                    && slack.postMessage(ws.getChannelId(), message) != null;
        };
        Function<String, String> message = workstreamId -> {
            Workstream ws = notifiers.workstreamFor(workstreamId);
            return escalation.compose(ws == null ? null : ws.getRepoUrl(),
                    ws == null ? null : ws.getDefaultBranch(), budget);
        };
        return new RemediationBudgetGuard(store, budget, notifier, message);
    }

    /**
     * Reads the per-PR cap from {@link #BUDGET_ENV}, falling back to
     * {@link #DEFAULT_BUDGET} when it is unset or not a positive integer.
     *
     * @return the configured cap
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
     * <p>Dispatching a pass counts it toward the per-PR cap. When the cap is
     * reached this pings a person — once — and marks the record so a later
     * refused submission on the same PR is silent until a commit resets the
     * count. A ping that cannot be delivered (no channel or notifier yet) leaves
     * the record unmarked, so a later refused submission tries again. Every skip
     * is logged with its reason.</p>
     *
     * @param workstreamId    the workstream (branch / PR) key
     * @param remediationPass whether this is a remediation-pass submission
     *                        (an {@code auto-review}/{@code auto-resolve} pass)
     * @param triggerCommit   the commit that triggered the submission, or
     *                        {@code null} when the caller did not supply one
     * @return a skip reason, or {@code null} to dispatch
     */
    public String skipReason(String workstreamId, boolean remediationPass, String triggerCommit) {
        if (store == null || !remediationPass || workstreamId == null || workstreamId.isEmpty()) {
            return null;
        }
        String reason = evaluate(workstreamId, triggerCommit);
        if (reason != null) {
            log("Skipping job submission for workstream " + workstreamId + ": " + reason);
        }
        return reason;
    }

    /**
     * Decides the skip for a remediation-pass submission on a known workstream,
     * sending the one cap-reached ping when this call claims it and releasing the
     * claim when the ping is not delivered.
     *
     * @param workstreamId  the workstream (branch / PR) key
     * @param triggerCommit the commit that triggered the submission, or {@code null}
     * @return a skip reason, or {@code null} to dispatch
     */
    private String evaluate(String workstreamId, String triggerCommit) {
        RemediationStateStore.Decision decision =
                store.evaluateSubmission(workstreamId, budget, triggerCommit);
        if (decision == RemediationStateStore.Decision.BUDGET_SPENT_NOTIFY
                && !notifyCapReached(workstreamId)) {
            warn("Cap-reached ping for workstream " + workstreamId
                + " was not delivered; a later refused submission will retry it");
            store.releaseNotification(workstreamId);
        }
        switch (decision) {
            case BUDGET_SPENT_NOTIFY:
            case BUDGET_SPENT_SILENT:
                return "Per-PR remediation cap of " + budget
                    + " passes reached; auto-dispatch paused pending a person";
            case UNAVAILABLE:
                return "Remediation state could not be read; refusing automated submission "
                    + "until it can be read again";
            default:
                return null;
        }
    }

    /**
     * Pings a person that the per-PR cap is reached, with the escalation message
     * built for the workstream.
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return {@code true} when the ping was delivered
     */
    private boolean notifyCapReached(String workstreamId) {
        if (notifier == null) return false;
        String message = escalationMessage.apply(workstreamId);
        if (message == null) message = capReachedMessage(budget);
        return notifier.test(workstreamId, message);
    }

    /**
     * The plain cap-reached ping, used when no PR context is available to list
     * the unresolved review threads and branch TODOs.
     *
     * @param budget the per-PR cap that was reached
     * @return the ping text
     */
    public static String capReachedMessage(int budget) {
        return "Per-PR remediation cap reached on this branch: " + budget
            + " auto-review/auto-resolve passes have run. Auto-dispatch is paused for this PR "
            + "until a commit lands — someone should look at the open review threads and the "
            + "TODOs the branch added, and at why it is not converging.";
    }

    /**
     * Records that a submission this guard let through was dispatched as job
     * {@code jobId}. A remediation pass ({@code auto-review}/{@code auto-resolve})
     * is registered as a remediation job, so its terminal event's produced commit
     * is recorded as the workstream's last-produced commit
     * ({@link RemediationStateStore#completeJob}), which lets the next pass that
     * commit triggers be recognised as the loop's own rather than a person's; any
     * other submission — a person's work, or an automated job that is the primary
     * work being reviewed — is not registered, so its commit is never taken as
     * the loop's.
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
