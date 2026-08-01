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
 * The outcome of {@link FalsificationGate#classify} for a single
 * {@link LoadBearingClaim}: the {@link FalsificationVerdict}, whether the claim
 * was settleable from the captured artifacts at all, the evidence that drove the
 * verdict (when any), and a human-readable reason.
 *
 * <p>{@link #shouldBounce()} encodes the conservative bounce policy: bounce on
 * {@link FalsificationVerdict#REFUTED}, or on a load-bearing
 * {@link FalsificationVerdict#UNSETTLED} claim the primary could plausibly
 * settle from artifacts ({@link #artifactSettleable()} is {@code true}). A
 * probe-requiring {@code UNSETTLED} claim (one no captured artifact can settle)
 * does not warrant a bounce — the honest action is to annotate and pass
 * through.</p>
 *
 * @author Michael Murray
 * @see FalsificationGate
 */
public final class ClaimAssessment {

    /** The claim this assessment is about. */
    private final LoadBearingClaim claim;

    /** The verdict the gate reached. */
    private final FalsificationVerdict verdict;

    /** Whether some captured artifact could settle the claim (distinguishes probe-requiring UNSETTLED). */
    private final boolean artifactSettleable;

    /** The artifact that drove a CONFIRMED/REFUTED verdict; {@code null} for UNSETTLED/NOT_GATED. */
    private final CapturedArtifact evidence;

    /** A short human-readable explanation of the verdict. */
    private final String reason;

    /**
     * Constructs a claim assessment.
     *
     * @param claim              the claim assessed
     * @param verdict            the verdict reached
     * @param artifactSettleable whether some captured artifact could settle the claim
     * @param evidence           the artifact that drove the verdict; may be {@code null}
     * @param reason             a short human-readable explanation
     */
    public ClaimAssessment(LoadBearingClaim claim, FalsificationVerdict verdict,
                           boolean artifactSettleable, CapturedArtifact evidence, String reason) {
        this.claim = claim;
        this.verdict = verdict;
        this.artifactSettleable = artifactSettleable;
        this.evidence = evidence;
        this.reason = reason == null ? "" : reason;
    }

    /**
     * Returns the claim this assessment is about.
     *
     * @return the claim
     */
    public LoadBearingClaim claim() {
        return claim;
    }

    /**
     * Returns the verdict the gate reached.
     *
     * @return the verdict
     */
    public FalsificationVerdict verdict() {
        return verdict;
    }

    /**
     * Returns whether some captured artifact could settle the claim. When this
     * is {@code false} and the verdict is {@link FalsificationVerdict#UNSETTLED},
     * the claim is probe-requiring and must not be bounced.
     *
     * @return {@code true} when the claim was settleable from captured artifacts
     */
    public boolean artifactSettleable() {
        return artifactSettleable;
    }

    /**
     * Returns the artifact that drove a {@code CONFIRMED}/{@code REFUTED}
     * verdict.
     *
     * @return the evidence; {@code null} for {@code UNSETTLED}/{@code NOT_GATED}
     */
    public CapturedArtifact evidence() {
        return evidence;
    }

    /**
     * Returns a short human-readable explanation of the verdict.
     *
     * @return the reason; never {@code null}
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns whether this assessment, on its own, warrants bouncing the job
     * back to a fresh primary run. {@code true} for {@code REFUTED}, and for a
     * load-bearing {@code UNSETTLED} claim that some captured artifact could
     * settle (so the primary can be sent back to capture it). A probe-requiring
     * {@code UNSETTLED} claim returns {@code false}: no amount of re-running the
     * primary can settle it from captured artifacts.
     *
     * @return {@code true} when this claim warrants a bounce
     */
    public boolean shouldBounce() {
        if (verdict == FalsificationVerdict.REFUTED) {
            return true;
        }
        return verdict == FalsificationVerdict.UNSETTLED
                && claim.isLoadBearing()
                && artifactSettleable;
    }
}
