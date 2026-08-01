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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The proof for the falsification gate: the three real failure cases from the
 * experiment (Part A), reconstructed as concrete fixtures (captured context +
 * the load-bearing claim + the dependent diff hunk), run against
 * {@link FalsificationGate}.
 *
 * <p>The fixtures are <em>fail-without / pass-with</em>: a naive
 * "presence-of-evidence ⇒ settled" predicate — the obvious stub — scores
 * <b>2/3</b>, waving through Case 3 (the lost-hide race), where the agent held
 * contradicting instrumentation in context and overrode it. The real gate's
 * disjunctive P3 (gap OR contradiction) scores <b>3/3</b>. The
 * {@link #case3LostHideRace_naiveWavesThrough_realGateRefutes()} test pins
 * exactly that difference, so the suite cannot pass with a tautological gate.</p>
 *
 * <p>The remaining tests pin the anti-gaming requirements (DESIGN §6): the gate
 * refuses to confirm a claim whose only evidence is the agent asserting it, a
 * wrong-configuration artifact, a missing truth condition, or a non-mechanical
 * one — it reports {@code UNSETTLED} rather than fabricating {@code CONFIRMED}.</p>
 */
public class FalsificationGateTest extends TestSuiteBase {

    /** The gate under test. */
    private final FalsificationGate gate = new FalsificationGate();

    // ── Case 1 — the isCPU() guard (runtime dispatch, probe-requiring) ──────

    /**
     * Case 1 fixture: a runtime-dispatch claim whose decision runs on a
     * dual-backend host, with NO captured artifact on that configuration. The
     * gate must report {@link FalsificationVerdict#UNSETTLED} — never
     * {@code CONFIRMED} — and recognise it as probe-requiring (not
     * artifact-settleable), so it is passed through rather than bounced.
     */
    @Test(timeout = 30000)
    public void case1IsCpuGuard_isUnsettledProbeRequiring_notConfirmed() {
        ClaimAssessment assessment = gate.classify(case1Claim(), case1Artifacts());

        Assert.assertEquals("Case 1 (isCPU dual-backend) must be UNSETTLED, not CONFIRMED:"
                        + " no captured artifact settles it on the dual-backend configuration",
                FalsificationVerdict.UNSETTLED, assessment.verdict());
        assertFalse("Case 1 is probe-requiring: no captured artifact can settle it,"
                + " so it must not be reported as artifact-settleable", assessment.artifactSettleable());
        assertFalse("Case 1 (probe-requiring UNSETTLED) must NOT bounce — it would loop"
                + " forever since no captured artifact can ever settle it", assessment.shouldBounce());
    }

    // ── Case 2 — anchoring the captured pitch on MIDI (representational) ─────

    /**
     * Case 2 fixture: a representational-capacity claim contradicted by the
     * internal pitch type's source (it can express microtonal pitches a MIDI
     * int cannot). Settleable from source, so the gate REFUTES it and bounces.
     */
    @Test(timeout = 30000)
    public void case2MidiAnchor_sourceContradicts_isRefutedAndBounces() {
        ClaimAssessment assessment = gate.classify(case2Claim(), case2RefutingArtifacts());

        Assert.assertEquals("Case 2 must be REFUTED: the internal pitch type's source entails the"
                        + " claim's negation (it expresses pitches a MIDI int cannot)",
                FalsificationVerdict.REFUTED, assessment.verdict());
        assertTrue("A refuted load-bearing claim must bounce the job back to primary",
                assessment.shouldBounce());
    }

    /**
     * Case 2, the other direction: when the source actually entails the
     * round-trip (a genuinely MIDI-equivalent type) under a pre-stated
     * mechanical truth condition, the gate CONFIRMS it. This proves the gate
     * settles from source both ways and is not a refuse-everything stub.
     */
    @Test(timeout = 30000)
    public void case2MidiAnchor_sourceEntails_isConfirmed() {
        ClaimAssessment assessment = gate.classify(case2Claim(), case2ConfirmingArtifacts());

        Assert.assertEquals("When the source entails the round-trip under a mechanical truth"
                        + " condition, Case 2's claim must be CONFIRMED",
                FalsificationVerdict.CONFIRMED, assessment.verdict());
        assertFalse("A confirmed claim must not bounce", assessment.shouldBounce());
    }

    // ── Case 3 — the lost-hide race (causal explanation, P3b) ───────────────

    /**
     * Case 3 fixture: a causal-explanation claim that the captured
     * instrumentation contradicts (the hide was received and returned cleanly;
     * the hang is elsewhere). The gate REFUTES it via the P3b contradiction
     * branch and bounces.
     */
    @Test(timeout = 30000)
    public void case3LostHideRace_instrumentationContradicts_isRefutedAndBounces() {
        ClaimAssessment assessment = gate.classify(case3Claim(), case3Artifacts());

        Assert.assertEquals("Case 3 must be REFUTED via P3b: the captured instrumentation entails"
                        + " the negation of the lost-hide theory",
                FalsificationVerdict.REFUTED, assessment.verdict());
        assertTrue("A refuted load-bearing claim must bounce the job back to primary",
                assessment.shouldBounce());
    }

    /**
     * The non-tautology proof: the naive "presence-of-runtime-evidence ⇒ settled"
     * predicate WAVES Case 3 through (the instrumentation is present, so it
     * assumes the claim is settled), while the real gate REFUTES it. This is the
     * single case that separates the disjunctive P3 from the naive gap-only
     * predicate, so it is asserted on its own.
     */
    @Test(timeout = 30000)
    public void case3LostHideRace_naiveWavesThrough_realGateRefutes() {
        List<CapturedArtifact> artifacts = case3Artifacts();

        assertTrue("The naive predicate sees runtime evidence present and waves Case 3"
                + " through — this is exactly the miss the disjunctive P3 fixes",
                naivePresenceAccepts(artifacts));
        Assert.assertEquals("The real gate must REFUTE Case 3 where the naive gate accepts it",
                FalsificationVerdict.REFUTED, gate.classify(case3Claim(), artifacts).verdict());
    }

    /**
     * The scoring summary: the naive gate catches 2/3 (misses Case 3); the real
     * gate catches 3/3. "Caught" means the gate did NOT wave the case through —
     * the claim is flagged for attention (UNSETTLED or REFUTED), not accepted as
     * settled.
     */
    @Test(timeout = 30000)
    public void naiveScoresTwoOfThree_realGateScoresThreeOfThree() {
        List<List<CapturedArtifact>> artifactSets = Arrays.asList(
                case1Artifacts(), case2RefutingArtifacts(), case3Artifacts());
        List<LoadBearingClaim> claims = Arrays.asList(case1Claim(), case2Claim(), case3Claim());

        int naiveCaught = 0;
        int realCaught = 0;
        for (int i = 0; i < claims.size(); i++) {
            if (!naivePresenceAccepts(artifactSets.get(i))) naiveCaught++;
            FalsificationVerdict verdict = gate.classify(claims.get(i), artifactSets.get(i)).verdict();
            if (verdict == FalsificationVerdict.UNSETTLED || verdict == FalsificationVerdict.REFUTED) {
                realCaught++;
            }
        }

        Assert.assertEquals("The naive presence-of-evidence predicate must score 2/3 (it misses"
                + " the lost-hide race, where evidence is present but contradicts)", 2, naiveCaught);
        Assert.assertEquals("The disjunctive-P3 gate must score 3/3", 3, realCaught);
    }

    // ── Anti-gaming (DESIGN §6) ─────────────────────────────────────────────

    /**
     * Truth-condition binding (DESIGN §6.1): a claim with NO pre-stated truth
     * condition can never be CONFIRMED, even when an artifact would look
     * confirming. The honest outcome is UNSETTLED.
     */
    @Test(timeout = 30000)
    public void noTruthCondition_cannotConfirm() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "the operation returns a sorted list",
                LoadBearingClaim.Facet.RUNTIME_BEHAVIOUR,
                "Sorter.java — relies on sorted output",
                "default",
                TruthCondition.unstated());
        List<CapturedArtifact> artifacts = Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.COMMAND_OUTPUT, "result=sorted", "default"));

        ClaimAssessment assessment = gate.classify(claim, artifacts);
        Assert.assertNotEquals("A claim with no pre-stated truth condition must never be CONFIRMED",
                FalsificationVerdict.CONFIRMED, assessment.verdict());
        assertEquals(FalsificationVerdict.UNSETTLED, assessment.verdict());
    }

    /**
     * Configuration tagging (DESIGN §6.2): a CPU-only artifact cannot confirm a
     * dual-backend claim, even though its text contains the entailment marker.
     * This is the mechanical encoding of "entails &hellip; on the relevant
     * configuration" — the exact trap that reproduced the original isCPU() bug.
     */
    @Test(timeout = 30000)
    public void wrongConfigurationArtifact_cannotConfirm() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "isCPU() reflects the operation's execution backend",
                LoadBearingClaim.Facet.RUNTIME_BEHAVIOUR,
                "FooTest.java — assumeTrue(isCPU()) guard",
                "dual-backend",
                new TruthCondition("backend=consistent", "backend=mismatch", true));
        // The CPU-only probe prints the confirming marker — but on the WRONG config.
        List<CapturedArtifact> artifacts = Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.COMMAND_OUTPUT, "backend=consistent", "cpu-only"));

        ClaimAssessment assessment = gate.classify(claim, artifacts);
        Assert.assertNotEquals("A CPU-only artifact must not confirm a dual-backend claim",
                FalsificationVerdict.CONFIRMED, assessment.verdict());
        Assert.assertEquals("Wrong-configuration evidence forces UNSETTLED", FalsificationVerdict.UNSETTLED,
                assessment.verdict());
    }

    /**
     * Adversarial preference for UNSETTLED (DESIGN §6.3): when the truth
     * condition is not mechanical (a soft judgement, not a deterministic match),
     * the gate refuses to confirm even with an entailing-looking artifact.
     */
    @Test(timeout = 30000)
    public void nonMechanicalTruthCondition_cannotConfirm() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "the captured pitch round-trips through a MIDI int",
                LoadBearingClaim.Facet.REPRESENTATIONAL_CAPACITY,
                "Capture.proto — pitch field typed MIDI int",
                "",
                new TruthCondition("looks midi equivalent", "", false));
        List<CapturedArtifact> artifacts = Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.SOURCE, "looks midi equivalent", ""));

        ClaimAssessment assessment = gate.classify(claim, artifacts);
        Assert.assertNotEquals("A non-mechanical truth condition must not yield CONFIRMED",
                FalsificationVerdict.CONFIRMED, assessment.verdict());
    }

    /**
     * The headline anti-gaming guarantee (DESIGN §6.4): the gate refuses to
     * CONFIRM a claim whose only evidence is the agent asserting it. A bare
     * assertion that restates the claim is not evidence.
     */
    @Test(timeout = 30000)
    public void bareAgentAssertion_cannotConfirm() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "this call dispatches to the GPU backend",
                LoadBearingClaim.Facet.RUNTIME_BEHAVIOUR,
                "Dispatcher.java — assumes GPU path",
                "default",
                new TruthCondition("backend=GPU", "backend=CPU", true));
        // The "evidence" is the agent restating its own conclusion.
        List<CapturedArtifact> artifacts = Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.AGENT_ASSERTION,
                        "I am confident the backend=GPU here", "default"));

        ClaimAssessment assessment = gate.classify(claim, artifacts);
        Assert.assertNotEquals("The gate must never CONFIRM on bare agent assertion",
                FalsificationVerdict.CONFIRMED, assessment.verdict());
        Assert.assertEquals("Bare assertion yields the honest UNSETTLED", FalsificationVerdict.UNSETTLED,
                assessment.verdict());
    }

    /**
     * Positive control: a genuine, configuration-matched, mechanically-entailing
     * runtime artifact DOES confirm. Without this the suite could pass with a
     * gate that always reports UNSETTLED.
     */
    @Test(timeout = 30000)
    public void matchingRuntimeEvidence_confirms() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "this call dispatches to the GPU backend",
                LoadBearingClaim.Facet.RUNTIME_BEHAVIOUR,
                "Dispatcher.java — assumes GPU path",
                "dual-backend",
                new TruthCondition("backend=GPU", "backend=CPU", true));
        List<CapturedArtifact> artifacts = Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.INSTRUMENTATION, "backend=GPU", "dual-backend"));

        Assert.assertEquals("Configuration-matched mechanically-entailing evidence must CONFIRM",
                FalsificationVerdict.CONFIRMED, gate.classify(claim, artifacts).verdict());
    }

    // ── P1 / P2 selectivity ─────────────────────────────────────────────────

    /** A non-contingent claim (fails P1) is NOT_GATED — there is nothing to falsify. */
    @Test(timeout = 30000)
    public void nonContingentClaim_isNotGated() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "the user asked for a new field", LoadBearingClaim.Facet.NONE,
                "Foo.java — adds the field", "", TruthCondition.unstated());
        assertEquals(FalsificationVerdict.NOT_GATED,
                gate.classify(claim, Collections.emptyList()).verdict());
    }

    /** A claim with no dependent hunk (fails P2) is NOT_GATED — a throwaway aside. */
    @Test(timeout = 30000)
    public void claimWithNoDependentHunk_isNotGated() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "Metal is generally slower", LoadBearingClaim.Facet.RUNTIME_BEHAVIOUR,
                "", "default", new TruthCondition("slower", "", true));
        assertEquals(FalsificationVerdict.NOT_GATED,
                gate.classify(claim, Collections.emptyList()).verdict());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** The naive "presence-of-runtime-evidence ⇒ settled" predicate (the stub that scores 2/3). */
    private static boolean naivePresenceAccepts(List<CapturedArtifact> artifacts) {
        for (CapturedArtifact artifact : artifacts) {
            CapturedArtifact.Kind kind = artifact.kind();
            if (kind == CapturedArtifact.Kind.TEST_OUTPUT
                    || kind == CapturedArtifact.Kind.COMMAND_OUTPUT
                    || kind == CapturedArtifact.Kind.INSTRUMENTATION) {
                return true;
            }
        }
        return false;
    }

    /** Case 1 load-bearing claim: isCPU() reflects the operation's backend on a dual-backend host. */
    private static LoadBearingClaim case1Claim() {
        return new LoadBearingClaim(
                "getLocalHardware().getComputeContext().isCPU() is true iff the operation under"
                        + " test runs on CPU and false iff it runs on Metal",
                LoadBearingClaim.Facet.RUNTIME_BEHAVIOUR,
                "ResamplingShapeTest.java — assumeTrue(isCPU()) skips the test on Metal;"
                        + " wrong if isCPU() does not reflect the operation's backend",
                "dual-backend",
                new TruthCondition("backend=consistent", "backend=mismatch", true));
    }

    /** Case 1 artifacts: only the agent's own assertion — no probe was run on a dual-backend host. */
    private static List<CapturedArtifact> case1Artifacts() {
        return new ArrayList<>(Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.AGENT_ASSERTION,
                        "Linux means CPU and macOS means Metal, so isCPU() tracks the backend",
                        "")));
    }

    /** Case 2 load-bearing claim: the captured pitch round-trips through a MIDI int without loss. */
    private static LoadBearingClaim case2Claim() {
        return new LoadBearingClaim(
                "the captured pitch is fully representable as a MIDI note integer (the internal"
                        + " pitch type round-trips through a MIDI int without loss)",
                LoadBearingClaim.Facet.REPRESENTATIONAL_CAPACITY,
                "CapturedPitch.proto — pitch field typed as a MIDI int; truncates any pitch the"
                        + " internal type can express but a MIDI int cannot",
                "",
                new TruthCondition("midiEquivalent=true", "microtonal", true));
    }

    /** Case 2 refuting artifacts: the internal pitch type's source shows microtonal support, plus the planning doc. */
    private static List<CapturedArtifact> case2RefutingArtifacts() {
        return new ArrayList<>(Arrays.asList(
                new CapturedArtifact(CapturedArtifact.Kind.SOURCE,
                        "class Pitch { double cents; // supports microtonal pitches beyond MIDI }", ""),
                new CapturedArtifact(CapturedArtifact.Kind.PLANNING_DOC,
                        "Anchor persistence on the internal representation; it is more general"
                                + " than MIDI and supports microtonal pitches", "")));
    }

    /** Case 2 confirming artifacts: a (hypothetical) MIDI-equivalent type whose source entails the round-trip. */
    private static List<CapturedArtifact> case2ConfirmingArtifacts() {
        return new ArrayList<>(Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.SOURCE,
                        "enum Pitch { /* exactly the 128 MIDI note numbers */ } // midiEquivalent=true", "")));
    }

    /** Case 3 load-bearing claim: a hide arrives before window registration and is lost. */
    private static LoadBearingClaim case3Claim() {
        return new LoadBearingClaim(
                "the hide is being lost before window registration (a hide arrives before the"
                        + " window is registered and is dropped)",
                LoadBearingClaim.Facet.CAUSAL_EXPLANATION,
                "PluginUIManager.java — the pendingHidePluginIds machinery; dead code guarding a"
                        + " path that never executes if the hide is not actually lost",
                "",
                new TruthCondition("hide dropped before registration",
                        "hide received and returned cleanly", true));
    }

    /** Case 3 artifacts: the instrumented run, already in context, showing the hide was NOT lost. */
    private static List<CapturedArtifact> case3Artifacts() {
        return new ArrayList<>(Collections.singletonList(
                new CapturedArtifact(CapturedArtifact.Kind.INSTRUMENTATION,
                        "hide received and returned cleanly; show: ENTRY then blocked in AU"
                                + " view-controller resolution (no window-server session)", "")));
    }
}
