/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowtree.github.PullRequest;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link TaskCloseOutReconciler}: which branch outcomes reach the
 * tracker, and which workstreams are never looked at.
 *
 * <p>The tracker is a local HTTP stub that reports which workstreams await
 * close-out and records every close-out it is sent; pull requests come from a
 * map standing in for GitHub.</p>
 */
public class TaskCloseOutReconcilerTest extends TestSuiteBase {

    /** JSON parser for request bodies and pull-request fixtures. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Stand-in for the tracker service. */
    private HttpServer tracker;

    /** The workstream ids the tracker stub reports as awaiting close-out. */
    private final List<String> awaiting = new ArrayList<>();

    /** Every close-out the tracker stub received, as {@code workstream_id=outcome}. */
    private final List<String> closeOuts = new ArrayList<>();

    /** The Authorization header of the last request the tracker stub received. */
    private String trackerAuth;

    /** The workstreams the controller knows, by id. */
    private final Map<String, Workstream> workstreams = new HashMap<>();

    /** Each branch's pull requests; a branch absent from the map could not be looked up. */
    private final Map<String, List<PullRequest>> pullRequests = new HashMap<>();

    /** The branches whose pull requests were looked up, in order. */
    private final List<String> lookedUp = new ArrayList<>();

    /** Starts the tracker stub. */
    @Before
    public void startTracker() throws IOException {
        tracker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tracker.createContext("/v1/close-out", this::answer);
        tracker.start();
    }

    /** Stops the tracker stub. */
    @After
    public void stopTracker() {
        if (tracker != null) tracker.stop(0);
    }

    /** A merged pull request closes the tasks linked to the branch's workstream. */
    @Test(timeout = 30000)
    public void mergedPullRequestClosesTheTasks() throws IOException {
        workstream("ws-1", "project/task-1");
        pullRequests.put("project/task-1", List.of(pr(7, "closed", null), pr(8, "closed", "2026-10-12T05:00:00Z")));

        Map<String, List<String>> changed = reconciler().reconcileOnce();

        assertEquals(List.of("ws-1=merged"), closeOuts);
        assertEquals(Map.of("ws-1", List.of("task-of-ws-1")), changed);
        assertEquals("Bearer tok", trackerAuth);
    }

    /** A pull request closed without merging, with nothing open, declines the tasks. */
    @Test(timeout = 30000)
    public void closedPullRequestDeclinesTheTasks() throws IOException {
        workstream("ws-1", "project/task-1");
        pullRequests.put("project/task-1", List.of(pr(7, "closed", null)));

        reconciler().reconcileOnce();

        assertEquals(List.of("ws-1=closed"), closeOuts);
    }

    /** No outcome is reported while a pull request is open, before one exists, or when GitHub cannot be asked. */
    @Test(timeout = 30000)
    public void unfinishedBranchesAreLeftAlone() throws IOException {
        workstream("ws-open", "project/task-open");
        pullRequests.put("project/task-open", List.of(pr(7, "closed", null), pr(9, "open", null)));
        workstream("ws-none", "project/task-none");
        pullRequests.put("project/task-none", List.of());
        workstream("ws-unknown", "project/task-unknown");

        Map<String, List<String>> changed = reconciler().reconcileOnce();

        assertEquals(List.of(), closeOuts);
        assertTrue(changed.isEmpty());
        assertEquals(List.of("project/task-open", "project/task-none", "project/task-unknown"), lookedUp);
    }

    /** Long-lived and unknown workstreams, and a branch that is its own base, are never looked up. */
    @Test(timeout = 30000)
    public void workstreamsWithoutATaskBranchAreSkipped() throws IOException {
        workstream("ws-standing", "feature/long-lived").setKind("standing");
        workstream("ws-orchestrator", "feature/orchestration").setKind("orchestrator");
        workstream("ws-master", "master");
        awaiting.add("ws-deleted");
        pullRequests.put("feature/long-lived", List.of(pr(1, "closed", "2026-10-12T05:00:00Z")));
        pullRequests.put("feature/orchestration", List.of(pr(2, "closed", "2026-10-12T05:00:00Z")));
        pullRequests.put("master", List.of(pr(3, "closed", "2026-10-12T05:00:00Z")));

        reconciler().reconcileOnce();

        assertEquals(List.of(), lookedUp);
        assertEquals(List.of(), closeOuts);
    }

    /** Builds a reconciler over the stub tracker, the known workstreams and the pull-request map. */
    private TaskCloseOutReconciler reconciler() {
        TrackerService service = new TrackerService(
                "http://127.0.0.1:" + tracker.getAddress().getPort() + "/", "tok");
        return new TaskCloseOutReconciler(service, workstreams::get, (ownerRepo, branch) -> {
            assertEquals("almostrealism/common", ownerRepo);
            lookedUp.add(branch);
            return pullRequests.get(branch);
        });
    }

    /** Registers a feature workstream on {@code branch} whose task awaits close-out. */
    private Workstream workstream(String id, String branch) {
        Workstream ws = new Workstream(id, "C_" + id, "#" + id);
        ws.setRepoUrl("https://github.com/almostrealism/common.git");
        ws.setDefaultBranch(branch);
        ws.setBaseBranch("master");
        workstreams.put(id, ws);
        awaiting.add(id);
        return ws;
    }

    /** A pull request as GitHub lists it. */
    private static PullRequest pr(int number, String state, String mergedAt) throws IOException {
        String merged = mergedAt == null ? "null" : "\"" + mergedAt + "\"";
        return new PullRequest(MAPPER.readTree("{\"number\":" + number + ",\"state\":\"" + state
                + "\",\"merged_at\":" + merged + "}"));
    }

    /** Answers the tracker stub's {@code /v1/close-out}: lists on GET, records on POST. */
    private void answer(HttpExchange exchange) throws IOException {
        trackerAuth = exchange.getRequestHeaders().getFirst("Authorization");
        String reply;
        if ("POST".equals(exchange.getRequestMethod())) {
            Map<?, ?> body = MAPPER.readValue(exchange.getRequestBody(), Map.class);
            closeOuts.add(body.get("workstream_id") + "=" + body.get("outcome"));
            reply = "{\"ok\":true,\"task_ids\":[\"task-of-" + body.get("workstream_id") + "\"]}";
        } else {
            reply = "{\"ok\":true,\"workstream_ids\":" + MAPPER.writeValueAsString(awaiting) + "}";
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
