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

import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.controller.ActiveJob;
import io.flowtree.controller.JobStatsStore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Verifies the controller-side job-state routes on {@link StatsQueryHandler}:
 * the dedicated heartbeat route
 * ({@link StatsQueryHandler#handleHeartbeat(String, String)}) that records the
 * heartbeat, node identity, and phase the harness posts; the pending-job
 * listing; and the configurable silence/lost thresholds that drive the derived
 * {@code UNRESPONSIVE}/{@code LOST} labels in the rendered JSON.
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

    /** The pending-job listing labels a job LOST once it waits past the configured lost threshold. */
    @Test(timeout = 30000)
    public void pendingListingLabelsLostPastConfiguredThreshold() throws Exception {
        JobStatsStore store = newStore();
        try {
            store.recordJobSubmitted("job-1", "ws-1", "job", "submitter", null,
                    Instant.now().minusSeconds(300));

            StatsQueryHandler handler = new StatsQueryHandler(store);
            handler.setLostThreshold(Duration.ofSeconds(60));

            String body = body(handler.handlePendingJobs("ws-1"));
            Assert.assertTrue("the pending job is listed", body.contains("\"jobId\":\"job-1\""));
            Assert.assertTrue("a job older than the lost threshold is labelled LOST",
                    body.contains("\"state\":\"LOST\""));
        } finally {
            store.close();
        }
    }

    /** A null/non-positive threshold restores the generous defaults, so a fresh job is not flagged. */
    @Test(timeout = 30000)
    public void nonPositiveThresholdRestoresDefaults() throws Exception {
        JobStatsStore store = newStore();
        try {
            store.recordJobStarted("job-1", "ws-1", "job", "node-a",
                    Instant.now().minusSeconds(30));

            StatsQueryHandler handler = new StatsQueryHandler(store);
            handler.setSilentThreshold(Duration.ofSeconds(-1));

            String body = body(handler.handleActiveJobs("ws-1"));
            Assert.assertTrue("a recently-active job is RUNNING under the default threshold",
                    body.contains("\"state\":\"RUNNING\""));
        } finally {
            store.close();
        }
    }

    /** Reads a NanoHTTPD response body as a UTF-8 string. */
    private static String body(Response response) throws Exception {
        return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
    }
}
