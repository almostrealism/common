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
 * The verdict the {@link FalsificationGate} reaches for a single load-bearing
 * behavioural claim, after applying the Part-A gating predicate and the
 * anti-gaming entailment rules.
 *
 * <p>The four values are deliberately distinct so the orchestration layer can
 * act differently on each. In particular {@link #UNSETTLED} is a first-class
 * outcome, NOT a synonym for {@link #CONFIRMED}: the gate reports
 * {@code UNSETTLED} when it cannot mechanically settle a claim from captured
 * artifacts (e.g. a runtime-dispatch claim that needs a probe on a
 * configuration the captured evidence does not cover). A gate that manufactured
 * {@code CONFIRMED} in that situation would be the exact failure mode the
 * falsification phase exists to prevent.</p>
 *
 * @author Michael Murray
 * @see FalsificationGate
 * @see ClaimAssessment
 */
public enum FalsificationVerdict {

    /**
     * Captured evidence ENTAILS the claim on the configuration the decision
     * runs under, under a pre-stated mechanical truth condition. The claim may
     * be relied upon; no bounce.
     */
    CONFIRMED,

    /**
     * Captured evidence ENTAILS the negation of the claim (the P3b
     * contradiction branch, or a source/definition that contradicts a
     * representational-capacity claim). The decision built on the claim is
     * unsound; this is the canonical bounce trigger.
     */
    REFUTED,

    /**
     * No captured artifact entails the claim or its negation on the relevant
     * configuration. The honest outcome when the claim can only be settled by
     * evidence not present in the captured context (e.g. a probe on a
     * configuration the phase cannot reach). Never upgraded to
     * {@link #CONFIRMED} by fiat.
     */
    UNSETTLED,

    /**
     * The claim is not gated at all: it fails P1 (not a contingent-empirical
     * behavioural claim) or P2 (no diff hunk depends on it). Nothing to settle.
     */
    NOT_GATED
}
