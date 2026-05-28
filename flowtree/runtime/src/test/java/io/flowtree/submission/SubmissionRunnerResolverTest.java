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

import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link SubmissionRunnerResolver}. Verifies the runner
 * precedence ladder applied at submission time:
 * per-job &gt; workstream per-phase &gt; workstream default &gt;
 * workspace per-phase &gt; workspace default &gt; {@code "claude"}.
 */
public class SubmissionRunnerResolverTest extends TestSuiteBase {

    /** Registered alongside {@code "claude"} for tests that need a second runner. */
    private static final String TEST_RUNNER = "submission-resolver-test-runner";

    /** Registers the test runner once per test. */
    @Before
    public void registerTestRunner() {
        AgentRunnerRegistry.register(TEST_RUNNER, () -> null);
    }

    /** Clears nothing — registry overrides are idempotent. */
    @After
    public void noop() { /* registry is process-wide; nothing to clean */ }

    @Test(timeout = 5000)
    public void emptyRunnersAndEmptyWorkstreamFallsBackToClaudeDefault() {
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), null, new LinkedHashMap<>());
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(AgentRunnerRegistry.CLAUDE, f.getDefaultRunner());
        assertTrue("no per-phase overrides expected",
                f.getRunnerByPhase().isEmpty());
    }

    @Test(timeout = 5000)
    public void requestDefaultRunnerOverridesWorkstreamDefault() {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("default", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                req, AgentRunnerRegistry.CLAUDE, new LinkedHashMap<>());
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getDefaultRunner());
    }

    @Test(timeout = 5000)
    public void workstreamDefaultUsedWhenRequestOmitsDefault() {
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), TEST_RUNNER, new LinkedHashMap<>());
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getDefaultRunner());
    }

    @Test(timeout = 5000)
    public void perJobPhaseOverridesWorkstreamPhase() {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("primary", TEST_RUNNER);
        Map<String, String> ws = new LinkedHashMap<>();
        ws.put("primary", AgentRunnerRegistry.CLAUDE);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(req, null, ws);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.PRIMARY));
    }

    @Test(timeout = 5000)
    public void workstreamPhaseUsedWhenRequestOmitsPhase() {
        Map<String, String> ws = new LinkedHashMap<>();
        ws.put("deduplication", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), null, ws);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.DEDUPLICATION));
        // Other phases fall back to default ("claude").
        assertEquals(AgentRunnerRegistry.CLAUDE,
                f.getRunnerForPhase(Phase.PRIMARY));
    }

    @Test(timeout = 5000)
    public void unknownRunnerNameInRequestReturnsError() {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("primary", "no-such-runner");
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                req, null, new LinkedHashMap<>());
        assertNotNull(r.error());
        assertTrue("error must mention runner name: " + r.error(),
                r.error().contains("no-such-runner"));
    }

    @Test(timeout = 5000)
    public void unknownPhaseNameInRequestReturnsError() {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("not-a-phase", AgentRunnerRegistry.CLAUDE);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                req, null, new LinkedHashMap<>());
        assertNotNull(r.error());
        assertTrue("error must mention phase name: " + r.error(),
                r.error().contains("not-a-phase"));
    }

    @Test(timeout = 5000)
    public void unknownPhaseInWorkstreamMapIsIgnoredNotFatal() {
        // Config-side bad entries must not be able to brick a submission.
        Map<String, String> ws = new LinkedHashMap<>();
        ws.put("future-phase", TEST_RUNNER);
        ws.put("primary", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), null, ws);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.PRIMARY));
    }

    // --- Workspace-layer precedence cases ----------------------------------

    /**
     * Case 1 of the precedence ladder: per-job override wins over a
     * workstream per-phase entry pointing at a different runner.
     */
    @Test(timeout = 5000)
    public void perJobOverrideBeatsWorkstreamPerPhase() {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("primary", TEST_RUNNER);
        Map<String, String> wsRunners = new LinkedHashMap<>();
        wsRunners.put("primary", AgentRunnerRegistry.CLAUDE);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                req, null, wsRunners, null, null);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.PRIMARY));
    }

    /**
     * Case 2: workstream per-phase beats the workstream default for that
     * phase only; other phases still use the workstream default.
     */
    @Test(timeout = 5000)
    public void workstreamPerPhaseBeatsWorkstreamDefault() {
        Map<String, String> wsRunners = new LinkedHashMap<>();
        wsRunners.put("deduplication", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), AgentRunnerRegistry.CLAUDE, wsRunners,
                null, null);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.DEDUPLICATION));
        assertEquals(AgentRunnerRegistry.CLAUDE,
                f.getRunnerForPhase(Phase.PRIMARY));
    }

    /**
     * Case 3: workstream default beats a workspace per-phase entry for that
     * phase. The workstream default shadows the workspace layer entirely.
     */
    @Test(timeout = 5000)
    public void workstreamDefaultBeatsWorkspacePerPhase() {
        Map<String, String> workspaceRunners = new LinkedHashMap<>();
        workspaceRunners.put("primary", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), AgentRunnerRegistry.CLAUDE,
                new LinkedHashMap<>(), null, workspaceRunners);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        // Workstream defaultRunner=claude wins over workspace runners[primary]=TEST_RUNNER.
        assertEquals(AgentRunnerRegistry.CLAUDE,
                f.getRunnerForPhase(Phase.PRIMARY));
    }

    /**
     * Case 4: workspace per-phase beats the workspace default for the phase
     * it covers; other phases still use the workspace default.
     */
    @Test(timeout = 5000)
    public void workspacePerPhaseBeatsWorkspaceDefault() {
        Map<String, String> workspaceRunners = new LinkedHashMap<>();
        workspaceRunners.put("primary", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), null, new LinkedHashMap<>(),
                AgentRunnerRegistry.CLAUDE, workspaceRunners);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.PRIMARY));
        assertEquals(AgentRunnerRegistry.CLAUDE,
                f.getRunnerForPhase(Phase.DEDUPLICATION));
    }

    /**
     * Case 5: workspace default beats the controller default ("claude") and
     * applies to every phase when nothing higher is set.
     */
    @Test(timeout = 5000)
    public void workspaceDefaultBeatsControllerDefault() {
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), null, new LinkedHashMap<>(),
                TEST_RUNNER, new LinkedHashMap<>());
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getDefaultRunner());
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.PRIMARY));
    }

    /**
     * Case 6: with every layer absent, the controller default ("claude")
     * is the floor.
     */
    @Test(timeout = 5000)
    public void controllerDefaultIsTheFloor() {
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), null, new LinkedHashMap<>(),
                null, new LinkedHashMap<>());
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(AgentRunnerRegistry.CLAUDE, f.getDefaultRunner());
    }

    /**
     * The middle of the ladder can be missing without breaking the layers
     * above and below. Per-job override jumps past empty workstream + empty
     * workspace defaults directly to the controller default for unrelated
     * phases.
     */
    @Test(timeout = 5000)
    public void missingMiddleLayersFallThroughCleanly() {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("primary", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                req, null, new LinkedHashMap<>(), null, new LinkedHashMap<>());
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.PRIMARY));
        assertEquals(AgentRunnerRegistry.CLAUDE,
                f.getRunnerForPhase(Phase.DEDUPLICATION));
    }

    /**
     * Unknown phase wire names in the workspace runner map are skipped, not
     * fatal, mirroring the workstream-side treatment. Workspace-level config
     * already validates phase keys at load time
     * ({@link WorkstreamConfig#validateWorkspaceRunners()}); this guard
     * exists for the rare case of a workspace that was loaded by a newer
     * controller and rolled back to a controller that doesn't recognise the
     * phase.
     */
    @Test(timeout = 5000)
    public void unknownPhaseInWorkspaceMapIsSkippedNotFatal() {
        Map<String, String> workspaceRunners = new LinkedHashMap<>();
        workspaceRunners.put("future-phase", TEST_RUNNER);
        workspaceRunners.put("primary", TEST_RUNNER);
        SubmissionRunnerResolver r = SubmissionRunnerResolver.resolve(
                new LinkedHashMap<>(), null, new LinkedHashMap<>(),
                null, workspaceRunners);
        assertNull(r.error());
        CodingAgentJobFactory f = new CodingAgentJobFactory("p");
        r.applyTo(f);
        assertEquals(TEST_RUNNER, f.getRunnerForPhase(Phase.PRIMARY));
    }
}
