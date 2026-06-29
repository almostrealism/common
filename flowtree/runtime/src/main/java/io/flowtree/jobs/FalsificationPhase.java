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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the falsification phase of a {@link CodingAgentJob}: the phase that runs
 * after the primary session and before the enforcement rules (review, dedup,
 * &hellip;), and that — uniquely among the post-primary phases — can BOUNCE the
 * job back to a fresh primary run when a load-bearing behavioural claim is
 * refuted or left unsettled by the captured evidence.
 *
 * <p>The collaborator mirrors {@link RetrospectivePhase}: it snapshots the job's
 * prompt and activity, swaps in the falsification analysis prompt
 * ({@link FalsificationPromptBuilder}), dispatches an analysis session through
 * {@link CodingAgentJob#executeSingleRun()}, and reads back a structured result
 * file. The analysis session only EXTRACTS structured claims and tags
 * artifacts; the verdict for each claim is recomputed mechanically in trusted
 * Java by {@link FalsificationGate}, so the agent cannot fabricate a
 * {@code CONFIRMED} with a persuasive narrative.</p>
 *
 * <h2>The bounce, and why it is bounded</h2>
 * <p>The bounce primitive is copied from {@code CodingAgentJob.onGitTampering()}:
 * set a field read by the instruction-prompt builder, clear the activity so the
 * redo runs as genuine primary work (with the primary runner and preambles),
 * re-run {@link CodingAgentJob#executeSingleRun()}, and clear the field in a
 * {@code finally}. Unlike git-tampering, a falsification bounce is bounded by
 * {@link #DEFAULT_MAX_BOUNCES}: an agent that keeps re-asserting a refuted claim
 * must not loop forever. When the budget is exhausted the phase PASSES THROUGH
 * with the unsettled findings annotated, rather than looping or hard-failing.</p>
 *
 * <h2>v1 scope: artifact-settleable only</h2>
 * <p>This version settles claims only from artifacts already captured in the job
 * context plus cross-codebase source. It does NOT emit probes. A claim that can
 * only be settled by a probe on a configuration the captured evidence does not
 * cover (the {@code isCPU()} dual-backend class) is reported
 * {@link FalsificationVerdict#UNSETTLED} and passed through — never bounced
 * repeatedly, never fabricated into {@code CONFIRMED}.</p>
 *
 * @author Michael Murray
 * @see FalsificationGate
 * @see FalsificationPromptBuilder
 * @see Phase#FALSIFICATION
 */
class FalsificationPhase {

    /**
     * Bare name of the result file written by the falsification analysis session.
     * It is written and read under {@link FlowtreeArtifacts#DIRECTORY} (see
     * {@link FlowtreeArtifacts#inDirectory(String)}); the bare name is retained as
     * the canonical identifier used in the prompt and read paths.
     */
    static final String RESULTS_FILE = "falsification-results.json";

    /**
     * Maximum number of times the phase will bounce the job back to primary
     * within one {@link CodingAgentJob#doWork()} call. A small cap: a single
     * bounce already delivers the refutation proximate to the redone decision,
     * and an agent that re-asserts a refuted claim twice is better surfaced than
     * looped on. After the cap the phase passes through with annotations.
     */
    static final int DEFAULT_MAX_BOUNCES = 2;

    /** The mechanical gate that computes a verdict per claim. */
    private final FalsificationGate gate = new FalsificationGate();

    /** {@code true} after {@link #run(CodingAgentJob)} executed in this {@code doWork()} call. */
    private boolean ran;

    /** Number of load-bearing claims extracted and assessed across all iterations. */
    private int claimsExtracted;

    /** Number of claims assessed {@link FalsificationVerdict#REFUTED}. */
    private int refutedCount;

    /** Number of claims left {@link FalsificationVerdict#UNSETTLED}. */
    private int unsettledCount;

    /** Number of times the phase bounced the job back to primary. */
    private int bounceCount;

    /** Cost (USD) of the falsification analysis sessions; computed as the delta in costByModel. */
    private double costUsd;

    /** Returns whether the falsification phase executed for the current job run. */
    boolean ran() { return ran; }

    /** Returns the number of load-bearing claims extracted and assessed. */
    int claimsExtracted() { return claimsExtracted; }

    /** Returns the number of claims assessed REFUTED. */
    int refutedCount() { return refutedCount; }

    /** Returns the number of claims left UNSETTLED. */
    int unsettledCount() { return unsettledCount; }

    /** Returns the number of times the phase bounced the job back to primary. */
    int bounceCount() { return bounceCount; }

    /** Returns the USD cost of the falsification analysis sessions in isolation. */
    double costUsd() { return costUsd; }

    /** Resets all per-run counters; called at the top of {@link CodingAgentJob#doWork()}. */
    void reset() {
        ran = false;
        claimsExtracted = 0;
        refutedCount = 0;
        unsettledCount = 0;
        bounceCount = 0;
        costUsd = 0.0;
    }

    /**
     * Runs the falsification phase for {@code job}: analyse, decide, and bounce
     * up to {@link #DEFAULT_MAX_BOUNCES} times, then pass through.
     *
     * <p>Each iteration analyses the current tree, classifies every load-bearing
     * claim with {@link FalsificationGate}, and — if any assessment warrants a
     * bounce and budget remains — assembles a findings block and re-runs primary
     * with it prepended. When no assessment warrants a bounce, or the budget is
     * exhausted, the phase returns; {@code doWork()} then continues to the
     * enforcement rules against the (possibly redone) tree.</p>
     *
     * @param job the orchestrator owning the phase configuration and session state
     */
    void run(CodingAgentJob job) {
        reset();
        ran = true;

        PhaseConfig config = job.resolveEffectivePhaseConfig(Phase.FALSIFICATION);
        double costBefore = job.getCostForModel(config.toModelKey());

        while (true) {
            List<ClaimAssessment> assessments = analyze(job);
            tallyAssessments(assessments);

            List<ClaimAssessment> bounceWorthy = new ArrayList<>();
            for (ClaimAssessment assessment : assessments) {
                if (assessment.shouldBounce()) bounceWorthy.add(assessment);
            }

            if (bounceWorthy.isEmpty()) {
                break;
            }
            if (bounceCount >= DEFAULT_MAX_BOUNCES) {
                // Budget exhausted: annotate and pass through rather than loop.
                annotatePassThrough(job, bounceWorthy);
                break;
            }

            String findings = FalsificationPromptBuilder.buildFindingsBlock(bounceWorthy);
            bounceToPrimary(job, findings);
            bounceCount++;
        }

        // TODO(review): costUsd only covers the falsification analysis model; bounce re-runs charge
        //   the primary model key and are excluded. falsificationCostUsd therefore under-counts the
        //   true feature cost. Consider tracking total delta across all model keys, or a separate bounce
        //   cost field, in a future pass.
        costUsd = Math.max(0.0, job.getCostForModel(config.toModelKey()) - costBefore);
    }

    /**
     * Runs the analysis session and classifies every extracted claim.
     *
     * <p>Snapshots the prompt and activity, swaps in the falsification analysis
     * prompt, dispatches one session via {@link CodingAgentJob#executeSingleRun()},
     * reads the structured result file, and runs the {@link FalsificationGate}
     * over each claim. Restores prior state in {@code finally}. Package-private
     * and non-static so a test subclass can supply canned assessments without a
     * live session.</p>
     *
     * @param job the orchestrator owning session state
     * @return one assessment per extracted load-bearing claim; empty when none
     */
    List<ClaimAssessment> analyze(CodingAgentJob job) {
        String originalPrompt = job.getPrompt();
        String previousActivity = job.getCurrentActivity();
        // executeSingleRun() deletes commit.txt at startup; save/restore so the
        // primary session's commit message survives a no-bounce falsification pass.
        String savedCommitMessage = CommitMessageBuilder.captureCommitTxt(job);
        // Delete any stale results file so a session that crashed or timed out
        // in a prior iteration cannot be read as fresh output by classifyResults().
        // A missing file is the safe/correct signal that analysis did not complete.
        Path staleResults = job.resolveWorkingPath(FlowtreeArtifacts.inDirectory(RESULTS_FILE));
        if (staleResults != null) {
            try {
                Files.deleteIfExists(staleResults);
            } catch (IOException e) {
                job.warn("Could not delete stale " + RESULTS_FILE + ": " + e.getMessage());
            }
        }
        job.setCurrentActivity(Phase.FALSIFICATION.wireName());
        try {
            job.setPrompt(FalsificationPromptBuilder.build(job));
            job.executeSingleRun();
            return classifyResults(job);
        } finally {
            job.setPrompt(originalPrompt);
            job.setCurrentActivity(previousActivity);
            CommitMessageBuilder.restoreCommitTxtIfUnwritten(job, savedCommitMessage);
        }
    }

    /**
     * Bounces the job back to a fresh primary run with {@code findingsBlock}
     * prepended to the top of the next primary context.
     *
     * <p>Modelled exactly on {@code CodingAgentJob.onGitTampering()}: set the
     * field the instruction-prompt builder reads, clear the activity so the redo
     * is treated as primary work (primary runner/model and full primary
     * preamble, not a suppressed correction session), re-run, and clear the
     * field in {@code finally} so it does not leak into later phases.</p>
     *
     * @param job           the orchestrator to re-run
     * @param findingsBlock the proximity payload to prepend to the redo prompt
     */
    void bounceToPrimary(CodingAgentJob job, String findingsBlock) {
        job.setFalsificationFindings(findingsBlock);
        String previousActivity = job.getCurrentActivity();
        job.setCurrentActivity(null);
        try {
            job.executeSingleRun();
        } finally {
            job.setCurrentActivity(previousActivity);
            job.setFalsificationFindings(null);
        }
    }

    /** Accumulates the per-claim counters from one iteration's assessments. */
    private void tallyAssessments(List<ClaimAssessment> assessments) {
        for (ClaimAssessment assessment : assessments) {
            if (assessment.verdict() == FalsificationVerdict.NOT_GATED) continue;
            claimsExtracted++;
            if (assessment.verdict() == FalsificationVerdict.REFUTED) refutedCount++;
            else if (assessment.verdict() == FalsificationVerdict.UNSETTLED) unsettledCount++;
        }
    }

    /**
     * Records a pass-through annotation when the bounce budget is exhausted: a
     * harness "unusual" signal naming the still-unsettled claims. The phase
     * deliberately does NOT block the job — annotate and continue.
     */
    private void annotatePassThrough(CodingAgentJob job, List<ClaimAssessment> remaining) {
        StringBuilder sb = new StringBuilder();
        sb.append("Falsification bounce budget (").append(DEFAULT_MAX_BOUNCES)
                .append(") exhausted with ").append(remaining.size())
                .append(" claim(s) still unsettled or refuted; passing through with annotations.");
        job.harnessStatus().unusual(sb.toString());
    }

    /**
     * Reads {@link #RESULTS_FILE} and classifies every claim it lists.
     * Missing-tolerant: a missing or unparseable file yields no assessments, so
     * an analysis session that legitimately found nothing to gate is not an error.
     *
     * @param job the orchestrator used to resolve the working-directory path
     * @return one assessment per parsed claim; empty on absence or parse failure
     */
    private List<ClaimAssessment> classifyResults(CodingAgentJob job) {
        List<ClaimAssessment> assessments = new ArrayList<>();
        Path resultsFile = job.resolveWorkingPath(FlowtreeArtifacts.inDirectory(RESULTS_FILE));
        if (resultsFile == null || !Files.exists(resultsFile)) return assessments;
        try {
            String json = Files.readString(resultsFile, StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode claims = root.get("claims");
            if (claims == null || !claims.isArray()) return assessments;
            for (JsonNode claimNode : claims) {
                LoadBearingClaim claim = parseClaim(claimNode);
                List<CapturedArtifact> artifacts = parseArtifacts(claimNode.get("artifacts"));
                assessments.add(gate.classify(claim, artifacts));
            }
        } catch (Exception e) {
            job.warn("Could not read " + RESULTS_FILE + ": " + e.getMessage());
        }
        return assessments;
    }

    /** Parses a single claim node into a {@link LoadBearingClaim}, defaulting missing fields. */
    private static LoadBearingClaim parseClaim(JsonNode node) {
        String text = textOrEmpty(node, "text");
        LoadBearingClaim.Facet facet = parseFacet(textOrEmpty(node, "facet"));
        String dependentHunk = textOrEmpty(node, "dependentHunk");
        String decisionConfiguration = textOrEmpty(node, "decisionConfiguration");
        TruthCondition condition = parseTruthCondition(node.get("truthCondition"));
        return new LoadBearingClaim(text, facet, dependentHunk, decisionConfiguration, condition);
    }

    /** Parses the truth-condition node, defaulting to an unstated condition when absent. */
    private static TruthCondition parseTruthCondition(JsonNode node) {
        if (node == null || node.isNull()) return TruthCondition.unstated();
        String entails = textOrEmpty(node, "entailsMarker");
        String refutes = textOrEmpty(node, "refutesMarker");
        boolean mechanical = node.has("mechanical") && node.get("mechanical").asBoolean();
        return new TruthCondition(entails, refutes, mechanical);
    }

    /** Parses the artifacts array into a list, skipping entries with an unknown kind. */
    private static List<CapturedArtifact> parseArtifacts(JsonNode node) {
        List<CapturedArtifact> artifacts = new ArrayList<>();
        if (node == null || !node.isArray()) return artifacts;
        for (JsonNode artifactNode : node) {
            CapturedArtifact.Kind kind = parseKind(textOrEmpty(artifactNode, "kind"));
            if (kind == null) continue;
            artifacts.add(new CapturedArtifact(kind,
                    textOrEmpty(artifactNode, "content"),
                    textOrEmpty(artifactNode, "configuration")));
        }
        return artifacts;
    }

    /** Returns the string value of {@code field} on {@code node}, or empty when absent. */
    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /** Parses a facet wire value, defaulting to {@link LoadBearingClaim.Facet#NONE} when unrecognised. */
    private static LoadBearingClaim.Facet parseFacet(String value) {
        try {
            return LoadBearingClaim.Facet.valueOf(value);
        } catch (IllegalArgumentException e) {
            return LoadBearingClaim.Facet.NONE;
        }
    }

    /** Parses an artifact-kind wire value, returning {@code null} when unrecognised. */
    private static CapturedArtifact.Kind parseKind(String value) {
        try {
            return CapturedArtifact.Kind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
