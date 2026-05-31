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

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link PhaseConfigBundle}. Covers bundle construction, the
 * {@code forPhase} per-level overlay rule, and conversions to/from the
 * legacy runner map shape.
 */
public class PhaseConfigBundleTest extends TestSuiteBase {

    /** EMPTY bundle is empty, default config is EMPTY, phase configs map is empty. */
    @Test(timeout = 5000)
    public void emptyBundleIsEmpty() {
        assertTrue(PhaseConfigBundle.EMPTY.isEmpty());
        assertTrue(PhaseConfigBundle.EMPTY.defaultPhaseConfig().isEmpty());
        assertTrue(PhaseConfigBundle.EMPTY.phaseConfigs().isEmpty());
    }

    /** null default normalises to EMPTY PhaseConfig; null overrides yields empty map. */
    @Test(timeout = 5000)
    public void nullDefaultNormalisesToEmpty() {
        PhaseConfigBundle b = new PhaseConfigBundle(null, null);
        assertSame(PhaseConfig.EMPTY, b.defaultPhaseConfig());
        assertTrue(b.phaseConfigs().isEmpty());
    }

    /** forPhase returns the default config when no per-phase override exists. */
    @Test(timeout = 5000)
    public void forPhaseReturnsDefaultWhenNoOverride() {
        PhaseConfig def = new PhaseConfig("claude", "opus", "high");
        PhaseConfigBundle b = new PhaseConfigBundle(def, null);
        assertEquals(def, b.forPhase(Phase.PRIMARY));
        assertEquals(def, b.forPhase(Phase.REVIEW));
    }

    /** forPhase null returns the default config. */
    @Test(timeout = 5000)
    public void forPhaseNullArgumentReturnsDefault() {
        PhaseConfig def = new PhaseConfig("claude", "opus", "high");
        PhaseConfigBundle b = new PhaseConfigBundle(def, null);
        assertEquals(def, b.forPhase(null));
    }

