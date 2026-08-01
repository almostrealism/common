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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural coverage for {@link FalsificationPhase}: the bounce decision, the
 * bounce budget, the pass-through of probe-requiring unsettled claims, and the
 * orchestrator gate that routes an enabled job into the phase.
 *
 * <p>The analysis session and the bounce re-run are stubbed out via a spy
 * subclass so the tests exercise the decision logic deterministically, without
 * dispatching a real agent.</p>
 */
public class FalsificationPhaseTest extends TestSuiteBase {

    /** A refuted load-bearing claim bounces the job back to primary exactly once. */
    @Test(timeout = 30000)
    public void refutedClaimBouncesOnce() {
        SpyFalsificationPhase phase = new SpyFalsificationPhase();
        phase.enqueue(Collections.singletonList(assessment(FalsificationVerdict.REFUTED, true)));
        phase.enqueue(Collections.emptyList()); // redone tree is clean

        phase.run(new CodingAgentJob("t-refute", "p"));

        assertEquals("A refuted load-bearing claim must bounce exactly once", 1, phase.bounceCount());
        assertEquals("The refuted claim must be tallied", 1, phase.refutedCount());
    }

    /**
     * A probe-requiring UNSETTLED claim (no captured artifact can settle it) is
     * passed through WITHOUT a bounce — bouncing would loop on a claim no
     * re-run can settle from captured artifacts. This is the v1 Case-1 contract.
     */
    @Test(timeout = 30000)
    public void probeRequiringUnsettledPassesThroughWithoutBounce() {
        SpyFalsificationPhase phase = new SpyFalsificationPhase();
        phase.enqueue(Collections.singletonList(assessment(FalsificationVerdict.UNSETTLED, false)));

        phase.run(new CodingAgentJob("t-probe", "p"));

        assertEquals("A probe-requiring UNSETTLED claim must NOT bounce", 0, phase.bounceCount());
        assertEquals("The unsettled claim must be tallied", 1, phase.unsettledCount());
    }

    /** A settleable UNSETTLED claim (the primary could capture the missing evidence) bounces. */
    @Test(timeout = 30000)
    public void settleableUnsettledClaimBounces() {
        SpyFalsificationPhase phase = new SpyFalsificationPhase();
        phase.enqueue(Collections.singletonList(assessment(FalsificationVerdict.UNSETTLED, true)));
        phase.enqueue(Collections.emptyList());

        phase.run(new CodingAgentJob("t-settleable", "p"));

        assertEquals("A settleable UNSETTLED claim must bounce so the primary can capture"
                + " the missing evidence", 1, phase.bounceCount());
    }

    /**
     * The bounce budget caps the loop: an agent that keeps re-asserting a refuted
     * claim is bounced at most {@link FalsificationPhase#DEFAULT_MAX_BOUNCES}
     * times, then the phase passes through with annotations instead of looping
     * forever or hard-failing.
     */
    @Test(timeout = 30000)
    public void bounceBudgetCapsTheLoopAndPassesThrough() {
        SpyFalsificationPhase phase = new SpyFalsificationPhase();
        // Every iteration the (stubborn) agent re-asserts the same refuted claim.
        phase.setDefaultAssessments(
                Collections.singletonList(assessment(FalsificationVerdict.REFUTED, true)));

        phase.run(new CodingAgentJob("t-budget", "p"));

        assertEquals("The phase must bounce at most DEFAULT_MAX_BOUNCES times",
                FalsificationPhase.DEFAULT_MAX_BOUNCES, phase.bounceCount());
        assertTrue("After exhausting the budget the phase must have passed through (annotated),"
                + " not looped", phase.passedThroughAfterBudget());
    }

    /** A clean primary (no gated claims) does not bounce and reports ran(). */
    @Test(timeout = 30000)
    public void cleanPrimaryDoesNotBounce() {
        SpyFalsificationPhase phase = new SpyFalsificationPhase();
        phase.enqueue(Collections.emptyList());

        phase.run(new CodingAgentJob("t-clean", "p"));

        assertEquals(0, phase.bounceCount());
        assertTrue("The phase must record that it ran", phase.ran());
    }

    // ── Orchestrator gate: doWork routes an enabled job into the phase ──────

    /**
     * The activation gate: when {@code falsificationEnabled} is true,
     * {@code doWork()} REACHES {@code runFalsificationPhase()} after primary and
     * before enforcement. Asserts the real effect (the phase is invoked), not
     * merely that a boolean is set.
     */
    @Test(timeout = 30000)
    public void enabledJobReachesFalsificationPhase() {
        SpyCodingAgentJob job = new SpyCodingAgentJob("t-reach", "p");
        job.setFalsificationEnabled(true);

        job.doWork();

        assertTrue("runFalsificationPhase() must be reached when the flag is enabled",
                job.falsificationPhaseCalled);
        assertEquals("runFalsificationPhase() must be reached exactly once (not looped)",
                1, job.falsificationPhaseCallCount);
        assertTrue("Falsification must run before enforcement rules",
                job.falsificationBeforeEnforcement);
    }

