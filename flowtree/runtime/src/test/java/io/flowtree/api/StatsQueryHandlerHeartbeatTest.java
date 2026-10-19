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

package io.flowtree.api;

import io.flowtree.controller.ActiveJob;
import io.flowtree.controller.JobStatsStore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Verifies that the dedicated heartbeat route handler
 * ({@link StatsQueryHandler#handleHeartbeat(String, String)}) records the
 * heartbeat, node identity, and phase the harness posts — the controller side
 * of the harness heartbeat, independent of any status event.
 */
public class StatsQueryHandlerHeartbeatTest extends TestSuiteBase {

    /** Creates and initialises a store backed by a fresh temp HSQLDB. */
    private JobStatsStore newStore() throws Exception {
        File dir = Files.createTempDirectory("heartbeat-handler").toFile();
        dir.deleteOnExit();
        JobStatsStore store = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** A posted heartbeat records the node, refreshes liveness, and records the phase. */
    @Test(timeout = 30000)
    public void heartbeatRecordsNodeAndPhase() throws Exception {
        JobStatsStore store = newStore();
        try {
            Instant startedAt = Instant.now().minusSeconds(120);
            store.recordJobStarted("job-1", "ws-1", "job", startedAt);

            StatsQueryHandler handler = new StatsQueryHandler(store);
            handler.handleHeartbeat("job-1",
                    "{\"node\":\"halo (10.0.0.2)\",\"pid\":4242,\"phase\":\"review\"}");

            List<ActiveJob> active = store.getActiveJobs("ws-1");
            Assert.assertEquals(1, active.size());
            ActiveJob job = active.get(0);
            Assert.assertEquals("the heartbeat records the worker node", "halo (10.0.0.2)", job.node());
            assertNotNull("the heartbeat refreshes liveness", job.heartbeatAt());
            Assert.assertEquals("the heartbeat records the phase", "review", store.getJobPhase("job-1"));
        } finally {
            store.close();
        }
    }

    /** A heartbeat with no store configured answers without throwing. */
    @Test(timeout = 30000)
    public void heartbeatWithoutStoreIsNoOp() {
        StatsQueryHandler handler = new StatsQueryHandler(null);
        Assert.assertNotNull("the handler still answers with no store",
                handler.handleHeartbeat("job-1", "{\"node\":\"n\"}"));
    }
}
