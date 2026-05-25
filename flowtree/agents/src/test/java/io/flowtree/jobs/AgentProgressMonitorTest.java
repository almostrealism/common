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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link AgentProgressMonitor} reports a loop only when a single
 * action signature concentrates within the recent window after the detector has
 * armed, and never on varied or insufficiently repeated activity.
 */
public class AgentProgressMonitorTest extends TestSuiteBase {

    /** Repeated identical signatures trip detection once the minimum-action gate is cleared. */
    @Test(timeout = 5000)
    public void firesOnRepeatedSignatureAfterArming() {
        AgentProgressMonitor monitor = new AgentProgressMonitor(3, 5, 4);
        assertFalse(monitor.observe("x"));
        assertFalse(monitor.observe("x"));
        // Third "x": repeat threshold met, but the min-action gate (4) holds it off.
        assertFalse(monitor.observe("x"));
        // Fourth "x": armed and concentrated -> loop reported.
        assertTrue(monitor.observe("x"));
        assertEquals("x", monitor.getOffendingSignature());
    }

    /** Varied activity never concentrates one signature in the window, so it never fires. */
    @Test(timeout = 5000)
    public void doesNotFireOnVariedActivity() {
        AgentProgressMonitor monitor = new AgentProgressMonitor(3, 5, 4);
        String[] stream = {"a", "b", "c", "d", "e", "a", "b", "c", "d", "e", "a", "b"};
        for (String sig : stream) {
            assertFalse(monitor.observe(sig));
        }
        assertNull(monitor.getOffendingSignature());
    }

    /** Repeats that fall outside the sliding window do not accumulate into a false positive. */
    @Test(timeout = 5000)
    public void windowEvictionPreventsStaleCounting() {
        AgentProgressMonitor monitor = new AgentProgressMonitor(3, 5, 4);
        // One early "x", then enough distinct signatures to evict it from the window.
        assertFalse(monitor.observe("x"));
        for (String sig : new String[] {"a", "b", "c", "d", "e"}) {
            assertFalse(monitor.observe(sig));
        }
        // Two more "x": only two remain in the window, below the threshold of 3.
        assertFalse(monitor.observe("x"));
        assertFalse(monitor.observe("x"));
        assertNull(monitor.getOffendingSignature());
    }

    /** Null signatures (non-action lines) are ignored: they neither arm nor trip the detector. */
    @Test(timeout = 5000)
    public void ignoresNullSignatures() {
        AgentProgressMonitor monitor = new AgentProgressMonitor(3, 5, 4);
        for (int i = 0; i < 20; i++) {
            assertFalse(monitor.observe(null));
        }
        // Still needs the full repeat count after the nulls were ignored.
        assertFalse(monitor.observe("x"));
        assertFalse(monitor.observe("x"));
        assertFalse(monitor.observe("x"));
        assertTrue(monitor.observe("x"));
    }

    /** Invalid thresholds are rejected at construction. */
    @Test(timeout = 5000)
    public void constructorRejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentProgressMonitor(1, 5, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentProgressMonitor(5, 3, 4));
    }

    /** Minimal assertion helper to avoid a JUnit version dependency on assertThrows. */
    private static void assertThrows(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getName()
                    + " but got " + t.getClass().getName(), t);
        }
        throw new AssertionError("Expected " + expected.getName() + " but nothing was thrown");
    }
}
