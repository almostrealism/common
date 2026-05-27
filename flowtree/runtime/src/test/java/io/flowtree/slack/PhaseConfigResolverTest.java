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
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PhaseConfigResolver}. Walks the seven-level precedence
 * ladder for each of {@code runner}, {@code model}, {@code effort} —
 * independently and combined — and documents the behavioural change
 * relative to {@link SubmissionRunnerResolver}.
 */
public class PhaseConfigResolverTest extends TestSuiteBase {

    /** Registered alongside {@code "claude"} for tests that need a second runner. */
    private static final String TEST_RUNNER = "phase-config-resolver-test-runner";

    @Before
    public void registerTestRunner() {
        AgentRunnerRegistry.register(TEST_RUNNER, () -> null);
    }

    private static PhaseConfigBundle bundle(PhaseConfig def, Phase phase, PhaseConfig override) {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        if (phase != null && override != null) overrides.put(phase, override);
        return new PhaseConfigBundle(def == null ? PhaseConfig.EMPTY : def, overrides);
    }

    // --- Runner resolution (mirror SubmissionRunnerResolverTest cases) -------

    @Test(timeout = 5000)
    public void allEmptyResolvesToControllerDefault() {
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        PhaseConfig resolved = r.forPhase(Phase.PRIMARY);
        assertEquals(AgentRunnerRegistry.CLAUDE, resolved.runner());
        assertNull(resolved.model());
        assertNull(resolved.effort());
    }

