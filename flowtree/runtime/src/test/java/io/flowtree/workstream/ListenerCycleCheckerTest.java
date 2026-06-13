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

package io.flowtree.workstream;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * Unit tests for {@link ListenerCycleChecker}, the config-time
 * cycle detection for the completion-listener graph. The acceptance
 * criteria for this checker are documented in
 * <em>docs/plans/COMPLETION_LISTENERS.md</em> §2.1.1 (cycle
 * rejection at config time).
 */
public class ListenerCycleCheckerTest extends TestSuiteBase {

    /**
     * Builds a workstream with a fixed ID and an optional listener
     * list. The returned object is the {@code self} workstream in
     * cycle-check tests, or a peer when constructed in
     * {@link #map(Workstream...)}.
     */
    private static Workstream ws(String id, String... listeners) {
        Workstream w = new Workstream(id, "C_" + id, "#" + id);
        if (listeners != null && listeners.length > 0) {
            w.setCompletionListeners(Arrays.asList(listeners));
        }
        return w;
    }

    /**
     * Builds a {@code Map<String, Workstream>} from the supplied
     * workstreams for the {@code allWorkstreams} argument of
     * {@link ListenerCycleChecker#check}. Each test passes the
     * minimum graph it needs to exercise the cycle path.
     */
    private static Map<String, Workstream> map(Workstream... ws) {
        Map<String, Workstream> m = new HashMap<>();
        for (Workstream w : ws) m.put(w.getWorkstreamId(), w);
        return m;
    }

    /**
     * An empty proposed listener list is always valid (the inert
     * default, no fan-out).
     */
    @Test(timeout = 10000)
    public void emptyListenersReturnsEmpty() {
        List<String> result = ListenerCycleChecker.check("A", null, map());
        assertTrue("null listeners must be valid", result.isEmpty());
        result = ListenerCycleChecker.check("A", Collections.emptyList(), map());
        assertTrue("empty listeners must be valid", result.isEmpty());
    }

    /**
     * A single listener edge (no cycle) is valid.
     */
    @Test(timeout = 10000)
    public void singleNonSelfListenerReturnsEmpty() {
        Map<String, Workstream> graph = map(ws("A"), ws("B"));
        List<String> result = ListenerCycleChecker.check("A",
                Collections.singletonList("B"), graph);
        assertTrue("A -> B is not a cycle", result.isEmpty());
    }

    /**
     * The most common cycle form — a workstream listing itself —
     * is rejected with the {@code self-listing} prefix and the
     * workstream's own ID. This is the {@code (u, u)} case the
     * design plan §2.1.3 calls out explicitly.
     */
    @Test(timeout = 10000)
    public void selfListingReturnsPath() {
        Map<String, Workstream> graph = map(ws("A"));
        List<String> result = ListenerCycleChecker.check("A",
                Collections.singletonList("A"), graph);
        assertNotNull("self-listing must produce a path", result);
        assertEquals(2, result.size());
        assertEquals("self-listing", result.get(0));
        assertEquals("A", result.get(1));
    }

    /**
     * A two-node mutual listing (A -> B, B -> A) is rejected as a
     * cycle. The DFS should walk the active stack from A to B
     * (because B is in A's listener list), then encounter the
     * back-edge B -> A and report the closed path.
     */
    @Test(timeout = 10000)
    public void mutualListingReturnsPath() {
        Map<String, Workstream> graph = map(
                ws("A", "B"),
                ws("B", "A"));
        List<String> result = ListenerCycleChecker.check("A",
                Collections.singletonList("B"), graph);
        assertNotNull("mutual listing must be rejected", result);
        assertEquals("cycle", result.get(0));
        // The reconstructed path should include A and B (cycle form).
        assertTrue("path must include A: " + result, result.contains("A"));
        assertTrue("path must include B: " + result, result.contains("B"));
    }

