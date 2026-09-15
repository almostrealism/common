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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Verifies that {@link JobStatsStore} records the lifecycle phase a running
 * job's harness reports, so a poller can tell where a {@code STARTED} job is.
 */
public class JobStatsStorePhaseTest extends TestSuiteBase {

    /** Creates and initialises a store backed by a fresh temp HSQLDB. */
    private JobStatsStore newStore() throws Exception {
        File tempDir = Files.createTempDirectory("phase-test").toFile();
        tempDir.deleteOnExit();
        JobStatsStore store = new JobStatsStore(new File(tempDir, "stats").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** The most recently recorded phase is the one read back; an unknown job has none. */
    @Test(timeout = 30000)
    public void latestPhaseIsReadBack() throws Exception {
        JobStatsStore store = newStore();
        try {
            store.recordJobStarted("job-1", "ws-1", "job", Instant.now());
            assertNull(store.getJobPhase("job-1"));

            store.recordPhase("job-1", "primary");
            assertEquals("primary", store.getJobPhase("job-1"));

            store.recordPhase("job-1", "primary complete");
            assertEquals("primary complete", store.getJobPhase("job-1"));

            store.recordPhase("job-1", "");
            Assert.assertEquals("an empty phase changes nothing", "primary complete", store.getJobPhase("job-1"));
            assertNull(store.getJobPhase("job-none"));
        } finally {
            store.close();
        }
    }
}
