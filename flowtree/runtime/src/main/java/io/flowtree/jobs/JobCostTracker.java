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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accumulates per-runner and per-model USD costs across every agent invocation
 * in a single {@link CodingAgentJob} run.
 *
 * <p>The orchestrator dispatches the same job through multiple phases
 * (PRIMARY, REVIEW, RETROSPECTIVE, correction sessions), each of which may
 * use a different runner and a different model. The completion-event payload
 * carries both breakdowns so downstream consumers (Slack notifier, job-stats
 * store, dashboards) can attribute cost to the correct provider/runner pair
 * without re-computing it from session-level data.</p>
 *
 * <p>{@link #snapshotByRunner()} and {@link #snapshotByModel()} return
 * unmodifiable copies safe to hand to event objects: the orchestrator can
 * continue mutating the live totals (e.g. during a follow-up review session)
 * without aliasing the snapshot already in flight.</p>
 */
final class JobCostTracker {

    /** Cumulative USD cost per runner name, summed across every phase invocation. */
    private final Map<String, Double> costByRunner = new LinkedHashMap<>();

    /** Cumulative USD cost per model (provider/model identifier), summed across every phase invocation. */
    private final Map<String, Double> costByModel = new LinkedHashMap<>();

    /**
     * Whether the accumulated cost is a lower bound rather than the true total.
     * Set once any agent session is killed for inactivity before it emits its
     * terminal cost JSON: Claude reports {@code 0.0} in that case and opencode
     * recovers only the steps written before the kill, so the lost cost cannot
     * be accounted for. Sticky for the lifetime of the job.
     */
    private boolean incomplete;

    /**
     * Records {@code costUsd} as additional cost for {@code runnerName}
     * (e.g. {@code "claude"}) and {@code modelKey} (e.g.
     * {@code "anthropic/claude-sonnet-4-7"}).
     *
     * @param runnerName runner identifier from {@code AgentRunnerRegistry}
     * @param modelKey   model-key produced by {@code PhaseConfig.toModelKey()}
     * @param costUsd    cost to add; must be non-negative
     */
    void record(String runnerName, String modelKey, double costUsd) {
        costByRunner.merge(runnerName, costUsd, Double::sum);
        costByModel.merge(modelKey, costUsd, Double::sum);
    }

    /**
     * Returns the cumulative cost so far for a single {@code modelKey}, or
     * {@code 0.0} when the model has not yet been billed. Used by
     * {@link RetrospectivePhase} to isolate the retrospective session's cost
     * from prior phase invocations on the same model.
     *
     * @param modelKey the model key to look up
     * @return cost in USD, or {@code 0.0} when absent
     */
    double costForModel(String modelKey) {
        return costByModel.getOrDefault(modelKey, 0.0);
    }

    /**
     * Marks the accumulated cost as incomplete (a lower bound), because an
     * agent session was killed for inactivity before reporting its cost.
     */
    void markIncomplete() {
        incomplete = true;
    }

    /**
     * Returns whether the accumulated cost is a lower bound rather than the
     * true total.
     *
     * @return {@code true} when an inactivity kill lost cost data
     */
    boolean isIncomplete() {
        return incomplete;
    }

    /** Returns an unmodifiable snapshot of the per-runner cost map. */
    Map<String, Double> snapshotByRunner() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(costByRunner));
    }

    /** Returns an unmodifiable snapshot of the per-model cost map. */
    Map<String, Double> snapshotByModel() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(costByModel));
    }

    /** Returns the live per-runner map for direct event-population by the orchestrator. */
    Map<String, Double> liveByRunner() {
        return costByRunner;
    }

    /** Returns the live per-model map for direct event-population by the orchestrator. */
    Map<String, Double> liveByModel() {
        return costByModel;
    }
}
