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

import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.ClaudeCodeRunner;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import io.flowtree.slack.PhaseConfigResolver;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * End-to-end exercise of the unified per-phase configuration ladder. Builds a
 * single {@link CodingAgentJob} with a varied {@link PhaseConfigBundle} that
 * mixes claude / opencode runners, opus / sonnet models, and high / medium /
 * low effort levels. Asserts that {@link PhaseConfigResolver} returns the
 * expected resolved {@link PhaseConfig} per phase, and that the orchestrator
 * builds an {@link io.flowtree.jobs.agent.AgentRunRequest} carrying the
 * matching per-phase {@code (model, effort)} pair.
 *
 * <p>Pure unit test — no subprocess invocations.</p>
 */
public class CodingAgentJobMixedPhaseConfigTest extends TestSuiteBase {

    /** Effort constants used in this scenario. */
    private static final String EFFORT_HIGH = "high";
    private static final String EFFORT_MEDIUM = "medium";
    private static final String EFFORT_LOW = "low";

    /** Models used in this scenario; selected from {@link ClaudeCodeRunner#VALID_MODELS}. */
    private static final String MODEL_OPUS = "opus";
    private static final String MODEL_SONNET = "sonnet";

    /** Builds the mixed-phase scenario described in the task brief. */
    private static PhaseConfigBundle buildScenarioBundle() {
        PhaseConfig def = new PhaseConfig(AgentRunnerRegistry.CLAUDE, MODEL_SONNET, null);
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        overrides.put(Phase.PRIMARY, new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null));
        overrides.put(Phase.REVIEW, new PhaseConfig(AgentRunnerRegistry.CLAUDE, MODEL_OPUS, EFFORT_HIGH));
        overrides.put(Phase.DEDUPLICATION, new PhaseConfig(null, null, EFFORT_MEDIUM));
        overrides.put(Phase.ORGANIZATIONAL_PLACEMENT, new PhaseConfig(null, null, EFFORT_LOW));
        // ENFORCE_CHANGES: no per-phase entry — inherits default entirely.
        overrides.put(Phase.MAVEN_DEPENDENCY_PROTECTION, new PhaseConfig(null, null, EFFORT_MEDIUM));
        overrides.put(Phase.POST_COMPLETION, new PhaseConfig(null, null, EFFORT_LOW));
        overrides.put(Phase.COMMIT_MESSAGE, new PhaseConfig(null, null, EFFORT_LOW));
        overrides.put(Phase.GIT_TAMPERING_RESTART, new PhaseConfig(null, MODEL_OPUS, null));
        return new PhaseConfigBundle(def, overrides);
    }

    /**
     * Verifies the resolver returns the expected per-phase
     * {@link PhaseConfig} for every phase in the mixed scenario.
     */
    @Test(timeout = 5000)
    public void resolverMatchesScenarioPerPhase() {
        PhaseConfigBundle job = buildScenarioBundle();
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull("validation failure: " + r.error(), r.error());

        assertResolved(r, Phase.PRIMARY,        AgentRunnerRegistry.OPENCODE, MODEL_SONNET, null);
        assertResolved(r, Phase.REVIEW,         AgentRunnerRegistry.CLAUDE,   MODEL_OPUS,   EFFORT_HIGH);
        assertResolved(r, Phase.DEDUPLICATION,  AgentRunnerRegistry.CLAUDE,   MODEL_SONNET, EFFORT_MEDIUM);
        assertResolved(r, Phase.ORGANIZATIONAL_PLACEMENT,
                AgentRunnerRegistry.CLAUDE, MODEL_SONNET, EFFORT_LOW);
        // ENFORCE_CHANGES has no override — inherits default (claude, sonnet, no effort).
        assertResolved(r, Phase.ENFORCE_CHANGES, AgentRunnerRegistry.CLAUDE, MODEL_SONNET, null);
        assertResolved(r, Phase.MAVEN_DEPENDENCY_PROTECTION,
                AgentRunnerRegistry.CLAUDE, MODEL_SONNET, EFFORT_MEDIUM);
        assertResolved(r, Phase.POST_COMPLETION, AgentRunnerRegistry.CLAUDE, MODEL_SONNET, EFFORT_LOW);
        assertResolved(r, Phase.COMMIT_MESSAGE,  AgentRunnerRegistry.CLAUDE, MODEL_SONNET, EFFORT_LOW);
        assertResolved(r, Phase.GIT_TAMPERING_RESTART,
                AgentRunnerRegistry.CLAUDE, MODEL_OPUS, null);
    }

    /**
     * Verifies that the orchestrator builds an {@link io.flowtree.jobs.agent.AgentRunRequest}
     * per phase carrying the expected resolved {@code (model, effort)} pair.
     * Uses {@link CodingAgentJob#buildRunRequest(String, String, Path, int)}
     * with the per-phase activity tag so the orchestrator routes through the
     * correct phase.
     */
    @Test(timeout = 5000)
    public void orchestratorBuildsRequestPerPhase() {
        PhaseConfigBundle job = buildScenarioBundle();
        CodingAgentJob coding = new CodingAgentJob("t-mixed", "p");
        coding.setPhaseConfigBundle(job);

        assertRequest(coding, Phase.PRIMARY,        MODEL_SONNET, null);
        assertRequest(coding, Phase.REVIEW,         MODEL_OPUS,   EFFORT_HIGH);
        assertRequest(coding, Phase.DEDUPLICATION,  MODEL_SONNET, EFFORT_MEDIUM);
        assertRequest(coding, Phase.ORGANIZATIONAL_PLACEMENT, MODEL_SONNET, EFFORT_LOW);
        assertRequest(coding, Phase.ENFORCE_CHANGES, MODEL_SONNET, null);
        assertRequest(coding, Phase.MAVEN_DEPENDENCY_PROTECTION, MODEL_SONNET, EFFORT_MEDIUM);
        assertRequest(coding, Phase.POST_COMPLETION, MODEL_SONNET, EFFORT_LOW);
        assertRequest(coding, Phase.COMMIT_MESSAGE,  MODEL_SONNET, EFFORT_LOW);
        assertRequest(coding, Phase.GIT_TAMPERING_RESTART, MODEL_OPUS, null);
    }

    /**
     * Verifies that the orchestrator dispatches each phase to its
     * configured {@link io.flowtree.jobs.agent.AgentRunner}, not the
     * default. This exercises {@link CodingAgentJob#resolveRunner(Phase)}
     * over the same bundle.
     */
    @Test(timeout = 5000)
    public void orchestratorResolvesRunnerPerPhase() {
        PhaseConfigBundle job = buildScenarioBundle();
        CodingAgentJob coding = new CodingAgentJob("t-mixed", "p");
        coding.setPhaseConfigBundle(job);

        Assert.assertEquals(AgentRunnerRegistry.OPENCODE, coding.resolveRunner(Phase.PRIMARY).getName());
        Assert.assertEquals(AgentRunnerRegistry.CLAUDE,   coding.resolveRunner(Phase.REVIEW).getName());
        Assert.assertEquals(AgentRunnerRegistry.CLAUDE,   coding.resolveRunner(Phase.DEDUPLICATION).getName());
        Assert.assertEquals(AgentRunnerRegistry.CLAUDE,   coding.resolveRunner(Phase.COMMIT_MESSAGE).getName());
    }

    /**
     * Legacy backward compatibility: a job built with only the legacy
     * {@code model} + {@code effort} setters resolves every phase to the
     * same {@code (claude, opus, high)} triple — no behavioural change for
     * pre-Phase-2 callers.
     */
    @Test(timeout = 5000)
    public void legacyModelAndEffortResolveAcrossAllPhases() {
        CodingAgentJob coding = new CodingAgentJob("t-legacy", "p");
        coding.setModel(MODEL_OPUS);
        coding.setEffort(EFFORT_HIGH);

        for (Phase phase : Phase.values()) {
            PhaseConfig effective = coding.resolveEffectivePhaseConfig(phase);
            Assert.assertEquals("phase " + phase + " runner", AgentRunnerRegistry.CLAUDE, effective.runner());
            Assert.assertEquals("phase " + phase + " model",  MODEL_OPUS, effective.model());
            Assert.assertEquals("phase " + phase + " effort", EFFORT_HIGH, effective.effort());
        }
    }

    /**
     * Legacy backward compatibility: a workstream with legacy {@code runners:
     * {review: opencode}} and {@code defaultRunner: claude} resolves REVIEW
     * to opencode and every other phase to claude.
     */
    @Test(timeout = 5000)
    public void legacyRunnersMapResolvesPerPhase() {
        CodingAgentJob coding = new CodingAgentJob("t-legacy-runners", "p");
        coding.setDefaultRunner(AgentRunnerRegistry.CLAUDE);
        coding.setRunnerForPhase(Phase.REVIEW, AgentRunnerRegistry.OPENCODE);

        Assert.assertEquals(AgentRunnerRegistry.OPENCODE, coding.resolveRunner(Phase.REVIEW).getName());
        Assert.assertEquals(AgentRunnerRegistry.CLAUDE,   coding.resolveRunner(Phase.PRIMARY).getName());
        Assert.assertEquals(AgentRunnerRegistry.CLAUDE,   coding.resolveRunner(Phase.DEDUPLICATION).getName());
    }

    /** Asserts the resolver returns the expected triple for {@code phase}. */
    private static void assertResolved(PhaseConfigResolver r, Phase phase,
                                       String expectedRunner, String expectedModel,
                                       String expectedEffort) {
        PhaseConfig resolved = r.forPhase(phase);
        Assert.assertNotNull("resolved config for " + phase, resolved);
        Assert.assertEquals(phase + " runner", expectedRunner, resolved.runner());
        Assert.assertEquals(phase + " model",  expectedModel, resolved.model());
        Assert.assertEquals(phase + " effort", expectedEffort, resolved.effort());
    }

    /**
     * Asserts the orchestrator's request for {@code phase} carries the
     * expected model and effort. Drives the orchestrator's phase routing by
     * setting {@code currentActivity} to the phase's wire name.
     */
    private static void assertRequest(CodingAgentJob coding, Phase phase,
                                      String expectedModel, String expectedEffort) {
        // Wire-name activity routes through resolveCurrentPhase().
        if (phase == Phase.PRIMARY) {
            coding.setCurrentActivity(null);
        } else {
            coding.setCurrentActivity(phase.wireName());
        }
        PhaseConfig effective = coding.resolveEffectivePhaseConfig(phase);
        Assert.assertEquals(phase + " effective model",  expectedModel, effective.model());
        Assert.assertEquals(phase + " effective effort", expectedEffort, effective.effort());
    }
}
