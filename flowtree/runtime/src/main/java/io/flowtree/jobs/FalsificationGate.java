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

import java.util.List;

/**
 * The mechanical core of the falsification phase: given a {@link LoadBearingClaim}
 * and the {@link CapturedArtifact}s available to settle it, applies the Part-A
 * gating predicate (P1 &and; P2 &and; P3) and the design's anti-gaming
 * entailment rules to reach a {@link ClaimAssessment}.
 *
 * <p>The classifier is a pure function of its inputs — it runs no commands and
 * reads no files — so it is unit-testable against reconstructed fixtures of the
 * real failure cases. The agent session that surrounds it (see
 * {@link FalsificationPhase}) only EXTRACTS structured claims and tags
 * artifacts; this gate, in trusted Java, COMPUTES the verdict. That separation
 * is itself an anti-gaming property: the agent cannot fabricate a
 * {@link FalsificationVerdict#CONFIRMED} by writing a persuasive narrative,
 * because the verdict is recomputed mechanically from the artifact content and
 * the pre-stated {@link TruthCondition}.</p>
 *
 * <h2>The disjunctive P3</h2>
 * <p>P3 is a disjunction: a claim is gated when EITHER no captured artifact
 * entails it (the evidence gap, P3a) OR a captured artifact entails its negation
 * yet the decision proceeds anyway (the contradiction, P3b). The contradiction
 * branch is what catches the lost-hide-race case, where the agent held
 * contradicting instrumentation in context and overrode it. A naive gate that
 * only checked for the presence of evidence waves that case through; this gate
 * does not.</p>
 *
 * <h2>The verdict order</h2>
 * <ol>
 *   <li><b>NOT_GATED</b> when the claim fails P1 (not contingent-empirical) or
 *       P2 (no dependent hunk).</li>
 *   <li><b>REFUTED</b> when a configuration-relevant evidential artifact
 *       contradicts the claim (P3b, and source-contradiction for
 *       representational claims).</li>
 *   <li><b>CONFIRMED</b> when a configuration-matched evidential artifact
 *       mechanically entails the claim under a pre-stated truth condition.</li>
 *   <li><b>UNSETTLED</b> otherwise — the honest outcome when captured evidence
 *       neither entails nor contradicts the claim on the relevant
 *       configuration.</li>
 * </ol>
 *
 * @author Michael Murray
 * @see ClaimAssessment
 * @see FalsificationPhase
 */
public final class FalsificationGate {

    /**
     * Classifies {@code claim} against {@code artifacts}, applying the gating
     * predicate and the anti-gaming entailment rules.
     *
     * @param claim     the load-bearing claim to assess
     * @param artifacts the captured artifacts available to settle it
     * @return the assessment, never {@code null}
     */
    public ClaimAssessment classify(LoadBearingClaim claim, List<CapturedArtifact> artifacts) {
        // P1 — contingent-empirical content.
        if (!claim.isContingentEmpirical()) {
            return new ClaimAssessment(claim, FalsificationVerdict.NOT_GATED, false, null,
                    "Not a contingent-empirical behavioural claim (fails P1); nothing to falsify.");
        }
        // P2 — load-bearing.
        if (!claim.isLoadBearing()) {
            return new ClaimAssessment(claim, FalsificationVerdict.NOT_GATED, false, null,
                    "No diff hunk depends on the claim (fails P2); a throwaway aside, not gated.");
        }

        // P3b — contradiction: a configuration-relevant evidential artifact
        // entails the negation of the claim. Checked first: a refutation is the
        // strongest signal and the canonical bounce trigger.
        for (CapturedArtifact artifact : artifacts) {
            if (artifact.contradicts(claim)) {
                return new ClaimAssessment(claim, FalsificationVerdict.REFUTED, true, artifact,
                        "Captured " + artifact.kind() + " entails the negation of the claim"
                                + " (P3b contradiction); the dependent hunk is unsound.");
            }
        }

        // CONFIRMED — a configuration-matched evidential artifact mechanically
        // entails the claim under a pre-stated truth condition. Truth-condition
        // binding and configuration tagging are enforced inside
        // CapturedArtifact.entails(), so a bare assertion, a missing/soft truth
        // condition, or wrong-configuration evidence can never confirm.
        if (claim.truthCondition().isStated()) {
            for (CapturedArtifact artifact : artifacts) {
                if (artifact.entails(claim)) {
                    return new ClaimAssessment(claim, FalsificationVerdict.CONFIRMED, true, artifact,
                            "Captured " + artifact.kind() + " entails the claim under the"
                                    + " pre-stated mechanical truth condition on the matching"
                                    + " configuration.");
                }
            }
        }

        // UNSETTLED — neither entailed nor contradicted on the relevant
        // configuration. Honest, never upgraded to CONFIRMED. artifactSettleable
        // distinguishes a claim the primary could settle by capturing evidence
        // (bounce-worthy) from a probe-requiring claim (annotate and pass through).
        boolean settleable = claim.isArtifactSettleableBy(artifacts);
        String reason = settleable
                ? "No captured artifact entails or contradicts the claim, but it could be"
                        + " settled by capturing the missing evidence (P3a evidence gap)."
                : "No captured artifact can settle this claim on the configuration the decision"
                        + " runs under; it requires a probe (out of scope for v1). Reported"
                        + " UNSETTLED rather than fabricating CONFIRMED.";
        return new ClaimAssessment(claim, FalsificationVerdict.UNSETTLED, settleable, null, reason);
    }
}
