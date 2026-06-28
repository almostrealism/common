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

import io.flowtree.jobs.agent.AgentRunner;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Holds the per-phase agent-runner and model configuration for a single
 * {@link CodingAgentJob} and resolves the effective runner / model / effort /
 * provider for each lifecycle {@link Phase}.
 *
 * <p>Three pieces of state are kept in lockstep:</p>
 * <ul>
 *   <li>{@code defaultRunner} — the runner used when a phase has no override
 *       (with {@code runnerName} retained as a legacy alias);</li>
 *   <li>{@code runnerByPhase} — per-phase runner overrides;</li>
 *   <li>{@code phaseConfigBundle} — the unified bundle that is the sole source
 *       of model / effort / provider and also mirrors the runner state.</li>
 * </ul>
 *
 * <p>Extracted from {@link CodingAgentJob} so the orchestrator class is not
 * burdened with the consistency logic between the legacy runner fields and the
 * {@link PhaseConfigBundle}. {@link CodingAgentJob} exposes the same public API
 * by delegating to an instance of this class. Not thread-safe; owned by a
 * single job.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob
 * @see PhaseConfigBundle
 */
class PhaseRunnerConfig {

    /**
     * Legacy single-runner field retained for backwards source compatibility.
     * Mirrors {@link #defaultRunner}; {@link #setRunnerName(String)} updates the
     * default runner so pre-Phase-2 callers continue to work without any
     * awareness of the per-phase map.
     */
    private String runnerName = AgentRunnerRegistry.CLAUDE;

    /** Default {@link AgentRunner} name used when a phase has no explicit override. */
    private String defaultRunner = AgentRunnerRegistry.CLAUDE;

    /** Per-phase {@link AgentRunner} overrides; empty when only the default applies. */
    private final Map<Phase, String> runnerByPhase = new EnumMap<>(Phase.class);

    /**
     * Unified per-phase configuration bundle holding the default
     * {@link PhaseConfig} plus per-phase overrides for runner / model / effort /
     * provider. This is the sole source of model, effort, and provider; the
     * runner-resolution fields {@link #defaultRunner} and {@link #runnerByPhase}
     * are kept in lockstep with it by {@link #setPhaseConfigBundle(PhaseConfigBundle)}.
     */
    private PhaseConfigBundle phaseConfigBundle = PhaseConfigBundle.EMPTY;

    /**
     * Returns the legacy default-runner alias. Equivalent to
     * {@link #getDefaultRunner()}.
     *
     * @return the runner identifier
     */
    String getRunnerName() { return runnerName; }

    /**
     * Sets the default runner, validating it against
     * {@link AgentRunnerRegistry#available()} so misconfiguration fails at the
     * caller. Keeps {@link #defaultRunner} and {@link #phaseConfigBundle} in sync.
     *
     * @param runnerName a registered runner identifier; {@code null}/empty resets
     *                   to {@link AgentRunnerRegistry#CLAUDE}
     * @throws IllegalArgumentException when the runner is not registered
     */
    void setRunnerName(String runnerName) {
        String resolved = (runnerName == null || runnerName.isEmpty())
                ? AgentRunnerRegistry.CLAUDE : runnerName;
        if (runnerName != null && !runnerName.isEmpty()) {
            AgentRunnerRegistry.validateName(runnerName);
        }
        this.runnerName = resolved;
        this.defaultRunner = resolved;
        this.phaseConfigBundle = phaseConfigBundle.withDefaultRunner(
                (runnerName == null || runnerName.isEmpty()) ? null : runnerName);
    }

    /**
     * Returns the default runner used when {@link #getRunnerForPhase(Phase)} has
     * no explicit override for a phase.
     *
     * @return the default runner identifier, never {@code null}
     */
    String getDefaultRunner() { return defaultRunner; }

    /**
     * Alias for {@link #setRunnerName(String)} that trims the input.
     *
     * @param runnerName a registered runner identifier; {@code null}/empty resets
     *                   to {@link AgentRunnerRegistry#CLAUDE}
     * @throws IllegalArgumentException when the runner is not registered
     */
    void setDefaultRunner(String runnerName) {
        setRunnerName(runnerName == null ? null : runnerName.trim());
    }

    /**
     * Returns the {@link AgentRunner} name to use for {@code phase}, falling back
     * to {@link #getDefaultRunner()} when no override is set.
     *
     * @param phase the lifecycle phase being dispatched
     * @return the runner identifier; never {@code null}
     */
    String getRunnerForPhase(Phase phase) {
        if (phase == null) return defaultRunner;
        return runnerByPhase.getOrDefault(phase, defaultRunner);
    }

