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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.controller.PendingJob;
import io.flowtree.slack.SlackNotifier;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for {@code GET /api/jobs/{id}} against a real
 * {@link JobStatsStore}: a job is found from the moment the controller accepts
 * it, and the response carries its derived state.
 */
public class JobLookupEndpointTest extends TestSuiteBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Live API endpoint under test. */
    private FlowTreeApiEndpoint endpoint;

    /** The store behind it. */
    private JobStatsStore store;

    /** Listening port assigned by NanoHTTPD. */
    private int port;

    /** Starts the endpoint on an ephemeral port with a fresh store and no notifier history. */
    @Before
    public void setUp() throws Exception {
        File dir = Files.createTempDirectory("job-lookup").toFile();
        dir.deleteOnExit();
        store = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        store.initialize();
        endpoint = new FlowTreeApiEndpoint(0, new SlackNotifier(null));
        endpoint.setStatsStore(store);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();
    }

    /** Stops the endpoint and closes the store. */
    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
        if (store != null) store.close();
    }

    /**
     * A job that was accepted but has not started is found, with state
     * {@code SUBMITTED}, its submission time and its workstream, although no
     * notifier has any history of it.
     */
    @Test(timeout = 30000)
    public void pendingJobIsFoundAsSubmitted() throws Exception {
        Instant submitted = Instant.now().minus(Duration.ofMinutes(5));
        store.recordJobSubmitted("pending", "ws-1", "queued work", "someone", null, submitted);

        JsonNode job = get("/api/jobs/pending");
        assertEquals("pending", job.get("jobId").asText());
        assertEquals("SUBMITTED", job.get("status").asText());
        assertEquals("SUBMITTED", job.get("state").asText());
        assertEquals(submitted.toEpochMilli(), Instant.parse(job.get("submittedAt").asText()).toEpochMilli());
        assertEquals("ws-1", job.get("workstreamId").asText());
    }

    /** A job that has waited past the lost threshold without starting has state {@code LOST}. */
    @Test(timeout = 30000)
    public void longPendingJobIsLost() throws Exception {
        Instant submitted = Instant.now().minus(PendingJob.DEFAULT_LOST_THRESHOLD).minus(Duration.ofMinutes(1));
        store.recordJobSubmitted("lost", "ws-1", "dropped work", "someone", null, submitted);

        JsonNode job = get("/api/jobs/lost");
        assertEquals("SUBMITTED", job.get("status").asText());
        assertEquals("LOST", job.get("state").asText());
        assertEquals("ws-1", job.get("workstreamId").asText());
    }

    /**
     * A running job is {@code RUNNING} while it reports, and {@code UNRESPONSIVE}
     * once it has been silent past the threshold; its submission time survives
     * the start.
     */
    @Test(timeout = 30000)
    public void runningJobStateFollowsItsHeartbeat() throws Exception {
        Instant now = Instant.now();
        store.recordJobSubmitted("live", "ws-1", "work", "someone", null, now.minus(Duration.ofMinutes(2)));
        store.recordJobStarted("live", "ws-1", "work", now.minus(Duration.ofMinutes(1)));
        store.recordJobStarted("silent", "ws-2", "work", now.minus(Duration.ofHours(1)));

        JsonNode live = get("/api/jobs/live");
        assertEquals("STARTED", live.get("status").asText());
        assertEquals("RUNNING", live.get("state").asText());
        assertTrue(live.has("submittedAt"));
        assertEquals("ws-1", live.get("workstreamId").asText());

        JsonNode silent = get("/api/jobs/silent");
        assertEquals("UNRESPONSIVE", silent.get("state").asText());
        assertFalse("a job never recorded as submitted has no submission time", silent.has("submittedAt"));
    }

    /** A finished job's state is its terminal status. */
    @Test(timeout = 30000)
    public void finishedJobStateIsItsStatus() throws Exception {
        store.recordJobStarted("done", "ws-1", "work", Instant.parse("2026-10-01T16:00:00Z"));
        store.recordJobCompleted("done", "ws-1", "SUCCESS", Instant.parse("2026-10-01T17:00:00Z"), "work",
                0, 0, 0, 1, null, 0, "success", false, 0, null, null, null, null);

        JsonNode job = get("/api/jobs/done");
        assertEquals("SUCCESS", job.get("status").asText());
        assertEquals("SUCCESS", job.get("state").asText());
    }

    /** A job neither the store nor any notifier knows is a 404. */
    @Test(timeout = 30000)
    public void unknownJobIsNotFound() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + "/api/jobs/nobody").openConnection();
        assertEquals(404, conn.getResponseCode());
    }

    /**
     * Reads a JSON response body.
     *
     * @param path the request path
     * @return the parsed body
     */
    private JsonNode get(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        assertEquals(200, conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            return MAPPER.readTree(in);
        }
    }
}
