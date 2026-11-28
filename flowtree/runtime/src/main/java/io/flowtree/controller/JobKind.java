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
 * The kind of work a recorded job represents, for the throughput retrospective's
 * per-kind breakdown.
 *
 * <p>No kind is persisted on a job's row today, so {@link #classify} back-derives
 * it from the two durable signals that survive on {@code job_timing}: the
 * {@code trigger_reason} the controller stamped at submission
 * ({@code manual} / {@code auto_resolve} / {@code completion_listener}) and the
 * last lifecycle {@code phase} the job's harness reported. A job for which
 * neither signal is present — an older row recorded before those columns
 * existed — is {@link #UNKNOWN} rather than silently bucketed as coding, so the
 * retrospective can report the uncertain fraction honestly instead of inflating
 * the coding count.</p>
 *
 * <p>The classification is a heuristic, not a contract: the durable fix is a
 * first-class {@code kind} property threaded through the job and its factory
 * (see the plan's §2.3/§6.3). Until that lands, this is the best signal
 * available for history and for new rows alike.</p>
 *
 * @author Michael Murray
 * @see WindowStatsAggregator
 */
public enum JobKind {

    /** An interactive or scheduled coding job — the default for manually submitted work. */
    CODING,

    /** A CI review / enforcement job, recognised by auto-resolve or a review/enforcement phase. */
    CI_REVIEW,

    /** A plan-only job, recognised by a planning phase. */
    PLANNING,

    /** A retrospective / falsification / QA-enforcement job, recognised by its phase. */
    QA,

    /** A shell-command job. */
    SHELL,

    /** A job with no durable signal to classify it (typically pre-dates the signal columns). */
    UNKNOWN;

    /**
     * Classifies a job from the two durable signals a {@code job_timing} row
     * carries. The {@code phase} is consulted first because a job's phase names
     * the work most specifically; {@code triggerReason} supplies the fallback.
     *
     * @param triggerReason the controller-stamped trigger reason
     *                      ({@code manual} / {@code auto_resolve} /
     *                      {@code completion_listener}), or {@code null}
     * @param phase         the last lifecycle phase the job reported, or {@code null}
     * @return the derived kind; {@link #UNKNOWN} when neither signal is present
     */
    public static JobKind classify(String triggerReason, String phase) {
        String p = phase == null ? "" : phase.toLowerCase();
        String t = triggerReason == null ? "" : triggerReason.toLowerCase();

        if (!p.isEmpty()) {
            if (p.contains("falsif") || p.contains("retrospective")
                    || p.contains("reflection") || p.contains("qa")) {
                return QA;
            }
            if (p.contains("plan")) {
                return PLANNING;
            }
            if (p.contains("review") || p.contains("enforcement")
                    || p.contains("settled") || p.contains("ci")) {
                return CI_REVIEW;
            }
        }

        if (t.equals("auto_resolve")) {
            return CI_REVIEW;
        }
        if (t.equals("manual") || t.equals("completion_listener")) {
            return CODING;
        }

        return UNKNOWN;
    }
}