    /** forPhase overlays the per-phase override on the default config. */
    @Test(timeout = 5000)
    public void forPhaseOverlaysOverrideOnDefault() {
        PhaseConfig def = new PhaseConfig("claude", "opus", "high");
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.REVIEW, new PhaseConfig(null, "sonnet", null));
        PhaseConfigBundle b = new PhaseConfigBundle(def, overrides);
        PhaseConfig review = b.forPhase(Phase.REVIEW);
        assertEquals("claude", review.runner());
        assertEquals("sonnet", review.model());
        assertEquals("high", review.effort());
        // Other phases still inherit default.
        assertEquals(def, b.forPhase(Phase.PRIMARY));
    }

    /** withDefault replaces the default config while preserving existing overrides. */
    @Test(timeout = 5000)
    public void withDefaultReplacesDefaultPreservingOverrides() {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.REVIEW, new PhaseConfig("opencode", null, null));
        PhaseConfigBundle b = new PhaseConfigBundle(PhaseConfig.EMPTY, overrides);
        PhaseConfigBundle updated = b.withDefault(new PhaseConfig("claude", "opus", null));
        assertEquals("claude", updated.defaultPhaseConfig().runner());
        assertEquals("opus", updated.defaultPhaseConfig().model());
        assertEquals("opencode", updated.phaseConfigs().get(Phase.REVIEW).runner());
    }

    /** withPhase adds a per-phase entry to the bundle. */
    @Test(timeout = 5000)
    public void withPhaseAddsEntry() {
        PhaseConfigBundle b = PhaseConfigBundle.EMPTY
                .withPhase(Phase.COMMIT_MESSAGE, new PhaseConfig("opencode", null, null));
        assertEquals(1, b.phaseConfigs().size());
        assertEquals("opencode", b.phaseConfigs().get(Phase.COMMIT_MESSAGE).runner());
    }

    /** withPhase null clears an existing per-phase entry. */
    @Test(timeout = 5000)
    public void withPhaseNullClearsEntry() {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.REVIEW, new PhaseConfig("opencode", null, null));
        PhaseConfigBundle b = new PhaseConfigBundle(PhaseConfig.EMPTY, overrides);
        PhaseConfigBundle updated = b.withPhase(Phase.REVIEW, null);
        assertTrue(updated.phaseConfigs().isEmpty());
    }

    /**
     * Tests that withPhase rejects null phase arguments.
     */
    @Test(timeout = 5000)
    public void withPhaseRejectsNullPhase() {
        try {
            PhaseConfigBundle.EMPTY.withPhase(null, new PhaseConfig("claude", null, null));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    /**
     * Tests that fromLegacyRunners builds a runner-only bundle correctly.
     */
    @Test(timeout = 5000)
    public void fromLegacyRunnersBuildsRunnerOnlyBundle() {
        Map<String, String> runners = new LinkedHashMap<>();
        runners.put("review", "opencode");
        PhaseConfigBundle b = PhaseConfigBundle.fromLegacyRunners("claude", runners);
        assertEquals("claude", b.defaultPhaseConfig().runner());
        assertNull(b.defaultPhaseConfig().model());
        assertNull(b.defaultPhaseConfig().effort());
        PhaseConfig review = b.phaseConfigs().get(Phase.REVIEW);
        assertNotNull(review);
        assertEquals("opencode", review.runner());
        assertNull(review.model());
        assertNull(review.effort());
    }

    /**
     * Tests that fromLegacyRunners handles null default runner gracefully.
     */
    @Test(timeout = 5000)
    public void fromLegacyRunnersHandlesNullDefault() {
        PhaseConfigBundle b = PhaseConfigBundle.fromLegacyRunners(null, null);
        assertTrue(b.isEmpty());
    }

    /**
     * Tests that fromLegacyRunners skips unknown phases.
     */
    @Test(timeout = 5000)
    public void fromLegacyRunnersSkipsUnknownPhase() {
        Map<String, String> runners = new LinkedHashMap<>();
        runners.put("nonsense-phase", "opencode");
        runners.put("review", "opencode");
        PhaseConfigBundle b = PhaseConfigBundle.fromLegacyRunners(null, runners);
        assertEquals(1, b.phaseConfigs().size());
        assertEquals("opencode", b.phaseConfigs().get(Phase.REVIEW).runner());
    }

    /**
     * Tests that toLegacyRunnerMap exposes runner-only configurations.
     */
    @Test(timeout = 5000)
    public void toLegacyRunnerMapExposesRunnerOnly() {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.REVIEW, new PhaseConfig("opencode", "qwen3-coder-30b", "high"));
        PhaseConfigBundle b = new PhaseConfigBundle(PhaseConfig.EMPTY, overrides);
        Map<String, String> legacy = b.toLegacyRunnerMap();
        assertEquals(1, legacy.size());
        assertEquals("opencode", legacy.get("review"));
    }

    /**
     * Tests that toLegacyRunnerMap skips entries without a runner.
     */
    @Test(timeout = 5000)
    public void toLegacyRunnerMapSkipsEntriesWithoutRunner() {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.REVIEW, new PhaseConfig(null, "opus", "high"));
        PhaseConfigBundle b = new PhaseConfigBundle(PhaseConfig.EMPTY, overrides);
        assertTrue(b.toLegacyRunnerMap().isEmpty());
    }

    /**
     * Tests that an empty bundle ignores empty configs in isEmpty check.
     */
    @Test(timeout = 5000)
    public void emptyBundleIgnoresEmptyConfigsInIsEmpty() {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.REVIEW, PhaseConfig.EMPTY);
        PhaseConfigBundle b = new PhaseConfigBundle(PhaseConfig.EMPTY, overrides);
        // Constructor preserves all-empty entries; isEmpty checks they're truly empty.
        assertTrue(b.isEmpty());
    }

    /**
     * Tests that a non-empty override bundle is not considered empty.
     */
    @Test(timeout = 5000)
    public void nonEmptyOverrideIsNotEmpty() {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.REVIEW, new PhaseConfig("opencode", null, null));
        PhaseConfigBundle b = new PhaseConfigBundle(PhaseConfig.EMPTY, overrides);
        assertFalse(b.isEmpty());
    }
}
