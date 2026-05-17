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
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@code model} and {@code effort} (thinking level) configuration
 * on {@link CodingAgentJob} and its factory.  Covers getter/setter round-trips,
 * validation of the effort level, wire-format serialisation, and factory
 * propagation.
 */
public class CodingAgentJobModelConfigTest extends TestSuiteBase {

    // ── model field ───────────────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void modelDefaultIsNull() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        assertNull(job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelAliasRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setModel("opus");
        assertEquals("opus", job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelFullNameRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setModel("claude-sonnet-4-6");
        assertEquals("claude-sonnet-4-6", job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelEmptyStringClearsValue() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setModel("sonnet");
        job.setModel("");
        assertNull(job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelNullClearsValue() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setModel("sonnet");
        job.setModel(null);
        assertNull(job.getModel());
    }

    @Test(timeout = 30000)
    public void setModelAcceptsAllValidIdentifiers() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        for (String id : CodingAgentJob.VALID_MODELS) {
            job.setModel(id);
            assertEquals(id, job.getModel());
        }
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setModelRejectsMissingClaudePrefix() {
        // Reproduces the wire-up bug that produced an unbounded enforcement
        // loop: "sonnet-4-6" is not a real CLI model, the resulting --model
        // flag triggered a 404, and the zero-output session looked like
        // "agent produced no changes" to enforce-changes.
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setModel("sonnet-4-6");
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setModelRejectsCompletelyUnknownIdentifier() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setModel("gpt-5");
    }

    @Test(timeout = 30000)
    public void factorySetModelRejectsInvalidIdentifier() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        try {
            factory.setModel("sonnet-4-6");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sonnet-4-6"));
        }
    }

    // ── effort field ──────────────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void effortDefaultIsNull() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000)
    public void setEffortAcceptsAllValidLevels() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        for (String level : CodingAgentJob.VALID_EFFORT_LEVELS) {
            job.setEffort(level);
            assertEquals(level, job.getEffort());
        }
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setEffortRejectsInvalidLevel() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setEffort("turbo");
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setEffortRejectsInvalidLevelCaseSensitive() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setEffort("HIGH");
    }

    @Test(timeout = 30000)
    public void setEffortEmptyStringClearsValue() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setEffort("high");
        job.setEffort("");
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000)
    public void setEffortNullClearsValue() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setEffort("high");
        job.setEffort(null);
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000)
    public void validEffortLevelsAreExactlyTheCliAcceptedSet() {
        // Mirror of the CLI's --effort choices. If the CLI adds or removes
        // a level, this test fails loud so the constant stays in sync.
        assertEquals(5, CodingAgentJob.VALID_EFFORT_LEVELS.size());
        assertTrue(CodingAgentJob.VALID_EFFORT_LEVELS.contains("low"));
        assertTrue(CodingAgentJob.VALID_EFFORT_LEVELS.contains("medium"));
        assertTrue(CodingAgentJob.VALID_EFFORT_LEVELS.contains("high"));
        assertTrue(CodingAgentJob.VALID_EFFORT_LEVELS.contains("xhigh"));
        assertTrue(CodingAgentJob.VALID_EFFORT_LEVELS.contains("max"));
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void modelAppearsInWireFormatWhenSet() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        job.setModel("opus");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertTrue("Expected model:=opus in: " + encoded,
                encoded.contains("model:=opus"));
    }

    @Test(timeout = 30000)
    public void modelAbsentInWireFormatWhenUnset() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertFalse("Did not expect model in: " + encoded,
                encoded.contains("model:="));
    }

    @Test(timeout = 30000)
    public void effortAppearsInWireFormatWhenSet() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        job.setEffort("high");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertTrue("Expected effort:=high in: " + encoded,
                encoded.contains("effort:=high"));
    }

    @Test(timeout = 30000)
    public void effortAbsentInWireFormatWhenUnset() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        String encoded = job.encode();
        assertNotNull(encoded);
        assertFalse("Did not expect effort in: " + encoded,
                encoded.contains("effort:="));
    }

    @Test(timeout = 30000)
    public void modelAndEffortDeserialise() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        job.setModel("claude-sonnet-4-6");
        job.setEffort("xhigh");

        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
        assertEquals("claude-sonnet-4-6", restored.getModel());
        assertEquals("xhigh", restored.getEffort());
    }

    // ── Factory — model/effort ────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void factoryModelDefaultIsNull() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertNull(factory.getModel());
    }

    @Test(timeout = 30000)
    public void factoryEffortDefaultIsNull() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertNull(factory.getEffort());
    }

    @Test(timeout = 30000)
    public void factorySetModelPropagatesToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        factory.setModel("opus");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals("opus", job.getModel());
    }

    @Test(timeout = 30000)
    public void factorySetEffortPropagatesToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        factory.setEffort("high");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals("high", job.getEffort());
    }

    @Test(timeout = 30000)
    public void factoryUnsetModelDoesNotPropagateToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertNull(job.getModel());
    }

    @Test(timeout = 30000)
    public void factoryUnsetEffortDoesNotPropagateToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertNull(job.getEffort());
    }

    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void factorySetEffortRejectsInvalidLevel() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setEffort("nuclear");
    }

    @Test(timeout = 30000)
    public void factoryModelRoundTripViaSet() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.set("model", "haiku");
        assertEquals("haiku", factory.getModel());
    }

    @Test(timeout = 30000)
    public void factoryEffortRoundTripViaSet() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.set("effort", "medium");
        assertEquals("medium", factory.getEffort());
    }

    @Test(timeout = 30000)
    public void factoryRoundTripPropagatesToJob() {
        CodingAgentJobFactory original = new CodingAgentJobFactory("prompt");
        original.setModel("opus");
        original.setEffort("max");

        // Simulate wire serialization round-trip.
        CodingAgentJobFactory restored = new CodingAgentJobFactory();
        restored.setPrompts("prompt");
        restored.set("model", "opus");
        restored.set("effort", "max");

        CodingAgentJob job = (CodingAgentJob) restored.nextJob();
        assertNotNull(job);
        assertEquals("opus", job.getModel());
        assertEquals("max", job.getEffort());
    }
}
