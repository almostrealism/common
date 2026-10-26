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

import java.util.Map;

/**
 * A persisted configuration level (a workspace, a workstream) that carries
 * one level of the unified phase-config ladder in serialized form: the new
 * {@code defaultPhaseConfig} / {@code phaseConfigs} fields plus the legacy
 * single-runner {@code defaultRunner} / {@code runners} fields that are
 * still accepted on load.
 *
 * <p>The container exposes its level as a {@link PhaseConfigBundle} through
 * {@link #toPhaseConfigBundle()}, accepts one back through
 * {@link PhaseConfigBundle#applyTo(PhaseConfigContainer)}, and folds its
 * legacy fields into the new shape with
 * {@link #migrateLegacyConfigToPhaseConfig()}.</p>
 */
public interface PhaseConfigContainer {

    /** Returns the legacy default runner identifier, or {@code null}. */
    String getDefaultRunner();

    /** Sets the legacy default runner identifier. */
    void setDefaultRunner(String defaultRunner);

    /** Returns the legacy per-phase runner map keyed by phase wire name; may be {@code null}. */
    Map<String, String> getRunners();

    /** Replaces the legacy per-phase runner map; {@code null} is treated as empty. */
    void setRunners(Map<String, String> runners);

    /** Returns this level's default {@link PhaseConfig}, or {@code null}. */
    PhaseConfig getDefaultPhaseConfig();

    /** Sets this level's default {@link PhaseConfig}; {@code null} clears it. */
    void setDefaultPhaseConfig(PhaseConfig defaultPhaseConfig);

    /** Returns this level's per-phase {@link PhaseConfig} overrides keyed by phase wire name. */
    Map<String, PhaseConfig> getPhaseConfigs();

    /** Replaces this level's per-phase {@link PhaseConfig} overrides. */
    void setPhaseConfigs(Map<String, PhaseConfig> phaseConfigs);

    /**
     * Builds the effective {@link PhaseConfigBundle} for this level, merging
     * the legacy single-runner fields with the new {@code defaultPhaseConfig}
     * / {@code phaseConfigs} fields. The new fields take precedence
     * field-by-field — when both forms set the same field, the new form wins;
     * the legacy form fills in any field the new form leaves null. Model,
     * effort, and provider come solely from the new fields.
     *
     * @return the merged bundle; never {@code null}
     */
    default PhaseConfigBundle toPhaseConfigBundle() {
        PhaseConfigBundle bundle = PhaseConfigBundle.fromLegacyRunners(getDefaultRunner(), getRunners());
        PhaseConfig newDefault = getDefaultPhaseConfig();
        if (newDefault != null && !newDefault.isEmpty()) {
            bundle = bundle.withDefault(newDefault.overlayOn(bundle.defaultPhaseConfig()));
        }
        Map<String, PhaseConfig> newPhaseConfigs = getPhaseConfigs();
        if (newPhaseConfigs != null) {
            for (Map.Entry<String, PhaseConfig> e : newPhaseConfigs.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                Phase phase;
                try {
                    phase = Phase.fromWireName(e.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                PhaseConfig existing = bundle.phaseConfigs().get(phase);
                bundle = bundle.withPhase(phase,
                        existing == null ? e.getValue() : e.getValue().overlayOn(existing));
            }
        }
        return bundle;
    }

    /**
     * Rewrites this level into the new shape: the merged
     * {@link #toPhaseConfigBundle()} is written back into
     * {@code defaultPhaseConfig} / {@code phaseConfigs} and the legacy
     * {@code defaultRunner} / {@code runners} fields are cleared. Because the
     * merge keeps every value present in either form, no data is lost.
     * Idempotent: a container already in the new shape is left unchanged.
     */
    default void migrateLegacyConfigToPhaseConfig() {
        toPhaseConfigBundle().applyTo(this);
        setDefaultRunner(null);
        setRunners(null);
    }
}
