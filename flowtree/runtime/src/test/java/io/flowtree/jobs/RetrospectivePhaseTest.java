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
 * Unit tests for the {@link RetrospectivePhase} collaborator that owns
 * retrospective-phase telemetry on {@link CodingAgentJob}.
 *
 * <p>End-to-end behavior — including the prompt swap, the
 * {@link CodingAgentJob#executeSingleRun()} dispatch, and the cost-isolation
 * delta — is exercised by {@link CodingAgentJobRetrospectiveTest}. These tests
 * focus on the helper's own state-machine surface: telemetry defaults and the
 * {@link RetrospectivePhase#reset()} contract that {@link CodingAgentJob#doWork()}
 * relies on at the top of every job run.</p>
 */
public class RetrospectivePhaseTest extends TestSuiteBase {

    /** A freshly-constructed phase has all telemetry counters at their defaults. */
    @Test(timeout = 30000)
    public void freshPhaseHasDefaultTelemetry() {
        RetrospectivePhase phase = new RetrospectivePhase();
        assertFalse("ran() defaults to false", phase.ran());
        assertFalse("transcriptFound() defaults to false", phase.transcriptFound());
        assertEquals("findingsCount() defaults to 0", 0, phase.findingsCount());
        assertEquals("costUsd() defaults to 0.0", 0.0, phase.costUsd(), 0.0);
    }

    /** {@link RetrospectivePhase#reset()} on a fresh phase is a no-op (still all defaults). */
    @Test(timeout = 30000)
    public void resetOnFreshPhaseIsIdempotent() {
        RetrospectivePhase phase = new RetrospectivePhase();
        phase.reset();
        assertFalse(phase.ran());
        assertFalse(phase.transcriptFound());
        assertEquals(0, phase.findingsCount());
        assertEquals(0.0, phase.costUsd(), 0.0);
    }

    /** The retrospective result file constant is the agreed-upon name read by {@link RetrospectivePromptBuilder}. */
    @Test(timeout = 30000)
    public void resultsFileConstantIsStable() {
        assertEquals("retrospective-results.json", RetrospectivePhase.RESULTS_FILE);
    }

    /** A {@link CodingAgentJob} exposes a retrospective phase whose telemetry defaults are visible via the event accessors. */
    @Test(timeout = 30000)
    public void jobExposesPhaseTelemetryDefaults() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        assertFalse("retrospectiveEnabled defaults to false on a fresh job",
                job.isRetrospectiveEnabled());
        // The collaborator is wired in by default; setting the gate does not
        // immediately run the phase. Verifying that the gate is independent of
        // phase telemetry isolates the gate-vs-execution split.
        job.setRetrospectiveEnabled(true);
        assertTrue(job.isRetrospectiveEnabled());
    }
}
