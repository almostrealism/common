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

import java.util.List;

/**
 * Builds the two prompts the falsification phase produces:
 * <ol>
 *   <li>{@link #build(CodingAgentJob)} — the analysis prompt for the
 *       {@link Phase#FALSIFICATION} session, which instructs the agent to
 *       extract load-bearing behavioural claims, tag the captured evidence, and
 *       write a structured {@code falsification-results.json} the orchestrator
 *       then scores mechanically with {@link FalsificationGate}.</li>
 *   <li>{@link #buildFindingsBlock(List)} — the proximity payload prepended to
 *       the top of the next primary context when the phase bounces, so the
 *       refutation lands right where the decision will be re-made.</li>
 * </ol>
 *
 * <p>The analysis session does ANALYSIS, not code: it must not modify code,
 * prompts, or configuration. Its only output is the result file, which carries
 * structured claims — never a verdict the agent wrote itself. The verdict is
 * recomputed in trusted Java so a persuasive-but-wrong narrative cannot
 * manufacture a {@code CONFIRMED}.</p>
 *
 * @author Michael Murray
 * @see Phase#FALSIFICATION
 * @see FalsificationGate
 */
final class FalsificationPromptBuilder {

    /** Static-only helper. */
    private FalsificationPromptBuilder() {}

    /**
     * Builds the falsification analysis prompt for {@code job}.
     *
     * @param job the job whose diff and narrative are to be analysed
     * @return the prompt string sent to the falsification analysis session
     */
    // TODO(review): job parameter unused — consider appending the diff (via GitOperations.captureBranchDiff,
    //   as ReviewPromptBuilder does) so the extraction agent has raw material in context rather than relying
    //   on whatever the harness session provides.
    static String build(CodingAgentJob job) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb);
        appendPredicate(sb);
        appendScope(sb);
        appendTruthConditions(sb);
        appendResultsFile(sb);
        appendForbidden(sb);
        return sb.toString();
    }

    /** Appends the phase header and role. */
    private static void appendHeader(StringBuilder sb) {
        sb.append("FALSIFICATION ANALYSIS\n\n");
        sb.append("This is the FALSIFICATION phase of a FlowTree coding job. It runs after\n");
        sb.append("the primary session and before review. Your task is to extract the\n");
        sb.append("load-bearing behavioural claims the prior attempt relied on and the\n");
        sb.append("captured evidence that bears on each, so the orchestrator can decide\n");
        sb.append("mechanically whether each claim is CONFIRMED, REFUTED, or UNSETTLED.\n");
        sb.append("You are NOT here to redo the work, and you do NOT write the verdict.\n\n");
    }

    /** Appends the gating predicate the agent applies when selecting claims. */
    private static void appendPredicate(StringBuilder sb) {
        sb.append("WHICH CLAIMS TO EXTRACT (the gating predicate)\n\n");
        sb.append("Extract a claim only when ALL of the following hold:\n");
        sb.append("  P1 — Contingent-empirical: the claim is about the system's actual\n");
        sb.append("       behaviour (runtime behaviour, representational capacity of a\n");
        sb.append("       type, or the causal explanation of an observed event) — NOT a\n");
        sb.append("       restatement of the task, a fact evident from the diff, or an\n");
        sb.append("       analytic truth.\n");
        sb.append("  P2 — Load-bearing: name the diff hunk that is correct ONLY IF the\n");
        sb.append("       claim is true. If you cannot name a hunk whose correctness flips\n");
        sb.append("       when the claim is negated, do NOT extract the claim.\n");
        sb.append("  P3 — Unfalsified OR contradicted: either no captured artifact entails\n");
        sb.append("       the claim on the configuration the decision runs under (an\n");
        sb.append("       evidence gap), OR a captured artifact entails the NEGATION of the\n");
        sb.append("       claim yet the decision proceeded anyway (a contradiction). The\n");
        sb.append("       contradiction branch is essential — a claim the captured\n");
        sb.append("       evidence already disproves is the most important kind to surface.\n\n");
    }

    /** Appends the v1 scope statement: artifact-settleable only, no probes. */
    private static void appendScope(StringBuilder sb) {
        sb.append("SCOPE (v1 — artifact-settleable only)\n\n");
        sb.append("Settle each claim using ONLY evidence already captured in this job's\n");
        sb.append("context — the prior session's tool output, test output, the diff,\n");
        sb.append("instrumentation/logs the agent already produced — plus cross-codebase\n");
        sb.append("SOURCE you read with Grep/Read or the consultant. Do NOT run new\n");
        sb.append("commands or emit probes to settle a runtime claim. When a claim can\n");
        sb.append("only be settled by running something on a configuration the captured\n");
        sb.append("evidence does not cover, mark it UNSETTLED and move on — never invent\n");
        sb.append("a CONFIRMED you did not earn.\n\n");
    }

    /** Appends the truth-condition requirement (the anti-gaming binding). */
    private static void appendTruthConditions(StringBuilder sb) {
        sb.append("TRUTH CONDITIONS (state them BEFORE you look)\n\n");
        sb.append("For each claim, state — before inspecting the evidence — the mechanical\n");
        sb.append("condition that would settle it:\n");
        sb.append("  - entailsMarker: a substring that, if present in a configuration-\n");
        sb.append("    relevant artifact, makes the claim TRUE.\n");
        sb.append("  - refutesMarker: a substring that, if present, makes the claim FALSE.\n");
        sb.append("  - mechanical: true only when the condition is a deterministic\n");
        sb.append("    expected-vs-actual match, not a judgement call. If the best you can\n");
        sb.append("    offer is a soft inference, set mechanical=false — the claim will be\n");
        sb.append("    reported UNSETTLED rather than CONFIRMED, which is the honest result.\n");
        sb.append("Also tag every artifact with the configuration it was gathered on:\n");
        sb.append("evidence from a different configuration than the decision runs under\n");
        sb.append("does NOT settle the claim.\n\n");
    }

    /** Appends the results-file schema the analysis session must write. */
    private static void appendResultsFile(StringBuilder sb) {
        sb.append("WRITE THE RESULTS FILE\n\n");
        sb.append("Write exactly one file, ").append(FalsificationPhase.RESULTS_FILE).append(", with:\n\n");
        sb.append("  {\n");
        sb.append("    \"claimsExtracted\": <N>,\n");
        sb.append("    \"claims\": [\n");
        sb.append("      {\n");
        sb.append("        \"text\": \"<the load-bearing claim>\",\n");
        sb.append("        \"facet\": \"RUNTIME_BEHAVIOUR | REPRESENTATIONAL_CAPACITY | CAUSAL_EXPLANATION\",\n");
        sb.append("        \"dependentHunk\": \"<file:line — why it is wrong if the claim is false>\",\n");
        sb.append("        \"decisionConfiguration\": \"<config the decision runs under, or empty>\",\n");
        sb.append("        \"truthCondition\": {\n");
        sb.append("          \"entailsMarker\": \"<substring>\",\n");
        sb.append("          \"refutesMarker\": \"<substring>\",\n");
        sb.append("          \"mechanical\": true\n");
        sb.append("        },\n");
        sb.append("        \"artifacts\": [\n");
        sb.append("          { \"kind\": \"TEST_OUTPUT | COMMAND_OUTPUT | INSTRUMENTATION | SOURCE | DOC | PLANNING_DOC | AGENT_ASSERTION\",\n");
        sb.append("            \"content\": \"<the captured text>\",\n");
        sb.append("            \"configuration\": \"<config it was gathered on, or empty>\" }\n");
        sb.append("        ]\n");
        sb.append("      }\n");
        sb.append("    ]\n");
        sb.append("  }\n\n");
        sb.append("This file is the ONLY file you may write. The orchestrator scores the\n");
        sb.append("verdict from the artifact content and your pre-stated truth conditions —\n");
        sb.append("you do not, and cannot, write the verdict yourself.\n\n");
    }

    /** Appends forbidden actions. */
    private static void appendForbidden(StringBuilder sb) {
        sb.append("FORBIDDEN ACTIONS\n");
        sb.append("  - No git commands of any kind.\n");
        sb.append("  - No Edit or Write on the codebase (you MAY Read/Grep to inspect source).\n");
        sb.append("  - No commits — the harness commits at session end.\n");
        sb.append("  - No new commands or probes to settle runtime claims (out of scope for v1).\n");
        sb.append("  - EXCEPT: you MAY write one file: ")
                .append(FalsificationPhase.RESULTS_FILE).append("\n\n");
    }

    /**
     * Builds the findings block prepended to the next primary context when the
     * phase bounces. One entry per bounce-worthy assessment, in the concrete
     * proximity format so the redone primary sees claim, dependent hunk, captured
     * evidence, configuration, and verdict right where the decision is re-made.
     *
     * @param assessments the bounce-worthy assessments driving this bounce
     * @return the findings block; never {@code null}
     */
    static String buildFindingsBlock(List<ClaimAssessment> assessments) {
        StringBuilder sb = new StringBuilder();
        for (ClaimAssessment assessment : assessments) {
            LoadBearingClaim claim = assessment.claim();
            sb.append("CLAIM (from your prior attempt): \"").append(claim.text()).append("\"\n");
            sb.append("THIS CODE DEPENDS ON IT: ").append(claim.dependentHunk()).append("\n");
            CapturedArtifact evidence = assessment.evidence();
            if (evidence != null) {
                sb.append("WHAT WE CAPTURED (").append(evidence.kind()).append("): ")
                        .append(evidence.content()).append("\n");
                sb.append("CONFIGURATION: artifact gathered on '")
                        .append(evidence.configuration().isEmpty() ? "(agnostic)" : evidence.configuration())
                        .append("'; decision runs on '")
                        .append(claim.decisionConfiguration().isEmpty() ? "(agnostic)" : claim.decisionConfiguration())
                        .append("'\n");
            } else {
                sb.append("WHAT WE CAPTURED: no captured artifact entails this claim on the"
                        + " configuration the decision runs under.\n");
                sb.append("CONFIGURATION: decision runs on '")
                        .append(claim.decisionConfiguration().isEmpty() ? "(agnostic)" : claim.decisionConfiguration())
                        .append("'\n");
            }
            sb.append("VERDICT: ").append(assessment.verdict()).append(" — ")
                    .append(assessment.reason()).append("\n\n");
        }
        return sb.toString();
    }
}
