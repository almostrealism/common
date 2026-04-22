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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@code model} and {@code effort} (thinking level) configuration
 * on {@link ClaudeCodeJob} and its factory.  Covers getter/setter round-trips,
 * validation of the effort level, wire-format serialisation, and factory
 * propagation.
 */
public class ClaudeCodeJobModelConfigTest extends TestSuiteBase {

    // ── model field ───────────────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void modelDefaultIsNull() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        assertNull(job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelAliasRoundTrip() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setModel("opus");
        assertEquals("opus", job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelFullNameRoundTrip() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setModel("claude-sonnet-4-6");
        assertEquals("claude-sonnet-4-6", job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelEmptyStringClearsValue() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setModel("sonnet");
        job.setModel("");
        assertNull(job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelNullClearsValue() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setModel("sonnet");
        job.setModel(null);
        assertNull(job.getModel());
    }

    // ── effort field ──────────────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void effortDefaultIsNull() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000)
    public void setEffortAcceptsAllValidLevels() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        for (String level : ClaudeCodeJob.VALID_EFFORT_LEVELS) {
            job.setEffort(level);
            assertEquals(level, job.getEffort());
        }
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setEffortRejectsInvalidLevel() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setEffort("turbo");
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setEffortRejectsInvalidLevelCaseSensitive() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setEffort("HIGH");
    }

    @Test(timeout = 30000)
    public void setEffortEmptyStringClearsValue() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setEffort("high");
        job.setEffort("");
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000)
    public void setEffortNullClearsValue() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setEffort("high");
        job.setEffort(null);
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000)
    public void validEffortLevelsAreExactlyTheCliAcceptedSet() {
        // Mirror of the CLI's --effort choices. If the CLI adds or removes
        // a level, this test fails loud so the constant stays in sync.
        assertEquals(5, ClaudeCodeJob.VALID_EFFORT_LEVELS.size());
        assertTrue(ClaudeCodeJob.VALID_EFFORT_LEVELS.contains("low"));
        assertTrue(ClaudeCodeJob.VALID_EFFORT_LEVELS.contains("medium"));
        assertTrue(ClaudeCodeJob.VALID_EFFORT_LEVELS.contains("high"));
        assertTrue(ClaudeCodeJob.VALID_EFFORT_LEVELS.contains("xhigh"));
        assertTrue(ClaudeCodeJob.VALID_EFFORT_LEVELS.contains("max"));
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void modelAppearsInWireFormatWhenSet() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
        job.setModel("opus");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertTrue("Expected model:=opus in: " + encoded,
                encoded.contains("model:=opus"));
    }

    @Test(timeout = 30000)
    public void modelAbsentInWireFormatWhenUnset() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertFalse("Did not expect model in: " + encoded,
                encoded.contains("model:="));
    }

    @Test(timeout = 30000)
    public void effortAppearsInWireFormatWhenSet() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
        job.setEffort("high");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertTrue("Expected effort:=high in: " + encoded,
                encoded.contains("effort:=high"));
    }

    @Test(timeout = 30000)
    public void effortAbsentInWireFormatWhenUnset() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertFalse("Did not expect effort in: " + encoded,
                encoded.contains("effort:="));
    }

    @Test(timeout = 30000)
    public void modelAndEffortDeserialise() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
        job.setModel("claude-sonnet-4-6");
        job.setEffort("xhigh");
        String encoded = job.encode();

        ClaudeCodeJob restored = new ClaudeCodeJob();
        for (String part : encoded.split("::")) {
            int sep = part.indexOf(":=");
            if (sep > 0) {
                restored.set(part.substring(0, sep), part.substring(sep + 2));
            }
        }
        assertEquals("claude-sonnet-4-6", restored.getModel());
        assertEquals("xhigh", restored.getEffort());
    }

    // ── Factory — model/effort ────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void factoryModelDefaultIsNull() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
        assertNull(factory.getModel());
    }

    @Test(timeout = 30000)
    public void factoryEffortDefaultIsNull() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
        assertNull(factory.getEffort());
    }

    @Test(timeout = 30000)
    public void factorySetModelPropagatesToJob() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
        factory.setModel("opus");
        ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
        assertNotNull(job);
        assertEquals("opus", job.getModel());
    }

    @Test(timeout = 30000)
    public void factorySetEffortPropagatesToJob() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
        factory.setEffort("high");
        ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
        assertNotNull(job);
        assertEquals("high", job.getEffort());
    }

    @Test(timeout = 30000)
    public void factoryUnsetModelDoesNotPropagateToJob() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
        ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
        assertNotNull(job);
        assertNull(job.getModel());
    }

    @Test(timeout = 30000)
    public void factoryUnsetEffortDoesNotPropagateToJob() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do something");
        ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
        assertNotNull(job);
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void factorySetEffortRejectsInvalidLevel() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
        factory.setEffort("nuclear");
    }

    @Test(timeout = 30000)
    public void factoryModelRoundTripViaSet() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
        factory.set("model", "haiku");
        assertEquals("haiku", factory.getModel());
    }

    @Test(timeout = 30000)
    public void factoryEffortRoundTripViaSet() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("prompt");
        factory.set("effort", "medium");
        assertEquals("medium", factory.getEffort());
    }

    @Test(timeout = 30000)
    public void factoryRoundTripPropagatesToJob() {
        ClaudeCodeJobFactory original = new ClaudeCodeJobFactory("prompt");
        original.setModel("opus");
        original.setEffort("max");

        // Simulate wire serialization round-trip.
        ClaudeCodeJobFactory restored = new ClaudeCodeJobFactory();
        restored.setPrompts("prompt");
        restored.set("model", "opus");
        restored.set("effort", "max");

        ClaudeCodeJob job = (ClaudeCodeJob) restored.nextJob();
        assertNotNull(job);
        assertEquals("opus", job.getModel());
        assertEquals("max", job.getEffort());
    }
}
