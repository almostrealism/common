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

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Coverage for {@link FalsificationPromptBuilder}: the analysis prompt that
 * drives claim extraction, and the findings block prepended to the next primary
 * context when the phase bounces.
 */
public class FalsificationPromptBuilderTest extends TestSuiteBase {

    /** The analysis prompt names the falsification phase and states the predicate and v1 scope. */
    @Test(timeout = 30000)
    public void analysisPromptStatesPredicateAndScope() {
        String prompt = FalsificationPromptBuilder.build(new CodingAgentJob("t1", "p"));
        assertTrue("Prompt must announce the FALSIFICATION ANALYSIS phase",
                prompt.contains("FALSIFICATION ANALYSIS"));
        assertTrue("Prompt must state the P1 contingent-empirical condition",
                prompt.contains("P1"));
        assertTrue("Prompt must state the P2 load-bearing condition",
                prompt.contains("P2"));
        assertTrue("Prompt must state the disjunctive P3 (gap OR contradiction)",
                prompt.contains("P3"));
        assertTrue("Prompt must state the contradiction branch explicitly",
                prompt.contains("NEGATION") || prompt.contains("contradiction"));
    }

    /** The prompt states v1 scope: artifact-settleable only, no probe emission. */
    @Test(timeout = 30000)
    public void analysisPromptForbidsProbes() {
        String prompt = FalsificationPromptBuilder.build(new CodingAgentJob("t1", "p"));
        assertTrue("Prompt must limit settlement to captured artifacts and source",
                prompt.contains("artifact-settleable"));
        assertTrue("Prompt must forbid probe emission for runtime claims",
                prompt.contains("probe"));
        assertTrue("Prompt must instruct UNSETTLED rather than a fabricated CONFIRMED",
                prompt.contains("UNSETTLED"));
    }

    /** The prompt requires pre-stated mechanical truth conditions and configuration tagging. */
    @Test(timeout = 30000)
    public void analysisPromptRequiresTruthConditions() {
        String prompt = FalsificationPromptBuilder.build(new CodingAgentJob("t1", "p"));
        assertTrue(prompt.contains("entailsMarker"));
        assertTrue(prompt.contains("refutesMarker"));
        assertTrue("Prompt must require a mechanical flag", prompt.contains("mechanical"));
        assertTrue("Prompt must require configuration tagging of artifacts",
                prompt.contains("configuration"));
    }

    /** The prompt instructs writing the structured results file and reserves verdict to the orchestrator. */
    @Test(timeout = 30000)
    public void analysisPromptInstructsResultsFileAndReservesVerdict() {
        String prompt = FalsificationPromptBuilder.build(new CodingAgentJob("t1", "p"));
        assertTrue("Prompt must name the results file",
                prompt.contains(FalsificationPhase.RESULTS_FILE));
        assertTrue("Prompt must make clear the agent does not write the verdict",
                prompt.contains("you do not, and cannot, write the verdict yourself"));
    }

    /** The findings block carries the claim, dependent hunk, evidence, configuration, and verdict. */
    @Test(timeout = 30000)
    public void findingsBlockCarriesProximityPayload() {
        LoadBearingClaim claim = new LoadBearingClaim(
                "the hide is lost before window registration",
                LoadBearingClaim.Facet.CAUSAL_EXPLANATION,
                "PluginUIManager.java — pendingHidePluginIds machinery",
                "headless-ci",
                new TruthCondition("hide dropped", "hide received and returned cleanly", true));
        CapturedArtifact evidence = new CapturedArtifact(
                CapturedArtifact.Kind.INSTRUMENTATION,
                "hide received and returned cleanly; blocked later in show", "headless-ci");
        ClaimAssessment assessment = new ClaimAssessment(claim, FalsificationVerdict.REFUTED,
                true, evidence, "instrumentation entails the negation");

        List<ClaimAssessment> assessments = Collections.singletonList(assessment);
        String block = FalsificationPromptBuilder.buildFindingsBlock(assessments);

        assertTrue("Block must carry the claim text",
                block.contains("the hide is lost before window registration"));
        assertTrue("Block must name the dependent hunk",
                block.contains("PluginUIManager.java"));
        assertTrue("Block must show the captured evidence",
                block.contains("hide received and returned cleanly"));
        assertTrue("Block must state the configuration", block.contains("headless-ci"));
        assertTrue("Block must state the verdict", block.contains("REFUTED"));
    }
}
