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
 * A behavioural claim extracted from an agent's narrative/diff that the
 * {@link FalsificationGate} evaluates against the Part-A gating predicate.
 *
 * <p>A claim is <em>gated</em> (must be falsified before it can be relied on)
 * iff it satisfies all three predicate conditions:</p>
 * <ul>
 *   <li><b>P1 — contingent-empirical:</b> {@link #facet()} is not
 *       {@link Facet#NONE}; the claim is about the system's actual behaviour,
 *       not the task spec, the diff alone, or an analytic truth.</li>
 *   <li><b>P2 — load-bearing:</b> {@link #isLoadBearing()}; a namable diff hunk
 *       ({@link #dependentHunk()}) is correct only if the claim is true.</li>
 *   <li><b>P3 — unfalsified or contradicted:</b> evaluated by the gate against
 *       the captured artifacts (the disjunction of evidence-gap and
 *       evidence-contradiction).</li>
 * </ul>
 *
 * @author Michael Murray
 * @see FalsificationGate
 */
public final class LoadBearingClaim {

    /**
     * The facet of system behaviour a claim is about. These are illustrations of
     * a single property — a contingent claim about system behaviour that
     * observation, not reasoning, must settle — not an exhaustive checklist.
     */
    public enum Facet {

        /**
         * Runtime behaviour of an API/operation: what it returns, which backend
         * it dispatches to, its side effects — under a stated configuration.
         * Settling this needs evidence captured on the matching configuration.
         */
        RUNTIME_BEHAVIOUR(true),

        /**
         * Representational capacity of a type/format: which values it can encode
         * and round-trip without loss. Decidable from the type definition/source.
         */
        REPRESENTATIONAL_CAPACITY(false),

        /**
         * Causal explanation of an observed event: which mechanism actually
         * produced a symptom. Settleable from already-captured instrumentation.
         */
        CAUSAL_EXPLANATION(false),

        /** Not a contingent-empirical behavioural claim; fails P1 and is never gated. */
        NONE(false);

        /** Whether settling this facet requires evidence captured on a specific runtime configuration. */
        private final boolean requiresRuntimeConfiguration;

        /**
         * Constructs a facet.
         *
         * @param requiresRuntimeConfiguration whether settling needs a configuration-matched runtime artifact
         */
        Facet(boolean requiresRuntimeConfiguration) {
            this.requiresRuntimeConfiguration = requiresRuntimeConfiguration;
        }

        /**
         * Returns whether a claim of this facet is contingent-empirical (P1).
         *
         * @return {@code true} for every facet except {@link #NONE}
         */
        public boolean isContingentEmpirical() {
            return this != NONE;
        }

        /**
         * Returns whether settling a claim of this facet requires evidence
         * captured on the specific configuration the decision runs under. Only
         * runtime-behaviour claims do; representational-capacity and
         * causal-explanation claims are settleable from configuration-agnostic
         * source or already-captured instrumentation.
         *
         * @return {@code true} for {@link #RUNTIME_BEHAVIOUR}
         */
        public boolean requiresRuntimeConfiguration() {
            return requiresRuntimeConfiguration;
        }
    }

    /** The claim text, verbatim or paraphrased from the agent's narrative. */
    private final String text;

    /** The behavioural facet this claim is about (P1). */
    private final Facet facet;

    /** The diff hunk that is correct only if the claim is true (P2); empty when not load-bearing. */
    private final String dependentHunk;

    /** The configuration the decision built on this claim runs under; empty means agnostic. */
    private final String decisionConfiguration;

    /** The pre-stated mechanical condition for settling the claim. */
    private final TruthCondition truthCondition;

    /**
     * Constructs a load-bearing claim.
     *
     * @param text                  the claim text
     * @param facet                 the behavioural facet (P1)
     * @param dependentHunk         the diff hunk that depends on the claim (P2); empty when none
     * @param decisionConfiguration the configuration the decision runs under; {@code null}/empty means agnostic
     * @param truthCondition        the pre-stated mechanical truth condition; {@code null} treated as unstated
     */
    public LoadBearingClaim(String text, Facet facet, String dependentHunk,
                            String decisionConfiguration, TruthCondition truthCondition) {
        this.text = text == null ? "" : text;
        this.facet = facet == null ? Facet.NONE : facet;
        this.dependentHunk = dependentHunk == null ? "" : dependentHunk;
        this.decisionConfiguration = decisionConfiguration == null ? "" : decisionConfiguration;
        this.truthCondition = truthCondition == null ? TruthCondition.unstated() : truthCondition;
    }

    /**
     * Returns the claim text.
     *
     * @return the text; never {@code null}
     */
    public String text() {
        return text;
    }

    /**
     * Returns the behavioural facet (P1).
     *
     * @return the facet; never {@code null}
     */
    public Facet facet() {
        return facet;
    }

    /**
     * Returns the diff hunk that is correct only if the claim is true (P2).
     *
     * @return the dependent hunk; empty when the claim is not load-bearing
     */
    public String dependentHunk() {
        return dependentHunk;
    }

    /**
     * Returns the configuration the decision built on this claim runs under.
     *
     * @return the configuration; empty when configuration-agnostic
     */
    public String decisionConfiguration() {
        return decisionConfiguration;
    }

    /**
     * Returns the pre-stated mechanical truth condition.
     *
     * @return the truth condition; never {@code null}
     */
    public TruthCondition truthCondition() {
        return truthCondition;
    }

    /**
     * Returns whether the claim is contingent-empirical (P1).
     *
     * @return {@code true} when the facet is not {@link Facet#NONE}
     */
    public boolean isContingentEmpirical() {
        return facet.isContingentEmpirical();
    }

    /**
     * Returns whether the claim is load-bearing (P2): a namable diff hunk is
     * correct only if the claim is true.
     *
     * @return {@code true} when a dependent hunk is named
     */
    public boolean isLoadBearing() {
        return !dependentHunk.isEmpty();
    }

    /**
     * Returns whether this claim could, in principle, be settled by the supplied
     * captured artifacts — used to distinguish a claim the primary could
     * plausibly settle (so a bounce is productive) from a probe-requiring claim
     * for which no captured artifact can ever entail or refute it (so the honest
     * action is to report {@link FalsificationVerdict#UNSETTLED} and pass
     * through rather than bounce repeatedly).
     *
     * <p>A representational-capacity or causal-explanation claim is always
     * artifact-settleable in principle (source/instrumentation can settle it). A
     * runtime-behaviour claim is artifact-settleable only when an evidential
     * artifact of a runtime-capable kind was captured on the matching
     * configuration; otherwise it needs a probe (out of scope for v1).</p>
     *
     * @param artifacts the captured artifacts available to the gate
     * @return {@code true} when some captured artifact can settle this claim
     */
    public boolean isArtifactSettleableBy(List<CapturedArtifact> artifacts) {
        if (!facet.requiresRuntimeConfiguration()) {
            return true;
        }
        for (CapturedArtifact artifact : artifacts) {
            if (artifact.kind().isEvidential()
                    && artifact.kind().settlesRuntimeDispatch()
                    && artifact.configurationRelevantFor(this)) {
                return true;
            }
        }
        return false;
    }
}
