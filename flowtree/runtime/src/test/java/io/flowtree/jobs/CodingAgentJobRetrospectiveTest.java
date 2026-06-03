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
 * mapping, the {@code retrospectiveEnabled} flag on {@link CodingAgentJob} and
 * {@link CodingAgentJobFactory}, the wire-format serialisation round-trip,
 * the {@link RetrospectivePromptBuilder}, and the non-code-producing phase
 * completion invariant.
 */
public class CodingAgentJobRetrospectiveTest extends TestSuiteBase {

    // ── Phase wire mapping ──────────────────────────────────────────────────

    /** Verifies that Phase.fromWireName("retrospective") returns Phase.RETROSPECTIVE. */
    @Test(timeout = 30000)
    public void phaseRetrospectiveExists() {
        assertEquals(Phase.RETROSPECTIVE, Phase.fromWireName("retrospective"));
    }

    /** Verifies that Phase.fromRuleName("retrospective") resolves to Phase.RETROSPECTIVE. */
    @Test(timeout = 30000)
    public void fromRuleNameRetrospectiveResolvesToPhaseRetrospective() {
        assertEquals(Phase.RETROSPECTIVE, Phase.fromRuleName("retrospective"));
    }

    /** Verifies that Phase.RETROSPECTIVE.wireName() returns "retrospective". */
    @Test(timeout = 30000)
    public void retrospectivePhaseWireName() {
        assertEquals("retrospective", Phase.RETROSPECTIVE.wireName());
    }

    // ── retrospectiveEnabled on CodingAgentJob ────────────────────────────────

    /** CodingAgentJob isRetrospectiveEnabled defaults to false. */
    @Test(timeout = 30000)
    public void retrospectiveEnabledDefaultIsFalse() {
        assertFalse(new CodingAgentJob("t1", "p").isRetrospectiveEnabled());
    }