    @Test(timeout = 5000)
    public void jobOverrideBeatsAllOtherLevels() {
        PhaseConfigBundle job = bundle(null, Phase.PRIMARY,
                new PhaseConfig(TEST_RUNNER, null, null));
        PhaseConfigBundle ws = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null);
        PhaseConfigBundle wsp = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(job, ws, wsp);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.PRIMARY).runner());
    }

    @Test(timeout = 5000)
    public void jobDefaultBeatsWorkstreamOverride() {
        PhaseConfigBundle job = bundle(new PhaseConfig(TEST_RUNNER, null, null), null, null);
        PhaseConfigBundle ws = bundle(null, Phase.PRIMARY,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(job, ws, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.PRIMARY).runner());
    }

    @Test(timeout = 5000)
    public void workstreamOverrideBeatsWorkstreamDefault() {
        PhaseConfigBundle ws = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null),
                Phase.REVIEW, new PhaseConfig(TEST_RUNNER, null, null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.REVIEW).runner());
        assertEquals(AgentRunnerRegistry.CLAUDE, r.forPhase(Phase.PRIMARY).runner());
    }

    @Test(timeout = 5000)
    public void workstreamDefaultBeatsWorkspaceDefault() {
        PhaseConfigBundle ws = bundle(new PhaseConfig(TEST_RUNNER, null, null), null, null);
        PhaseConfigBundle wsp = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, wsp);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.PRIMARY).runner());
    }

    @Test(timeout = 5000)
    public void workspaceOverrideBeatsWorkspaceDefault() {
        PhaseConfigBundle wsp = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null),
                Phase.REVIEW, new PhaseConfig(TEST_RUNNER, null, null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, wsp);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.REVIEW).runner());
        assertEquals(AgentRunnerRegistry.CLAUDE, r.forPhase(Phase.PRIMARY).runner());
    }

    // --- Model resolution -----------------------------------------------------

    @Test(timeout = 5000)
    public void modelResolvesFromWorkstreamDefault() {
        PhaseConfigBundle ws = bundle(new PhaseConfig(null, "claude-opus-4-7", null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals("claude-opus-4-7", r.forPhase(Phase.PRIMARY).model());
    }

    @Test(timeout = 5000)
    public void modelPerPhaseOverrideBeatsDefault() {
        PhaseConfigBundle job = bundle(new PhaseConfig(null, "claude-opus-4-7", null),
                Phase.REVIEW, new PhaseConfig(null, "claude-sonnet-4-6", null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals("claude-sonnet-4-6", r.forPhase(Phase.REVIEW).model());
        assertEquals("claude-opus-4-7", r.forPhase(Phase.PRIMARY).model());
    }

    // --- Effort resolution ----------------------------------------------------

    @Test(timeout = 5000)
    public void effortPerPhaseOverrideBeatsDefault() {
        PhaseConfigBundle job = bundle(new PhaseConfig(null, null, "high"),
                Phase.COMMIT_MESSAGE, new PhaseConfig(null, null, "low"));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals("low", r.forPhase(Phase.COMMIT_MESSAGE).effort());
        assertEquals("high", r.forPhase(Phase.PRIMARY).effort());
    }

    // --- Independent per-field fall-through -----------------------------------

    @Test(timeout = 5000)
    public void independentFieldsFallThroughIndependently() {
        // Job sets effort only, workstream sets runner only, workspace sets model only.
        PhaseConfigBundle job = bundle(new PhaseConfig(null, null, "high"), null, null);
        PhaseConfigBundle ws = bundle(new PhaseConfig(TEST_RUNNER, null, null), null, null);
        PhaseConfigBundle wsp = bundle(new PhaseConfig(null, "claude-opus-4-7", null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(job, ws, wsp);
        assertNull(r.error());
        PhaseConfig resolved = r.forPhase(Phase.PRIMARY);
        assertEquals(TEST_RUNNER, resolved.runner());
        assertEquals("claude-opus-4-7", resolved.model());
        assertEquals("high", resolved.effort());
    }

    /**
     * Documents the behavioural change relative to the legacy
     * {@link SubmissionRunnerResolver}. Under the legacy resolver, a
     * workstream {@code defaultRunner} fully shadowed any workspace
     * per-phase entry. Under {@link PhaseConfigResolver}, each field
     * falls through independently: a workstream default that sets only
     * {@code runner} must NOT block {@code model} on a workspace per-phase
     * entry.
     */
    @Test(timeout = 5000)
    public void workspacePerPhaseNoLongerShadowedByWorkstreamDefault() {
        PhaseConfigBundle ws = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null);
        PhaseConfigBundle wsp = bundle(null, Phase.REVIEW,
                new PhaseConfig(null, "claude-sonnet-4-6", "high"));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, wsp);
        assertNull(r.error());
        PhaseConfig review = r.forPhase(Phase.REVIEW);
        // Under the new ladder, workspace per-phase model/effort flow through
        // even though the workstream sets a default runner.
        assertEquals(AgentRunnerRegistry.CLAUDE, review.runner());
        assertEquals("claude-sonnet-4-6", review.model());
        assertEquals("high", review.effort());
    }

    // --- Validation -----------------------------------------------------------

    @Test(timeout = 5000)
    public void unknownRunnerReturnsError() {
        PhaseConfigBundle job = bundle(new PhaseConfig("no-such-runner", null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNotNull(r.error());
        assertTrue("error mentions runner: " + r.error(),
                r.error().contains("no-such-runner"));
    }

    @Test(timeout = 5000)
    public void invalidEffortReturnsError() {
        PhaseConfigBundle job = bundle(new PhaseConfig(null, null, "nonsense"), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNotNull(r.error());
        assertTrue("error mentions effort: " + r.error(),
                r.error().toLowerCase().contains("effort"));
    }

    // --- applyTo --------------------------------------------------------------

    @Test(timeout = 5000)
    public void applyToFactoryPopulatesBundle() {
        PhaseConfigBundle job = bundle(new PhaseConfig(null, "claude-opus-4-7", "high"),
                Phase.REVIEW, new PhaseConfig(null, "claude-sonnet-4-6", "medium"));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        r.applyTo(factory);
        PhaseConfigBundle applied = factory.getPhaseConfigBundle();
        // Default carries the resolved model + effort plus the controller default runner.
        assertEquals("claude-opus-4-7", applied.defaultPhaseConfig().model());
        assertEquals("high", applied.defaultPhaseConfig().effort());
        assertEquals(AgentRunnerRegistry.CLAUDE, applied.defaultPhaseConfig().runner());
        // REVIEW entry has its own model + effort.
        PhaseConfig review = applied.phaseConfigs().get(Phase.REVIEW);
        assertNotNull(review);
        assertEquals("claude-sonnet-4-6", review.model());
        assertEquals("medium", review.effort());
    }

    // --- Provider-axis validation ---------------------------------------------

    /**
     * Provider field flows through the precedence ladder and is available on
     * the resolved {@link PhaseConfig}. The opencode runner accepts any provider.
     */
    @Test(timeout = 5000)
    public void providerFlowsThroughLadder() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull("opencode + openrouter should be valid", r.error());
        assertEquals("openrouter", r.forPhase(Phase.PRIMARY).provider());
    }

    /**
     * A per-phase provider override wins over the default provider, independently
     * of runner and model resolution.
     */
    @Test(timeout = 5000)
    public void perPhaseProviderBeatsDefault() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "local"),
                Phase.REVIEW,
                new PhaseConfig(null, null, null, "openrouter"));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals("local", r.forPhase(Phase.PRIMARY).provider());
        assertEquals("openrouter", r.forPhase(Phase.REVIEW).provider());
    }

    /**
     * {@code runner=claude} with a non-anthropic provider must fail with a clear
     * error message. Using claude against openrouter is not supported in this pass.
     */
    @Test(timeout = 5000)
    public void claudeWithNonAnthropicProviderFails() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null, "openrouter"), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNotNull("claude + openrouter should fail validation", r.error());
        assertTrue("error should mention claude", r.error().contains("claude"));
        assertTrue("error should mention anthropic", r.error().contains("anthropic"));
        assertTrue("error should mention openrouter", r.error().contains("openrouter"));
    }

    /**
     * {@code runner=claude} with {@code provider=anthropic} is explicitly permitted
     * since that is the only supported combination.
     */
    @Test(timeout = 5000)
    public void claudeWithAnthropicProviderIsAllowed() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null, "anthropic"), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull("claude + anthropic should be valid", r.error());
        assertEquals("anthropic", r.forPhase(Phase.PRIMARY).provider());
    }

    /**
     * Per-phase provider=openrouter rejection still triggers even when the
     * default provider is acceptable.
     */
    @Test(timeout = 5000)
    public void perPhaseClaudeWithOpenrouterFails() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null, "anthropic"),
                Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null, "openrouter"));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNotNull("per-phase claude + openrouter should fail", r.error());
        assertTrue(r.error().contains("review"));
    }

    /**
     * When no provider is set, the resolver does not impose one — it stays
     * {@code null} and the runner applies its own default.
     */
    @Test(timeout = 5000)
    public void noProviderStaysNull() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertNull("provider should remain null when not configured", r.forPhase(Phase.PRIMARY).provider());
    }

    // --- resolveLegacyRunners convenience overload ----------------------------

    @Test(timeout = 5000)
    public void resolveLegacyRunnersMirrorsRunnerOnlyLadder() {
        Map<String, String> requestRunners = new LinkedHashMap<>();
        requestRunners.put("review", TEST_RUNNER);
        PhaseConfigResolver r = PhaseConfigResolver.resolveLegacyRunners(
                requestRunners, AgentRunnerRegistry.CLAUDE, null, null, null);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.REVIEW).runner());
        assertEquals(AgentRunnerRegistry.CLAUDE, r.forPhase(Phase.PRIMARY).runner());
    }

    // --- rejectLegacyRequestFields: the clean-break HTTP guard ----------------

    @Test(timeout = 5000)
    public void rejectLegacyRequestFieldsAcceptsNullEmptyAndCleanBody() {
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields(null));
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields(""));
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields("{}"));
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields(
                "{\"defaultPhaseConfig\":{\"runner\":\"claude\"},"
                        + "\"phaseConfigs\":{\"review\":{\"model\":\"opus\"}}}"));
    }

    @Test(timeout = 5000)
    public void rejectLegacyRequestFieldsRejectsEachRemovedField() {
        for (String field : new String[] {"model", "effort", "runners", "defaultRunner"}) {
            String err = PhaseConfigResolver.rejectLegacyRequestFields(
                    "{\"" + field + "\":\"x\"}");
            assertNotNull("expected rejection for legacy field " + field, err);
            assertTrue("error should name the field " + field, err.contains(field));
            assertTrue("error should point at the per-phase replacement",
                    err.contains("defaultPhaseConfig") || err.contains("phaseConfigs"));
        }
    }

    @Test(timeout = 5000)
    public void rejectLegacyRequestFieldsNamesFirstRemovedFieldPresent() {
        String err = PhaseConfigResolver.rejectLegacyRequestFields(
                "{\"effort\":\"high\",\"runners\":{\"primary\":\"opencode\"}}");
        assertNotNull(err);
        assertTrue("model/effort are checked before runners", err.contains("effort"));
    }

    @Test(timeout = 5000)
    public void rejectLegacyRequestFieldsIgnoresMalformedJson() {
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields("not-json"));
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields("[1,2,3]"));
    }

    // --- requestBundle() accessor ----------------------------------------------------

    /**
     * Verifies that requestBundle() exposes exactly what was supplied in the
     * request, before merging with workstream / workspace / controller defaults.
     */
    @Test(timeout = 5000)
    public void requestBundleExposesRawRequestDefault() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(TEST_RUNNER, "claude-opus-4-7", "high"),
                null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        PhaseConfigBundle reqBundle = r.requestBundle();
        assertNotNull(reqBundle);
        assertEquals(TEST_RUNNER, reqBundle.defaultPhaseConfig().runner());
        assertEquals("claude-opus-4-7", reqBundle.defaultPhaseConfig().model());
        assertEquals("high", reqBundle.defaultPhaseConfig().effort());
    }

    @Test(timeout = 5000)
    public void requestBundleExposesRawRequestPerPhaseOverrides() {
        PhaseConfigBundle req = bundle(
                new PhaseConfig(null, null, "high"),
                Phase.REVIEW, new PhaseConfig(TEST_RUNNER, null, null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        PhaseConfigBundle reqBundle = r.requestBundle();
        assertNotNull(reqBundle);
        PhaseConfig review = reqBundle.phaseConfigs().get(Phase.REVIEW);
        assertNotNull(review);
        assertEquals(TEST_RUNNER, review.runner());
    }

    @Test(timeout = 5000)
    public void requestBundleReturnsEmptyForNoRequestConfig() {
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        PhaseConfigBundle reqBundle = r.requestBundle();
        assertNotNull(reqBundle);
        assertTrue(reqBundle.isEmpty());
    }

    // --- appendBundleJson with custom field names ----------------------------------

    /**
     * Verifies that the two-argument appendBundleJson overload emits the
     * configured field names instead of the defaults.
     */
    @Test(timeout = 5000)
    public void appendBundleJsonWithCustomNames() {
        PhaseConfigBundle b = bundle(
                new PhaseConfig(TEST_RUNNER, "claude-opus-4-7", null),
                Phase.REVIEW, new PhaseConfig(null, "claude-sonnet-4-6", "medium"));

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        PhaseConfigResolver.appendBundleJson(json, b,
                "customDefault", "customPhases");
        json.append("}");

        String result = json.toString();
        assertTrue("Should contain customDefault",
                result.contains("\"customDefault\""));
        assertTrue("Should contain customPhases",
                result.contains("\"customPhases\""));
        assertTrue("Should contain runner value",
                result.contains(TEST_RUNNER));
        assertFalse("Should NOT contain defaultPhaseConfig label",
                result.contains("\"defaultPhaseConfig\""));
        assertFalse("Should NOT contain phaseConfigs label",
                result.contains("\"phaseConfigs\""));
        assertTrue("Should contain review phase key",
                result.contains("\"review\""));
    }

    /**
     * Verifies that appendBundleJson with custom names emits nothing when
     * the bundle is empty or null.
     */
    @Test(timeout = 5000)
    public void appendBundleJsonWithCustomNamesSkipsEmpty() {
        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        PhaseConfigResolver.appendBundleJson(json, PhaseConfigBundle.EMPTY,
                "customDefault", "customPhases");
        json.append("}");

        String result = json.toString();
        assertEquals("{\"ok\":true}", result);
    }

    /**
     * Verifies that passing null as a field name suppresses that field,
     * allowing callers to emit only one of default or per-phase.
     */
    @Test(timeout = 5000)
    public void appendBundleJsonWithNullFieldNameSuppressesField() {
        PhaseConfigBundle b = bundle(
                new PhaseConfig(TEST_RUNNER, null, null),
                Phase.REVIEW, new PhaseConfig(null, "claude-sonnet-4-6", null));

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        PhaseConfigResolver.appendBundleJson(json, b, null, "perPhaseConfigs");
        json.append("}");

        String result = json.toString();
        assertFalse("default field should be suppressed",
                result.contains("defaultPhaseConfig"));
        assertTrue("phases field should be present",
                result.contains("\"perPhaseConfigs\""));
    }

    // --- Submit response: resolved (effective) config under plain names -----------

    /**
     * The submit response echoes the fully-resolved bundle under the same
     * {@code defaultPhaseConfig} / {@code phaseConfigs} names the config input
     * uses. A job-level default overrides the workstream default, and that
     * resolved value is what appears in the response.
     */
    @Test(timeout = 5000)
    public void submitResponseEmitsResolvedConfigUnderPlainNames() {
        PhaseConfigBundle workstreamBundle = bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null);
        PhaseConfigBundle req = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, "minimax", "high"),
                null, null);

        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, workstreamBundle, PhaseConfigBundle.EMPTY);
        assertNull(r.error());

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        PhaseConfigResolver.appendBundleJson(json, r.resolvedBundle());
        json.append("}");

        String result = json.toString();
        assertTrue("Should emit defaultPhaseConfig",
                result.contains("\"defaultPhaseConfig\""));
        assertTrue("Resolved runner should be opencode (job overrides workstream claude)",
                result.contains("\"runner\":\"opencode\""));
        assertTrue("Resolved model should be minimax",
                result.contains("\"model\":\"minimax\""));
        assertFalse("Response must not use the removed requested/effective prefixes",
                result.contains("requestedDefaultPhaseConfig")
                        || result.contains("effectiveDefaultPhaseConfig"));
    }
}
