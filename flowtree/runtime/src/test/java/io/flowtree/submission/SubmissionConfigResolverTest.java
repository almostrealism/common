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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkspaceEntry;
import io.flowtree.workstream.WorkstreamConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    /** Registers {@link #TEST_RUNNER} in the {@link AgentRunnerRegistry} before each test. */
    @Before
    public void registerTestRunner() {
        AgentRunnerRegistry.register(TEST_RUNNER, () -> null);
    }

    /** Returns a minimal {@link Workstream} instance suitable for use in tests. */
    private static Workstream workstream() {
        return new Workstream("ws-1", "C0001", "general");
    }

    /**
     * Returns a {@link WorkspaceEntry} with the given identifier
     * and no other configuration.
     */
    private static WorkspaceEntry workspace(String id) {
        WorkspaceEntry entry = new WorkspaceEntry();
        entry.setId(id);
        return entry;
    }

    /**
     * Returns a {@link PhaseConfigBundle} with the given default {@link PhaseConfig}
     * and an optional per-phase override.
     */
    private static PhaseConfigBundle bundle(PhaseConfig def, Phase phase, PhaseConfig override) {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        if (phase != null && override != null) overrides.put(phase, override);
        return new PhaseConfigBundle(def == null ? PhaseConfig.EMPTY : def, overrides);
    }

    /**
     * Verifies that when no workstream or workspace configuration is present the
     * resolver produces no error and the resolved phase config uses the controller
     * default runner.
     */
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
        WorkspaceEntry wsEntry = workspace("acme");
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
        WorkspaceEntry wsEntry = workspace("acme");
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

    /**
     * Verifies that when both a workstream and a workspace supply a default runner the
     * workstream-level value takes precedence over the workspace-level value.
     */
    @Test(timeout = 5000)
    public void workstreamDefaultBeatsWorkspaceDefault() {
        WorkspaceEntry wsEntry = workspace("acme");
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

    /**
     * Verifies that a {@link PhaseConfigBundle} supplied on the request overrides both
     * workspace-level and workstream-level phase configuration.
     */
    @Test(timeout = 5000)
    public void requestBundleBeatsWorkspaceAndWorkstream() {
        Workstream ws = workstream();
        ws.setPhaseConfigBundle(bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null));
        WorkspaceEntry wsEntry = workspace("acme");
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

    /**
     * Verifies that passing {@code null} as the request {@link PhaseConfigBundle}
     * is treated identically to {@link PhaseConfigBundle#EMPTY} and does not throw.
     */
    @Test(timeout = 5000)
    public void nullRequestBundleTreatedAsEmpty() {
        SubmissionConfigResolver r = SubmissionConfigResolver.resolve(
                null, workstream(), null);
        assertNull(r.error());
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        r.applyTo(f); // must not throw
    }

    /**
     * Verifies that a runner-validation error is surfaced through {@link SubmissionConfigResolver#error()}
     * and that {@link SubmissionConfigResolver#applyTo} is a no-op when an error is present.
     */
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

    /**
     * Parses the members {@link SubmissionConfigResolver#appendResolvedJobConfigJson}
     * appends for {@code factory}, behind a single preceding member so the
     * leading comma yields a well-formed object.
     */
    private static JsonNode resolvedJobConfig(CodingAgentJob.Factory factory) throws IOException {
        StringBuilder json = new StringBuilder("{\"ok\":true");
        SubmissionConfigResolver.appendResolvedJobConfigJson(json, factory);
        json.append('}');
        return new ObjectMapper().readTree(json.toString());
    }

    /**
     * Verifies that a default factory echoes every optional-phase member with its
     * default value, deduplication reported disabled for the default
     * {@link CodingAgentJob#DEDUP_NONE} mode, and the enforce-changes cap at its default.
     */
    @Test(timeout = 5000)
    public void resolvedJobConfigEchoesDefaults() throws IOException {
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        JsonNode node = resolvedJobConfig(f);
        assertTrue(node.get("ok").asBoolean());
        assertFalse(node.get("deduplicationEnabled").asBoolean());
        assertFalse(node.get("enforceChanges").asBoolean());
        assertEquals(CodingAgentJob.DEFAULT_MAX_RULE_RETRIES,
                node.get("maxEnforceChangesRetries").asInt());
        assertEquals(f.isAutoCreatePr(), node.get("autoCreatePr").asBoolean());
        assertEquals(f.isCollaborative(), node.get("collaborative").asBoolean());
        assertEquals(f.isFalsificationEnabled(), node.get("falsificationEnabled").asBoolean());
        assertEquals(f.isRetrospectiveEnabled(), node.get("retrospectiveEnabled").asBoolean());
        assertEquals(f.isObjectOrientedReviewEnabled(),
                node.get("objectOrientedReviewEnabled").asBoolean());
        assertEquals(f.isEnforceOrganizationalPlacement(),
                node.get("organizationalPlacementEnabled").asBoolean());
        assertEquals(f.isSensitiveFileProtectionEnabled(),
                node.get("sensitiveFileProtectionEnabled").asBoolean());
        assertEquals(f.isBypassAgentPermissionPrompts(),
                node.get("bypassAgentPermissionPrompts").asBoolean());
        assertEquals("ok plus eleven configuration members", 12, node.size());
    }

    /**
     * Verifies that settings applied to the factory are what the echo reports:
     * enforce-changes with a cap of {@code 0} (a meaningful value, not a default),
     * and toggled boolean phases.
     */
    @Test(timeout = 5000)
    public void resolvedJobConfigEchoesAppliedSettings() throws IOException {
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        f.setEnforceChanges(true);
        f.setMaxEnforceChangesRetries(0);
        f.setCollaborative(true);
        f.setFalsificationEnabled(true);
        f.setAutoCreatePr(true);
        JsonNode node = resolvedJobConfig(f);
        assertTrue(node.get("enforceChanges").asBoolean());
        assertEquals(0, node.get("maxEnforceChangesRetries").asInt());
        assertTrue(node.get("collaborative").asBoolean());
        assertTrue(node.get("falsificationEnabled").asBoolean());
        assertTrue(node.get("autoCreatePr").asBoolean());
    }

    /**
     * Verifies the deduplication flag: enabled for {@link CodingAgentJob#DEDUP_LOCAL}
     * and {@link CodingAgentJob#DEDUP_SPAWN}, disabled for an explicit
     * {@link CodingAgentJob#DEDUP_NONE} and for an empty mode.
     */
    @Test(timeout = 5000)
    public void resolvedJobConfigReportsDeduplicationByMode() throws IOException {
        CodingAgentJob.Factory f = new CodingAgentJob.Factory("p");
        f.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
        assertTrue(resolvedJobConfig(f).get("deduplicationEnabled").asBoolean());
        f.setDeduplicationMode(CodingAgentJob.DEDUP_SPAWN);
        assertTrue(resolvedJobConfig(f).get("deduplicationEnabled").asBoolean());
        f.setDeduplicationMode(CodingAgentJob.DEDUP_NONE);
        assertFalse(resolvedJobConfig(f).get("deduplicationEnabled").asBoolean());
        f.setDeduplicationMode("");
        assertFalse(resolvedJobConfig(f).get("deduplicationEnabled").asBoolean());
    }
}