    /** When the flag is off (the default), doWork() must NOT reach the falsification phase. */
    @Test(timeout = 30000)
    public void disabledJobSkipsFalsificationPhase() {
        SpyCodingAgentJob job = new SpyCodingAgentJob("t-skip", "p");
        assertFalse("falsificationEnabled defaults to false", job.isFalsificationEnabled());

        job.doWork();

        assertFalse("runFalsificationPhase() must NOT be reached when the flag is off",
                job.falsificationPhaseCalled);
        assertEquals(0, job.falsificationPhaseCallCount);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** Builds a {@link ClaimAssessment} with the given verdict and settleability over a load-bearing claim. */
    private static ClaimAssessment assessment(FalsificationVerdict verdict, boolean settleable) {
        LoadBearingClaim claim = new LoadBearingClaim(
                "a load-bearing behavioural claim", LoadBearingClaim.Facet.RUNTIME_BEHAVIOUR,
                "Foo.java — depends on the claim", "default",
                new TruthCondition("ok", "bad", true));
        return new ClaimAssessment(claim, verdict, settleable, null, "fixture");
    }

    /**
     * A {@link FalsificationPhase} whose analysis session and bounce re-run are
     * stubbed: {@link #analyze} returns canned assessment lists (one per
     * iteration) and {@link #bounceToPrimary} counts instead of re-running.
     */
    private static final class SpyFalsificationPhase extends FalsificationPhase {
        /** Canned assessment lists, one popped per analyze() call. */
        private final Deque<List<ClaimAssessment>> queued = new ArrayDeque<>();
        /** Assessments returned by analyze() once the queue is drained; defaults to empty (clean). */
        private List<ClaimAssessment> defaultAssessments = Collections.emptyList();
        /** Set true once the phase passed through after exhausting the budget. */
        private boolean passedThroughAfterBudget;

        /** Enqueues the assessments analyze() should return on its next call. */
        void enqueue(List<ClaimAssessment> assessments) {
            queued.add(new ArrayList<>(assessments));
        }

        /** Sets the assessments analyze() returns on every call once the queue is drained. */
        void setDefaultAssessments(List<ClaimAssessment> assessments) {
            this.defaultAssessments = new ArrayList<>(assessments);
        }

        /** Returns whether the phase passed through after exhausting the bounce budget. */
        boolean passedThroughAfterBudget() { return passedThroughAfterBudget; }

        @Override
        List<ClaimAssessment> analyze(CodingAgentJob job) {
            List<ClaimAssessment> next = queued.isEmpty() ? defaultAssessments : queued.poll();
            // When the budget is spent the phase makes one final analyze() call
            // whose bounce-worthy results it must annotate-and-pass-through.
            if (bounceCount() >= DEFAULT_MAX_BOUNCES) {
                for (ClaimAssessment assessment : next) {
                    if (assessment.shouldBounce()) {
                        passedThroughAfterBudget = true;
                        break;
                    }
                }
            }
            return next;
        }

        @Override
        void bounceToPrimary(CodingAgentJob job, String findingsBlock) {
            // Do not re-run primary in the unit test; run() owns the bounce counter.
        }
    }

    /**
     * Spy job recording whether and in what order {@code doWork()} reaches the
     * falsification phase and the enforcement rules, mirroring the retrospective
     * test's spy.
     */
    static final class SpyCodingAgentJob extends CodingAgentJob {
        /** Whether runFalsificationPhase() was reached. */
        boolean falsificationPhaseCalled;
        /** Count of runFalsificationPhase() invocations. */
        int falsificationPhaseCallCount;
        /** Whether runEnforcementRules() was reached. */
        boolean enforcementRulesCalled;
        /** Whether falsification was reached before enforcement. */
        boolean falsificationBeforeEnforcement;

        /** Constructs a spy job. */
        SpyCodingAgentJob(String taskId, String prompt) {
            super(taskId, prompt);
        }

        @Override
        void runFalsificationPhase() {
            falsificationPhaseCalled = true;
            falsificationPhaseCallCount++;
        }

        @Override
        void runEnforcementRules() {
            enforcementRulesCalled = true;
            if (falsificationPhaseCalled) falsificationBeforeEnforcement = true;
        }

        @Override
        void executeSingleRun() {
            // No-op: track call ordering only, no real agent execution.
        }
    }
}
