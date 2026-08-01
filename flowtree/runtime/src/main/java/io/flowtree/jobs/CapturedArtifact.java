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
 * A single piece of evidence available to the {@link FalsificationGate} when
 * settling a {@link LoadBearingClaim}: the captured content, the kind of
 * artifact it is, and — critically — the configuration it was gathered on.
 *
 * <p>Configuration tagging is the mechanical encoding of the design's
 * "entails &hellip; on the relevant configuration" clause. Evidence gathered on
 * a configuration that differs from the one the decision runs under does NOT
 * entail the claim — a CPU-only command output cannot confirm a dual-backend
 * dispatch claim, even if its text looks confirming. This is exactly the trap
 * that produced the original {@code isCPU()} bug, so it is enforced here rather
 * than left to agent narrative.</p>
 *
 * <p>Entailment and contradiction are evaluated against the claim's pre-stated
 * {@link TruthCondition} via mechanical substring tests, so the verdict is not
 * a prose summary the agent could fabricate.</p>
 *
 * @author Michael Murray
 * @see FalsificationGate
 * @see TruthCondition
 */
public final class CapturedArtifact {

    /**
     * The provenance of a captured artifact, which determines whether it counts
     * as evidence and whether it can settle a runtime-dispatch claim.
     */
    public enum Kind {

        /** Output of a test run (e.g. JUnit/Surefire). Runtime evidence. */
        TEST_OUTPUT(true, true),

        /** Combined stdout/stderr of a command the agent ran. Runtime evidence. */
        COMMAND_OUTPUT(true, true),

        /** Instrumentation / log lines the agent's own code emitted at runtime. Runtime evidence. */
        INSTRUMENTATION(true, true),

        /** Source excerpt (a symbol definition or call site). Settles representational/structural claims, not runtime dispatch. */
        SOURCE(true, false),

        /** Documentation excerpt. Constitutively insufficient for a runtime-dispatch claim. */
        DOC(true, false),

        /** Branch planning document. A contradiction source for the P3b branch; not runtime evidence. */
        PLANNING_DOC(true, false),

        /** The agent's own assertion of the claim. NOT evidence — restating a claim cannot entail it. */
        AGENT_ASSERTION(false, false);

        /** Whether an artifact of this kind counts as evidence at all. */
        private final boolean evidential;

        /** Whether an artifact of this kind can settle a runtime-dispatch (P1 runtime-behaviour) claim. */
        private final boolean settlesRuntimeDispatch;

        /**
         * Constructs a kind.
         *
         * @param evidential             whether the kind counts as evidence
         * @param settlesRuntimeDispatch whether the kind can settle a runtime-dispatch claim
         */
        Kind(boolean evidential, boolean settlesRuntimeDispatch) {
            this.evidential = evidential;
            this.settlesRuntimeDispatch = settlesRuntimeDispatch;
        }

        /**
         * Returns whether artifacts of this kind count as evidence. A bare
         * {@link #AGENT_ASSERTION} is not evidence: an agent restating its own
         * claim cannot make the claim true.
         *
         * @return {@code true} when the kind is evidential
         */
        public boolean isEvidential() {
            return evidential;
        }

        /**
         * Returns whether artifacts of this kind can settle a runtime-dispatch
         * claim. Documentation and source are constitutively insufficient to
         * establish which backend a specific operation dispatches to at runtime.
         *
         * @return {@code true} when the kind can settle a runtime-dispatch claim
         */
        public boolean settlesRuntimeDispatch() {
            return settlesRuntimeDispatch;
        }
    }

    /** The kind / provenance of this artifact. */
    private final Kind kind;

    /** The captured textual content (command output, source excerpt, log lines, ...). */
    private final String content;

    /** The configuration this artifact was gathered on; empty means configuration-agnostic. */
    private final String configuration;

    /**
     * Constructs a captured artifact.
     *
     * @param kind          the provenance of the artifact
     * @param content       the captured textual content; {@code null} treated as empty
     * @param configuration the configuration it was gathered on; {@code null}/empty means agnostic
     */
    public CapturedArtifact(Kind kind, String content, String configuration) {
        this.kind = kind;
        this.content = content == null ? "" : content;
        this.configuration = configuration == null ? "" : configuration;
    }

    /**
     * Returns the kind / provenance of this artifact.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the captured textual content.
     *
     * @return the content; never {@code null}
     */
    public String content() {
        return content;
    }

    /**
     * Returns the configuration this artifact was gathered on.
     *
     * @return the configuration; empty when configuration-agnostic
     */
    public String configuration() {
        return configuration;
    }

    /**
     * Returns whether this artifact is configuration-relevant to {@code claim}.
     *
     * <p>For a runtime-behaviour claim the configuration is part of the claim:
     * the artifact is relevant only when its configuration exactly matches the
     * configuration the decision runs under (and both are stated). For other
     * facets (representational capacity, causal explanation) the evidence is
     * configuration-agnostic, so any artifact is relevant.</p>
     *
     * @param claim the claim being settled
     * @return {@code true} when this artifact may bear on the claim for its configuration
     */
    public boolean configurationRelevantFor(LoadBearingClaim claim) {
        if (!claim.facet().requiresRuntimeConfiguration()) {
            return true;
        }
        String decision = claim.decisionConfiguration();
        return !decision.isEmpty() && !configuration.isEmpty()
                && decision.equalsIgnoreCase(configuration);
    }

    /**
     * Returns whether this artifact mechanically ENTAILS {@code claim}: it is
     * evidence, it is configuration-relevant, the claim's truth condition is a
     * stated mechanical condition, the entailment marker appears in the content,
     * and the refutation marker does NOT (no consistent reading under which the
     * claim is false).
     *
     * @param claim the claim being settled
     * @return {@code true} when this artifact entails the claim
     */
    public boolean entails(LoadBearingClaim claim) {
        TruthCondition condition = claim.truthCondition();
        if (!kind.isEvidential() || !configurationRelevantFor(claim)) return false;
        if (!condition.isStated() || !condition.isMechanical()) return false;
        if (condition.entailsMarker().isEmpty()) return false;
        boolean entailingPresent = content.contains(condition.entailsMarker());
        boolean refutingPresent = !condition.refutesMarker().isEmpty()
                && content.contains(condition.refutesMarker());
        return entailingPresent && !refutingPresent;
    }

    /**
     * Returns whether this artifact ENTAILS the negation of {@code claim}: it is
     * evidence, it is configuration-relevant, a refutation marker is stated, and
     * that marker appears in the content. This is the mechanical encoding of the
     * P3b contradiction branch and of a source/definition that contradicts a
     * representational-capacity claim.
     *
     * @param claim the claim being settled
     * @return {@code true} when this artifact contradicts the claim
     */
    public boolean contradicts(LoadBearingClaim claim) {
        TruthCondition condition = claim.truthCondition();
        if (!kind.isEvidential() || !configurationRelevantFor(claim)) return false;
        return !condition.refutesMarker().isEmpty()
                && content.contains(condition.refutesMarker());
    }
}
