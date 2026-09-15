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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link AgentActivityTracker} pairs tool-call boundaries into an
 * in-flight table, and that {@link AgentInactivityMonitor} extends its patience
 * only while an MCP-served call is open.
 */
public class AgentActivityTrackerTest extends TestSuiteBase {

    /** A classifier that treats each line as a pre-parsed boundary spec: {@code start:<id>:<name>} or {@code end:<id>}. */
    private static List<AgentActivity> spec(String line) {
        String[] parts = line.split(":", 3);
        if ("start".equals(parts[0])) {
            return List.of(AgentActivity.started(parts[1], parts[2]));
        }
        if ("end".equals(parts[0])) {
            return List.of(AgentActivity.finished(parts[1]));
        }
        return Collections.emptyList();
    }

    /** An MCP call is in flight from its start until its matching result. */
    @Test(timeout = 5000)
    public void mcpCallIsInFlightBetweenStartAndFinish() {
        AgentActivityTracker tracker = new AgentActivityTracker(AgentActivityTrackerTest::spec);
        assertFalse(tracker.isMcpCallInFlight());

        tracker.observe("start:t1:mcp__ar-test-runner__start_test_run");
        assertTrue(tracker.isMcpCallInFlight());
        assertEquals(1, tracker.inFlightCount());

        tracker.observe("end:t1");
        assertFalse(tracker.isMcpCallInFlight());
        assertEquals(0, tracker.inFlightCount());
    }

    /** A shell call in flight is tracked but earns no MCP grace. */
    @Test(timeout = 5000)
    public void bashCallDoesNotCountAsMcp() {
        AgentActivityTracker tracker = new AgentActivityTracker(AgentActivityTrackerTest::spec);
        tracker.observe("start:t1:Bash");
        assertEquals(1, tracker.inFlightCount());
        assertFalse(tracker.isMcpCallInFlight());
    }

    /** A finish for a call that was never opened, and a failing classifier, are both ignored. */
    @Test(timeout = 5000)
    public void unmatchedFinishAndClassifierFailureAreIgnored() {
        AgentActivityTracker tracker = new AgentActivityTracker(line -> {
            if (line.startsWith("boom")) throw new IllegalStateException(line);
            return spec(line);
        });
        tracker.observe("end:never-opened");
        tracker.observe("boom");
        tracker.observe("ignored line");
        assertEquals(0, tracker.inFlightCount());
        assertFalse(tracker.isMcpCallInFlight());
    }

    /** The monitor tolerates the configured window normally and the MCP grace while an MCP call is open. */
    @Test(timeout = 5000)
    public void monitorExtendsToleratedSilenceOnlyDuringMcpCall() {
        AgentActivityTracker tracker = new AgentActivityTracker(AgentActivityTrackerTest::spec);
        long window = 1_000L;
        AgentInactivityMonitor monitor = new AgentInactivityMonitor(
                () -> true, () -> { }, new AtomicLong(System.currentTimeMillis()),
                window, tracker, idle -> { }, "test-monitor");

        assertEquals(window, monitor.toleratedSilenceMillis());

        tracker.observe("start:t1:Bash");
        assertEquals("a shell call earns no grace", window, monitor.toleratedSilenceMillis());

        tracker.observe("start:t2:mcp__ar-manager__await_message");
        assertEquals(AgentInactivityMonitor.IN_FLIGHT_MCP_CALL_MILLIS, monitor.toleratedSilenceMillis());

        tracker.observe("end:t2");
        assertEquals(window, monitor.toleratedSilenceMillis());
    }

    /** A monitor built without a tracker keeps the configured window. */
    @Test(timeout = 5000)
    public void monitorWithoutTrackerKeepsConfiguredWindow() {
        AgentInactivityMonitor monitor = new AgentInactivityMonitor(
                () -> true, () -> { }, new AtomicLong(System.currentTimeMillis()),
                2_000L, idle -> { }, "test-monitor");
        assertEquals(2_000L, monitor.toleratedSilenceMillis());
    }
}
