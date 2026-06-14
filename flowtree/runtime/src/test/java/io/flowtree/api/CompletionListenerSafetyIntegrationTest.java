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
import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.CompletionListenerFanout;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Safety integration tests for the completion-listener feature.
 *
 * <p>These tests spin up a real {@link FlowTreeApiEndpoint} and
 * wire a {@link CompletionListenerFanout} backed by a recording
 * server, then exercise the four safety ceilings end to end
 * (registration, fan-out, kill switch, depth, coalescing). They
 * are the load-bearing tests in the completion-listener plan: a
 * missing or weakened ceiling is a real safety defect, not a
 * test-side false positive.</p>
 *
 * <p>These tests run unconditionally — no {@code @TestDepth}
 * filtering. Safety tests must always run on CI; a ceiling test
 * that is silently gated by depth is a coverage gap and a
 * false-confidence risk for the cascade's bound.</p>
 */
public class CompletionListenerSafetyIntegrationTest extends TestSuiteBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** API endpoint under test, started on an ephemeral port. */
    private FlowTreeApiEndpoint endpoint;
    /** Slack notifier that owns the registered workstreams. */
    private SlackNotifier notifier;
    /** Test server that records every {@code addTask} invocation. */
    private RecordingServer server;
    /** Listening port assigned by NanoHTTPD. */
    private int port;
    /** Fanout under test; rewired per test for the kill-switch case. */
    private CompletionListenerFanout fanout;
    /** Test clock advanced by tests to exercise window / coalesce logic. */
    private AtomicLong clock;

    /**
     * Spins up the controller, the recording server, and the
     * fanout on an ephemeral port. Each test gets a clean
     * notifier (no workstreams registered) and a fresh in-memory
     * coalesce / window state. The endpoint's
     * {@code acceptAutomatedJobs} gate is the kill switch the
     * fanout checks first; the default constructor leaves it
     * closed (defence-in-depth), but every test in this class
     * other than {@code killSwitchHaltsCascade} needs the gate
     * open, so setUp opens it. The kill-switch test flips it
     * closed mid-test and back at the end.
     */
    @Before
    public void setUp() throws IOException {
        notifier = new SlackNotifier(null);
        endpoint = new FlowTreeApiEndpoint(0, notifier);
        server = new RecordingServer();
        clock = new AtomicLong(0L);
        fanout = new CompletionListenerFanout(
                () -> endpoint.isAcceptAutomatedJobs(),
                () -> {
                    Map<String, Workstream> map = new HashMap<>();
                    for (String id : notifier.getWorkstreams().keySet()) {
                        Workstream w = notifier.getWorkstream(id);
                        if (w != null) map.put(id, w);
                    }
                    return map;
                },
                server,
                null,
                wsId -> "http://localhost:" + port + "/api/workstreams/" + wsId,
                null, null, null, id -> null, null,
                wsId -> notifier.getWorkstream(wsId) != null ? notifier : null,
                clock::get);
        endpoint.setServer(server);
        endpoint.setCompletionListenerFanout(fanout);
        // Open the kill switch so the fanout's first check
        // (acceptSupplier.isAccepting()) does not silently drop
        // every wake-up. The kill-switch test in this class
        // re-closes the gate mid-test to verify the kill path.
        endpoint.setAcceptAutomatedJobs(true);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();
    }

    /**
     * Stops the API endpoint between tests so the ephemeral port
     * is released and a fresh port is allocated next time. The
     * recording server has no resources to release; the
     * notifier is local to each test.
     */
    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
    }

    /**
     * SAFETY TEST: a flood of completions on a single worker
     * workstream must NOT flood the orchestrator. The
     * {@code maxWakeUpsPerWindow} ceiling (6 per 600s) is the
     * load-bearing guard; without it, the orchestrator would be
     * woken once per completion and the loop would run
     * unconstrained. The test fires {@code cap + 2} completions
     * inside the sliding window, so the ceiling rejects the
     * excess and exactly {@code cap} wake-ups are submitted.
     *
     * <p>The window is a sliding counter (not a fixed bucket) so
     * a long quiet stretch does not accumulate budget; the unit
     * test {@code windowEvictsOldEntries} in
     * {@code CompletionListenerFanoutTest} exercises the eviction
     * path. This integration test stays inside one window so the
     * expected count is the per-window cap, not a multiple of
     * it.</p>
     */
    @Test(timeout = 15000)
    public void runawayCeilingStopsSpawning() throws Exception {
        Workstream orchestrator = register("feature/orch", "orch");
        Workstream worker = register("feature/worker", "worker");
        String orchId = orchestrator.getWorkstreamId();
        String workerId = worker.getWorkstreamId();
        worker.setCompletionListeners(Collections.singletonList(orchId));
        notifier.registerWorkstream(worker);
        // The orchestrator is itself a listener target; no
        // completion-listener list needed (we are the listener).
        int cap = CompletionListenerFanout.DEFAULT_MAX_WAKE_UPS_PER_WINDOW;
        long coalesceMs = CompletionListenerFanout.DEFAULT_COALESCE_WINDOW_SECONDS * 1000L;
        // Fire `cap + 2` completions within the first window
        // (each separated by > coalesceMs so coalescing does
        // not collapse them). The first `cap` should fire; the
        // remaining 2 should be dropped by the per-listener
        // window ceiling.
        for (int i = 0; i < cap + 2; i++) {
            clock.set((long) i * (coalesceMs + 1));
            fanout.fanout(workerId,
                    JobCompletionEvent.success("j-burst-" + i, "flood test"));
        }
        // Without the ceiling, cap+2 wake-ups would have fired.
        // With it, exactly `cap` fire; the rest are dropped.
        assertEquals("flood ceiling must cap wake-ups at " + cap,
                cap, server.added.size());
    }

    /**
     * SAFETY TEST: with the kill switch engaged
     * ({@code acceptAutomatedJobs=false}), no wake-up jobs are
     * spawned regardless of how many completions fire. The
     * existing kill switch is the documented operator lever to
     * halt the cascade globally; this test asserts the fan-out
     * path respects it.
     */
    @Test(timeout = 15000)
    public void killSwitchHaltsCascade() throws Exception {
        Workstream orchestrator = register("feature/orch-ks", "orch-ks");
        Workstream worker = register("feature/worker-ks", "worker-ks");
        String orchId = orchestrator.getWorkstreamId();
        String workerId = worker.getWorkstreamId();
        worker.setCompletionListeners(Collections.singletonList(orchId));
        notifier.registerWorkstream(worker);
        // With switch on: 6 completions -> cap wake-ups.
        long coalesceMs = CompletionListenerFanout.DEFAULT_COALESCE_WINDOW_SECONDS * 1000L;
        for (int i = 0; i < 6; i++) {
            clock.set((long) i * (coalesceMs + 1));
            fanout.fanout(workerId,
                    JobCompletionEvent.success("j-pre-" + i, "pre-kill"));
        }
        int preCount = server.added.size();
        assertTrue("pre-kill switch should fire at least one wake-up",
                preCount > 0);
        // Flip the switch.
        endpoint.setAcceptAutomatedJobs(false);
        server.added.clear();
        for (int i = 0; i < 20; i++) {
            clock.set(coalesceMs * 10L + (long) i * (coalesceMs + 1));
            fanout.fanout(workerId,
                    JobCompletionEvent.success("j-post-" + i, "post-kill"));
        }
        assertEquals("kill switch must halt all wake-ups",
                0, server.added.size());
    }

    /**
     * SAFETY TEST: cycle creation is rejected at config time
     * with a 400 response and an error message that begins with
     * {@code cycle:}. The cycle check runs BEFORE the
     * workstream is registered, so a misconfigured graph cannot
     * exist in the first place.
     */
    @Test(timeout = 10000)
    public void cycleConfigRejected() throws Exception {
        Workstream a = register("feature/a", "a");
        Workstream b = register("feature/b", "b");
        a.setCompletionListeners(Collections.singletonList(
                b.getWorkstreamId()));
        notifier.registerWorkstream(a);
        // Try to update B to list A; that closes A -> B -> A.
        String body = "{\"completionListeners\":[\""
                + a.getWorkstreamId() + "\"]}";
        HttpURLConnection conn = openPost(
                "/api/workstreams/" + b.getWorkstreamId() + "/update", body);
        assertEquals(400, conn.getResponseCode());
        JsonNode err = MAPPER.readTree(readErrorBody(conn));
        String msg = err.get("error").asText();
        assertTrue("Error must start with 'cycle:': " + msg,
                msg.startsWith("cycle:"));
        // The path includes A and B.
        assertTrue("Error must name A: " + msg, msg.contains(a.getWorkstreamId()));
        assertTrue("Error must name B: " + msg, msg.contains(b.getWorkstreamId()));
    }

    /**
     * SAFETY TEST: self-listing is rejected at config time with
     * a 400 response and an error message that begins with
     * {@code self-listing:}. The most common cycle-by-accident
     * case has its own error wording.
     */
    @Test(timeout = 10000)
    public void selfListingRejected() throws Exception {
        Workstream a = register("feature/self", "self");
        // Update A to list A.
        String body = "{\"completionListeners\":[\""
                + a.getWorkstreamId() + "\"]}";
        HttpURLConnection conn = openPost(
                "/api/workstreams/" + a.getWorkstreamId() + "/update", body);
        assertEquals(400, conn.getResponseCode());
        JsonNode err = MAPPER.readTree(readErrorBody(conn));
        String msg = err.get("error").asText();
        assertTrue("Error must start with 'self-listing:': " + msg,
                msg.startsWith("self-listing:"));
        assertTrue("Error must name the workstream: " + msg,
                msg.contains(a.getWorkstreamId()));
    }

    /**
     * SAFETY TEST: the inert default — a workstream with no
     * listeners — produces no wake-ups at all, no matter how
     * many completions fire. This is the v0 behavior that the
     * feature must preserve.
     */
    @Test(timeout = 10000)
    public void inertDefaultSpawnsNothing() throws Exception {
        Workstream worker = register("feature/inert", "inert");
        for (int i = 0; i < 50; i++) {
            fanout.fanout(worker.getWorkstreamId(),
                    JobCompletionEvent.success("j-inert-" + i, "inert"));
        }
        assertEquals("inert workstream must spawn no wake-ups",
                0, server.added.size());
    }

    /**
     * SAFETY TEST: the wake-up job's prompt instructs the
     * listener to reconcile the FULL state of every workstream
     * it has delegated to, not just the specific completion
     * mentioned. The reconciliation invariant is what makes
     * dropping / coalescing wake-ups lossless.
     */
    @Test(timeout = 10000)
    public void wakeUpJobPromptInstructsFullReconciliation() throws Exception {
        Workstream orchestrator = register("feature/orch-prompt", "orch-prompt");
        Workstream worker = register("feature/worker-prompt", "worker-prompt");
        worker.setCompletionListeners(Collections.singletonList(
                orchestrator.getWorkstreamId()));
        notifier.registerWorkstream(worker);
        fanout.fanout(worker.getWorkstreamId(),
                JobCompletionEvent.success("j-prompt", "worker did a thing"));
        assertEquals(1, server.added.size());
        CodingAgentJob.Factory factory =
                (CodingAgentJob.Factory) server.added.get(0);
        List<String> prompts = factory.getPrompts();
        String prompt = prompts.get(0);
        assertTrue("Prompt must reference workstream_get_job: " + prompt,
                prompt.contains("workstream_get_job"));
        assertTrue("Prompt must reference workstream_context: " + prompt,
                prompt.contains("workstream_context"));
        assertTrue("Prompt must mention the reconciliation invariant: " + prompt,
                prompt.contains("reconcile the full state"));
        assertTrue("Prompt must carry the chain ID: " + prompt,
                prompt.contains("ch-"));
    }

    // ----- helpers -----

    /**
     * Registers a minimal workstream on the live notifier. Each
     * test invokes this twice (orchestrator + worker, etc.) so
     * the helper keeps the boilerplate down.
     */
    private Workstream register(String branch, String channelName) {
        Workstream ws = new Workstream(null, channelName);
        ws.setDefaultBranch(branch);
        notifier.registerWorkstream(ws);
        return ws;
    }

    /**
     * Opens a POST connection with a JSON body. Returns the
     * connection so the test can read the response (or error)
     * stream and assert on it.
     */
    private HttpURLConnection openPost(String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    /**
     * Reads the error stream from a connection whose response
     * code was non-2xx; the 400-path tests assert against the
     * JSON in the body, and that JSON is on the error stream.
     */
    private static String readErrorBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Test-only server that records every {@code addTask}
     * invocation. The fan-out's safety logic only cares about
     * the count of wake-ups actually submitted, so this is
     * sufficient.
     */
    private static class RecordingServer extends Server {
        /** Captured job factories in submission order. */
        final List<JobFactory> added = new ArrayList<>();
        /**
         * Constructs the recording server with port 0 so the
         * superclass skips binding a real listening socket; the
         * integration tests never start the accept loop, and a
         * fixed port would collide between test cases.
         */
        RecordingServer() throws IOException {
            // Port 0 tells the Server constructor to skip binding a
            // listening socket; the integration tests never start
            // the accept loop, so a real port is unnecessary and
            // would collide between the many test cases that each
            // construct their own RecordingServer.
            super(serverPropertiesWithEphemeralPort());
        }
        @Override
        public boolean addTask(JobFactory task) {
            added.add(task);
            return true;
        }
    }

    /**
     * Builds a {@link Properties} bag configured to skip server-socket
     * binding (port 0) so the recording-server fake can be constructed
     * any number of times in the same JVM without port collisions.
     */
    private static Properties serverPropertiesWithEphemeralPort() {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        return p;
    }
}
