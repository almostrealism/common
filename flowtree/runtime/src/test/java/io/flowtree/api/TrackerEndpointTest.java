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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fi.iki.elonen.NanoHTTPD;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the controller's tracker-facing surface: the
 * {@code GET /api/tracker/claimable} endpoint CI uses to decide whether to
 * start a task-planning round, and the {@code trackerCapabilities} workstream
 * setting that grants agents the narrow tracker tools.
 *
 * <p>The tracker is replaced by a local HTTP stub, so the tests check what the
 * controller forwards and what it returns without a tracker running.</p>
 */
public class TrackerEndpointTest extends TestSuiteBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP client for requests to the endpoint. */
    private final HttpClient client = HttpClient.newHttpClient();

    /** Live API endpoint under test. */
    private FlowTreeApiEndpoint endpoint;

    /** Notifier holding the registered workstreams. */
    private SlackNotifier notifier;

    /** Stand-in for the tracker service. */
    private HttpServer tracker;

    /** The last request line the tracker stub received. */
    private final AtomicReference<String> trackerQuery = new AtomicReference<>();

    /** The Authorization header of the last request the tracker stub received. */
    private final AtomicReference<String> trackerAuth = new AtomicReference<>();

    /** Starts the endpoint and a tracker stub answering {@code /v1/claimable}. */
    @Before
    public void setUp() throws Exception {
        tracker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tracker.createContext("/v1/claimable", exchange -> {
            trackerQuery.set(exchange.getRequestURI().getRawQuery());
            trackerAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"ok\":true,\"release_id\":\"r1\",\"count\":2}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        tracker.start();

        notifier = new SlackNotifier(null);
        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    /** Stops the endpoint and the tracker stub. */
    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
        if (tracker != null) tracker.stop(0);
    }

    /** The tracker's own count is returned, for the names CI asked about. */
    @Test(timeout = 30000)
    public void claimableReturnsTheTrackersCount() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort() + "/", "tok");
        HttpResponse<String> resp = get("/api/tracker/claimable?project=Framework&release=Framework%201.2");
        assertEquals(200, resp.statusCode());
        assertEquals(2, MAPPER.readTree(resp.body()).get("count").asInt());
        assertEquals("project=Framework&release=Framework+1.2", trackerQuery.get());
        assertEquals("Bearer tok", trackerAuth.get());
    }

    /** A tracker without a token is called without an Authorization header. */
    @Test(timeout = 30000)
    public void noTokenMeansNoAuthorizationHeader() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort(), "");
        get("/api/tracker/claimable?project=P&release=R");
        assertNull(trackerAuth.get());
    }

    /** Both names are required. */
    @Test(timeout = 30000)
    public void claimableRequiresBothNames() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort(), null);
        assertEquals(400, get("/api/tracker/claimable?project=Framework").statusCode());
    }

    /** An unreachable tracker is reported, not mistaken for "nothing to claim". */
    @Test(timeout = 30000)
    public void anUnreachableTrackerIsAnError() throws Exception {
        int port = tracker.getAddress().getPort();
        tracker.stop(0);
        tracker = null;
        endpoint.setTrackerService("http://127.0.0.1:" + port, null);
        HttpResponse<String> resp = get("/api/tracker/claimable?project=P&release=R");
        assertEquals(503, resp.statusCode());
        assertFalse(MAPPER.readTree(resp.body()).get("ok").asBoolean());
    }

    /** Without a configured tracker the endpoint refuses rather than guessing. */
    @Test(timeout = 30000)
    public void anUnconfiguredTrackerIsAnError() throws Exception {
        HttpResponse<String> resp = get("/api/tracker/claimable?project=P&release=R");
        assertEquals(400, resp.statusCode());
    }

    /** Registration stores the roles and the workstream list reports them. */
    @Test(timeout = 30000)
    public void registrationGrantsTrackerCapabilities() throws Exception {
        HttpResponse<String> resp = post("/api/workstreams",
                "{\"defaultBranch\":\"feature/planner\",\"trackerCapabilities\":[\"planner\"]}");
        assertEquals(resp.body(), 200, resp.statusCode());
        Workstream ws = notifier.getWorkstream(MAPPER.readTree(resp.body()).get("workstreamId").asText());
        assertEquals(List.of("planner"), ws.getTrackerCapabilities());
        assertTrue(ws.toSummaryJson().contains("\"trackerCapabilities\":[\"planner\"]"));
    }

    /** An unknown role is rejected at registration. */
    @Test(timeout = 30000)
    public void registrationRejectsAnUnknownRole() throws Exception {
        HttpResponse<String> resp = post("/api/workstreams",
                "{\"defaultBranch\":\"feature/admin\",\"trackerCapabilities\":[\"admin\"]}");
        assertEquals(400, resp.statusCode());
    }

    /** An update replaces the roles when the field is present and keeps them when it is not. */
    @Test(timeout = 30000)
    public void updateUsesPresenceSemantics() throws Exception {
        Workstream ws = new Workstream(null, "steward");
        ws.setDefaultBranch("feature/steward");
        ws.setTrackerCapabilities(List.of("steward"));
        notifier.registerWorkstream(ws);
        String path = "/api/workstreams/" + ws.getWorkstreamId() + "/update";

        assertEquals(200, post(path, "{\"planningDocument\":\"docs/x.md\"}").statusCode());
        assertEquals(List.of("steward"), ws.getTrackerCapabilities());

        assertEquals(200, post(path, "{\"trackerCapabilities\":[]}").statusCode());
        assertTrue(ws.getTrackerCapabilities().isEmpty());

        assertEquals(400, post(path, "{\"trackerCapabilities\":[\"root\"]}").statusCode());
        assertTrue(ws.getTrackerCapabilities().isEmpty());
    }

    /** An unknown role rejects the whole update without applying other fields. */
    @Test(timeout = 30000)
    public void updateValidatesRolesBeforeMutating() throws Exception {
        Workstream ws = new Workstream(null, "steward");
        ws.setDefaultBranch("feature/steward");
        ws.setPlanningDocument("docs/original.md");
        notifier.registerWorkstream(ws);
        String path = "/api/workstreams/" + ws.getWorkstreamId() + "/update";

        assertEquals(400, post(path,
                "{\"planningDocument\":\"docs/changed.md\",\"trackerCapabilities\":[\"root\"]}").statusCode());
        assertEquals("docs/original.md", ws.getPlanningDocument());
    }

    /** Issues a GET against the endpoint. */
    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Issues a POST with a JSON body against the endpoint. */
    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Resolves a path against the endpoint's port. */
    private URI uri(String path) {
        return URI.create("http://localhost:" + endpoint.getListeningPort() + path);
    }}
