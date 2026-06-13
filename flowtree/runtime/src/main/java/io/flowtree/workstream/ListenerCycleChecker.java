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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects cycles in the completion-listener graph defined by
 * {@link Workstream#getCompletionListeners()}.
 *
 * <p>The delegation pattern is intentionally a loop (worker -> orchestrator
 * -> worker -> ...) and the only thing that bounds it is the orchestrator
 * choosing to stop. The hard ceilings enforced at wake-up time bound cost
 * after a misconfiguration, but a true cycle (A->B->A or A->A) would
 * ping-pong forever even with ceilings in place. This checker rejects
 * cycles at config time so a misconfigured graph cannot exist in the
 * first place.</p>
 *
 * <p>The check is a single DFS over the live listener graph at config
 * time. The returned path includes the self-edge in the
 * {@code A -> A} case so the operator sees a precise error. A
 * diamond shape (A->B, A->C, B->D, C->D) is <strong>not</strong> a
 * cycle and is allowed.</p>
 *
 * <p>This class is stateless; the single static entry point is safe to
 * call from the request handler thread. Errors are reported by returning
 * a non-empty path; success is the empty path.</p>
 */
public final class ListenerCycleChecker {

    /**
     * Utility class; not instantiable.
     */
    private ListenerCycleChecker() {}

    /**
     * Checks the proposed listener list against the live listener graph
     * and returns a non-empty cycle path if a cycle would be created,
     * or an empty list if the configuration is valid.
     *
     * <p>Self-listing ({@code A -> A}) is detected as a cycle of length
     * 1 and rejected with an explicit {@code "self-listing"} prefix so
     * the error message can be matched by callers that want to
     * differentiate. Other cycles (A->B->A, A->B->C->A, etc.) are
     * reported with the {@code "cycle"} prefix and the full node
     * sequence.</p>
     *
     * @param selfId            the workstream being configured; may be
     *                          {@code null} for the no-self case (the
     *                          checker treats the proposed listeners as
     *                          edges of a new node)
     * @param proposedListeners the listener workstream IDs being added
     *                          to {@code selfId}; {@code null} is
     *                          treated as an empty list
     * @param allWorkstreams    the current live workstream map; the
     *                          checker walks the listener graph implied
     *                          by every entry's
     *                          {@link Workstream#getCompletionListeners()}
     * @return a non-empty cycle path on rejection (prefixed with
     *         {@code "self-listing"} for the self-edge case, or
     *         {@code "cycle"} for any other), or an empty list on
     *         success
     */
    public static List<String> check(String selfId,
                                     List<String> proposedListeners,
                                     Map<String, Workstream> allWorkstreams) {
        if (proposedListeners == null || proposedListeners.isEmpty()) {
            return Collections.emptyList();
        }
        if (allWorkstreams == null) {
            allWorkstreams = Collections.emptyMap();
        }

        // Self-listing is the most common form of the cycle, so check
        // it explicitly and report a clearer error message. The DFS
        // would also catch it (a 1-cycle), but the error wording the
        // plan asks for is "self-listing: workstream A cannot list
        // itself as a completion listener" — different from the
        // generic "cycle: A -> B -> A".
        if (selfId != null && !selfId.isEmpty()) {
            for (String listener : proposedListeners) {
                if (listener == null) continue;
                if (listener.equals(selfId)) {
                    List<String> path = new ArrayList<>(2);
                    path.add("self-listing");
                    path.add(selfId);
                    return path;
                }
            }
        }

        // Build the effective listener map: every live workstream
        // contributes its current listener list, except the workstream
        // being reconfigured, which contributes the PROPOSED list (the
        // new state we are validating).
        Map<String, List<String>> effective = new HashMap<>();
        for (Map.Entry<String, Workstream> e : allWorkstreams.entrySet()) {
            String id = e.getKey();
            Workstream ws = e.getValue();
            if (ws == null) {
                effective.put(id, Collections.emptyList());
                continue;
            }
            if (selfId != null && id.equals(selfId)) {
                effective.put(id, new ArrayList<>(proposedListeners));
            } else {
                List<String> current = ws.getCompletionListeners();
                effective.put(id, current == null
                        ? Collections.emptyList()
                        : new ArrayList<>(current));
            }
        }
        // The proposed self may not yet be in the live map (e.g. the
        // very first register call). Make it visible to the DFS so a
        // cycle that pivots through it is still caught.
        if (selfId != null && !selfId.isEmpty()
                && !effective.containsKey(selfId)) {
            effective.put(selfId, new ArrayList<>(proposedListeners));
        }

        // Standard iterative DFS with a per-node "currently-on-stack"
        // set so a back-edge into the active path is detected as a
        // cycle. The path is reconstructed from the parent map.
        Set<String> onStack = new HashSet<>();
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        List<String> cyclePath = null;

        for (String start : effective.keySet()) {
            if (visited.contains(start)) continue;
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            onStack.add(start);
            while (!stack.isEmpty() && cyclePath == null) {
                String node = stack.peek();
                List<String> listeners = effective.getOrDefault(node,
                        Collections.emptyList());
                boolean descended = false;
                for (String next : listeners) {
                    if (next == null || next.isEmpty()) continue;
                    if (onStack.contains(next)) {
                        // Back-edge: reconstruct the path from `next`
                        // back to `next` along the parent chain.
                        cyclePath = reconstructCycle(parent, next, node);
                        break;
                    }
                    if (visited.contains(next)) continue;
                    parent.put(next, node);
                    onStack.add(next);
                    stack.push(next);
                    descended = true;
                    break;
                }
                if (cyclePath != null) break;
                if (!descended) {
                    String done = stack.pop();
                    onStack.remove(done);
                    visited.add(done);
                }
            }
            if (cyclePath != null) break;
        }

        if (cyclePath == null || cyclePath.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(cyclePath.size() + 1);
        result.add("cycle");
        result.addAll(cyclePath);
        return result;
    }

    /**
     * Reconstructs a cycle path from the parent map by walking back from
     * {@code cursor} to {@code backEdgeTarget}, then appending the
     * closing edge from {@code cursor} back to {@code backEdgeTarget}
     * so the path reads as a closed loop.
     *
     * <p>For an edge {@code B -> A} discovered while {@code A} is on the
     * stack, {@code backEdgeTarget} is {@code A} and {@code cursor} is
     * {@code B}. The path returned is {@code [A, ..., B]} (root-to-leaf
     * along the active stack from {@code A} to {@code B}); the caller
     * reads the loop by mentally closing the path with {@code -> A}.</p>
     */
    private static List<String> reconstructCycle(Map<String, String> parent,
                                                 String backEdgeTarget,
                                                 String cursor) {
        // Walk parent pointers from `cursor` back to `backEdgeTarget`.
        List<String> reverse = new ArrayList<>();
        String cur = cursor;
        // Bound the walk so a malformed parent map cannot loop forever.
        int safetyBound = parent.size() + 2;
        while (cur != null && !cur.equals(backEdgeTarget) && safetyBound-- > 0) {
            reverse.add(cur);
            cur = parent.get(cur);
        }
        if (cur == null || safetyBound <= 0) {
            // Parent chain did not reach the back-edge target; report
            // the two endpoints so the operator at least sees a
            // connected pair.
            List<String> fallback = new ArrayList<>(2);
            fallback.add(backEdgeTarget);
            fallback.add(cursor);
            return fallback;
        }
        reverse.add(backEdgeTarget);
        Collections.reverse(reverse);
        return reverse;
    }
}
