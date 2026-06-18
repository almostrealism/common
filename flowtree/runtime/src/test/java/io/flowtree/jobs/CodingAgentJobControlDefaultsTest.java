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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the default-disabled behavior of deduplication and organizational
 * placement, and the explicit opt-in paths for both controls.
 *
 * <p>Newly created factories and jobs have both expensive phases disabled by
 * default so that routine exploratory submissions remain cheap. Final pre-merge
 * cleanup jobs opt in explicitly.</p>
 */
public class CodingAgentJobControlDefaultsTest extends TestSuiteBase {

    // ── Deduplication — disabled by default ─────────────────────────────────

    /**
     * Verifies that a newly created factory reports DEDUP_NONE as its deduplication mode.
     */
    @Test(timeout = 30000)
    public void factoryDeduplicationDisabledByDefault() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertEquals(CodingAgentJob.DEDUP_NONE, factory.getDeduplicationMode());
    }

    /**
     * Verifies that a job produced by a default factory inherits the DEDUP_NONE deduplication mode.
     */
    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsNoneDeduplication() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals(CodingAgentJob.DEDUP_NONE, job.getDeduplicationMode());
    }

    // ── Deduplication — explicit opt-in ─────────────────────────────────────

    /**
     * Verifies that setting DEDUP_LOCAL on a factory is reflected by its getter.
     */
    @Test(timeout = 30000)
    public void factoryDeduplicationOptInLocal() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
        assertEquals(CodingAgentJob.DEDUP_LOCAL, factory.getDeduplicationMode());
    }

    /**
     * Verifies that setting DEDUP_SPAWN on a factory is reflected by its getter.
     */
    @Test(timeout = 30000)
    public void factoryDeduplicationOptInSpawn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_SPAWN);
        assertEquals(CodingAgentJob.DEDUP_SPAWN, factory.getDeduplicationMode());
    }

    /**
     * Verifies that a job produced by a factory with DEDUP_LOCAL inherits that deduplication mode.
     */
    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsLocalOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals(CodingAgentJob.DEDUP_LOCAL, job.getDeduplicationMode());
    }

    /**
     * Verifies that DEDUP_NONE survives a serialization and deserialization round-trip.
     */
    @Test(timeout = 30000)
    public void deduplicationNoneWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertEquals(CodingAgentJob.DEDUP_NONE, decoded.getDeduplicationMode());
    }

    /**
     * Verifies that DEDUP_LOCAL survives a serialization and deserialization round-trip.
     */
    @Test(timeout = 30000)
    public void deduplicationLocalWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertEquals(CodingAgentJob.DEDUP_LOCAL, decoded.getDeduplicationMode());
    }

    // ── Organizational placement — disabled by default ───────────────────────

    /**
     * Verifies that a directly constructed job has organizational placement enforcement disabled.
     */
    @Test(timeout = 30000)
    public void jobOrganizationalPlacementDisabledByDefault() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        assertFalse(job.isEnforceOrganizationalPlacement());
    }

    /**
     * Verifies that a newly created factory has organizational placement enforcement disabled.
     */
    @Test(timeout = 30000)
    public void factoryOrganizationalPlacementDisabledByDefault() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertFalse(factory.isEnforceOrganizationalPlacement());
    }

    /**
     * Verifies that a job produced by a default factory inherits the disabled placement enforcement.
     */
    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsPlacementDisabled() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertFalse(job.isEnforceOrganizationalPlacement());
    }

    // ── Organizational placement — explicit opt-in ───────────────────────────

    /**
     * Verifies that enabling organizational placement enforcement on a factory is reflected by its getter.
     */
    @Test(timeout = 30000)
    public void factoryOrganizationalPlacementOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setEnforceOrganizationalPlacement(true);
        assertTrue(factory.isEnforceOrganizationalPlacement());
    }

    /**
     * Verifies that a job produced by a factory with placement enforcement enabled inherits that setting.
     */
    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsPlacementOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setEnforceOrganizationalPlacement(true);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue(job.isEnforceOrganizationalPlacement());
    }

    /**
     * Verifies that organizational placement enforcement survives a serialization and deserialization round-trip.
     */
    @Test(timeout = 30000)
    public void organizationalPlacementOptInWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setEnforceOrganizationalPlacement(true);
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertTrue(decoded.isEnforceOrganizationalPlacement());
    }

    /**
     * Verifies that the default disabled placement enforcement survives a serialization and deserialization round-trip.
     */
    @Test(timeout = 30000)
    public void organizationalPlacementDisabledDefaultWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertFalse(decoded.isEnforceOrganizationalPlacement());
    }

    // ── tmux-backed launch — disabled by default ─────────────────────────────

    /**
     * Verifies that a directly constructed job has tmux-backed launch disabled.
     */
    @Test(timeout = 30000)
    public void jobUseTmuxDisabledByDefault() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        assertFalse(job.isUseTmux());
    }

    /**
     * Verifies that a newly created factory has tmux-backed launch disabled.
     */
    @Test(timeout = 30000)
    public void factoryUseTmuxDisabledByDefault() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertFalse(factory.isUseTmux());
    }

    /**
     * Verifies that a job produced by a default factory inherits the disabled tmux setting.
     */
    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsUseTmuxDisabled() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertFalse(job.isUseTmux());
    }

    // ── tmux-backed launch — explicit opt-in ──────────────────────────────────

    /**
     * Verifies that enabling tmux-backed launch on a factory is reflected by its getter.
     */
    @Test(timeout = 30000)
    public void factoryUseTmuxOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setUseTmux(true);
        assertTrue(factory.isUseTmux());
    }

    /**
     * Verifies that a job produced by a factory with tmux enabled inherits that setting.
     */
    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsUseTmuxOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setUseTmux(true);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue(job.isUseTmux());
    }

    /**
     * Verifies that the tmux opt-in survives a factory serialization round-trip.
     */
    @Test(timeout = 30000)
    public void useTmuxOptInFactoryWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setUseTmux(true);
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertTrue(decoded.isUseTmux());
    }

    /**
     * Verifies that the default disabled tmux setting survives a factory serialization round-trip.
     */
    @Test(timeout = 30000)
    public void useTmuxDisabledDefaultFactoryWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertFalse(decoded.isUseTmux());
    }

    /**
     * Verifies that the tmux opt-in survives a {@link CodingAgentJob} wire
     * round-trip, exercising {@link CodingAgentJobCodec} encode/decode.
     */
    @Test(timeout = 30000)
    public void useTmuxOptInJobWireRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setUseTmux(true);
        CodingAgentJob decoded = GitManagedJobSerializationTest.roundTrip(job);
        assertTrue(decoded.isUseTmux());
    }
}
