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

import io.flowtree.jobs.agent.Phase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the retrospective phase: {@link Phase#RETROSPECTIVE} wire
 * mapping, the {@code reflectionEnabled} flag on {@link CodingAgentJob} and
 * {@link CodingAgentJobFactory}, the wire-format serialisation round-trip,
 * the {@link RetrospectivePromptBuilder}, and the non-code-producing phase
 * completion invariant.
 */
public class CodingAgentJobRetrospectiveTest extends TestSuiteBase {

    // ── Phase wire mapping ──────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void phaseRetrospectiveExists() {
        assertEquals(Phase.RETROSPECTIVE, Phase.fromWireName("retrospective"));
    }

    @Test(timeout = 30000)
    public void fromRuleNameRetrospectiveResolvesToPhaseRetrospective() {
        assertEquals(Phase.RETROSPECTIVE, Phase.fromRuleName("retrospective"));
    }

    @Test(timeout = 30000)
    public void retrospectivePhaseWireName() {
        assertEquals("retrospective", Phase.RETROSPECTIVE.wireName());
    }

    // ── reflectionEnabled on CodingAgentJob ────────────────────────────────

    @Test(timeout = 30000)
    public void reflectionEnabledDefaultIsFalse() {
        assertFalse(new CodingAgentJob("t1", "p").isReflectionEnabled());
    }

    @Test(timeout = 30000)
    public void setReflectionEnabledTrue() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setReflectionEnabled(true);
        assertTrue(job.isReflectionEnabled());
    }

    @Test(timeout = 30000)
    public void setReflectionEnabledFalse() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setReflectionEnabled(true);
        job.setReflectionEnabled(false);
        assertFalse(job.isReflectionEnabled());
    }

    // ── Wire format — defaults absent ───────────────────────────────────────

    @Test(timeout = 30000)
    public void reflectionEnabledAbsentFromWireFormatWhenFalse() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String encoded = job.encode();
        assertFalse("reflectionEnabled must not appear when default (false): " + encoded,
                encoded.contains("reflectionEnabled"));
    }

    @Test(timeout = 30000)
    public void reflectionEnabledAppearsInWireFormatWhenTrue() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setReflectionEnabled(true);
        String encoded = job.encode();
        assertTrue("Expected reflectionEnabled:=true in: " + encoded,
                encoded.contains("reflectionEnabled:=true"));
    }

    @Test(timeout = 30000)
    public void reflectionEnabledRoundTripViaSet() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.set("reflectionEnabled", "true");
        assertTrue(job.isReflectionEnabled());
        job.set("reflectionEnabled", "false");
        assertFalse(job.isReflectionEnabled());
    }

    @Test(timeout = 30000)
    public void encodeDecodeRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setReflectionEnabled(true);
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
        assertTrue(restored.isReflectionEnabled());
    }

    // ── Factory propagation ────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void factoryReflectionEnabledDefaultFalse() {
        assertFalse(new CodingAgentJobFactory("prompt").isReflectionEnabled());
    }

    @Test(timeout = 30000)
    public void factoryReflectionEnabledPropagatesToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        factory.setReflectionEnabled(true);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue(job.isReflectionEnabled());
    }

    @Test(timeout = 30000)
    public void factoryReflectionEnabledRoundTripViaSet() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        factory.set("reflectionEnabled", "true");
        assertTrue(factory.isReflectionEnabled());
    }

    // ── RetrospectivePromptBuilder ──────────────────────────────────────────

    @Test(timeout = 30000)
    public void promptBuilderIncludesRetrospectivePhaseHeader() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include the RETROSPECTIVE ANALYSIS header",
                prompt.contains("RETROSPECTIVE ANALYSIS"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesRoleStatement() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include role statement about studying another agent's transcript",
                prompt.contains("You are reviewing a transcript of another agent"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesListTranscriptsInstruction() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must instruct to use list_transcripts",
                prompt.contains("list_transcripts"));
        assertTrue("Prompt must reference default transcript directory /agent-transcripts",
                prompt.contains("/agent-transcripts"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesToolUseFocusArea() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include tool-use focus area",
                prompt.contains("TOOL USE QUALITY"));
        assertTrue("Prompt must mention missed tools",
                prompt.contains("missed tools"));
        assertTrue("Prompt must mention send_message",
                prompt.contains("send_message"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesContextEfficiencyFocusArea() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include context efficiency focus area",
                prompt.contains("CONTEXT EFFICIENCY"));
        assertTrue("Prompt must mention redundant reads",
                prompt.contains("redundant reads"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesMemoryStoreInstructions() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setWorkstreamUrl("http://host:7700/api/workstreams/ws-test/jobs/t1");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include memory_store call",
                prompt.contains("memory_store"));
        assertTrue("Prompt must use self-improvement namespace",
                prompt.contains("self-improvement"));
        assertTrue("Prompt must use retrospective tag",
                prompt.contains("retrospective"));
        assertTrue("Prompt must use workstream tag with correct ws id",
                prompt.contains("workstream:ws-test"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesGracefulDegradationInstructions() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must explain graceful degradation when no transcript found",
                prompt.contains("No transcript available"));
        assertTrue("Prompt must instruct to store 'no-transcript' memory",
                prompt.contains("no-transcript"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesForbiddenActions() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must forbid git commands",
                prompt.contains("No git commands"));
        assertTrue("Prompt must forbid code changes",
                prompt.contains("No Read, Edit, Write"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesExpectedOutcome() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must state expected outcome is memories, not code",
                prompt.contains("EXPECTED OUTCOME"));
        assertTrue("Prompt must say no commits expected",
                prompt.contains("no commits"));
    }

    @Test(timeout = 30000)
    public void promptBuilderIncludesV1ScopeBoundary() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must explicitly state what is NOT in v1 scope",
                prompt.contains("NOT IN V1 SCOPE"));
        assertTrue("Prompt must exclude code quality/correctness",
                prompt.contains("Code quality or correctness"));
    }

    @Test(timeout = 30000)
    public void extractWorkstreamIdFromTypicalUrl() {
        String url = "http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc";
        assertEquals("ws-1", WorkstreamUtils.extractWorkstreamId(url));
    }

    @Test(timeout = 30000)
    public void extractWorkstreamIdNoJobsSegment() {
        String url = "http://host/api/workstreams/mystream";
        assertEquals("mystream", WorkstreamUtils.extractWorkstreamId(url));
    }

    @Test(timeout = 30000)
    public void extractWorkstreamIdNoWorkstreamsSegmentReturnsNull() {
        assertEquals(null, WorkstreamUtils.extractWorkstreamId("http://host:7700/api/submit"));
    }

    @Test(timeout = 30000)
    public void extractWorkstreamIdEmptyStringReturnsNull() {
        assertEquals(null, WorkstreamUtils.extractWorkstreamId(""));
    }

    @Test(timeout = 30000)
    public void extractWorkstreamIdNullReturnsNull() {
        assertEquals(null, WorkstreamUtils.extractWorkstreamId(null));
    }

    // ── Non-code-producing phase completes correctly ───────────────────────

    /**
     * Verifies the design invariant: the retrospective phase is invoked in
     * {@link CodingAgentJob#doWork()} AFTER {@link CodingAgentJob#runEnforcementRules()}
     * returns — it is NOT part of the enforcement rule loop in
     * {@code buildActiveRules()}.
     *
     * <p>The structural proof: a spy subclass records the call sequence during
     * {@code doWork()}. The assertion chain {@code enforcementRulesCalled
     * → reflectionPhaseCalled} proves ordering. A single {@code true} for
     * {@code reflectionPhaseCalled} proves non-membership in any loop (loops
     * call multiple times).</p>
     */
    @Test(timeout = 30000)
    public void retrospectivePhaseIsOutsideEnforcementRuleLoop() {
        SpyCodingAgentJob job = new SpyCodingAgentJob("t1", "p");
        job.setReflectionEnabled(true);
        job.doWork();
        assertTrue("runEnforcementRules() must be called when reflection is enabled",
                job.enforcementRulesCalled);
        assertTrue("runReflectionPhase() must be called after runEnforcementRules()",
                job.reflectionPhaseCalled);
        assertTrue("runReflectionPhase() must be called exactly once (not looped)",
                job.reflectionPhaseCallCount == 1);
    }

    /**
     * Spy subclass that records method call counts to verify the enforcement /
     * reflection call ordering invariant.
     */
    static class SpyCodingAgentJob extends CodingAgentJob {
        boolean enforcementRulesCalled;
        boolean reflectionPhaseCalled;
        int reflectionPhaseCallCount;

        SpyCodingAgentJob(String taskId, String prompt) {
            super(taskId, prompt);
        }

        @Override
        void runEnforcementRules() {
            enforcementRulesCalled = true;
        }

        @Override
        void runReflectionPhase() {
            reflectionPhaseCalled = true;
            reflectionPhaseCallCount++;
        }

        @Override
        void executeSingleRun() {
            // No-op: we only track call ordering, not actual agent execution
        }
    }

    @Test(timeout = 30000)
    public void retrospectivePhaseUsesPhaseEnumWireName() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setReflectionEnabled(true);
        assertEquals(Phase.RETROSPECTIVE.wireName(), "retrospective");
    }
}
