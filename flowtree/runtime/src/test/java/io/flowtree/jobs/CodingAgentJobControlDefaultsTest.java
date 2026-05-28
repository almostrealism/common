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

    @Test(timeout = 30000)
    public void factoryDeduplicationDisabledByDefault() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertEquals(CodingAgentJob.DEDUP_NONE, factory.getDeduplicationMode());
    }

    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsNoneDeduplication() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals(CodingAgentJob.DEDUP_NONE, job.getDeduplicationMode());
    }

    // ── Deduplication — explicit opt-in ─────────────────────────────────────

    @Test(timeout = 30000)
    public void factoryDeduplicationOptInLocal() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
        assertEquals(CodingAgentJob.DEDUP_LOCAL, factory.getDeduplicationMode());
    }

    @Test(timeout = 30000)
    public void factoryDeduplicationOptInSpawn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_SPAWN);
        assertEquals(CodingAgentJob.DEDUP_SPAWN, factory.getDeduplicationMode());
    }

    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsLocalOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals(CodingAgentJob.DEDUP_LOCAL, job.getDeduplicationMode());
    }

    @Test(timeout = 30000)
    public void deduplicationNoneWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertEquals(CodingAgentJob.DEDUP_NONE, decoded.getDeduplicationMode());
    }

    @Test(timeout = 30000)
    public void deduplicationLocalWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertEquals(CodingAgentJob.DEDUP_LOCAL, decoded.getDeduplicationMode());
    }

    // ── Organizational placement — disabled by default ───────────────────────

    @Test(timeout = 30000)
    public void jobOrganizationalPlacementDisabledByDefault() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        assertFalse(job.isEnforceOrganizationalPlacement());
    }

    @Test(timeout = 30000)
    public void factoryOrganizationalPlacementDisabledByDefault() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertFalse(factory.isEnforceOrganizationalPlacement());
    }

    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsPlacementDisabled() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertFalse(job.isEnforceOrganizationalPlacement());
    }

    // ── Organizational placement — explicit opt-in ───────────────────────────

    @Test(timeout = 30000)
    public void factoryOrganizationalPlacementOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setEnforceOrganizationalPlacement(true);
        assertTrue(factory.isEnforceOrganizationalPlacement());
    }

    @Test(timeout = 30000)
    public void jobCreatedByFactoryInheritsPlacementOptIn() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setEnforceOrganizationalPlacement(true);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue(job.isEnforceOrganizationalPlacement());
    }

    @Test(timeout = 30000)
    public void organizationalPlacementOptInWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setEnforceOrganizationalPlacement(true);
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertTrue(decoded.isEnforceOrganizationalPlacement());
    }

    @Test(timeout = 30000)
    public void organizationalPlacementDisabledDefaultWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        CodingAgentJobFactory decoded = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertFalse(decoded.isEnforceOrganizationalPlacement());
    }
}
