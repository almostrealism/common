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

package io.flowtree.jobs.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-container holder for a {@link PhaseConfig} default plus per-phase
 * overrides. Used by every level of the unified phase-config ladder:
 * workspace, workstream, and job submission.
 *
 * <p>Within a single level, the bundle's {@link #forPhase(Phase)} method
 * overlays the per-phase override on the default field-by-field: a phase
 * that sets only {@code model} inherits {@code runner} and {@code effort}
 * from the level default.</p>
 *
 * <p>Instances are immutable; construct via the canonical constructor.</p>
 */
public record PhaseConfigBundle(PhaseConfig defaultPhaseConfig,
                                Map<Phase, PhaseConfig> phaseConfigs) {

    /** A bundle with no defaults and no per-phase overrides. */
    public static final PhaseConfigBundle EMPTY = new PhaseConfigBundle(
            PhaseConfig.EMPTY, Collections.emptyMap());

    /**
     * Canonical constructor; normalises {@code null} arguments to safe
     * defaults and snapshots the per-phase map into an immutable
     * {@link EnumMap} view.
     */
    public PhaseConfigBundle {
        if (defaultPhaseConfig == null) {
            defaultPhaseConfig = PhaseConfig.EMPTY;
        }
        if (phaseConfigs == null || phaseConfigs.isEmpty()) {
            phaseConfigs = Collections.emptyMap();
        } else {
            EnumMap<Phase, PhaseConfig> copy = new EnumMap<>(Phase.class);
            for (Map.Entry<Phase, PhaseConfig> e : phaseConfigs.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                copy.put(e.getKey(), e.getValue());
            }
            phaseConfigs = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * Returns {@code true} when both the default and every per-phase entry
     * are {@linkplain PhaseConfig#isEmpty() empty} — the bundle contributes
     * nothing to resolution.
     *
     * <p>{@code @JsonIgnore} keeps this derived predicate out of the YAML
     * / JSON wire form (see the analogous note on
     * {@link PhaseConfig#isEmpty()}).</p>
     */
    @JsonIgnore
    public boolean isEmpty() {
        if (!defaultPhaseConfig.isEmpty()) return false;
        for (PhaseConfig config : phaseConfigs.values()) {
            if (!config.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Returns the resolved {@link PhaseConfig} for {@code phase} at THIS
     * level only — the per-phase override (if any) overlaid on the level
     * default. The caller is responsible for layering this onto the next
     * precedence level.
     *
     * @param phase the phase to look up; {@code null} returns the default
     * @return the resolved per-level config, never {@code null}
     */
    public PhaseConfig forPhase(Phase phase) {
        if (phase == null) return defaultPhaseConfig;
        PhaseConfig override = phaseConfigs.get(phase);
        return override == null
                ? defaultPhaseConfig
                : override.overlayOn(defaultPhaseConfig);
    }

    /**
     * Returns a copy of this bundle with {@code defaultPhaseConfig}
     * replaced; preserves the per-phase override map.
     *
     * @param newDefault the new default config; {@code null} resets to
     *                   {@link PhaseConfig#EMPTY}
     * @return a fresh bundle
     */
    public PhaseConfigBundle withDefault(PhaseConfig newDefault) {
        return new PhaseConfigBundle(newDefault, phaseConfigs);
    }

    /** Convenience: replaces only the default runner; preserves model and effort. */
    public PhaseConfigBundle withDefaultRunner(String runner) {
        return withDefault(defaultPhaseConfig.withRunner(runner));
    }

    /** Convenience: replaces only the default model; preserves runner and effort. */
    public PhaseConfigBundle withDefaultModel(String model) {
        return withDefault(defaultPhaseConfig.withModel(model));
    }

    /** Convenience: replaces only the default effort; preserves runner and model. */
    public PhaseConfigBundle withDefaultEffort(String effort) {
        return withDefault(defaultPhaseConfig.withEffort(effort));
    }

    /**
     * Returns a copy of this bundle with the per-phase entry for
     * {@code phase} set to {@code config}. Passing {@code null} clears
     * any existing entry for that phase.
     *
     * @param phase  the phase to set; must not be {@code null}
     * @param config the new per-phase config, or {@code null} to clear
     * @return a fresh bundle
     */
    public PhaseConfigBundle withPhase(Phase phase, PhaseConfig config) {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        Map<Phase, PhaseConfig> next = new EnumMap<>(Phase.class);
        next.putAll(phaseConfigs);
        if (config == null || config.isEmpty()) {
            next.remove(phase);
        } else {
            next.put(phase, config);
        }
        return new PhaseConfigBundle(defaultPhaseConfig, next);
    }

    /**
     * Builds a bundle from the legacy single-runner shape
     * ({@code defaultRunner} plus a phase-wire-name → runner map).
     *
     * @param defaultRunner the default runner identifier, or {@code null}
     * @param runnerByPhase per-phase overrides keyed by phase wire name;
     *                      {@code null} treated as empty
     * @return a fresh bundle whose {@link PhaseConfig#runner()} fields
     *         carry the supplied runners; {@code model} and {@code effort}
     *         remain {@code null}
     */
    public static PhaseConfigBundle fromLegacyRunners(String defaultRunner,
                                                     Map<String, String> runnerByPhase) {
        PhaseConfig def = new PhaseConfig(
                (defaultRunner == null || defaultRunner.isEmpty()) ? null : defaultRunner,
                null, null);
        Map<Phase, PhaseConfig> phases = new EnumMap<>(Phase.class);
        if (runnerByPhase != null) {
            for (Map.Entry<String, String> e : runnerByPhase.entrySet()) {
                String runner = e.getValue();
                if (runner == null || runner.isEmpty()) continue;
                Phase phase;
                try {
                    phase = Phase.fromWireName(e.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                phases.put(phase, new PhaseConfig(runner, null, null));
            }
        }
        return new PhaseConfigBundle(def, phases);
    }

    /**
     * Returns the per-phase map keyed by phase wire name for each entry
     * whose {@code runner} is non-null. Convenience helper for code paths
     * that still operate on the legacy {@code Map<String,String>} shape.
     *
     * @return the wire-name → runner map; never {@code null}, may be empty
     */
    public Map<String, String> toLegacyRunnerMap() {
        if (phaseConfigs.isEmpty()) return new LinkedHashMap<>();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Phase, PhaseConfig> e : phaseConfigs.entrySet()) {
            String runner = e.getValue().runner();
            if (runner != null && !runner.isEmpty()) {
                out.put(e.getKey().wireName(), runner);
            }
        }
        return out;
    }
}