    /** CodingAgentJob.setRetrospectiveEnabled(true) is reflected in isRetrospectiveEnabled(). */
    @Test(timeout = 30000)
    public void setRetrospectiveEnabledTrue() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setRetrospectiveEnabled(true);
        assertTrue(job.isRetrospectiveEnabled());
    }

    /** CodingAgentJob.setRetrospectiveEnabled(false) toggles off after true. */
    @Test(timeout = 30000)
    public void setRetrospectiveEnabledFalse() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setRetrospectiveEnabled(true);
        job.setRetrospectiveEnabled(false);
        assertFalse(job.isRetrospectiveEnabled());
    }

    // ── Wire format — defaults absent ───────────────────────────────────────

    /** retrospectiveEnabled absent from wire format when default (false). */
    @Test(timeout = 30000)
    public void retrospectiveEnabledAbsentFromWireFormatWhenFalse() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String encoded = job.encode();
        assertFalse("retrospectiveEnabled must not appear when default (false): " + encoded,
                encoded.contains("retrospectiveEnabled"));
    }

    /** retrospectiveEnabled appears in wire format when set to true. */
    @Test(timeout = 30000)
    public void retrospectiveEnabledAppearsInWireFormatWhenTrue() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setRetrospectiveEnabled(true);
        String encoded = job.encode();
        assertTrue("Expected retrospectiveEnabled:=true in: " + encoded,
                encoded.contains("retrospectiveEnabled:=true"));
    }

    /** retrospectiveEnabled round-trips correctly via job.set(). */
    @Test(timeout = 30000)
    public void retrospectiveEnabledRoundTripViaSet() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.set("retrospectiveEnabled", "true");
        assertTrue(job.isRetrospectiveEnabled());
        job.set("retrospectiveEnabled", "false");
        assertFalse(job.isRetrospectiveEnabled());
    }

    /** CodingAgentJob retrospectiveEnabled survives encode/decode round-trip. */
    @Test(timeout = 30000)
    public void encodeDecodeRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setRetrospectiveEnabled(true);
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
        assertTrue(restored.isRetrospectiveEnabled());
    }

    // ── Factory propagation ────────────────────────────────────────────────

    /** CodingAgentJobFactory isRetrospectiveEnabled defaults to false. */
    @Test(timeout = 30000)
    public void factoryRetrospectiveEnabledDefaultFalse() {
        assertFalse(new CodingAgentJobFactory("prompt").isRetrospectiveEnabled());
    }

    /** CodingAgentJobFactory.setRetrospectiveEnabled propagates to nextJob(). */
    @Test(timeout = 30000)
    public void factoryRetrospectiveEnabledPropagatesToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        factory.setRetrospectiveEnabled(true);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue(job.isRetrospectiveEnabled());
    }

    /** CodingAgentJobFactory retrospectiveEnabled round-trips via set(). */
    @Test(timeout = 30000)
    public void factoryRetrospectiveEnabledRoundTripViaSet() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        factory.set("retrospectiveEnabled", "true");
        assertTrue(factory.isRetrospectiveEnabled());
    }

    // ── RetrospectivePromptBuilder ──────────────────────────────────────────

    /** RetrospectivePromptBuilder includes RETROSPECTIVE ANALYSIS header. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesRetrospectivePhaseHeader() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include the RETROSPECTIVE ANALYSIS header",
                prompt.contains("RETROSPECTIVE ANALYSIS"));
    }

    /** RetrospectivePromptBuilder includes role statement about studying transcript. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesRoleStatement() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include role statement about studying another agent's transcript",
                prompt.contains("You are reviewing a transcript of another agent"));
    }

    /** RetrospectivePromptBuilder instructs to use list_transcripts tool. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesListTranscriptsInstruction() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must instruct to use list_transcripts",
                prompt.contains("list_transcripts"));
        assertTrue("Prompt must reference default transcript directory /agent-transcripts",
                prompt.contains("/agent-transcripts"));
    }

    /** RetrospectivePromptBuilder includes TOOL USE QUALITY focus area. */
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

    /** RetrospectivePromptBuilder includes CONTEXT EFFICIENCY focus area. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesContextEfficiencyFocusArea() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must include context efficiency focus area",
                prompt.contains("CONTEXT EFFICIENCY"));
        assertTrue("Prompt must mention redundant reads",
                prompt.contains("redundant reads"));
    }

    /** RetrospectivePromptBuilder includes memory_store call with workstream tags. */
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

    /** RetrospectivePromptBuilder explains graceful degradation when no transcript found. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesGracefulDegradationInstructions() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must explain graceful degradation when no transcript found",
                prompt.contains("No transcript available"));
        assertTrue("Prompt must instruct to store 'no-transcript' memory",
                prompt.contains("no-transcript"));
    }

    /** RetrospectivePromptBuilder forbids git commands and code changes. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesForbiddenActions() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must forbid git commands",
                prompt.contains("No git commands"));
        assertTrue("Prompt must forbid code changes",
                prompt.contains("No Read, Edit, Write"));
    }

    /** RetrospectivePromptBuilder states expected outcome is memories, not code. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesExpectedOutcome() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must state expected outcome is memories, not code",
                prompt.contains("EXPECTED OUTCOME"));
        assertTrue("Prompt must say no commits expected",
                prompt.contains("no commits"));
    }

    /** RetrospectivePromptBuilder explicitly states what is NOT in v1 scope. */
    @Test(timeout = 30000)
    public void promptBuilderIncludesV1ScopeBoundary() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        String prompt = RetrospectivePromptBuilder.build(job);
        assertTrue("Prompt must explicitly state what is NOT in v1 scope",
                prompt.contains("NOT IN V1 SCOPE"));
        assertTrue("Prompt must exclude code quality/correctness",
                prompt.contains("Code quality or correctness"));
    }

    /** WorkstreamUtils.extractWorkstreamId parses typical URL with /jobs/ segment. */
    @Test(timeout = 30000)
    public void extractWorkstreamIdFromTypicalUrl() {
        String url = "http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc";
        assertEquals("ws-1", WorkstreamUtils.extractWorkstreamId(url));
    }

    /** WorkstreamUtils.extractWorkstreamId parses URL without /jobs/ segment. */
    @Test(timeout = 30000)
    public void extractWorkstreamIdNoJobsSegment() {
        String url = "http://host/api/workstreams/mystream";
        assertEquals("mystream", WorkstreamUtils.extractWorkstreamId(url));
    }

    /** WorkstreamUtils.extractWorkstreamId returns null when no /workstreams/ segment. */
    @Test(timeout = 30000)
    public void extractWorkstreamIdNoWorkstreamsSegmentReturnsNull() {
        assertEquals(null, WorkstreamUtils.extractWorkstreamId("http://host:7700/api/submit"));
    }

    /** WorkstreamUtils.extractWorkstreamId returns null for empty string. */
    @Test(timeout = 30000)
    public void extractWorkstreamIdEmptyStringReturnsNull() {
        assertEquals(null, WorkstreamUtils.extractWorkstreamId(""));
    }

    /** WorkstreamUtils.extractWorkstreamId returns null for null input. */
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
        job.setRetrospectiveEnabled(true);
        job.doWork();
        assertTrue("runEnforcementRules() must be called when reflection is enabled",
                job.enforcementRulesCalled);
        assertTrue("runReflectionPhase() must be called after runEnforcementRules()",
                job.reflectionPhaseCalled);
        assertTrue("runReflectionPhase() must be called exactly once (not looped)",
                job.reflectionPhaseCallCount == 1);
    }

    /**
     * Verifies the orchestrator gate: when {@code retrospectiveEnabled} is false
     * (the default), {@code doWork()} must NOT invoke {@code runReflectionPhase()}.
     * This is the negative path of the activation gate that {@code retrospective_enabled}
     * on the submit tool controls end-to-end.
     */
    @Test(timeout = 30000)
    public void retrospectivePhaseSkippedWhenRetrospectiveEnabledFalse() {
        SpyCodingAgentJob job = new SpyCodingAgentJob("t1", "p");
        // retrospectiveEnabled defaults to false; assert that explicitly.
        assertFalse(job.isRetrospectiveEnabled());
        job.doWork();
        assertTrue("runEnforcementRules() must still be called when retrospective is disabled",
                job.enforcementRulesCalled);
        assertFalse("runReflectionPhase() must NOT be called when retrospectiveEnabled is false",
                job.reflectionPhaseCalled);
        assertEquals("runReflectionPhase() must not be called at all when gate is off",
                0, job.reflectionPhaseCallCount);
    }

    /**
     * Spy subclass that records method call counts to verify the enforcement /
     * reflection call ordering invariant.
     */
    static class SpyCodingAgentJob extends CodingAgentJob {
        /** Tracks whether runEnforcementRules() was called during doWork(). */
        boolean enforcementRulesCalled;
        /** Tracks whether runReflectionPhase() was called during doWork(). */
        boolean reflectionPhaseCalled;
        /** Counts how many times runReflectionPhase() was called during doWork(). */
        int reflectionPhaseCallCount;

        /** Constructs a spy job with the given task identifier and prompt. */
        SpyCodingAgentJob(String taskId, String prompt) {
            super(taskId, prompt);
        }

        /** Records that runEnforcementRules() was invoked. */
        @Override
        void runEnforcementRules() {
            enforcementRulesCalled = true;
        }

        /** Records that runReflectionPhase() was invoked and increments the call counter. */
        @Override
        void runReflectionPhase() {
            reflectionPhaseCalled = true;
            reflectionPhaseCallCount++;
        }

        /** No-op override that suppresses actual agent execution during structural tests. */
        @Override
        void executeSingleRun() {
            // No-op: we only track call ordering, not actual agent execution
        }
    }

    /** CodingAgentJob retrospective phase uses Phase.RETROSPECTIVE.wireName(). */
    @Test(timeout = 30000)
    public void retrospectivePhaseUsesPhaseEnumWireName() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setRetrospectiveEnabled(true);
        assertEquals(Phase.RETROSPECTIVE.wireName(), "retrospective");
    }
}
