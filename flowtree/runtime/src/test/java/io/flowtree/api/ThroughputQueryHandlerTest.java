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

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.github.GitHubProxyHandler;
import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ThroughputQueryHandler#handleWindow}: the preset and explicit
 * window forms, rejection of malformed and ambiguous requests, the workstream
 * filter, and the "stats not configured" response.
 */
public class ThroughputQueryHandlerTest extends TestSuiteBase {

    /** Responds to a rejected request with HTTP 400 carrying the message. */
    private static final GitHubProxyHandler.ErrorResponder BAD_REQUEST = message ->
            NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", message);

    /** Creates and initialises a store backed by a fresh temp HSQLDB. */
    private static JobStatsStore newStore() throws Exception {
        File dir = Files.createTempDirectory("throughput-handler").toFile();
        dir.deleteOnExit();
        JobStatsStore store = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** Builds a session whose only meaningful member is {@code getParms()}. */
    private static IHTTPSession session(String... keyValues) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return (IHTTPSession) Proxy.newProxyInstance(IHTTPSession.class.getClassLoader(),
                new Class<?>[] {IHTTPSession.class},
                (proxy, method, args) -> "getParms".equals(method.getName()) ? params : null);
    }

    /** Reads a response body as UTF-8 text. */
    private static String body(Response response) throws Exception {
        return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Seeds one job that ran and succeeded an hour before now, in {@code ws}. */
    private static void seedRecentJob(JobStatsStore store, String jobId, String ws) {
        Instant now = Instant.now();
        store.recordJobStarted(jobId, ws, jobId, "n1", now.minus(Duration.ofMinutes(70)));
        store.recordJobCompleted(jobId, ws, JobCompletionEvent.Status.SUCCESS.name(),
                now.minus(Duration.ofMinutes(60)), jobId, 0L, 0L, 0.25, 4, "sess-" + jobId,
                0, "success", false, 0, null, null, null, null);
    }

    /** With no store configured the handler answers 200 with an explanatory error. */
    @Test(timeout = 30000)
    public void noStoreReportsStatsNotConfigured() throws Exception {
        ThroughputQueryHandler handler = new ThroughputQueryHandler(() -> null);
        Response r = handler.handleWindow(session("preset", "3h"), BAD_REQUEST);
        assertEquals(Response.Status.OK, r.getStatus());
        assertEquals("{\"error\":\"Stats not configured\"}", body(r));
    }

    /** A preset window counts the job that finished inside it. */
    @Test(timeout = 30000)
    public void presetWindowCountsRecentJob() throws Exception {
        JobStatsStore store = newStore();
        try {
            seedRecentJob(store, "J1", "ws-1");
            ThroughputQueryHandler handler = new ThroughputQueryHandler(() -> store);
            Response r = handler.handleWindow(session("preset", "3h"), BAD_REQUEST);
            assertEquals(Response.Status.OK, r.getStatus());
            String json = body(r);
            assertTrue(json, json.contains("\"finished\":1"));
            assertTrue(json, json.contains("\"success\":1"));
        } finally {
            store.close();
        }
    }

    /** An explicit window and workstream filter excludes another workstream's job. */
    @Test(timeout = 30000)
    public void explicitWindowHonoursWorkstreamFilter() throws Exception {
        JobStatsStore store = newStore();
        try {
            seedRecentJob(store, "J1", "ws-1");
            seedRecentJob(store, "J2", "ws-2");
            Instant now = Instant.now();
            ThroughputQueryHandler handler = new ThroughputQueryHandler(() -> store);
            Response r = handler.handleWindow(session(
                    "from", now.minus(Duration.ofHours(2)).toString(),
                    "to", now.toString(),
                    "workstream", "ws-2"), BAD_REQUEST);
            assertEquals(Response.Status.OK, r.getStatus());
            String json = body(r);
            assertTrue(json, json.contains("\"finished\":1"));
            assertTrue(json, json.contains("\"ws-2\":1"));
            assertTrue(json, !json.contains("\"ws-1\""));
        } finally {
            store.close();
        }
    }

    /** A preset together with either explicit bound is ambiguous and is rejected. */
    @Test(timeout = 30000)
    public void presetWithExplicitBoundIsRejected() throws Exception {
        JobStatsStore store = newStore();
        try {
            ThroughputQueryHandler handler = new ThroughputQueryHandler(() -> store);
            String from = "2026-01-01T00:00:00Z";
            String to = "2026-01-01T06:00:00Z";
            assertEquals(Response.Status.BAD_REQUEST, handler.handleWindow(
                    session("preset", "3h", "from", from, "to", to), BAD_REQUEST).getStatus());
            assertEquals(Response.Status.BAD_REQUEST, handler.handleWindow(
                    session("preset", "3h", "from", from), BAD_REQUEST).getStatus());
            assertEquals(Response.Status.BAD_REQUEST, handler.handleWindow(
                    session("preset", "3h", "to", to), BAD_REQUEST).getStatus());
        } finally {
            store.close();
        }
    }

    /** Missing, half-supplied, unparseable, inverted and unknown-preset windows are rejected. */
    @Test(timeout = 30000)
    public void malformedWindowsAreRejected() throws Exception {
        JobStatsStore store = newStore();
        try {
            ThroughputQueryHandler handler = new ThroughputQueryHandler(() -> store);
            assertEquals(Response.Status.BAD_REQUEST,
                    handler.handleWindow(session(), BAD_REQUEST).getStatus());
            assertEquals(Response.Status.BAD_REQUEST, handler.handleWindow(
                    session("from", "2026-01-01T00:00:00Z"), BAD_REQUEST).getStatus());
            assertEquals(Response.Status.BAD_REQUEST, handler.handleWindow(
                    session("from", "yesterday", "to", "2026-01-01T06:00:00Z"),
                    BAD_REQUEST).getStatus());
            assertEquals(Response.Status.BAD_REQUEST, handler.handleWindow(
                    session("from", "2026-01-01T06:00:00Z", "to", "2026-01-01T00:00:00Z"),
                    BAD_REQUEST).getStatus());
            assertEquals(Response.Status.BAD_REQUEST, handler.handleWindow(
                    session("preset", "5h"), BAD_REQUEST).getStatus());
        } finally {
            store.close();
        }
    }
}
