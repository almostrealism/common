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
 * start a task-planning round, the {@code POST /api/tracker/claim} endpoint CI
 * uses to claim the task it hands to an agent, and the
 * {@code trackerCapabilities} workstream setting that grants the
 * {@code planner} role.
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

    /** The body of the last claim the tracker stub received. */
    private final AtomicReference<String> claimRequest = new AtomicReference<>();

    /** The status the tracker stub answers a claim with. */
    private final AtomicReference<Integer> claimStatus = new AtomicReference<>(200);

    /** The body the tracker stub answers a claim with. */
    private final AtomicReference<String> claimReply = new AtomicReference<>(
            "{\"ok\":true,\"task\":{\"id\":\"t1\",\"title\":\"Do it\"}}");

    /** Starts the endpoint and a tracker stub answering {@code /v1/claimable} and {@code /v1/claim}. */
    @Before
    public void setUp() throws Exception {
        tracker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tracker.createContext("/v1/claim", exchange -> {
            claimRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            trackerAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = claimReply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(claimStatus.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
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
        assertEquals(List.of("planner"), ws.getTrackerCapabilities());

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

    /**
     * A role list that is not an array of strings is refused at registration
     * rather than coerced: a bare string must not read as the body's next
     * array (here {@code dependentRepos}), and a non-string element must not be
     * silently dropped.
     */
    @Test(timeout = 30000)
    public void registrationRejectsMalformedRoles() throws Exception {
        assertEquals(400, post("/api/workstreams", "{\"defaultBranch\":\"feature/a\","
                + "\"trackerCapabilities\":\"planner\",\"dependentRepos\":[\"steward\"]}").statusCode());
        assertEquals(400, post("/api/workstreams", "{\"defaultBranch\":\"feature/b\","
                + "\"trackerCapabilities\":[\"planner\",1]}").statusCode());
        assertEquals(400, post("/api/workstreams", "{\"defaultBranch\":\"feature/c\","
                + "\"trackerCapabilities\":[null]}").statusCode());
        assertTrue(notifier.getWorkstreams().isEmpty());
    }

    /** A malformed role list rejects an update and leaves the granted roles and other fields alone. */
    @Test(timeout = 30000)
    public void updateRejectsMalformedRoles() throws Exception {
        Workstream ws = new Workstream(null, "steward");
        ws.setDefaultBranch("feature/steward");
        ws.setPlanningDocument("docs/original.md");
        ws.setTrackerCapabilities(List.of("steward"));
        notifier.registerWorkstream(ws);
        String path = "/api/workstreams/" + ws.getWorkstreamId() + "/update";

        assertEquals(400, post(path, "{\"planningDocument\":\"docs/changed.md\","
                + "\"trackerCapabilities\":\"planner\"}").statusCode());
        assertEquals(400, post(path, "{\"trackerCapabilities\":[\"planner\",true]}").statusCode());
        assertEquals("docs/original.md", ws.getPlanningDocument());
        assertEquals(List.of("planner"), ws.getTrackerCapabilities());

        assertEquals(200, post(path, "{\"trackerCapabilities\":null}").statusCode());
        assertTrue(ws.getTrackerCapabilities().isEmpty());
    }

    /** A claim is forwarded for the registered workstream and the tracker's task is returned. */
    @Test(timeout = 30000)
    public void claimReturnsTheClaimedTask() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort(), "tok");
        Workstream ws = registeredWorkstream();
        HttpResponse<String> resp = post("/api/tracker/claim", "{\"project\":\"Framework\","
                + "\"release\":\"Framework 1.2\",\"workstreamId\":\"" + ws.getWorkstreamId() + "\"}");
        assertEquals(resp.body(), 200, resp.statusCode());
        assertTrue(MAPPER.readTree(resp.body()).get("ok").asBoolean());
        assertEquals("t1", MAPPER.readTree(resp.body()).get("task").get("id").asText());
        assertEquals("Framework 1.2", MAPPER.readTree(claimRequest.get()).get("release").asText());
        assertEquals(ws.getWorkstreamId(), MAPPER.readTree(claimRequest.get()).get("workstream_id").asText());
        assertEquals("Bearer tok", trackerAuth.get());
    }

    /** A release with nothing to claim answers with a null task, not an error. */
    @Test(timeout = 30000)
    public void claimReturnsNullWhenNothingIsClaimable() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort(), null);
        claimReply.set("{\"ok\":true,\"task\":null}");
        HttpResponse<String> resp = post("/api/tracker/claim", "{\"project\":\"P\",\"release\":\"R\","
                + "\"workstreamId\":\"" + registeredWorkstream().getWorkstreamId() + "\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(MAPPER.readTree(resp.body()).get("task").isNull());
    }

    /** Every field is required, and a blank one counts as missing. */
    @Test(timeout = 30000)
    public void claimRequiresEveryField() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort(), null);
        String id = registeredWorkstream().getWorkstreamId();
        assertEquals(400, post("/api/tracker/claim",
                "{\"project\":\"P\",\"release\":\"R\"}").statusCode());
        assertEquals(400, post("/api/tracker/claim",
                "{\"project\":\"P\",\"release\":\" \",\"workstreamId\":\"" + id + "\"}").statusCode());
        assertNull(claimRequest.get());
    }

    /** A claim for a workstream the controller does not know is refused before reaching the tracker. */
    @Test(timeout = 30000)
    public void claimRejectsAnUnknownWorkstream() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort(), null);
        HttpResponse<String> resp = post("/api/tracker/claim",
                "{\"project\":\"P\",\"release\":\"R\",\"workstreamId\":\"ws-missing\"}");
        assertEquals(404, resp.statusCode());
        assertNull(claimRequest.get());
    }

    /** Without a configured tracker a claim is reported as unavailable. */
    @Test(timeout = 30000)
    public void claimWithoutATrackerIsUnavailable() throws Exception {
        HttpResponse<String> resp = post("/api/tracker/claim", "{\"project\":\"P\",\"release\":\"R\","
                + "\"workstreamId\":\"" + registeredWorkstream().getWorkstreamId() + "\"}");
        assertEquals(503, resp.statusCode());
        assertFalse(MAPPER.readTree(resp.body()).get("ok").asBoolean());
    }

    /** A tracker that refuses the claim is reported as a bad gateway, not as nothing to claim. */
    @Test(timeout = 30000)
    public void claimReportsATrackerFailure() throws Exception {
        endpoint.setTrackerService("http://127.0.0.1:" + tracker.getAddress().getPort(), null);
        claimStatus.set(500);
        claimReply.set("{\"ok\":false,\"error\":\"boom\"}");
        HttpResponse<String> resp = post("/api/tracker/claim", "{\"project\":\"P\",\"release\":\"R\","
                + "\"workstreamId\":\"" + registeredWorkstream().getWorkstreamId() + "\"}");
        assertEquals(502, resp.statusCode());
        assertFalse(MAPPER.readTree(resp.body()).get("ok").asBoolean());
    }

    /** Registers a workstream with the notifier and returns it. */
    private Workstream registeredWorkstream() {
        Workstream ws = new Workstream(null, "claimer");
        ws.setDefaultBranch("feature/claimer");
        notifier.registerWorkstream(ws);
        return ws;
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
    }
}
