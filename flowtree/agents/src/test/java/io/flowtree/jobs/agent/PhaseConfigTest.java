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

package io.flowtree.jobs.agent;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PhaseConfig}. Focuses on overlay semantics, null-vs-set
 * distinction, and the {@code withX} accessor preservation of siblings.
 */
public class PhaseConfigTest extends TestSuiteBase {

    @Test(timeout = 5000)
    public void emptyIsAllNull() {
        assertTrue(PhaseConfig.EMPTY.isEmpty());
        assertNull(PhaseConfig.EMPTY.runner());
        assertNull(PhaseConfig.EMPTY.model());
        assertNull(PhaseConfig.EMPTY.effort());
    }

    @Test(timeout = 5000)
    public void anyFieldSetMakesNonEmpty() {
        assertFalse(new PhaseConfig("claude", null, null).isEmpty());
        assertFalse(new PhaseConfig(null, "opus", null).isEmpty());
        assertFalse(new PhaseConfig(null, null, "high").isEmpty());
    }

    @Test(timeout = 5000)
    public void withRunnerPreservesOtherFields() {
        PhaseConfig original = new PhaseConfig(null, "opus", "high");
        PhaseConfig updated = original.withRunner("claude");
        assertEquals("claude", updated.runner());
        assertEquals("opus", updated.model());
        assertEquals("high", updated.effort());
    }

    @Test(timeout = 5000)
    public void withModelPreservesOtherFields() {
        PhaseConfig original = new PhaseConfig("claude", null, "low");
        PhaseConfig updated = original.withModel("sonnet");
        assertEquals("claude", updated.runner());
        assertEquals("sonnet", updated.model());
        assertEquals("low", updated.effort());
    }

    @Test(timeout = 5000)
    public void withEffortPreservesOtherFields() {
        PhaseConfig original = new PhaseConfig("claude", "opus", null);
        PhaseConfig updated = original.withEffort("medium");
        assertEquals("claude", updated.runner());
        assertEquals("opus", updated.model());
        assertEquals("medium", updated.effort());
    }

    @Test(timeout = 5000)
    public void overlayFillsNullsFromOther() {
        PhaseConfig top = new PhaseConfig("claude", null, null);
        PhaseConfig bottom = new PhaseConfig("opencode", "qwen3-coder-30b", "high");
        PhaseConfig merged = top.overlayOn(bottom);
        // Top wins where set; bottom fills the rest.
        assertEquals("claude", merged.runner());
        assertEquals("qwen3-coder-30b", merged.model());
        assertEquals("high", merged.effort());
    }

    @Test(timeout = 5000)
    public void overlayOnNullReturnsSelf() {
        PhaseConfig top = new PhaseConfig("claude", null, null);
        PhaseConfig merged = top.overlayOn(null);
        assertSame(top, merged);
    }

    @Test(timeout = 5000)
    public void overlayChainsThreeLevels() {
        PhaseConfig job = new PhaseConfig(null, null, "high");
        PhaseConfig workstream = new PhaseConfig(null, "opus", "low");
        PhaseConfig workspace = new PhaseConfig("claude", "sonnet", "medium");
        PhaseConfig resolved = job.overlayOn(workstream).overlayOn(workspace);
        assertEquals("claude", resolved.runner());
        assertEquals("opus", resolved.model());
        assertEquals("high", resolved.effort());
    }

    @Test(timeout = 5000)
    public void overlayDoesNotMutateInputs() {
        PhaseConfig top = new PhaseConfig("claude", null, null);
        PhaseConfig bottom = new PhaseConfig("opencode", "opus", "high");
        PhaseConfig merged = top.overlayOn(bottom);
        // Originals must be unchanged.
        assertEquals("claude", top.runner());
        assertNull(top.model());
        assertEquals("opencode", bottom.runner());
        assertEquals("opus", bottom.model());
        assertNotNull(merged);
    }

    @Test(timeout = 5000)
    public void nonNullFieldsAlwaysWinOverNull() {
        PhaseConfig top = new PhaseConfig(null, null, null);
        PhaseConfig bottom = new PhaseConfig("claude", "opus", "high");
        PhaseConfig merged = top.overlayOn(bottom);
        assertEquals("claude", merged.runner());
        assertEquals("opus", merged.model());
        assertEquals("high", merged.effort());
    }
}
