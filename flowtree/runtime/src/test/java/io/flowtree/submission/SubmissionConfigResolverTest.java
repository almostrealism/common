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

package io.flowtree.submission;

import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link SubmissionConfigResolver}. The runner-only and Phase-config
 * ladders are covered exhaustively by {@link SubmissionRunnerResolverTest} and
 * {@link PhaseConfigResolverTest}; these tests focus on what the orchestrator
 * itself contributes: running both resolvers, merging their errors, and
 * applying both to a {@link CodingAgentJob.Factory}.
 *
 * <p>The two regression scenarios that motivated this class &mdash; workspace
 * default runner reaching a job submitted with no workstream-level overrides,
 * and a workspace-level {@link PhaseConfigBundle} reaching the factory &mdash;
 * are explicit named tests so the original bug cannot silently regress.</p>
 */
public class SubmissionConfigResolverTest extends TestSuiteBase {

    /** Registered alongside {@code "claude"} for tests that need a second runner. */
    private static final String TEST_RUNNER = "submission-config-resolver-test-runner";

    @Before
    public void registerTestRunner() {
        AgentRunnerRegistry.register(TEST_RUNNER, () -> null);
    }

    private static Workstream workstream() {
        return new Workstream("ws-1", "C0001", "general");
    }

    private static WorkstreamConfig.WorkspaceEntry workspace(String id) {
        WorkstreamConfig.WorkspaceEntry entry = new WorkstreamConfig.WorkspaceEntry();
        entry.setId(id);
        return entry;
    }

    private static PhaseConfigBundle bundle(PhaseConfig def, Phase phase, PhaseConfig override) {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        if (phase != null && override != null) overrides.put(phase, override);
        return new PhaseConfigBundle(def == null ? PhaseConfig.EMPTY : def, overrides);
    }

    @Test(timeout = 5000)
    public void allEmptyAppliesControllerDefaults() {
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, workstream(), null);
        assertNull(r.error());
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        r.applyTo(f);
        // Controller default runner reaches the factory only as a bundle entry;
        // SubmissionRunnerResolver intentionally omits the byte-identical
        // "claude" default from setDefaultRunner. The bundle reflects it.
        PhaseConfig resolved = r.phaseConfigResolver().forPhase(Phase.PRIMARY);
        assertEquals(AgentRunnerRegistry.CLAUDE, resolved.runner());
    }

    /**
     * Regression: workspace-level default runner must reach a job whose
     * workstream sets no runner. Prior to this orchestrator, the Slack
     * submission path skipped resolution entirely and inherited only the
     * controller default; the API path inlined an equivalent of this resolver.
     */
    @Test(timeout = 5000)
    public void workspaceDefaultRunnerPropagatesWhenWorkstreamEmpty() {
        WorkstreamConfig.WorkspaceEntry wsEntry = workspace("acme");
        wsEntry.setDefaultRunner(TEST_RUNNER);
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, workstream(), wsEntry);
        assertNull(r.error());
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getDefaultRunner());
    }

    /**
     * Regression: workspace-level {@link PhaseConfigBundle} (default
     * PhaseConfig with model) must reach the factory through the orchestrator.
     */
    @Test(timeout = 5000)
    public void workspacePhaseConfigBundleReachesFactory() {
        WorkstreamConfig.WorkspaceEntry wsEntry = workspace("acme");
        wsEntry.setDefaultPhaseConfig(new PhaseConfig(TEST_RUNNER, "model-x", null));
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, workstream(), wsEntry);
        assertNull(r.error());
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        r.applyTo(f);
        assertNotNull("phase config bundle should be set on factory",
                f.getPhaseConfigBundle());
        PhaseConfig resolved = r.phaseConfigResolver().forPhase(Phase.PRIMARY);
        assertEquals(TEST_RUNNER, resolved.runner());
        assertEquals("model-x", resolved.model());
    }

    @Test(timeout = 5000)
    public void workstreamDefaultBeatsWorkspaceDefault() {
        WorkstreamConfig.WorkspaceEntry wsEntry = workspace("acme");
        wsEntry.setDefaultRunner(TEST_RUNNER);
        Workstream ws = workstream();
        ws.setDefaultRunner(AgentRunnerRegistry.CLAUDE);
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, wsEntry);
        assertNull(r.error());
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        r.applyTo(f);
        // Workstream default "claude" is identical to the controller default
        // so SubmissionRunnerResolver omits the explicit setDefaultRunner
        // call (byte-identical wire format). The resolved bundle is what we
        // can inspect.
        PhaseConfig resolved = r.phaseConfigResolver().forPhase(Phase.PRIMARY);
        assertEquals(AgentRunnerRegistry.CLAUDE, resolved.runner());
    }

    @Test(timeout = 5000)
    public void requestBundleBeatsWorkspaceAndWorkstream() {
        Workstream ws = workstream();
        ws.setPhaseConfigBundle(bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null));
        WorkstreamConfig.WorkspaceEntry wsEntry = workspace("acme");
        wsEntry.setDefaultPhaseConfig(new PhaseConfig(AgentRunnerRegistry.CLAUDE, "ws-model", null));
        PhaseConfigBundle request = bundle(
                new PhaseConfig(TEST_RUNNER, "req-model", null), null, null);
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                request, ws, wsEntry);
        assertNull(r.error());
        PhaseConfig resolved = r.phaseConfigResolver().forPhase(Phase.PRIMARY);
        assertEquals(TEST_RUNNER, resolved.runner());
        assertEquals("req-model", resolved.model());
    }

    @Test(timeout = 5000)
    public void nullRequestBundleTreatedAsEmpty() {
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                null, workstream(), null);
        assertNull(r.error());
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        r.applyTo(f); // must not throw
    }

    @Test(timeout = 5000)
    public void runnerResolverErrorSurfacedAndApplyIsNoOp() {
        Workstream ws = workstream();
        Map<String, String> badRunners = new LinkedHashMap<>();
        badRunners.put(Phase.PRIMARY.wireName(), "no-such-runner");
        ws.setRunners(badRunners);
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, null);
        assertNotNull("expected runner-validation error", r.error());
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        // applyTo must not mutate the factory when error() is non-null.
        String beforeRunner = f.getDefaultRunner();
        r.applyTo(f);
        assertEquals(beforeRunner, f.getDefaultRunner());
    }
}
