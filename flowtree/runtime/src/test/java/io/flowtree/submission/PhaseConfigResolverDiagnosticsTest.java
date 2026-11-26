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

import io.flowtree.jobs.agent.AgentCapabilities;
import io.flowtree.jobs.agent.AgentRunRequest;
import io.flowtree.jobs.agent.AgentRunResult;
import io.flowtree.jobs.agent.AgentRunner;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the submission-validation and diagnostic surface of
 * {@link PhaseConfigResolver}: per-phase model and provider rejection against
 * the resolved runner's capabilities, per-field resolution-source attribution
 * ({@link PhaseConfigResolver#resolutionOf(Phase)}), capability warnings, and
 * strict request-shape checking ({@link PhaseConfigResolver#requestShapeError(String)}).
 * The precedence ladder itself is covered by {@link PhaseConfigResolverTest}.
 */
public class PhaseConfigResolverDiagnosticsTest extends TestSuiteBase {

    /** A second runner whose supplier yields {@code null}, so it cannot be asked anything. */
    private static final String TEST_RUNNER = "phase-config-diagnostics-test-runner";

    /** A runner that does not honour effort levels and accepts any provider. */
    private static final String NO_EFFORT_RUNNER = "phase-config-no-effort-runner";

    /** Registers the test runners with {@link AgentRunnerRegistry} before each test. */
    @Before
    public void registerTestRunners() {
        AgentRunnerRegistry.register(TEST_RUNNER, () -> null);
        AgentRunnerRegistry.register(NO_EFFORT_RUNNER, () -> new AgentRunner() {
            @Override
            public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
                return null;
            }

            @Override
            public AgentCapabilities capabilities() {
                return new AgentCapabilities(false, false, false, false,
                        false, false, false, Set.of());
            }

            @Override
            public String getName() {
                return NO_EFFORT_RUNNER;
            }
        });
    }

    /** Builds a bundle from a default config and an optional single per-phase entry. */
    private static PhaseConfigBundle bundle(PhaseConfig def, Phase phase, PhaseConfig override) {
        Map<Phase, PhaseConfig> overrides = new EnumMap<>(Phase.class);
        if (phase != null && override != null) overrides.put(phase, override);
        return new PhaseConfigBundle(def == null ? PhaseConfig.EMPTY : def, overrides);
    }

    /** Resolves {@code job} alone, with empty workstream and workspace layers. */
    private static PhaseConfigResolver resolveJob(PhaseConfigBundle job) {
        return PhaseConfigResolver.resolve(job, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
    }

    // --- Submission-time per-phase model rejection ---------------------------

    /**
     * An unsupported model set on a single phase is rejected at submission with
     * an error naming that phase (not just the default), so the job fails fast
     * rather than mid-run.
     */
    @Test(timeout = 5000)
    public void unsupportedPerPhaseModelIsRejectedNamingThePhase() {
        PhaseConfigResolver r = resolveJob(bundle(null, Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.CLAUDE, "gpt-4", null)));
        assertNotNull("an unsupported per-phase model must be rejected", r.error());
        assertTrue(r.error(), r.error().contains("gpt-4"));
        assertTrue(r.error(), r.error().contains(AgentRunnerRegistry.CLAUDE));
        assertTrue("the error must name the offending phase: " + r.error(),
                r.error().contains(Phase.REVIEW.wireName()));
    }

    // --- Capability-driven provider rejection --------------------------------

    /**
     * An opencode provider outside the runner's advertised
     * {@link AgentCapabilities#supportedProviders()} is rejected at submission —
     * the same set opencode's run step enforces — with an error naming the
     * provider, the runner and the phase.
     */
    @Test(timeout = 5000)
    public void opencodeWithUnknownProviderIsRejected() {
        PhaseConfigResolver r = resolveJob(bundle(null, Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "no-such-provider")));
        assertNotNull("opencode + an unknown provider must be rejected", r.error());
        assertTrue(r.error(), r.error().contains("no-such-provider"));
        assertTrue(r.error(), r.error().contains(AgentRunnerRegistry.OPENCODE));
        assertTrue(r.error(), r.error().contains(Phase.REVIEW.wireName()));
        assertTrue("the error must list what opencode accepts: " + r.error(),
                r.error().contains("openrouter"));
    }

    /** Every provider opencode advertises is accepted. */
    @Test(timeout = 5000)
    public void opencodeWithEachAdvertisedProviderIsAccepted() {
        Set<String> providers = AgentRunnerRegistry.get(AgentRunnerRegistry.OPENCODE)
                .capabilities().supportedProviders();
        assertFalse("opencode must advertise its providers", providers.isEmpty());
        for (String provider : providers) {
            PhaseConfigResolver r = resolveJob(bundle(
                    new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, provider), null, null));
            assertNull("provider " + provider + " must be accepted: " + r.error(), r.error());
            assertEquals(provider, r.forPhase(Phase.PRIMARY).provider());
        }
    }

    /**
     * A runner that advertises no providers is unconstrained, and a runner that
     * cannot be instantiated is treated as unconstrained rather than rejected.
     */
    @Test(timeout = 5000)
    public void unconstrainedOrUnavailableRunnerAcceptsAnyProvider() {
        assertNull(resolveJob(bundle(
                new PhaseConfig(NO_EFFORT_RUNNER, null, null, "anything"), null, null)).error());
        assertNull(resolveJob(bundle(
                new PhaseConfig(TEST_RUNNER, null, null, "anything"), null, null)).error());
    }

    // --- Resolution-source attribution --------------------------------------

    /**
     * Each resolved field reports the precedence level that supplied it: a
     * job-level default model and a workstream-level default runner resolve to
     * {@code job default} and {@code workstream default} respectively, with
     * unset fields reported as {@code none}.
     */
    @Test(timeout = 5000)
    public void resolutionReportsPerFieldSources() {
        PhaseConfigBundle job = bundle(new PhaseConfig(null, "claude-opus-5-5", null), null, null);
        PhaseConfigBundle ws = bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(job, ws, PhaseConfigBundle.EMPTY);
        assertNull(r.error());

        PhaseConfigResolver.PhaseResolution primary = r.resolutionOf(Phase.PRIMARY);
        assertEquals("claude-opus-5-5", primary.model().value());
        assertEquals(PhaseConfigResolver.SOURCE_JOB_DEFAULT, primary.model().source());
        assertEquals(AgentRunnerRegistry.CLAUDE, primary.runner().value());
        assertEquals(PhaseConfigResolver.SOURCE_WORKSTREAM_DEFAULT, primary.runner().source());
        assertNull(primary.effort().value());
        assertEquals(PhaseConfigResolver.SOURCE_NONE, primary.effort().source());
        assertNull(primary.provider().value());
        assertEquals(PhaseConfigResolver.SOURCE_NONE, primary.provider().source());

        assertEquals(r.forPhase(Phase.PRIMARY).runner(), primary.runner().value());
        assertEquals(r.forPhase(Phase.PRIMARY).model(), primary.model().value());
    }

    /**
     * A workstream per-phase override is attributed to {@code workstream per-phase}
     * for the phase it sets, while a phase the workstream leaves unset falls
     * through to the {@code controller default}.
     */
    @Test(timeout = 5000)
    public void resolutionAttributesWorkstreamPerPhaseAndControllerDefault() {
        PhaseConfigBundle ws = bundle(null, Phase.REVIEW, new PhaseConfig(TEST_RUNNER, null, null));
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, ws, PhaseConfigBundle.EMPTY);
        assertNull(r.error());

        PhaseConfigResolver.PhaseResolution review = r.resolutionOf(Phase.REVIEW);
        assertEquals(TEST_RUNNER, review.runner().value());
        assertEquals(PhaseConfigResolver.SOURCE_WORKSTREAM_PHASE, review.runner().source());

        PhaseConfigResolver.PhaseResolution primary = r.resolutionOf(Phase.PRIMARY);
        assertEquals(AgentRunnerRegistry.CLAUDE, primary.runner().value());
        assertEquals(PhaseConfigResolver.SOURCE_CONTROLLER_DEFAULT, primary.runner().source());
    }

    /**
     * A per-job fallback that applies (because the workstream leaves the phase
     * unset) is attributed to {@code fallback default}.
     */
    @Test(timeout = 5000)
    public void resolutionAttributesFallbackWhenWorkstreamUnset() {
        PhaseConfigBundle fallback = bundle(new PhaseConfig(TEST_RUNNER, null, null), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY, fallback, PhaseConfigBundle.EMPTY);
        assertNull(r.error());

        PhaseConfigResolver.PhaseResolution primary = r.resolutionOf(Phase.PRIMARY);
        assertEquals(TEST_RUNNER, primary.runner().value());
        assertEquals(PhaseConfigResolver.SOURCE_FALLBACK_DEFAULT, primary.runner().source());
    }

    /**
     * Provider provenance accounts for runner-aware clearing: when a job
     * per-phase runner clears the job default's provider (because the runner
     * differs), the surviving provider is credited to the workspace default that
     * actually supplied it — not to the equal-valued job default whose provider
     * was discarded. Reproduces the attribution bug that value-matching a
     * flattened layer list after the merge could not see.
     */
    @Test(timeout = 5000)
    public void providerSourceAccountsForRunnerAwareClearing() {
        PhaseConfigBundle job = bundle(
                new PhaseConfig(null, null, null, "openrouter"),
                Phase.REVIEW,
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null));
        PhaseConfigBundle workspace = bundle(
                new PhaseConfig(AgentRunnerRegistry.OPENCODE, null, null, "openrouter"), null, null);
        PhaseConfigResolver r = PhaseConfigResolver.resolve(job, PhaseConfigBundle.EMPTY, workspace);
        assertNull(r.error());

        PhaseConfigResolver.PhaseResolution review = r.resolutionOf(Phase.REVIEW);
        assertEquals("openrouter", review.provider().value());
        assertEquals(r.forPhase(Phase.REVIEW).provider(), review.provider().value());
        assertEquals(PhaseConfigResolver.SOURCE_WORKSPACE_DEFAULT, review.provider().source());
        assertEquals(AgentRunnerRegistry.OPENCODE, review.runner().value());
        assertEquals(PhaseConfigResolver.SOURCE_JOB_PHASE, review.runner().source());
    }

    /**
     * The {@code phaseConfigResolution} JSON reports every phase with each
     * field's value and source.
     */
    @Test(timeout = 5000)
    public void resolutionJsonReportsValueAndSource() {
        PhaseConfigResolver r = resolveJob(
                bundle(new PhaseConfig(null, "claude-opus-5-5", null), null, null));
        assertNull(r.error());
        StringBuilder json = new StringBuilder("{\"ok\":true");
        r.appendPhaseConfigResolutionJson(json);
        json.append("}");
        String result = json.toString();
        assertTrue(result, result.contains("\"phaseConfigResolution\""));
        for (Phase phase : Phase.values()) {
            assertTrue(result, result.contains("\"" + phase.wireName() + "\""));
        }
        assertTrue(result, result.contains("\"model\":{\"value\":\"claude-opus-5-5\","
                + "\"source\":\"job default\"}"));
        assertTrue(result, result.contains("\"source\":\"controller default\""));
        assertTrue(result, result.contains("\"effort\":{\"value\":null,\"source\":\"none\"}"));
    }

    // --- Capability warnings -------------------------------------------------

    /**
     * An effort set for a runner that cannot honour it produces a capability
     * warning naming the phase, runner and effort; a runner that does support
     * effort levels (claude) produces none.
     */
    @Test(timeout = 5000)
    public void effortForRunnerWithoutEffortSupportWarns() {
        PhaseConfigResolver r = resolveJob(
                bundle(new PhaseConfig(NO_EFFORT_RUNNER, null, "high"), null, null));
        assertNull(r.error());
        List<String> warnings = r.capabilityWarnings();
        assertEquals("one warning per phase: " + warnings, Phase.values().length, warnings.size());
        assertTrue(warnings.toString(), warnings.get(0).contains(NO_EFFORT_RUNNER));
        assertTrue(warnings.toString(), warnings.get(0).contains("high"));
        assertTrue(warnings.toString(), warnings.get(0).contains(Phase.values()[0].wireName()));

        PhaseConfigResolver claude = resolveJob(
                bundle(new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, "high"), null, null));
        assertTrue("claude honours effort, so no warning", claude.capabilityWarnings().isEmpty());
    }

    /** A runner that cannot be instantiated produces no warning, and neither does an unset effort. */
    @Test(timeout = 5000)
    public void noWarningWithoutEffortOrWithoutRunnerInstance() {
        assertTrue(resolveJob(bundle(new PhaseConfig(NO_EFFORT_RUNNER, null, null), null, null))
                .capabilityWarnings().isEmpty());
        assertTrue(resolveJob(bundle(new PhaseConfig(TEST_RUNNER, null, "high"), null, null))
                .capabilityWarnings().isEmpty());
    }

    // --- Strict request-shape checking --------------------------------------

    /** An absent, blank or empty-object body carries no configuration and is valid. */
    @Test(timeout = 5000)
    public void emptyBodiesHaveValidShape() {
        assertNull(PhaseConfigResolver.requestShapeError(null));
        assertNull(PhaseConfigResolver.requestShapeError(""));
        assertNull(PhaseConfigResolver.requestShapeError("   "));
        assertNull(PhaseConfigResolver.requestShapeError("{}"));
    }

    /** Every well-formed configuration field, including explicit nulls, is accepted. */
    @Test(timeout = 5000)
    public void wellFormedBodyHasValidShape() {
        String body = "{\"defaultPhaseConfig\":{\"runner\":\"claude\",\"model\":null},"
                + "\"phaseConfigs\":{\"review\":{\"effort\":\"high\",\"provider\":\"anthropic\"}},"
                + "\"fallbackPhaseConfig\":null,"
                + "\"fallbackPhaseConfigs\":{\"primary\":{\"runner\":\"opencode\"}},"
                + "\"unrelated\":42}";
        assertNull(PhaseConfigResolver.requestShapeError(body));
    }

    /** A body that is not JSON, or is JSON but not an object, is rejected. */
    @Test(timeout = 5000)
    public void malformedOrNonObjectBodyIsRejected() {
        String notJson = PhaseConfigResolver.requestShapeError("{\"defaultPhaseConfig\":");
        assertNotNull(notJson);
        assertTrue(notJson, notJson.contains("not valid JSON"));
        String array = PhaseConfigResolver.requestShapeError("[1,2]");
        assertEquals("Request body must be a JSON object", array);
    }

    /** A non-string field value — the case the lenient parser silently drops — is rejected. */
    @Test(timeout = 5000)
    public void nonStringFieldValueIsRejected() {
        String err = PhaseConfigResolver.requestShapeError(
                "{\"phaseConfigs\":{\"review\":{\"model\":42}}}");
        assertEquals("'phaseConfigs.review.model' must be a string", err);
        assertNull("the lenient parser drops the same value",
                PhaseConfigResolver.bundleFromRequest(
                        "{\"phaseConfigs\":{\"review\":{\"model\":42}}}").forPhase(Phase.REVIEW).model());
    }

    /** An unknown phase name in either per-phase map is rejected, naming the map and the phase. */
    @Test(timeout = 5000)
    public void unknownPhaseIsRejected() {
        assertEquals("'phaseConfigs' has unknown phase 'not-a-phase'",
                PhaseConfigResolver.requestShapeError(
                        "{\"phaseConfigs\":{\"not-a-phase\":{\"runner\":\"claude\"}}}"));
        assertEquals("'fallbackPhaseConfigs' has unknown phase 'nope'",
                PhaseConfigResolver.requestShapeError(
                        "{\"fallbackPhaseConfigs\":{\"nope\":{}}}"));
    }

    /** A misspelled key inside a config object is rejected rather than ignored. */
    @Test(timeout = 5000)
    public void unknownConfigKeyIsRejected() {
        String err = PhaseConfigResolver.requestShapeError(
                "{\"defaultPhaseConfig\":{\"modle\":\"claude-opus-5-5\"}}");
        assertNotNull(err);
        assertTrue(err, err.startsWith("'defaultPhaseConfig' has unknown key 'modle'"));
    }

    /** A config field or per-phase map that is not an object is rejected. */
    @Test(timeout = 5000)
    public void nonObjectConfigFieldIsRejected() {
        assertEquals("'fallbackPhaseConfig' must be a JSON object",
                PhaseConfigResolver.requestShapeError("{\"fallbackPhaseConfig\":\"claude\"}"));
        assertEquals("'phaseConfigs' must be a JSON object keyed by phase",
                PhaseConfigResolver.requestShapeError("{\"phaseConfigs\":[]}"));
        assertEquals("'phaseConfigs.review' must be a JSON object",
                PhaseConfigResolver.requestShapeError("{\"phaseConfigs\":{\"review\":\"claude\"}}"));
    }
}
