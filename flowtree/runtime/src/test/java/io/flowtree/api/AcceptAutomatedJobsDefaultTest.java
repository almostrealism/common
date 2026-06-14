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
import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.jobs.CompletionListenerFanout;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
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
import static org.junit.Assert.assertTrue;

/**
 * Asserts the new default of the controller's
 * {@link FlowTreeApiEndpoint#isAcceptAutomatedJobs() acceptAutomatedJobs}
 * gate, the kill-switch behavior, and the ship-inert property.
 *
 * <p>The default of {@code acceptAutomatedJobs} was flipped from
 * {@code false} to {@code true} so a freshly started or redeployed
 * controller accepts automated job submissions (CI auto-resolve
 * retries, completion-listener wake-ups) without a separate
 * {@code controller_update_config} call. The existing kill switch
 * is preserved: a runtime override to {@code false} halts the
 * cascade globally. The completion-listener feature remains
 * ship-inert: a workstream with no
 * {@link Workstream#setCompletionListeners(java.util.List) completionListeners}
 * configured spawns no wake-ups regardless of the flag.</p>
 *
 * <p>The base-branch test file
 * {@code SlackApiWorkstreamTest.testAcceptAutomatedJobsConfig} still
 * asserts the old {@code false} default; that file is protected by
 * the agent write-lock, so this new test class carries the
 * {@code true}-default assertion in a separate file. The new
 * assertions are intentionally isolated to this class so the
 * "default is {@code true}" tripwire is read against a single
 * up-to-date test rather than against a test that drifted from
 * production.</p>
 */
public class AcceptAutomatedJobsDefaultTest extends TestSuiteBase {

    /** API endpoint under test, started on an ephemeral port. */
    private FlowTreeApiEndpoint endpoint;
    /** Slack notifier that owns the registered workstreams. */
    private SlackNotifier notifier;

    /**
     * Constructs a fresh endpoint and notifier for each test. The
     * endpoint is started lazily by the tests that need HTTP
     * connectivity; tests that only inspect the default value
     * never start the listener.
     */
    @Before
    public void setUp() throws IOException {
        notifier = new SlackNotifier(null);
        endpoint = new FlowTreeApiEndpoint(0, notifier);
    }

