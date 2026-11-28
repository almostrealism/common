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

/**
 * Classifies a single {@link EnforcementRule} violation by whether the agent
 * can do anything about it.
 *
 * <p>Two kinds exist:</p>
 * <ul>
 *   <li><b>Agent-fixable</b> — the violation is within the agent's power to
 *       resolve in another session (it did not write the required change, it
 *       edited a method it could have added instead, it left a duplicate it
 *       can remove). These are retried up to the rule's cap, as before.</li>
 *   <li><b>Environment-determined</b> — the violation is fixed by something
 *       the job may not change from where it runs: a missing resource, a
 *       locked file it is not allowed to touch, content a guardrail rejects
 *       outright. Retrying only spends sessions re-asking a question whose
 *       answer cannot change, so {@link EnforcementRunner} gives these zero
 *       retries and retires the rule with {@link #getReason()} as the record
 *       of why.</li>
 * </ul>
 *
 * <p>The classification is a property of the violation itself: a rule decides
 * it by inspecting the job through {@link EnforcementRule#classifyViolation},
 * so the runner never has to know which concrete rule it is driving.</p>
 *
 * @author Michael Murray
 * @see EnforcementRule#classifyViolation(CodingAgentJob)
 * @see EnforcementRunner
 */
public final class ViolationClassification {

    /** Shared instance for the common agent-fixable case; carries no reason. */
    private static final ViolationClassification AGENT_FIXABLE =
            new ViolationClassification(false, null);

    /** Whether the violation is determined by the environment rather than the agent. */
    private final boolean environmentDetermined;

    /** Why the violation is environment-determined, or {@code null} when it is agent-fixable. */
    private final String reason;

    /**
     * Creates a classification.
     *
     * @param environmentDetermined whether the violation is environment-determined
     * @param reason                why, for an environment-determined violation; {@code null} otherwise
     */
    private ViolationClassification(boolean environmentDetermined, String reason) {
        this.environmentDetermined = environmentDetermined;
        this.reason = reason;
    }

    /**
     * Returns the classification for a violation the agent can resolve by
     * running again. This is the default for every rule.
     *
     * @return the shared agent-fixable classification
     */
    public static ViolationClassification agentFixable() {
        return AGENT_FIXABLE;
    }

    /**
     * Returns the classification for a violation the agent cannot resolve from
     * where it runs, so it must not be retried.
     *
     * @param reason why the violation is environment-determined; recorded as the
     *               rule's retirement reason on the completion event. Must be
     *               non-null and non-blank: {@link EnforcementRunner} folds it
     *               into the retirement record a reader relies on to know why
     *               the rule was never retried, so an empty reason would leave
     *               that record with no explanation.
     * @return an environment-determined classification carrying {@code reason}
     * @throws IllegalArgumentException if {@code reason} is {@code null} or blank
     */
    public static ViolationClassification environmentDetermined(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "An environment-determined classification requires a non-blank reason");
        }
        return new ViolationClassification(true, reason);
    }

    /**
     * Returns whether this violation is environment-determined and must not be
     * retried.
     *
     * @return {@code true} when the environment, not the agent, decides the outcome
     */
    public boolean isEnvironmentDetermined() {
        return environmentDetermined;
    }

    /**
     * Returns why the violation is environment-determined.
     *
     * @return the reason, or {@code null} for an agent-fixable violation
     */
    public String getReason() {
        return reason;
    }
}
