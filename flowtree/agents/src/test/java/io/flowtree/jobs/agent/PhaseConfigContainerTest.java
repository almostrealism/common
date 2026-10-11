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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the default behaviour of {@link PhaseConfigContainer}: merging
 * the legacy and new fields into a {@link PhaseConfigBundle}, writing a
 * bundle back through {@link PhaseConfigBundle#applyTo(PhaseConfigContainer)},
 * and migrating the legacy fields into the new shape.
 */
public class PhaseConfigContainerTest extends TestSuiteBase {

    /** Minimal mutable container holding the four persisted fields. */
    private static class Level implements PhaseConfigContainer {
        /** Legacy default runner. */
        private String defaultRunner;
        /** Legacy per-phase runner map. */
        private Map<String, String> runners = new LinkedHashMap<>();
        /** New-form default config. */
        private PhaseConfig defaultPhaseConfig;
        /** New-form per-phase configs. */
        private Map<String, PhaseConfig> phaseConfigs = new LinkedHashMap<>();

        @Override public String getDefaultRunner() { return defaultRunner; }
        @Override public void setDefaultRunner(String defaultRunner) { this.defaultRunner = defaultRunner; }
        @Override public Map<String, String> getRunners() { return runners; }
        @Override public void setRunners(Map<String, String> runners) {
            this.runners = runners != null ? new LinkedHashMap<>(runners) : new LinkedHashMap<>();
        }
        @Override public PhaseConfig getDefaultPhaseConfig() { return defaultPhaseConfig; }
        @Override public void setDefaultPhaseConfig(PhaseConfig defaultPhaseConfig) {
            this.defaultPhaseConfig = defaultPhaseConfig;
        }
        @Override public Map<String, PhaseConfig> getPhaseConfigs() { return phaseConfigs; }
        @Override public void setPhaseConfigs(Map<String, PhaseConfig> phaseConfigs) {
            this.phaseConfigs = phaseConfigs != null ? new LinkedHashMap<>(phaseConfigs) : new LinkedHashMap<>();
        }
    }

    /** An empty container yields the empty bundle. */
    @Test(timeout = 5000)
    public void emptyContainerYieldsEmptyBundle() {
        assertTrue(new Level().toPhaseConfigBundle().isEmpty());
    }

    /** New fields win field-by-field; legacy runners fill in fields the new form leaves null. */
    @Test(timeout = 5000)
    public void newFieldsOverlayLegacyRunners() {
        Level level = new Level();
        level.setDefaultRunner("claude");
        level.getRunners().put("review", "opencode");
        level.setDefaultPhaseConfig(new PhaseConfig(null, "opus", "high"));
        level.getPhaseConfigs().put("review", new PhaseConfig(null, "sonnet", null));
        level.getPhaseConfigs().put("not-a-phase", new PhaseConfig("claude", null, null));

        PhaseConfigBundle bundle = level.toPhaseConfigBundle();
        assertEquals(new PhaseConfig("claude", "opus", "high"), bundle.defaultPhaseConfig());
        assertEquals(new PhaseConfig("opencode", "sonnet", null), bundle.phaseConfigs().get(Phase.REVIEW));
        assertEquals(1, bundle.phaseConfigs().size());
    }

    /** applyTo writes non-empty entries by wire name and clears an empty default. */
    @Test(timeout = 5000)
    public void applyToWritesNewFields() {
        Level level = new Level();
        level.setDefaultPhaseConfig(new PhaseConfig("claude", null, null));
        PhaseConfigBundle bundle = PhaseConfigBundle.EMPTY
                .withPhase(Phase.REVIEW, new PhaseConfig("opencode", null, null));
        bundle.applyTo(level);

        assertNull(level.getDefaultPhaseConfig());
        assertEquals(Map.of("review", new PhaseConfig("opencode", null, null)), level.getPhaseConfigs());
    }

    /** Migration folds legacy fields into the new shape, clears them, and is idempotent. */
    @Test(timeout = 5000)
    public void migrationFoldsLegacyFields() {
        Level level = new Level();
        level.setDefaultRunner("claude");
        level.getRunners().put("review", "opencode");
        level.setDefaultPhaseConfig(new PhaseConfig(null, "opus", null));
        PhaseConfigBundle before = level.toPhaseConfigBundle();

        level.migrateLegacyConfigToPhaseConfig();
        assertNull(level.getDefaultRunner());
        assertTrue(level.getRunners().isEmpty());
        assertEquals(new PhaseConfig("claude", "opus", null), level.getDefaultPhaseConfig());
        assertEquals(before, level.toPhaseConfigBundle());

        Map<String, PhaseConfig> migrated = level.getPhaseConfigs();
        level.migrateLegacyConfigToPhaseConfig();
        assertEquals(migrated, level.getPhaseConfigs());
        assertNull(level.getDefaultRunner());
    }
}
