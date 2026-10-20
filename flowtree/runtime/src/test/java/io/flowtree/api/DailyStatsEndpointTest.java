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
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for {@code GET /api/stats/daily}: jobs recorded in a real
 * {@link JobStatsStore}, read back over HTTP from a live
 * {@link FlowTreeApiEndpoint}, across workstreams and in the requested zone.
 */
public class DailyStatsEndpointTest extends TestSuiteBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** One hour in milliseconds. */
    private static final long HOUR = 3_600_000L;

    /** Live API endpoint under test. */
    private FlowTreeApiEndpoint endpoint;
    /** The store behind it. */
    private JobStatsStore store;
    /** Listening port assigned by NanoHTTPD. */
    private int port;

    /** Starts the endpoint on an ephemeral port with a fresh store. */
    @Before
    public void setUp() throws Exception {
        File dir = Files.createTempDirectory("daily-stats").toFile();
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

    /** Records a finished job between two instants. */
    private void finishedJob(String id, String workstream, String start, String end, double cost) {
        store.recordJobStarted(id, workstream, id, Instant.parse(start));
        store.recordJobCompleted(id, workstream, "SUCCESS", Instant.parse(end), id,
                0, 0, cost, 1, null, 0, "success", false, 0, null, null, null, null);
    }

    /** Jobs from every workstream are summed per local day, and submitted-only jobs are ignored. */
    @Test(timeout = 30000)
    public void sumsEveryWorkstreamPerLocalDay() throws Exception {
        // Oct 1, 09:00-12:00 and 10:00-11:00 Pacific, in two workstreams.
        finishedJob("a", "ws-1", "2026-10-01T16:00:00Z", "2026-10-01T19:00:00Z", 1.0);
        finishedJob("b", "ws-2", "2026-10-01T17:00:00Z", "2026-10-01T18:00:00Z", 0.5);
        store.recordJobSubmitted("never-ran", "ws-1", "queued", "someone", null,
                Instant.parse("2026-10-01T17:00:00Z"));

        JsonNode body = get("/api/stats/daily?from=2026-10-01&to=2026-10-02&tz=America/Los_Angeles");
        assertEquals("America/Los_Angeles", body.get("tz").asText());
        JsonNode days = body.get("days");
        assertEquals(2, days.size());
        assertEquals("2026-10-01", days.get(0).get("date").asText());
        assertEquals(4 * HOUR, days.get(0).get("wallClockMs").asLong());
        assertEquals(2, days.get(0).get("jobCount").asInt());
        assertEquals(1.5, days.get(0).get("costUsd").asDouble(), 1e-9);
        assertEquals(0, days.get(1).get("wallClockMs").asLong());
    }

    /** The zone decides the day: the same job lands on different dates in UTC and Pacific time. */
    @Test(timeout = 30000)
    public void theZoneDecidesTheDay() throws Exception {
        // 18:00-20:00 Pacific on Oct 1 is 01:00-03:00 UTC on Oct 2.
        finishedJob("late", "ws-1", "2026-10-02T01:00:00Z", "2026-10-02T03:00:00Z", 0);

        JsonNode utc = get("/api/stats/daily?from=2026-10-01&to=2026-10-02").get("days");
        assertEquals(0, utc.get(0).get("wallClockMs").asLong());
        assertEquals(2 * HOUR, utc.get(1).get("wallClockMs").asLong());

        JsonNode la = get("/api/stats/daily?from=2026-10-01&to=2026-10-02&tz=America/Los_Angeles").get("days");
        assertEquals(2 * HOUR, la.get(0).get("wallClockMs").asLong());
        assertEquals(0, la.get(1).get("wallClockMs").asLong());
    }

    /** Bad dates, an unknown zone, a reversed or oversized range are each a 400. */
    @Test(timeout = 30000)
    public void rejectsBadParameters() throws Exception {
        assertEquals(400, status("/api/stats/daily?from=yesterday&to=2026-10-02"));
        assertEquals(400, status("/api/stats/daily?from=2026-10-01&to=2026-10-02&tz=Mars/Olympus"));
        assertEquals(400, status("/api/stats/daily?from=2026-10-02&to=2026-10-01"));
        assertEquals(400, status("/api/stats/daily?from=2024-01-01&to=2026-10-01"));
    }

    /** The weekly route still answers with the weekly shape. */
    @Test(timeout = 30000)
    public void theWeeklyRouteIsUnchanged() throws Exception {
        JsonNode weekly = get("/api/stats");
        assertTrue(weekly.has("thisWeek"));
        assertTrue(weekly.has("lastWeek"));
    }

    /** GETs {@code path} and parses the JSON body. */
    private JsonNode get(String path) throws Exception {
        HttpURLConnection conn = open(path);
        assertEquals(200, conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            return MAPPER.readTree(in);
        }
    }

    /** GETs {@code path} and returns only the HTTP status. */
    private int status(String path) throws Exception {
        return open(path).getResponseCode();
    }

    /** Opens a GET connection to the endpoint. */
    private HttpURLConnection open(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("GET");
        return conn;
    }
}