    /**
     * A three-node cycle (A -> B, B -> C, C -> A) is rejected.
     */
    @Test(timeout = 10000)
    public void threeNodeCycleReturnsPath() {
        Map<String, Workstream> graph = map(
                ws("A", "B"),
                ws("B", "C"),
                ws("C", "A"));
        List<String> result = ListenerCycleChecker.check("A",
                Collections.singletonList("B"), graph);
        assertNotNull("3-node cycle must be rejected", result);
        assertEquals("cycle", result.get(0));
        // Path must reference all three nodes.
        assertTrue("path must include A: " + result, result.contains("A"));
        assertTrue("path must include B: " + result, result.contains("B"));
        assertTrue("path must include C: " + result, result.contains("C"));
    }

    /**
     * Two listeners from the same source with no edges back is
     * not a cycle and must be valid.
     */
    @Test(timeout = 10000)
    public void disjointListenersReturnEmpty() {
        Map<String, Workstream> graph = map(
                ws("A"),
                ws("B"),
                ws("C"));
        List<String> result = ListenerCycleChecker.check("A",
                Arrays.asList("B", "C"), graph);
        assertTrue("A -> B, A -> C with no return edges is not a cycle",
                result.isEmpty());
    }

    /**
     * A diamond (A -> B, A -> C, B -> D, C -> D) is not a cycle.
     */
    @Test(timeout = 10000)
    public void diamondReturnsEmpty() {
        Map<String, Workstream> graph = map(
                ws("A", "B", "C"),
                ws("B", "D"),
                ws("C", "D"),
                ws("D"));
        List<String> result = ListenerCycleChecker.check("A",
                Arrays.asList("B", "C"), graph);
        assertTrue("diamond shape is not a cycle: " + result, result.isEmpty());
    }

    /**
     * The proposed-listener case is validated against the proposed
     * state of {@code self}, not the pre-update state. A workstream
     * with an empty current listener list that proposes a single
     * non-self listener is valid even when the live graph contains
     * other workstreams with their own listeners.
     */
    @Test(timeout = 10000)
    public void proposedListenersAreCheckedBeforePersist() {
        Workstream a = ws("A");
        Workstream b = ws("B", "C");
        Workstream c = ws("C");
        Map<String, Workstream> graph = map(a, b, c);
        List<String> result = ListenerCycleChecker.check("A",
                Collections.singletonList("B"), graph);
        assertTrue("A -> B is valid: " + result, result.isEmpty());
    }

    /**
     * An attempt to register a 2-node cycle as an update
     * (the workstream's current listener list is the input) is
     * rejected: the DFS sees the proposed edge AND the existing
     * return edge, so the cycle is detected even though the
     * proposed change alone is a single forward edge.
     */
    @Test(timeout = 10000)
    public void updateThatCompletesCycleIsRejected() {
        Workstream a = ws("A");
        Workstream b = ws("B", "A");
        Map<String, Workstream> graph = map(a, b);
        // The caller is updating A; current A has no listeners, but
        // we will validate the proposed set A -> B. The DFS in
        // ListenerCycleChecker walks the LIVE graph, so the existing
        // B -> A edge is visible, and the A -> B edge under test
        // closes the cycle. The cycle MUST be detected.
        List<String> result = ListenerCycleChecker.check("A",
                Collections.singletonList("B"), graph);
        assertNotNull("update that closes a cycle must be rejected: " + result,
                result);
        assertEquals("cycle", result.get(0));
    }

    /**
     * The workstream-being-validated may be absent from the
     * supplied {@code allWorkstreams} map (e.g. the very first
     * register call). The proposed listeners are still checked
     * correctly: a self-edge is detected, an outward edge to a
     * registered listener is valid, an outward edge to a phantom
     * listener is silently allowed (the runtime tolerates missing
     * listeners at fan-out time, which the plan documents).
     */
    @Test(timeout = 10000)
    public void selfEntryInPhantomGraphHandled() {
        // Empty graph; only the proposed edges are visible.
        // Self-edge must still be caught.
        List<String> result = ListenerCycleChecker.check("A",
                Collections.singletonList("A"), Collections.emptyMap());
        assertNotNull(result);
        assertEquals("self-listing", result.get(0));
        assertEquals("A", result.get(1));

        // Outward edge to a not-yet-registered listener is valid.
        result = ListenerCycleChecker.check("A",
                Collections.singletonList("B"), Collections.emptyMap());
        assertTrue(result.isEmpty());
    }
}
