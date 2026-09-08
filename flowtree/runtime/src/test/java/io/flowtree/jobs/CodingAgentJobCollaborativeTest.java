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
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the {@code collaborative} job property: its default, its
 * propagation from {@link CodingAgentJobFactory} to {@link CodingAgentJob},
 * its survival of a wire round trip, and the Collaboration Protocol section it
 * causes {@link InstructionPromptBuilder} to emit.
 *
 * <p>The property has to reach the worker for the feature to exist at all: an
 * agent that never sees the protocol does not announce readiness and does not
 * wait, so a collaborator waiting for it waits forever.</p>
 */
public class CodingAgentJobCollaborativeTest extends TestSuiteBase {

    /** Builds a prompt with the given collaboration setting. */
    private static String prompt(boolean collaborative) {
        return new InstructionPromptBuilder()
                .setPrompt("Bring up the model server.")
                .setWorkstreamUrl("http://controller:7780/api/workstreams/ws-1")
                .setCollaborative(collaborative)
                .build();
    }

    /** A job is not collaborative unless someone asks for it. */
    @Test(timeout = 30000)
    public void collaborativeDefaultsToFalse() {
        assertFalse(new CodingAgentJob("task-1", "do the thing").isCollaborative());
        assertFalse(new CodingAgentJobFactory("do the thing").isCollaborative());
    }

    /** The flag toggles in both directions on the job. */
    @Test(timeout = 30000)
    public void collaborativeTogglesOnTheJob() {
        CodingAgentJob job = new CodingAgentJob("task-1", "do the thing");
        job.setCollaborative(true);
        assertTrue(job.isCollaborative());
        job.setCollaborative(false);
        assertFalse(job.isCollaborative());
    }

    /** A factory configured for collaboration produces collaborative jobs. */
    @Test(timeout = 30000)
    public void factoryPropagatesCollaborativeToCreatedJobs() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do the thing");
        factory.setCollaborative(true);
        assertTrue(((CodingAgentJob) factory.nextJob()).isCollaborative());
    }

    /**
     * The flag survives serialization. This is the property that actually
     * matters: the factory is configured on the controller and the job runs on
     * another machine, so a flag that does not travel does nothing.
     */
    @Test(timeout = 30000)
    public void collaborativeSurvivesTheFactoryWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do the thing");
        factory.setCollaborative(true);
        assertTrue(GitManagedJobSerializationTest.roundTripFactory(factory).isCollaborative());
    }

    /** The flag survives a job's own wire round trip. */
    @Test(timeout = 30000)
    public void collaborativeSurvivesTheJobWireRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("task-1", "do the thing");
        job.setCollaborative(true);
        assertTrue(GitManagedJobSerializationTest.roundTrip(job).isCollaborative());
    }

    /** A job that is not collaborative round-trips as not collaborative. */
    @Test(timeout = 30000)
    public void nonCollaborativeSurvivesTheWireRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do the thing");
        assertFalse(GitManagedJobSerializationTest.roundTripFactory(factory).isCollaborative());
    }

    /** The protocol appears only when the job asked for it. */
    @Test(timeout = 30000)
    public void protocolIsAbsentByDefault() {
        assertFalse(prompt(false).contains("Collaboration Protocol"));
    }

    /**
     * The protocol states each of the four things an agent cannot infer:
     * that a peer is waiting, which tools carry the conversation, that a
     * timeout means keep waiting, and that lasting work must be detached.
     */
    @Test(timeout = 30000)
    public void protocolStatesWhatTheAgentCannotInfer() {
        String text = prompt(true);
        assertTrue(text.contains("Collaboration Protocol"));
        assertTrue(text.contains("NOT working alone"));
        assertTrue(text.contains("await_message"));
        assertTrue(text.contains("send_message"));
        assertTrue(text.contains("timed_out"));
        assertTrue(text.contains("next_since"));
        assertTrue(text.contains("tmux new-session -d"));
        assertTrue(text.contains("nohup"));
        assertTrue(text.contains("memory_store"));
    }

    /** The submitter's own request is never altered by the protocol. */
    @Test(timeout = 30000)
    public void protocolDoesNotDisturbTheUserRequest() {
        String text = prompt(true);
        assertTrue(text.contains("--- BEGIN USER REQUEST ---\nBring up the model server."));
        assertEquals(1, text.split("Bring up the model server\\.", -1).length - 1);
    }
}
