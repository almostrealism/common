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

import java.util.function.BiConsumer;

/**
 * The submit-time gate for the per-PR remediation loop (RETRO-20261026 P3,
 * {@code review-loop-cap}, designed with P2). It reads the one
 * {@link RemediationState} record for a workstream and decides whether an
 * automated remediation submission should be dispatched or skipped, so the
 * controller stops re-dispatching {@code auto-review}/{@code auto-resolve} on
 * every CI run once a PR has stopped making progress — the loop that charged PR
 * #593 about 80 jobs.
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
     * the pathological tail — PR #593's roughly 80 jobs — is capped far below
     * it. Override with {@link #BUDGET_ENV}.
     */
    public static final int DEFAULT_BUDGET = 5;

    /** The per-PR remediation record store, or {@code null} to disable the gate. */
    private final RemediationStateStore store;

    /** Consecutive no-progress passes allowed before auto-dispatch stops. */
    private final int budget;

    /** Pings a person: given (workstreamId, message). */
    private final BiConsumer<String, String> notifier;

    /**
     * Creates a gate over {@code store}.
     *
     * @param store    the remediation record store, or {@code null} to disable
     * @param budget   the per-PR budget; non-positive disables the budget skip
     * @param notifier called once with (workstreamId, message) when a budget is
     *                 first found spent, to ping a person
     */
    public RemediationBudgetGuard(RemediationStateStore store, int budget,
                                  BiConsumer<String, String> notifier) {
        this.store = store;
        this.budget = budget;
        this.notifier = notifier;
    }

    /**
     * Builds a guard whose person-ping posts to the PR's workstream Slack
     * channel, with the budget from {@link #budgetFromEnv()}. This is the
     * controller's production wiring; tests use the constructor with a
     * recording {@link BiConsumer} instead.
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
            if (ws != null && ws.getChannelId() != null && notifier != null) {
                notifier.postMessage(ws.getChannelId(), message);
            }
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
     * Returns why an automated remediation submission should be skipped, or
     * {@code null} to dispatch it. A non-automated submission (a person's work)
     * is never skipped.
     *
     * <p>Pings a person — once — when it first finds the budget spent, and
     * marks the record so a later refused submission on the same PR is silent
     * until a commit resets the count. Every skip is logged with its reason.</p>
     *
     * @param workstreamId  the workstream (branch / PR) key
     * @param automated     whether this is an automated remediation submission
     * @param triggerCommit the commit that triggered the submission, or
     *                      {@code null} when the caller did not supply one
     * @return a skip reason, or {@code null} to dispatch
     */
    public String skipReason(String workstreamId, boolean automated, String triggerCommit) {
        if (store == null || !automated || workstreamId == null || workstreamId.isEmpty()) {
            return null;
        }
        String reason = evaluate(workstreamId, triggerCommit);
        if (reason != null) {
            log("Skipping job submission for workstream " + workstreamId + ": " + reason);
        }
        return reason;
    }

    /**
     * Decides the skip for an automated submission on a known workstream,
     * sending the one budget-spent ping when this call claims it.
     *
     * @param workstreamId  the workstream (branch / PR) key
     * @param triggerCommit the commit that triggered the submission, or {@code null}
     * @return a skip reason, or {@code null} to dispatch
     */
    private String evaluate(String workstreamId, String triggerCommit) {
        // TODO(review): a direct push the controller never sees does not reset exhaustion.
        RemediationStateStore.Decision decision =
                store.evaluateSubmission(workstreamId, budget, triggerCommit);
        if (decision == RemediationStateStore.Decision.BUDGET_SPENT_NOTIFY && notifier != null) {
            notifier.accept(workstreamId, "Per-PR remediation budget reached on this branch: "
                + budget + " consecutive auto-review/auto-resolve passes changed nothing. "
                + "Auto-dispatch is paused for this PR until a commit lands — someone should "
                + "look at why it is not converging.");
        }
        switch (decision) {
            case BUDGET_SPENT_NOTIFY:
            case BUDGET_SPENT_SILENT:
                return "Per-PR remediation budget of " + budget
                    + " consecutive no-progress passes reached; auto-dispatch paused pending a person";
            case OWN_COMMIT:
                return "The only new commit is the previous remediation pass's own ("
                    + triggerCommit + "); not re-reviewing it";
            case UNAVAILABLE:
                return "Remediation state could not be read; refusing automated submission "
                    + "until it can be read again";
            default:
                return null;
        }
    }

    /**
     * Records that a submission this guard let through was dispatched as job
     * {@code jobId}. An automated submission is registered as a remediation
     * job, so its terminal event counts against the per-PR budget
     * ({@link RemediationStateStore#completeJob}); any other submission — a
     * person's work — is not registered and never moves the budget.
     *
     * @param jobId        the dispatched job's id
     * @param workstreamId the workstream (branch / PR) key
     * @param automated    whether the submission was an automated remediation
     */
    public void dispatched(String jobId, String workstreamId, boolean automated) {
        if (store == null || !automated) return;
        store.registerJob(jobId, workstreamId);
    }
}
