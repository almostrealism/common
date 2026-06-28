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

/**
 * A pre-stated, mechanical condition for deciding whether a captured artifact
 * entails (or contradicts) a {@link LoadBearingClaim}.
 *
 * <p>This is the anti-gaming primitive from the falsification design's
 * entailment requirement: a claim may only be marked {@link
 * FalsificationVerdict#CONFIRMED} when the falsifier states, <em>before</em>
 * inspecting the artifact, exactly what content would make the claim true
 * ({@link #entailsMarker()}) or false ({@link #refutesMarker()}). The verdict
 * is then a mechanical substring comparison rather than a prose summary the
 * agent could fabricate. A truth condition that is not {@link #isStated()} or
 * not {@link #isMechanical()} can never yield {@code CONFIRMED}; the gate
 * reports {@link FalsificationVerdict#UNSETTLED} instead.</p>
 *
 * <p>"Mechanical" means the condition is a deterministic expected-vs-actual
 * match (e.g. "the probe output must contain {@code backend=CPU}"), not an
 * inference-to-best-explanation that a verifier could read more than one way.
 * The falsifier sets {@link #isMechanical()} to {@code false} when it can only
 * offer a soft, judgement-laden condition — in which case the adversarial
 * preference for {@code UNSETTLED} over {@code CONFIRMED} applies.</p>
 *
 * @author Michael Murray
 * @see FalsificationGate
 * @see CapturedArtifact
 */
public final class TruthCondition {

    /** Substring whose presence in a relevant artifact entails the claim; empty when none stated. */
    private final String entailsMarker;

    /** Substring whose presence in a relevant artifact entails the claim's negation; empty when none stated. */
    private final String refutesMarker;

    /** Whether the condition is a deterministic expected-vs-actual match rather than a soft judgement. */
    private final boolean mechanical;

    /**
     * Constructs a truth condition.
     *
     * @param entailsMarker substring that entails the claim; {@code null} treated as empty
     * @param refutesMarker substring that entails the claim's negation; {@code null} treated as empty
     * @param mechanical    {@code true} when the condition is a deterministic match
     */
    public TruthCondition(String entailsMarker, String refutesMarker, boolean mechanical) {
        this.entailsMarker = entailsMarker == null ? "" : entailsMarker;
        this.refutesMarker = refutesMarker == null ? "" : refutesMarker;
        this.mechanical = mechanical;
    }

    /**
     * Returns a truth condition with no stated markers — the absence of a truth
     * condition. A claim carrying this can never be {@code CONFIRMED}.
     *
     * @return an unstated truth condition
     */
    public static TruthCondition unstated() {
        return new TruthCondition("", "", false);
    }

    /**
     * Returns the substring whose presence in a relevant artifact entails the claim.
     *
     * @return the entailment marker; empty when none was stated
     */
    public String entailsMarker() {
        return entailsMarker;
    }

    /**
     * Returns the substring whose presence in a relevant artifact entails the claim's negation.
     *
     * @return the refutation marker; empty when none was stated
     */
    public String refutesMarker() {
        return refutesMarker;
    }

    /**
     * Returns whether this condition is a deterministic expected-vs-actual match.
     *
     * @return {@code true} when mechanical; {@code false} for a soft, judgement-laden condition
     */
    public boolean isMechanical() {
        return mechanical;
    }

    /**
     * Returns whether at least one marker (entailment or refutation) was stated.
     * An unstated condition cannot produce a {@code CONFIRMED} verdict.
     *
     * @return {@code true} when an entailment or refutation marker is present
     */
    public boolean isStated() {
        return !entailsMarker.isEmpty() || !refutesMarker.isEmpty();
    }
}
