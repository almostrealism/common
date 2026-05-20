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

package io.flowtree.slack;

import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link SubmissionRunnerResolver}. Verifies the runner
 * precedence ladder applied at submission time:
 * per-job > workstream per-phase > workstream default > {@code "claude"}.
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
}