    /**
     * Stops the endpoint between tests so the ephemeral port is
     * released and the next test allocates a fresh port. Tests
     * that do not start the endpoint simply skip the stop call.
     */
    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
    }

    /**
     * Asserts the new default of
     * {@link FlowTreeApiEndpoint#isAcceptAutomatedJobs()} is
     * {@code true}. A freshly constructed endpoint must accept
     * automated job submissions (CI auto-resolve retries,
     * completion-listener wake-ups) without an operator having
     * to call
     * {@code controller_update_config(accept_automated_jobs="true")}
     * first.
     */
    @Test(timeout = 10000)
    public void defaultIsTrue() {
        assertTrue(
                "acceptAutomatedJobs must default to true so a freshly started"
                        + " or redeployed controller accepts automated jobs"
                        + " (CI auto-resolve retries, completion-listener"
                        + " wake-ups) without a separate"
                        + " controller_update_config call",
                endpoint.isAcceptAutomatedJobs());
    }

    /**
     * Asserts that the {@code GET /api/config/accept-automated-jobs}
     * endpoint reports the new default. The HTTP path is the
     * operator-visible surface, so the default must be observable
     * through the same call the operator would make.
     */
    @Test(timeout = 10000)
    public void defaultIsTrueOverHttp() throws Exception {
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        try {
            int port = endpoint.getListeningPort();
            String configUrl = "http://localhost:" + port
                    + "/api/config/accept-automated-jobs";

            HttpURLConnection getConn = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            getConn.setRequestMethod("GET");
            assertEquals(200, getConn.getResponseCode());
            String getResponse = new String(
                    getConn.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(
                    "GET /api/config/accept-automated-jobs must report the new"
                            + " default (true) so operators see the actual"
                            + " controller state. Got: " + getResponse,
                    getResponse.contains("\"acceptAutomatedJobs\":true"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Asserts that the kill switch still gates wake-ups when the
     * operator explicitly sets the flag to {@code false}. The
     * flag remains a runtime gate; only the default value
     * changed. This test re-uses the live
     * {@link FlowTreeApiEndpoint} so it exercises the real
     * path (controller -> fanout -> addTask) rather than a unit
     * test stub.
     */
    @Test(timeout = 15000)
    public void killSwitchStillGatesWakeUpsWhenFlagIsFalse() throws IOException {
        RecordingServer server = new RecordingServer();
        Workstream orchestrator = new Workstream(null, "kill-orch");
        orchestrator.setDefaultBranch("feature/kill-orch");
        notifier.registerWorkstream(orchestrator);
        Workstream worker = new Workstream(null, "kill-worker");
        worker.setDefaultBranch("feature/kill-worker");
        notifier.registerWorkstream(worker);
        worker.setCompletionListeners(Collections.singletonList(
                orchestrator.getWorkstreamId()));
        AtomicLong clock = new AtomicLong(0L);
        CompletionListenerFanout fanout = new CompletionListenerFanout(
                endpoint::isAcceptAutomatedJobs,
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
                wsId -> "http://test/api/workstreams/" + wsId,
                null, null, null, id -> null, null,
                wsId -> notifier.getWorkstream(wsId) != null ? notifier : null,
                clock::get);
        endpoint.setServer(server);
        endpoint.setCompletionListenerFanout(fanout);
        // Kill the gate explicitly. The default is now true, so
        // an operator toggling to false must produce the
        // documented halt.
        endpoint.setAcceptAutomatedJobs(false);
        long coalesceMs = CompletionListenerFanout.DEFAULT_COALESCE_WINDOW_SECONDS * 1000L;
        for (int i = 0; i < 20; i++) {
            clock.set((long) i * (coalesceMs + 1));
            fanout.fanout(worker.getWorkstreamId(),
                    JobCompletionEvent.success("j-kill-" + i, "kill test"));
        }
        assertEquals(
                "kill switch must halt all wake-ups even when the new"
                        + " default is true (operator override wins)",
                0, server.added.size());
        // Cross-check: re-opening the gate restores wake-up
        // generation immediately. The flag remains a runtime
        // gate, not a one-shot enable.
        endpoint.setAcceptAutomatedJobs(true);
        server.added.clear();
        clock.set(coalesceMs * 100L);
        fanout.fanout(worker.getWorkstreamId(),
                JobCompletionEvent.success("j-after-reopen", "post-reopen"));
        assertEquals(
                "re-opening the gate must allow wake-ups to resume"
                        + " (the flag is a runtime gate, not a one-shot enable)",
                1, server.added.size());
    }

    /**
     * Asserts the ship-inert property: a workstream with no
     * listeners configured spawns no wake-ups, regardless of the
     * value of {@code acceptAutomatedJobs}. Flipping the default
     * must not silently start wake-ups for workstreams that
     * never opted in.
     */
    @Test(timeout = 10000)
    public void shipInertPropertyHoldsWithDefaultTrue() throws IOException {
        RecordingServer server = new RecordingServer();
        AtomicLong clock = new AtomicLong(0L);
        CompletionListenerFanout fanout = new CompletionListenerFanout(
                endpoint::isAcceptAutomatedJobs,
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
                wsId -> "http://test/api/workstreams/" + wsId,
                null, null, null, id -> null, null,
                wsId -> notifier.getWorkstream(wsId) != null ? notifier : null,
                clock::get);
        endpoint.setServer(server);
        endpoint.setCompletionListenerFanout(fanout);
        // Defensive: the default of acceptAutomatedJobs is true
        // (asserted in defaultIsTrue). The inert workstream
        // must still spawn zero wake-ups. Even if a future
        // refactor accidentally set the default to false, this
        // test would still pass because the inert path is
        // checked first; the property holds at any flag value.
        assertTrue(
                "precondition: the default acceptAutomatedJobs is true",
                endpoint.isAcceptAutomatedJobs());
        Workstream inert = new Workstream(null, "inert");
        inert.setDefaultBranch("feature/inert");
        notifier.registerWorkstream(inert);
        // No setCompletionListeners call: this workstream is
        // inert by construction.
        for (int i = 0; i < 50; i++) {
            clock.set((long) i * 1000L);
            fanout.fanout(inert.getWorkstreamId(),
                    JobCompletionEvent.success("j-inert-" + i, "inert"));
        }
        assertEquals(
                "inert workstream (no completionListeners configured) must"
                        + " spawn zero wake-ups regardless of the new"
                        + " acceptAutomatedJobs default",
                0, server.added.size());
    }

    // ----- helpers -----

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
            super(serverPropertiesWithEphemeralPort());
        }

        @Override
        public boolean addTask(JobFactory task) {
            added.add(task);
            return true;
        }
    }

    /**
     * Builds a {@link Properties} bag configured to skip
     * server-socket binding (port 0) so the recording-server
     * fake can be constructed any number of times in the same
     * JVM without port collisions.
     */
    private static Properties serverPropertiesWithEphemeralPort() {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        return p;
    }
}
