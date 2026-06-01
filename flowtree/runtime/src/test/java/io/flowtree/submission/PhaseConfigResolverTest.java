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
import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /**
     * Registers the test runner with {@link AgentRunnerRegistry} before each test.
     */
    @Before
    public void registerTestRunner() {
        AgentRunnerRegistry.register(TEST_RUNNER, () -> null);
    }

    /**
     * Convenience factory that builds a {@link PhaseConfigBundle} from a default config and
     * an optional single per-phase override entry.
     */
    private static PhaseConfigBundle bundle(PhaseConfig def, Phase phase, PhaseConfig override) {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        if (phase != null && override != null) overrides.put(phase, override);
        return new PhaseConfigBundle(def == null ? PhaseConfig.EMPTY : def, overrides);
    }

    // --- Runner resolution (mirror SubmissionRunnerResolverTest cases) -------

    /**
     * Verifies that all-empty bundles resolve to the controller's hard-coded default runner.
     */
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

    /**
     * Verifies that a job-level per-phase override takes precedence over workstream and workspace defaults.
     */
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

    /**
     * Verifies that a job-level default config beats a workstream per-phase override.
     */
    @Test(timeout = 5000)
    public void jobDefaultBeatsWorkstreamOverride() {
        PhaseConfigBundle job = bundle(new PhaseConfig(TEST_RUNNER, null, null), null, null);
        PhaseConfigBundle ws = bundle(null, Phase.PRIMARY,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(job, ws, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.PRIMARY).runner());
    }

    /**
     * Verifies that a workstream per-phase override beats the workstream default for that phase
     * while other phases continue to use the workstream default.
     */
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

    /**
     * Verifies that the workstream default runner beats the workspace default runner.
     */
    @Test(timeout = 5000)
    public void workstreamDefaultBeatsWorkspaceDefault() {
        PhaseConfigBundle ws = bundle(new PhaseConfig(TEST_RUNNER, null, null), null, null);
        PhaseConfigBundle wsp = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, wsp);
        assertNull(r.error());
        assertEquals(TEST_RUNNER, r.forPhase(Phase.PRIMARY).runner());
    }

    /**
     * Verifies that a workspace per-phase override beats the workspace default for that phase
     * while other phases continue to use the workspace default.
     */
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

    /**
     * Verifies that the model field resolves correctly from a workstream default bundle.
     */
    @Test(timeout = 5000)
    public void modelResolvesFromWorkstreamDefault() {
        PhaseConfigBundle ws = bundle(new PhaseConfig(null, "claude-opus-4-7", null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, PhaseConfigBundle.EMPTY);
        assertNull(r.error());
        assertEquals("claude-opus-4-7", r.forPhase(Phase.PRIMARY).model());
    }

    /**
     * Verifies that a per-phase model override beats the bundle's default model for that phase.
     */
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

    /**
     * Verifies that a per-phase effort override beats the bundle's default effort for that phase.
     */
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

    /**
     * Verifies that runner, model, and effort fall through the precedence ladder independently
     * from different bundle levels.
     */
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

    /**
     * Verifies that specifying an unknown runner name produces a non-null error
     * that mentions the unrecognized runner identifier.
     */
    @Test(timeout = 5000)
    public void unknownRunnerReturnsError() {
        PhaseConfigBundle job = bundle(new PhaseConfig("no-such-runner", null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
        assertNotNull(r.error());
        assertTrue("error mentions runner: " + r.error(),
                r.error().contains("no-such-runner"));
    }

    /**
     * Verifies that specifying an invalid effort value produces a non-null error
     * that mentions the word "effort".
     */
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

    /**
     * Verifies that {@code applyTo(CodingAgentJobFactory)} populates the factory's bundle
     * with the fully-resolved default config and per-phase entries.
     */
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

    /**
     * Verifies that {@code resolveLegacyRunners} mirrors the runner-only precedence ladder
     * from per-phase request map entries.
     */
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

    /**
     * Verifies that {@code rejectLegacyRequestFields} accepts null, empty string, and
     * request bodies that contain only the new-shape fields.
     */
    @Test(timeout = 5000)
    public void rejectLegacyRequestFieldsAcceptsNullEmptyAndCleanBody() {
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields(null));
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields(""));
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields("{}"));
        assertNull(PhaseConfigResolver.rejectLegacyRequestFields(
                "{\"defaultPhaseConfig\":{\"runner\":\"claude\"},"
                        + "\"phaseConfigs\":{\"review\":{\"model\":\"opus\"}}}"));
    }

    /**
     * Verifies that {@code rejectLegacyRequestFields} returns a non-null error for each
     * removed legacy field and that the error message names both the field and its replacement.
     */
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

    /**
     * Verifies that when multiple legacy fields are present, {@code rejectLegacyRequestFields}
     * names the first removed field encountered in the defined check order.
     */
    @Test(timeout = 5000)
    public void rejectLegacyRequestFieldsNamesFirstRemovedFieldPresent() {
        String err = PhaseConfigResolver.rejectLegacyRequestFields(
                "{\"effort\":\"high\",\"runners\":{\"primary\":\"opencode\"}}");
        assertNotNull(err);
        assertTrue("model/effort are checked before runners", err.contains("effort"));
    }

    /**
     * Verifies that {@code rejectLegacyRequestFields} returns null (no error) for malformed
     * JSON strings that cannot be parsed as an object.
     */
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

    /**
     * Verifies that {@code requestBundle()} exposes per-phase overrides exactly as supplied
     * in the request, before any merging with higher-level bundles.
     */
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

    /**
     * Verifies that {@code requestBundle()} returns a non-null empty bundle when no request
     * config was supplied to the resolver.
     */
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

    // --- Provider leakage fix ------------------------------------------------

    /**
     * Root-cause regression test. Workspace default has opencode+openrouter;
     * review per-phase override changes runner to claude but omits provider.
     * Before the fix: provider="openrouter" leaked from the default into the
     * resolved review config, causing validateProviderForRunner to fail.
     * After the fix: the provider is NOT inherited when the runner changes,
     * so the resolved review config has provider=null (claude uses its own
     * default) and validation passes.
     */
    @Test(timeout = 5000)
    public void providerDoesNotLeakFromDefaultWhenRunnerChanges() {
        // Workspace: default=opencode+openrouter, review override=claude+null provider
        PhaseConfigBundle wsp = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"),
                Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, "claude-sonnet-4-6", null, null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, wsp);
        assertNull("provider must not leak into incompatible runner phase: " + r.error(),
                r.error());
        PhaseConfig review = r.forPhase(Phase.REVIEW);
        assertEquals(AgentRunnerRegistry.CLAUDE, review.runner());
        assertEquals("claude-sonnet-4-6", review.model());
        assertNull("provider must be null — openrouter was for opencode, not claude",
                review.provider());
    }

    /**
     * Provider must also not leak across bundle boundaries. If the workspace
     * default has opencode+openrouter and the workstream default overrides
     * only the runner to claude, the openrouter provider must not inherit.
     */
    @Test(timeout = 5000)
    public void providerDoesNotLeakAcrossBundleBoundaries() {
        PhaseConfigBundle ws = bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null, null), null, null);
        PhaseConfigBundle wsp = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, wsp);
        assertNull("cross-bundle leakage must not cause validation failure: " + r.error(),
                r.error());
        PhaseConfig primary = r.forPhase(Phase.PRIMARY);
        assertEquals(AgentRunnerRegistry.CLAUDE, primary.runner());
        assertNull("openrouter must not leak from workspace-opencode to workstream-claude",
                primary.provider());
    }

    /**
     * A provider that is explicitly set at the same level as a runner
     * (incompatible combination) must still fail validation. The leakage
     * suppression must not mask deliberately invalid configs.
     */
    @Test(timeout = 5000)
    public void explicitlyIncompatibleProviderStillFailsValidation() {
        // Explicit (claude, openrouter) in the same workspace per-phase entry.
        PhaseConfigBundle wsp = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"),
                Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null, "openrouter"));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, wsp);
        assertNotNull("explicitly incompatible provider must still fail", r.error());
        assertTrue(r.error().contains("anthropic"));
    }

    /**
     * When the workstream review phase overrides runner to claude with an
     * explicit provider=anthropic, that provider IS preserved (it is compatible
     * and was explicitly chosen by the operator).
     */
    @Test(timeout = 5000)
    public void explicitCompatibleProviderPreservedAcrossRunnerChange() {
        PhaseConfigBundle wsp = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"),
                Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, "claude-sonnet-4-6", null, "anthropic"));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, wsp);
        assertNull("explicit anthropic on claude review must be valid: " + r.error(), r.error());
        assertEquals("anthropic", r.forPhase(Phase.REVIEW).provider());
    }

    // --- Clearing semantics for workstream stored configs --------------------

    /**
     * {@code "phaseConfigs": {}} sent to applyToWorkstream clears all
     * per-phase overrides while leaving the default bundle intact.
     */
    @Test(timeout = 5000)
    public void clearAllPhaseOverridesOnWorkstream() {
        Workstream ws = new Workstream();
        ws.setPhaseConfigBundle(bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null),
                Phase.REVIEW, new PhaseConfig(TEST_RUNNER, "sonnet", null)));

        String err = PhaseConfigResolver.applyToWorkstream(ws,
                "{\"phaseConfigs\":{}}");
        assertNull("clearing phase overrides must not fail", err);
        assertTrue("all per-phase overrides must be cleared",
                ws.getPhaseConfigBundle().phaseConfigs().isEmpty());
        assertEquals(AgentRunnerRegistry.CLAUDE,
                ws.getPhaseConfigBundle().defaultPhaseConfig().runner());
    }

    /**
     * {@code "defaultPhaseConfig": {}} sent to applyToWorkstream clears the
     * stored default while leaving per-phase overrides intact.
     */
    @Test(timeout = 5000)
    public void clearDefaultConfigOnWorkstream() {
        Workstream ws = new Workstream();
        ws.setPhaseConfigBundle(bundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, "opus", null),
                Phase.REVIEW, new PhaseConfig(TEST_RUNNER, null, null)));

        String err = PhaseConfigResolver.applyToWorkstream(ws,
                "{\"defaultPhaseConfig\":{}}");
        assertNull("clearing default must not fail", err);
        assertTrue("default must be cleared",
                ws.getPhaseConfigBundle().defaultPhaseConfig().isEmpty());
        assertNotNull("per-phase entry must survive",
                ws.getPhaseConfigBundle().phaseConfigs().get(Phase.REVIEW));
    }

    /**
     * A phase set to {@code null} in {@code "phaseConfigs"} clears just that
     * phase's override while leaving others and the default intact.
     */
    @Test(timeout = 5000)
    public void clearOnePhaseOverrideOnWorkstream() {
        Workstream ws = new Workstream();
        Map<Phase, PhaseConfig> phases = new EnumMap<>(Phase.class);
        phases.put(Phase.REVIEW, new PhaseConfig(TEST_RUNNER, null, null));
        phases.put(Phase.PRIMARY, new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null));
        ws.setPhaseConfigBundle(new PhaseConfigBundle(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), phases));

        String err = PhaseConfigResolver.applyToWorkstream(ws,
                "{\"phaseConfigs\":{\"review\":null}}");
        assertNull("clearing one phase must not fail", err);
        assertNull("review override must be cleared",
                ws.getPhaseConfigBundle().phaseConfigs().get(Phase.REVIEW));
        assertNotNull("primary override must survive",
                ws.getPhaseConfigBundle().phaseConfigs().get(Phase.PRIMARY));
    }

    // --- Clearing semantics for workspace stored configs --------------------

    /**
     * {@code "phaseConfigs": {}} sent to applyToWorkspace clears all
     * per-phase workspace overrides while leaving the default intact.
     */
    @Test(timeout = 5000)
    public void clearAllPhaseOverridesOnWorkspace() {
        WorkstreamConfig.WorkspaceEntry entry = new WorkstreamConfig.WorkspaceEntry();
        entry.setDefaultPhaseConfig(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"));
        Map<String, PhaseConfig> pc = new LinkedHashMap<>();
        pc.put("review", new PhaseConfig(AgentRunnerRegistry.CLAUDE, "sonnet", null, null));
        entry.setPhaseConfigs(pc);

        String err = PhaseConfigResolver.applyToWorkspace(entry,
                "{\"phaseConfigs\":{}}");
        assertNull("clearing workspace phase overrides must not fail", err);
        assertTrue("workspace per-phase overrides must be cleared",
                entry.getPhaseConfigs().isEmpty());
        assertNotNull("workspace default must survive", entry.getDefaultPhaseConfig());
        assertEquals(AgentRunnerRegistry.OPENCODE, entry.getDefaultPhaseConfig().runner());
    }

    /**
     * {@code "defaultPhaseConfig": {}} sent to applyToWorkspace clears the
     * stored default while leaving per-phase overrides intact.
     */
    @Test(timeout = 5000)
    public void clearDefaultConfigOnWorkspace() {
        WorkstreamConfig.WorkspaceEntry entry = new WorkstreamConfig.WorkspaceEntry();
        entry.setDefaultPhaseConfig(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"));
        Map<String, PhaseConfig> pc = new LinkedHashMap<>();
        pc.put("review", new PhaseConfig(AgentRunnerRegistry.CLAUDE, "sonnet", null, null));
        entry.setPhaseConfigs(pc);

        String err = PhaseConfigResolver.applyToWorkspace(entry,
                "{\"defaultPhaseConfig\":{}}");
        assertNull("clearing workspace default must not fail", err);
        assertNull("workspace default must be null after clear",
                entry.getDefaultPhaseConfig());
        assertFalse("workspace per-phase overrides must survive",
                entry.getPhaseConfigs().isEmpty());
    }

    /**
     * A phase set to {@code null} in {@code "phaseConfigs"} clears just that
     * phase's workspace override while leaving others and the default intact.
     */
    @Test(timeout = 5000)
    public void clearOnePhaseOverrideOnWorkspace() {
        WorkstreamConfig.WorkspaceEntry entry = new WorkstreamConfig.WorkspaceEntry();
        entry.setDefaultPhaseConfig(
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null));
        Map<String, PhaseConfig> pc = new LinkedHashMap<>();
        pc.put("review", new PhaseConfig(TEST_RUNNER, null, null));
        pc.put("primary", new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null));
        entry.setPhaseConfigs(pc);

        String err = PhaseConfigResolver.applyToWorkspace(entry,
                "{\"phaseConfigs\":{\"review\":null}}");
        assertNull("clearing one workspace phase must not fail", err);
        assertNull("review override must be cleared",
                entry.getPhaseConfigs().get("review"));
        assertNotNull("primary override must survive",
                entry.getPhaseConfigs().get("primary"));
        assertNotNull("workspace default must survive", entry.getDefaultPhaseConfig());
    }

    // --- Job-level overrides taking precedence ------------------------------

    /**
     * A job-level per-phase config beats both workstream and workspace defaults,
     * including when the job introduces a runner incompatible with an inherited
     * provider from the workspace default.
     */
    @Test(timeout = 5000)
    public void jobLevelOverridesTakePrecedenceOverWorkstreamAndWorkspace() {
        PhaseConfigBundle ws = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, "opencode-model", null, "openrouter"),
                null, null);
        PhaseConfigBundle wsp = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"),
                null, null);
        // Job sets claude for the review phase — must beat both workspace and workstream
        PhaseConfigBundle req = bundle(
                null,
                Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, "claude-sonnet-4-6", null, null));

        PhaseConfigResolver r = PhaseConfigResolver.resolve(req, ws, wsp);
        assertNull("job-level claude override must not fail: " + r.error(), r.error());
        PhaseConfig review = r.forPhase(Phase.REVIEW);
        assertEquals(AgentRunnerRegistry.CLAUDE, review.runner());
        assertEquals("claude-sonnet-4-6", review.model());
        assertNull("job-level review must not inherit openrouter from workspace", review.provider());
        // Primary phase should still use workstream opencode+openrouter
        PhaseConfig primary = r.forPhase(Phase.PRIMARY);
        assertEquals(AgentRunnerRegistry.OPENCODE, primary.runner());
        assertEquals("openrouter", primary.provider());
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

    /**
     * Regression test verifying that a workstream with no phase config of its own inherits
     * the workspace-level defaultPhaseConfig and phaseConfigs through the full YAML
     * deserialization and resolver path.
     */
    @Test(timeout = 10000)
    public void workspaceLevelPhaseConfigInheritedWhenWorkstreamHasNone() throws IOException {
        // Reproduces the reported regression: a workstream with NO phase config
        // of its own must inherit the workspace-level defaultPhaseConfig /
        // phaseConfigs. Exercises the full path: YAML deserialization ->
        // WorkspaceEntry.toPhaseConfigBundle() -> PhaseConfigResolver.
        String yaml = "workstreams:\n"
                + "- workstreamId: \"ws-repro\"\n"
                + "  channelId: \"C_REPRO\"\n"
                + "  workspaceId: \"almostrealism\"\n"
                + "workspaces:\n"
                + "- id: \"almostrealism\"\n"
                + "  name: \"almostrealism\"\n"
                + "  defaultPhaseConfig:\n"
                + "    runner: \"opencode\"\n"
                + "    model: \"minimax/minimax-m2.7\"\n"
                + "    effort: \"medium\"\n"
                + "    provider: \"openrouter\"\n"
                + "  phaseConfigs:\n"
                + "    primary:\n"
                + "      runner: \"opencode\"\n"
                + "      model: \"minimax/minimax-m2.7\"\n"
                + "      provider: \"openrouter\"\n"
                + "    review:\n"
                + "      runner: \"claude\"\n"
                + "      model: \"sonnet\"\n"
                + "      effort: \"high\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry wsp = config.findWorkspace("almostrealism");
        assertNotNull("workspace entry must deserialize", wsp);

        PhaseConfigBundle workspaceBundle = wsp.toPhaseConfigBundle();
        Assert.assertEquals("workspace default runner must deserialize",
                "opencode", workspaceBundle.defaultPhaseConfig().runner());

        PhaseConfigResolver resolver = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, workspaceBundle);
        assertNull(resolver.error());
        Assert.assertEquals("primary must inherit workspace opencode runner",
                "opencode", resolver.forPhase(Phase.PRIMARY).runner());
        Assert.assertEquals("review must use workspace claude override",
                "claude", resolver.forPhase(Phase.REVIEW).runner());

        // End-to-end: mirror the actual submit sequence. The workspace uses the
        // new-shape phase config, so the legacy runner fields are null (as at
        // runtime); SubmissionRunnerResolver therefore contributes nothing and
        // PhaseConfigResolver.applyTo(factory) must drive the runner selection.
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        SubmissionRunnerResolver runnerResolver = SubmissionRunnerResolver.resolve(
                Map.of(), null, null, null, null);
        assertNull(runnerResolver.error());
        runnerResolver.applyTo(factory);
        resolver.applyTo(factory);

        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        Assert.assertEquals("job PRIMARY runner must resolve to workspace opencode",
                "opencode", job.getRunnerForPhase(Phase.PRIMARY));
        Assert.assertEquals("job REVIEW runner must resolve to workspace claude",
                "claude", job.getRunnerForPhase(Phase.REVIEW));
    }
}
