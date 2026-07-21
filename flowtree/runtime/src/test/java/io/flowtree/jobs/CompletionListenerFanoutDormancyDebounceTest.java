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

package io.flowtree.jobs;

import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the per-listener wake-up debounce and the
 * listener-dormancy gate added to {@link CompletionListenerFanout}
 * to fix the production defect where an orchestrator workstream was
 * woken in an unbounded loop of no-op reconciles, and where two
 * concurrent orchestrator sessions wrote the same durable
 * log/branch state and produced push conflicts.
 *
 * <p>The two protections are:</p>
 *
 * <ol>
 *   <li><strong>Per-listener debounce / single-in-flight-wake
 *       invariant</strong> ({@link
 *       CompletionListenerFanout#DEFAULT_DEBOUNCE_SECONDS}). After a
 *       wake-up is submitted to a listener, no further wake-up is
 *       submitted to that listener until the debounce expires. The
 *       debounce is cleared by
 *       {@link CompletionListenerFanout#notifyListenerWakeUpCompleted}
 *       when the wake-up's status event arrives, so a fast-completing
 *       wake-up does not artificially extend the window.</li>
 *   <li><strong>Listener dormancy</strong> ({@link
 *       Workstream#isDormantForCompletionListeners()}). The listener
 *       itself declares "no further wake-ups until something
 *       materially new happens"; the fan-out drops every wake-up
 *       aimed at a dormant listener. Cleared via
 *       {@code workstream_update_config} when a genuinely new event
 *       (human action, fresh ticket, etc.) unblocks work.</li>
 * </ol>
 *
 * <p>The tests deliberately use a 6-second production-shaped
 * debounce (vs. the 300-second default) so the suite runs in
 * seconds, not minutes. The safety properties being verified are
 * independent of the exact window length — what matters is "wake-ups
 * are bounded when nothing materially changed" and "wake-ups resume
 * when the debounce is cleared or the dormancy gate is lifted."</p>
 */
public class CompletionListenerFanoutDormancyDebounceTest extends TestSuiteBase {

    /** Test server that records every {@code addTask} invocation. */
    private RecordingServer server;
    /** Live workstream map visible to the fanout. */
    private Map<String, Workstream> workstreams;
    /** Test clock the fanout reads; advanced by tests to exercise the debounce. */
    private AtomicLong clockMillis;
    /** The fanout under test, created fresh in {@link #setUp()}. */
    private CompletionListenerFanout fanout;
    /** Debounce window used in this test class, in seconds. */
    private static final int TEST_DEBOUNCE_SECONDS = 6;

    /**
     * Wires a fresh fan-out with a 6-second debounce, an isolated
     * {@link RecordingServer}, and a controllable clock. The
     * debounce is shorter than the production 300-second default so
     * the suite finishes quickly; the safety properties under test
     * are independent of the exact window length.
     */
    @Before
    public void setUp() throws IOException {
        server = new RecordingServer();
        workstreams = new HashMap<>();
        clockMillis = new AtomicLong(0L);
        fanout = new CompletionListenerFanout(
                () -> true,
                () -> workstreams,
                server,
                null,
                wsId -> "http://test/api/workstreams/" + wsId,
                null, null, null, id -> null, null,
                null,
                clockMillis::get);
        fanout.setDebounceSeconds(TEST_DEBOUNCE_SECONDS);
    }

    /**
     * Builds a workstream with a fixed ID and an optional listener
     * list. The listener list is the {@code source -> listener}
     * edge: {@code source} lists {@code listeners} as its completion
     * listeners, so any completion on {@code source} wakes each
     * listener. The listener workstream (e.g. "B") can be marked
     * dormant via the returned instance.
     */
    private static Workstream ws(String id, String... listeners) {
        Workstream w = new Workstream(id, "C_" + id, "#" + id);
        w.setAllowedTools("Read");
        w.setDefaultBranch("feature/" + id);
        w.setMaxTurns(50);
        w.setMaxBudgetUsd(10.0);
        if (listeners != null && listeners.length > 0) {
            w.setCompletionListeners(Arrays.asList(listeners));
        }
        return w;
    }

    /** Builds a synthetic success event with the given job ID. */
    private static JobCompletionEvent success(String jobId) {
        return JobCompletionEvent.success(jobId, "test job " + jobId);
    }

    // --- Property (a): N rapid child completions -> at most 1 wake-up ---

    /**
     * Property (a) — single source: N completions in rapid
     * succession on the same source wake the listener at most
     * once. The first fires; subsequent completions within the
     * debounce window are dropped with the
     * {@code wakeup_listener_recently_woken} log line.
     */
    @Test(timeout = 10000)
    public void rapidSuccessiveCompletionsOnSameSourceCoalesceToOneWake() {
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", ws("B"));
        for (int i = 0; i < 10; i++) {
            clockMillis.set(i * 1_000L);
            fanout.fanout("A", success("j-" + i));
        }
        assertEquals("10 rapid completions must produce at most 1 wake-up",
                1, server.added.size());
    }

    /**
     * Property (a) — fan-in: N sources all listing the same
     * listener fire completions in a burst. The pre-fix behavior
     * was one wake-up per source — up to N concurrent orchestrator
     * sessions writing the same branch and producing push
     * conflicts. With the per-listener debounce, at most one
     * wake-up fires for the burst; subsequent source completions
     * are dropped with the {@code wakeup_listener_recently_woken}
     * log line.
     */
    @Test(timeout = 10000)
    public void fanInFromManySourcesCollapsesToOneWake() {
        // 10 distinct sources all list the same listener B.
        for (int i = 0; i < 10; i++) {
            String sourceId = "src-" + i;
            workstreams.put(sourceId, ws(sourceId, "B"));
        }
        workstreams.put("B", ws("B"));
        // Each source fires a completion within 1 second of the
        // previous — a tight fan-in burst.
        for (int i = 0; i < 10; i++) {
            clockMillis.set(i * 1_000L);
            fanout.fanout("src-" + i, success("j-src-" + i));
        }
        assertEquals("fan-in burst must collapse to 1 wake-up",
                1, server.added.size());
        // The fan-out's recorded debounce timestamp for B must be
        // the moment the single wake-up fired — useful for
        // confirming the dedup bookkeeping, and as a regression
        // guard against a future refactor that drops the timestamp.
        Long bLast = fanout.peekWakeUpDispatchedAt("B");
        assertNotNull("listener B must have a recorded debounce timestamp",
                bLast);
        assertEquals("the recorded timestamp is the moment of the single wake-up",
                0L, bLast.longValue());
    }

    /**
     * Property (a) — second wake-up is allowed after the debounce
     * expires. The test fires one wake-up, advances the clock past
     * the debounce, fires a second completion, and asserts the
     * second wake-up fires. This pins the "wake-ups resume after
     * the debounce" half of the contract — without it the debounce
     * would silently turn into a permanent block.
     */
    @Test(timeout = 10000)
    public void wakeUpFiresAgainAfterDebounceExpires() {
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", ws("B"));
        clockMillis.set(0L);
        fanout.fanout("A", success("j-1"));
        assertEquals(1, server.added.size());
        // Just before the debounce expires: still blocked.
        clockMillis.set((TEST_DEBOUNCE_SECONDS * 1000L) - 1L);
        fanout.fanout("A", success("j-2"));
        assertEquals("wake-up must still be blocked just before debounce expires",
                1, server.added.size());
        // Just after: allowed.
        clockMillis.set((TEST_DEBOUNCE_SECONDS * 1000L) + 1L);
        fanout.fanout("A", success("j-3"));
        assertEquals("wake-up must fire once the debounce expires",
                2, server.added.size());
    }

    // --- Property (b): concurrent wakes serialized ---

    /**
     * Property (b) — the per-listener debounce enforces a single
     * in-flight wake-up at a time. The fan-out records the
     * dispatch timestamp on the listener when the wake-up is
     * submitted; subsequent attempts inside the debounce see the
     * timestamp and drop. The implementation does not need a
     * separate lock for this property — the timestamp IS the
     * serialization — but the test pins the recorded timestamp
     * explicitly so a future refactor that swaps the debounce for
     * a true lock cannot accidentally drop the property.
     */
    @Test(timeout = 10000)
    public void concurrentWakesSerializedByDebounce() {
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", ws("B"));
        // Two completions on the same source at t=0: only the
        // first fires a wake-up; the second is dropped (coalesce
        // path). The recorded debounce timestamp is what blocks
        // any later wake-up until the debounce expires.
        clockMillis.set(0L);
        fanout.fanout("A", success("j-first"));
        fanout.fanout("A", success("j-second-coalesced"));
        assertEquals("first wake-up fires; second coalesced",
                1, server.added.size());
        Long bLast = fanout.peekWakeUpDispatchedAt("B");
        assertNotNull("listener B debounce timestamp must be recorded",
                bLast);
        assertEquals("recorded timestamp is the moment of the fired wake-up",
                0L, bLast.longValue());
        // A completion from a different source arrives at t=2s
        // (well inside the debounce). Without the debounce this
        // would have raced a second orchestrator session on the
        // same branch; with the debounce it is dropped.
        workstreams.put("C", ws("C", "B"));
        clockMillis.set(2_000L);
        fanout.fanout("C", success("j-other-source"));
        assertEquals("second source wake-up must be debounced",
                1, server.added.size());
    }

    /**
     * Property (b) — {@link
     * CompletionListenerFanout#notifyListenerWakeUpCompleted(String, String)
     * notifyListenerWakeUpCompleted} clears the debounce when the
     * wake-up job reports its terminal status. This is the
     * "wake-up completes quickly so the debounce does not
     * artificially extend" half of the serialization contract —
     * without it, a wake-up that completes in 5 seconds would
     * still block new wake-ups for the remainder of the
     * 300-second production default, which defeats the
     * "genuinely new event still wakes the orchestrator"
     * correctness property. The description prefix is what
     * distinguishes a wake-up completion from any other terminal
     * event on the listener workstream.
     */
    @Test(timeout = 10000)
    public void notifyListenerWakeUpCompletedClearsDebounce() {
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", ws("B"));
        clockMillis.set(0L);
        fanout.fanout("A", success("j-1"));
        assertEquals(1, server.added.size());
        assertNotNull("debounce must be recorded after dispatch",
                fanout.peekWakeUpDispatchedAt("B"));
        // Simulate the wake-up reporting its terminal status with
        // the wake-up description prefix.
        fanout.notifyListenerWakeUpCompleted("B", "wake-up: B <- A");
        assertNull("debounce must be cleared after the wake-up completes",
                fanout.peekWakeUpDispatchedAt("B"));
        // A new completion event arriving any time after the
        // clear should fire a fresh wake-up — even at t=0+1s,
        // inside what would have been the original debounce
        // window.
        clockMillis.set(1_000L);
        fanout.fanout("A", success("j-2"));
        assertEquals("new wake-up must fire after debounce clear",
                2, server.added.size());
    }

    /**
     * Property (b) — null/empty listenerId on
     * {@link CompletionListenerFanout#notifyListenerWakeUpCompleted}
     * is a no-op (the production caller in the status-event
     * handler may pass through values it has not validated; the
     * fan-out's contract is "never throw"). A description that
     * is not a wake-up prefix is also a no-op (only wake-up
     * completions should clear the debounce).
     */
    @Test(timeout = 10000)
    public void notifyListenerWakeUpCompletedIgnoresBadInputs() {
        fanout.notifyListenerWakeUpCompleted(null, "wake-up: x");
        fanout.notifyListenerWakeUpCompleted("", "wake-up: x");
        fanout.notifyListenerWakeUpCompleted("B", "regular job description");
        fanout.notifyListenerWakeUpCompleted("B", null);
        // No assertion on side effects: the no-op contract is
        // "don't throw." A subsequent dispatch still works.
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", ws("B"));
        clockMillis.set(0L);
        fanout.fanout("A", success("j-1"));
        assertEquals(1, server.added.size());
    }

    // --- Property (c): genuinely new event still wakes the listener ---

    /**
     * Property (c) — a completion arriving AFTER the debounce
     * window expires fires a fresh wake-up. This is the
     * "genuinely new event still wakes the listener" half of the
     * correctness contract. The test fires one wake-up, advances
     * the clock past the debounce, fires a new completion on the
     * same source/listener pair, and asserts the second wake-up
     * fires — the listener was woken by a real new event, not
     * dropped by a stuck debounce.
     */
    @Test(timeout = 10000)
    public void genuinelyNewEventAfterDebounceWakesListener() {
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", ws("B"));
        clockMillis.set(0L);
        fanout.fanout("A", success("j-1"));
        assertEquals(1, server.added.size());
        // Advance past the debounce.
        clockMillis.set((TEST_DEBOUNCE_SECONDS + 1) * 1000L);
        fanout.fanout("A", success("j-2-genuinely-new"));
        assertEquals("a new completion after the debounce must wake the listener",
                2, server.added.size());
        CodingAgentJob.Factory second = (CodingAgentJob.Factory) server.added.get(1);
        String prompt = second.getPrompts().get(0);
        assertTrue("second wake-up prompt must reference the new job id",
                prompt.contains("j-2-genuinely-new"));
    }

    // --- Listener dormancy ---

    /**
     * A listener in the dormant state drops every wake-up the
     * fan-out would otherwise submit to it, with a
     * {@code wakeup_listener_dormant} log line. The flag is the
     * listener's own declaration that it has no new work and
     * should not be woken by child completions.
     */
    @Test(timeout = 10000)
    public void dormantListenerDropsAllWakeUps() {
        Workstream listener = ws("B");
        listener.setDormantForCompletionListeners(true);
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", listener);
        for (int i = 0; i < 5; i++) {
            clockMillis.set(i * 1_000L);
            fanout.fanout("A", success("j-" + i));
        }
        assertEquals("dormant listener must receive 0 wake-ups",
                0, server.added.size());
    }

    /**
     * Clearing the dormancy flag (a human action or a different
     * workstream calling {@code workstream_update_config}) resumes
     * wake-ups on the next completion event. The test marks the
     * listener dormant, fires a completion (dropped), clears the
     * flag, fires a second completion (wakes the listener).
     */
    @Test(timeout = 10000)
    public void clearingDormancyResumesWakeUps() {
        Workstream listener = ws("B");
        listener.setDormantForCompletionListeners(true);
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", listener);
        clockMillis.set(0L);
        fanout.fanout("A", success("j-while-dormant"));
        assertEquals(0, server.added.size());
        // A genuinely new event after the dormancy flag is
        // cleared wakes the listener.
        listener.setDormantForCompletionListeners(false);
        clockMillis.set(1_000L);
        fanout.fanout("A", success("j-after-resume"));
        assertEquals("wake-up must resume once dormancy is cleared",
                1, server.added.size());
    }

    /**
     * Dormancy is checked BEFORE the debounce and the coalesce
     * bookkeeping so a dormant listener's dropped wake-ups do not
     * even consume coalesce state. The order is observable: a
     * completion on a dormant listener must not "prime" the
     * coalesce window for the first non-dormant listener. (This
     * is also why clearing dormancy does not retroactively fire a
     * wake-up — the source-side completion has already been
     * recorded; the listener's next wake is triggered by the next
     * completion event after the flag is cleared.)
     */
    @Test(timeout = 10000)
    public void dormantListenerDoesNotConsumeCoalesceWindow() {
        Workstream listener = ws("B");
        listener.setDormantForCompletionListeners(true);
        workstreams.put("A", ws("A", "B"));
        workstreams.put("B", listener);
        // Many completions while dormant. None should produce a
        // wake-up AND none should pollute the coalesce map.
        for (int i = 0; i < 5; i++) {
            clockMillis.set(i * 1_000L);
            fanout.fanout("A", success("j-dormant-" + i));
        }
        assertEquals("dormant listener must receive 0 wake-ups",
                0, server.added.size());
        // The (A, B) coalesce key must not exist — the dormancy
        // gate ran before the coalesce-bookkeeping step.
        assertTrue("dormant wake-ups must not prime the coalesce state",
                fanout.activeCoalesceKeys().isEmpty());
        // Now resume and fire one completion: it must produce a
        // wake-up (no leftover coalesce state to consume it).
        listener.setDormantForCompletionListeners(false);
        clockMillis.set(10_000L);
        fanout.fanout("A", success("j-after-resume"));
        assertEquals("first completion after resume must fire a wake-up",
                1, server.added.size());
    }

    /**
     * Round-trip: a {@link Workstream} marked dormant persists
     * through the YAML serializer's {@code toSummaryJson} path so
     * operators can see the dormancy state on
     * {@code workstream_list}. The fan-out's gate is a runtime
     * read of the boolean, so the serialization is what makes the
     * state observable to operators — without it the gate would
     * still work, but operators would have no way to see which
     * listeners are dormant.
     */
    @Test(timeout = 10000)
    public void dormantFlagAppearsInWorkstreamSummaryJson() {
        Workstream listener = ws("B");
        listener.setDormantForCompletionListeners(true);
        String json = listener.toSummaryJson();
        assertTrue("dormant flag must appear in summary JSON: " + json,
                json.contains("\"dormantForCompletionListeners\":true"));
        // Off-by-default round-trip: the flag must NOT appear in
        // the summary JSON for a non-dormant workstream, mirroring
        // the @JsonInclude(NON_DEFAULT) annotation on
        // WorkstreamConfig.WorkstreamEntry.
        Workstream active = ws("A");
        String activeJson = active.toSummaryJson();
        assertTrue("active workstream must not advertise the dormancy flag: "
                        + activeJson,
                !activeJson.contains("dormantForCompletionListeners"));
    }

    /**
     * Test-only fake {@link io.flowtree.Server} that records every
     * {@code addTask} invocation. Duplicated from
     * {@code CompletionListenerFanoutTest} (private there) so this
     * class can stand alone — the fanout's safety logic only
     * cares about the count of wake-ups actually submitted, so
     * this is sufficient.
     */
    private static class RecordingServer extends Server {
        /** Captured job factories in submission order. */
        final List<JobFactory> added = new ArrayList<>();
        /** Constructs the recording server on an ephemeral port. */
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