    /**
     * Sets the runner used for {@code phase}, overriding the default. A
     * {@code null}/empty runner clears any existing override.
     *
     * @param phase      the phase to configure
     * @param runnerName a registered runner identifier, or {@code null}/empty to
     *                   clear the override
     * @throws IllegalArgumentException when {@code phase} is {@code null} or
     *                                  {@code runnerName} is not registered
     */
    void setRunnerForPhase(Phase phase, String runnerName) {
        if (phase == null) throw new IllegalArgumentException("phase must not be null");
        if (runnerName == null || runnerName.isEmpty()) {
            runnerByPhase.remove(phase);
            PhaseConfig existing = phaseConfigBundle.phaseConfigs().get(phase);
            if (existing != null) {
                phaseConfigBundle = phaseConfigBundle.withPhase(phase, existing.withRunner(null));
            }
            return;
        }
        AgentRunnerRegistry.validateName(runnerName);
        runnerByPhase.put(phase, runnerName);
        PhaseConfig existing = phaseConfigBundle.phaseConfigs().get(phase);
        PhaseConfig updated = (existing != null ? existing : PhaseConfig.EMPTY)
                .withRunner(runnerName);
        phaseConfigBundle = phaseConfigBundle.withPhase(phase, updated);
    }

    /**
     * Returns an immutable snapshot of the per-phase runner overrides.
     *
     * @return the override map; empty when no overrides are set
     */
    Map<Phase, String> getRunnerByPhase() {
        return new EnumMap<>(runnerByPhase);
    }

    /**
     * Returns the unified per-phase configuration bundle.
     *
     * @return the bundle, never {@code null}
     */
    PhaseConfigBundle getPhaseConfigBundle() {
        return phaseConfigBundle;
    }

    /**
     * Replaces the per-phase configuration bundle, resyncing the legacy runner
     * fields ({@link #defaultRunner}, {@link #runnerByPhase}) to reflect it.
     *
     * @param bundle the new bundle; {@code null} resets to
     *               {@link PhaseConfigBundle#EMPTY}
     */
    void setPhaseConfigBundle(PhaseConfigBundle bundle) {
        this.phaseConfigBundle = bundle != null ? bundle : PhaseConfigBundle.EMPTY;
        PhaseConfig def = phaseConfigBundle.defaultPhaseConfig();
        // Resync legacy runner fields, bypassing the bundle update path that the
        // public setters would otherwise trigger.
        String r = def.runner();
        this.defaultRunner = (r != null && !r.isEmpty()) ? r : AgentRunnerRegistry.CLAUDE;
        this.runnerName = this.defaultRunner;
        this.runnerByPhase.clear();
        for (Map.Entry<Phase, PhaseConfig> e : phaseConfigBundle.phaseConfigs().entrySet()) {
            String phaseRunner = e.getValue().runner();
            if (phaseRunner != null && !phaseRunner.isEmpty()) {
                this.runnerByPhase.put(e.getKey(), phaseRunner);
            }
        }
    }

    /**
     * Replaces the per-phase runner overrides with the decoded contents of
     * {@code wireValue}. Syncs both {@link #runnerByPhase} and
     * {@link #phaseConfigBundle} so {@link #resolveRunner(Phase)} sees them.
     *
     * @param wireValue the encoded runner map from {@link CodingAgentJobCodec}
     * @param warn       receives a message when a wire entry cannot be decoded
     */
    void applyRunnerMap(String wireValue, Consumer<String> warn) {
        runnerByPhase.clear();
        Map<Phase, String> decoded = Phase.decodeRunnerMap(wireValue, warn);
        runnerByPhase.putAll(decoded);

        // Ensure the bundle's runner overrides match the decoded map.
        for (Map.Entry<Phase, PhaseConfig> e : phaseConfigBundle.phaseConfigs().entrySet()) {
            Phase phase = e.getKey();
            String runner = e.getValue().runner();
            if (!decoded.containsKey(phase) && runner != null && !runner.isEmpty()) {
                phaseConfigBundle = phaseConfigBundle.withPhase(phase, e.getValue().withRunner(null));
            }
        }

        for (Map.Entry<Phase, String> entry : decoded.entrySet()) {
            PhaseConfig existing = phaseConfigBundle.phaseConfigs().get(entry.getKey());
            PhaseConfig updated = (existing != null ? existing : PhaseConfig.EMPTY)
                    .withRunner(entry.getValue());
            phaseConfigBundle = phaseConfigBundle.withPhase(entry.getKey(), updated);
        }
    }

    /**
     * Returns the effective {@link PhaseConfig} for {@code phase}: the bundle's
     * per-phase overlay over the default runner.
     *
     * @param phase the lifecycle phase; may be {@code null}
     * @return the resolved phase config, never {@code null}
     */
    PhaseConfig resolveEffectivePhaseConfig(Phase phase) {
        return phaseConfigBundle.forPhase(phase)
                .overlayOn(new PhaseConfig(defaultRunner, null, null));
    }

    /**
     * Resolves the {@link AgentRunner} to use for {@code phase}. Consults
     * {@link #resolveEffectivePhaseConfig(Phase)} first; when the resolved
     * {@link PhaseConfig} carries no runner, falls back to
     * {@link #getRunnerForPhase(Phase)}, then to {@link AgentRunnerRegistry#CLAUDE}.
     *
     * @param phase the lifecycle phase being dispatched; may be {@code null}
     * @return the runner, never {@code null}
     */
    AgentRunner resolveRunner(Phase phase) {
        String name = resolveEffectivePhaseConfig(phase).runner();
        if (name == null || name.isEmpty()) name = getRunnerForPhase(phase);
        return AgentRunnerRegistry.get(name != null ? name : AgentRunnerRegistry.CLAUDE);
    }
}
