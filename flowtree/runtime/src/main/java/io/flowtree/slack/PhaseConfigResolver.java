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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.ClaudeCodeRunner;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves per-phase agent configuration ({@link PhaseConfig}) by walking
 * the unified seven-level precedence ladder:
 *
 * <ol>
 *   <li>Per-job per-phase override</li>
 *   <li>Per-job default ({@code defaultPhaseConfig})</li>
 *   <li>Workstream per-phase override</li>
 *   <li>Workstream default</li>
 *   <li>Workspace per-phase override</li>
 *   <li>Workspace default</li>
 *   <li>Controller default ({@code runner=claude}, {@code model=null}, {@code effort=null})</li>
 * </ol>
 *
 * <p>Each field of the resulting {@link PhaseConfig} is resolved
 * independently: a workstream that sets only its default {@code runner}
 * does not prevent {@code model} or {@code effort} from falling through
 * to workspace per-phase entries.</p>
 *
 * <p>This is a behavioural change relative to the legacy
 * {@code SubmissionRunnerResolver}, which let a workstream {@code defaultRunner}
 * shadow the entire workspace layer for runner resolution. The shadowing
 * rule was a workaround for the runner-only model; under the unified
 * model, "each field falls through independently" is the natural
 * generalisation and the only one that produces sensible results when
 * different containers contribute different fields.</p>
 */
public final class PhaseConfigResolver {

    /** The controller default: {@code (runner=claude, model=null, effort=null)}. */
    public static final PhaseConfig CONTROLLER_DEFAULT =
            new PhaseConfig(AgentRunnerRegistry.CLAUDE, null, null);

    /** Error message returned when validation fails; {@code null} on success. */
    private final String error;

    /** Resolved bundle after merging the three input bundles; {@code null} on error. */
    private final PhaseConfigBundle resolved;

    /** Per-job (request body) bundle; preserved so {@link #forPhase(Phase)} can replay overlay. */
    private final PhaseConfigBundle requestBundle;
    /** Workstream-level bundle; preserved for {@link #forPhase(Phase)}. */
    private final PhaseConfigBundle workstreamBundle;
    /** Workspace-level bundle; preserved for {@link #forPhase(Phase)}. */
    private final PhaseConfigBundle workspaceBundle;

    /** Private constructor; use {@link #resolve(PhaseConfigBundle, PhaseConfigBundle, PhaseConfigBundle)}. */
    private PhaseConfigResolver(String error, PhaseConfigBundle resolved,
                                PhaseConfigBundle requestBundle,
                                PhaseConfigBundle workstreamBundle,
                                PhaseConfigBundle workspaceBundle) {
        this.error = error;
        this.resolved = resolved;
        this.requestBundle = requestBundle != null ? requestBundle : PhaseConfigBundle.EMPTY;
        this.workstreamBundle = workstreamBundle != null ? workstreamBundle : PhaseConfigBundle.EMPTY;
        this.workspaceBundle = workspaceBundle != null ? workspaceBundle : PhaseConfigBundle.EMPTY;
    }

    /** Returns a 400-able validation error, or {@code null} on success. */
    public String error() {
        return error;
    }

    /**
     * Returns the fully-resolved bundle (job-level overrides plus job-level
     * defaults, with workstream / workspace / controller defaults baked into
     * the {@link PhaseConfigBundle#defaultPhaseConfig()}). {@code null} when
     * {@link #error()} is non-null.
     */
    public PhaseConfigBundle resolvedBundle() {
        return resolved;
    }

    /**
     * Returns the raw per-job bundle as submitted in the request body, before
     * merging with workstream / workspace / controller defaults. This exposes
     * exactly what the caller supplied, independent of the resolved result in
     * {@link #resolvedBundle()}.
     *
     * <p>Never {@code null}; returns {@link PhaseConfigBundle#EMPTY} when the
     * request specified no phase configuration.</p>
     */
    public PhaseConfigBundle requestBundle() {
        return requestBundle;
    }

    /**
     * Returns the fully-resolved {@link PhaseConfig} for {@code phase}: the
     * job-level per-phase override (if any) overlaid on the job-level
     * default, then layered through workstream and workspace levels, then
     * onto the controller default.
     *
     * @param phase the phase to look up; {@code null} returns the resolved
     *              default
     * @return the resolved per-phase config, never {@code null}
     */
    public PhaseConfig forPhase(Phase phase) {
        return requestBundle.forPhase(phase)
                .overlayOnClearingInheritedProvider(workstreamBundle.forPhase(phase))
                .overlayOnClearingInheritedProvider(workspaceBundle.forPhase(phase))
                .overlayOn(CONTROLLER_DEFAULT);
    }

    /**
     * Resolves the per-phase configuration from the three precedence
     * levels. Validates every non-null runner name and every per-phase
     * effort value; returns a resolver carrying an {@link #error()}
     * message on validation failure.
     *
     * @param requestBundle    per-job bundle (request body); may be {@code null}
     * @param workstreamBundle workstream-level bundle; may be {@code null}
     * @param workspaceBundle  workspace-level bundle; may be {@code null}
     * @return a fresh resolver
     */
    public static PhaseConfigResolver resolve(PhaseConfigBundle requestBundle,
                                              PhaseConfigBundle workstreamBundle,
                                              PhaseConfigBundle workspaceBundle) {
        PhaseConfigBundle req = requestBundle != null ? requestBundle : PhaseConfigBundle.EMPTY;
        PhaseConfigBundle ws = workstreamBundle != null ? workstreamBundle : PhaseConfigBundle.EMPTY;
        PhaseConfigBundle wsp = workspaceBundle != null ? workspaceBundle : PhaseConfigBundle.EMPTY;

        // Validate runner names referenced anywhere.
        String runnerErr = validateRunners(req);
        if (runnerErr != null) return fail(runnerErr);
        runnerErr = validateRunners(ws);
        if (runnerErr != null) return fail(runnerErr);
        runnerErr = validateRunners(wsp);
        if (runnerErr != null) return fail(runnerErr);

        // Validate effort values referenced anywhere.
        String effortErr = validateEffort(req);
        if (effortErr != null) return fail(effortErr);
        effortErr = validateEffort(ws);
        if (effortErr != null) return fail(effortErr);
        effortErr = validateEffort(wsp);
        if (effortErr != null) return fail(effortErr);

        // Build the "applied to factory" bundle: each phase fully resolved
        // through the ladder. The default is the resolved default at the
        // request level overlaid on workstream/workspace/controller.
        // Use runner-aware overlay so a provider configured for one runner
        // at a lower level does not leak into a phase that resolves to a
        // different runner.
        PhaseConfig resolvedDefault = req.defaultPhaseConfig()
                .overlayOnClearingInheritedProvider(ws.defaultPhaseConfig())
                .overlayOnClearingInheritedProvider(wsp.defaultPhaseConfig())
                .overlayOn(CONTROLLER_DEFAULT);

        // Collect every phase that has any override at any level.
        Map<Phase, PhaseConfig> phases = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) {
            PhaseConfig perPhase = req.forPhase(phase)
                    .overlayOnClearingInheritedProvider(ws.forPhase(phase))
                    .overlayOnClearingInheritedProvider(wsp.forPhase(phase))
                    .overlayOn(CONTROLLER_DEFAULT);
            // Only record an entry when the resolved per-phase config
            // differs from the resolved default; this keeps applyTo()
            // compact for the common case.
            if (!perPhase.equals(resolvedDefault)) {
                phases.put(phase, perPhase);
            }
        }

        // Validate model against resolved runner for every phase
        // (and the default itself).
        String modelErr = validateModelForRunner(resolvedDefault, "default");
        if (modelErr != null) return fail(modelErr);
        for (Map.Entry<Phase, PhaseConfig> e : phases.entrySet()) {
            modelErr = validateModelForRunner(e.getValue(), e.getKey().wireName());
            if (modelErr != null) return fail(modelErr);
        }

        // Validate provider-runner compatibility.
        String providerErr = validateProviderForRunner(resolvedDefault, "default");
        if (providerErr != null) return fail(providerErr);
        for (Map.Entry<Phase, PhaseConfig> e : phases.entrySet()) {
            providerErr = validateProviderForRunner(e.getValue(), e.getKey().wireName());
            if (providerErr != null) return fail(providerErr);
        }

        PhaseConfigBundle resolved = new PhaseConfigBundle(resolvedDefault, phases);
        return new PhaseConfigResolver(null, resolved, req, ws, wsp);
    }

    /**
     * Convenience overload for callers that have already parsed legacy
     * runner-only fields. Builds bundles via
     * {@link PhaseConfigBundle#fromLegacyRunners(String, Map)} and delegates.
     *
     * @param requestRunners    parsed {@code "runners"} object (phase wire
     *                          name → runner identifier; the
     *                          {@code "default"} key sets the default runner)
     * @param workstreamDefault workstream default runner, or {@code null}
     * @param workstreamRunners workstream per-phase runner map, or {@code null}
     * @param workspaceDefault  workspace default runner, or {@code null}
     * @param workspaceRunners  workspace per-phase runner map, or {@code null}
     * @return a fresh resolver carrying only the runner field
     */
    public static PhaseConfigResolver resolveLegacyRunners(
            Map<String, String> requestRunners,
            String workstreamDefault, Map<String, String> workstreamRunners,
            String workspaceDefault, Map<String, String> workspaceRunners) {
        PhaseConfigBundle req = bundleFromLegacyRequest(requestRunners);
        PhaseConfigBundle ws = PhaseConfigBundle.fromLegacyRunners(
                workstreamDefault, workstreamRunners);
        PhaseConfigBundle wsp = PhaseConfigBundle.fromLegacyRunners(
                workspaceDefault, workspaceRunners);
        return resolve(req, ws, wsp);
    }

    /**
     * Builds a {@link PhaseConfigBundle} from a request body's
     * {@code defaultPhaseConfig} and {@code phaseConfigs} JSON fields, merged
     * with any legacy {@code runners} / {@code "default"} entries supplied
     * via {@code legacyRunners} and the legacy job-level {@code model} /
     * {@code effort} strings. The new shape wins field-by-field when both
     * are supplied; the legacy form fills in any field the new form leaves
     * null.
     *
     * @param body          the full request body JSON; may be {@code null}
     * @param legacyRunners parsed legacy {@code runners} object (phase wire
     *                      name → runner identifier, plus optional
     *                      {@code "default"}); may be {@code null}
     * @param legacyModel   legacy job-level {@code model}; may be {@code null}
     * @param legacyEffort  legacy job-level {@code effort}; may be {@code null}
     * @return a fresh bundle merging all forms of input
     */
    public static PhaseConfigBundle bundleFromRequest(String body,
                                                     Map<String, String> legacyRunners,
                                                     String legacyModel,
                                                     String legacyEffort) {
        PhaseConfigBundle legacy = bundleFromLegacyRequest(legacyRunners);
        // Layer legacy job-level model/effort onto the legacy-derived default.
        if ((legacyModel != null && !legacyModel.isEmpty())
                || (legacyEffort != null && !legacyEffort.isEmpty())) {
            PhaseConfig def = legacy.defaultPhaseConfig();
            String mergedModel = (legacyModel != null && !legacyModel.isEmpty()) ? legacyModel : def.model();
            String mergedEffort = (legacyEffort != null && !legacyEffort.isEmpty()) ? legacyEffort : def.effort();
            legacy = legacy.withDefault(new PhaseConfig(def.runner(), mergedModel, mergedEffort));
        }
        JsonNode root = parseBodyRoot(body);
        if (root == null) return legacy;
        PhaseConfigBundle result = legacy;
        JsonNode defaultNode = root.get("defaultPhaseConfig");
        if (defaultNode != null && defaultNode.isObject()) {
            PhaseConfig parsed = phaseConfigFromNode(defaultNode);
            if (!parsed.isEmpty()) {
                // New wins field-by-field; overlay parsed on legacy default.
                PhaseConfig merged = parsed.overlayOn(result.defaultPhaseConfig());
                result = result.withDefault(merged);
            }
        }
        JsonNode phaseConfigsNode = root.get("phaseConfigs");
        if (phaseConfigsNode != null && phaseConfigsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = phaseConfigsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (entry.getValue() == null || !entry.getValue().isObject()) continue;
                Phase phase;
                try {
                    phase = Phase.fromWireName(entry.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                PhaseConfig parsed = phaseConfigFromNode(entry.getValue());
                if (parsed.isEmpty()) continue;
                PhaseConfig existing = result.phaseConfigs().get(phase);
                PhaseConfig merged = existing == null ? parsed : parsed.overlayOn(existing);
                result = result.withPhase(phase, merged);
            }
        }
        return result;
    }

    /** Parses a {@link PhaseConfig} from a JSON object node; ignores unknown fields. */
    private static PhaseConfig phaseConfigFromNode(JsonNode node) {
        String runner = textOrNull(node.get("runner"));
        String model = textOrNull(node.get("model"));
        String effort = textOrNull(node.get("effort"));
        String provider = textOrNull(node.get("provider"));
        return new PhaseConfig(runner, model, effort, provider);
    }

    /** Returns the textual value of {@code node}, or {@code null} when missing/empty. */
    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) return null;
        String text = node.asText();
        return (text == null || text.isEmpty()) ? null : text;
    }

    /** Builds a bundle from a legacy {@code runners} request object (uses {@code "default"} for the default). */
    static PhaseConfigBundle bundleFromLegacyRequest(Map<String, String> requestRunners) {
        if (requestRunners == null || requestRunners.isEmpty()) {
            return PhaseConfigBundle.EMPTY;
        }
        String defaultRunner = requestRunners.get("default");
        Map<String, String> perPhase = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : requestRunners.entrySet()) {
            if ("default".equals(e.getKey())) continue;
            perPhase.put(e.getKey(), e.getValue());
        }
        return PhaseConfigBundle.fromLegacyRunners(defaultRunner, perPhase);
    }

    /** Validates every runner referenced in {@code bundle}; returns a 400-able message on failure. */
    private static String validateRunners(PhaseConfigBundle bundle) {
        String err = validateRunnerName(bundle.defaultPhaseConfig().runner());
        if (err != null) return err;
        for (PhaseConfig config : bundle.phaseConfigs().values()) {
            err = validateRunnerName(config.runner());
            if (err != null) return err;
        }
        return null;
    }

    /** Validates a single runner name; {@code null} on success or when input is null. */
    private static String validateRunnerName(String runner) {
        if (runner == null || runner.isEmpty()) return null;
        if (!AgentRunnerRegistry.available().contains(runner)) {
            return "Unknown runner: '" + runner + "'. Available: "
                    + AgentRunnerRegistry.available();
        }
        return null;
    }

    /** Validates every effort referenced in {@code bundle}. */
    private static String validateEffort(PhaseConfigBundle bundle) {
        String err = validateEffortValue(bundle.defaultPhaseConfig().effort());
        if (err != null) return err;
        for (PhaseConfig config : bundle.phaseConfigs().values()) {
            err = validateEffortValue(config.effort());
            if (err != null) return err;
        }
        return null;
    }

    /** Validates a single effort value; null/empty are accepted. */
    private static String validateEffortValue(String effort) {
        if (effort == null || effort.isEmpty()) return null;
        if (!ClaudeCodeRunner.VALID_EFFORT_LEVELS.contains(effort)) {
            return "Invalid effort level '" + effort + "'. Must be one of "
                    + ClaudeCodeRunner.VALID_EFFORT_LEVELS;
        }
        return null;
    }

    /**
     * Validates the resolved model for {@code config} against the resolved
     * runner's supported set, if any. Empty {@code supportedModels} on the
     * runner means "unconstrained" (e.g. opencode against arbitrary
     * OpenAI-compatible endpoints) and the check is skipped.
     */
    private static String validateModelForRunner(PhaseConfig config, String phaseLabel) {
        String model = config.model();
        if (model == null || model.isEmpty()) return null;
        String runner = config.runner();
        if (runner == null || runner.isEmpty()) return null;
        if (!AgentRunnerRegistry.available().contains(runner)) {
            // Already caught by validateRunners; defensive guard.
            return null;
        }
        Set<String> supported;
        try {
            supported = AgentRunnerRegistry.get(runner).capabilities().supportedModels();
        } catch (Exception e) {
            // Runners that fail to instantiate (e.g. opencode without a
            // configured binary) should not block validation; treat as
            // unconstrained.
            return null;
        }
        if (supported == null || supported.isEmpty()) return null;
        if (!supported.contains(model)) {
            return "Invalid model '" + model + "' for runner '" + runner
                    + "' (phase " + phaseLabel + "). Must be one of " + supported;
        }
        return null;
    }

    /**
     * Validates provider compatibility with the resolved runner for {@code config}.
     * Currently only the {@code "claude"} runner enforces provider restrictions:
     * claude only supports the {@code "anthropic"} provider; using any other
     * provider with claude is not supported and fails with a clear error.
     *
     * <p>The {@code "opencode"} runner is not validated here. Provider validation
     * for opencode happens at run time inside the runner itself, where it checks the
     * provider against its known set and fails with an explicit error if unrecognised.</p>
     */
    private static String validateProviderForRunner(PhaseConfig config, String phaseLabel) {
        String provider = config.provider();
        if (provider == null || provider.isEmpty()) return null;
        String runner = config.runner();
        if (runner == null || runner.isEmpty()) return null;
        if (AgentRunnerRegistry.CLAUDE.equals(runner)
                && !"anthropic".equals(provider)) {
            return "runner='claude' only supports provider='anthropic'"
                    + " (phase " + phaseLabel + "); got provider='" + provider + "'."
                    + " Use runner='opencode' for other providers.";
        }
        return null;
    }

    /** Shared Jackson mapper for request body parsing; thread-safe after construction. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The legacy job/workstream/workspace-level configuration fields that are
     * no longer accepted on live HTTP requests. Each is replaced by per-phase
     * configuration via {@code defaultPhaseConfig} / {@code phaseConfigs}.
     */
    private static final String[] REMOVED_REQUEST_FIELDS =
            {"model", "effort", "runners", "defaultRunner"};

    /**
     * Returns a 400-able error message when {@code body} carries any removed
     * legacy configuration field ({@code model}, {@code effort},
     * {@code runners}, {@code defaultRunner}), or {@code null} when none are
     * present. Callers must use {@code defaultPhaseConfig} (a single
     * {@link PhaseConfig} applied as the default across phases) or
     * {@code phaseConfigs} (a per-phase map) instead.
     *
     * <p>This guards the live HTTP request path only — it is a deliberate
     * clean break so stale callers fail loudly rather than being silently
     * translated. YAML on disk may still carry the legacy fields; that path
     * is auto-migrated on load and is not affected by this check (see
     * {@link WorkstreamConfig}).</p>
     *
     * @param body the raw request body JSON; {@code null} or empty returns
     *             {@code null}
     * @return an error message naming the first removed field present, or
     *         {@code null} when the body uses only the supported shape
     */
    public static String rejectLegacyRequestFields(String body) {
        JsonNode root = parseBodyRoot(body);
        if (root == null) return null;
        for (String field : REMOVED_REQUEST_FIELDS) {
            if (root.has(field)) {
                return "The `" + field + "` field is no longer supported. Use "
                        + "`defaultPhaseConfig` to set a default across all phases, "
                        + "or `phaseConfigs` to set per-phase values.";
            }
        }
        return null;
    }

    /** Wraps an error message in a failed resolver. */
    private static PhaseConfigResolver fail(String message) {
        return new PhaseConfigResolver(message, null, null, null, null);
    }

    /**
     * Applies the resolved bundle to {@code factory}. The factory's
     * {@link CodingAgentJobFactory#setPhaseConfigBundle(PhaseConfigBundle)}
     * method receives the resolved bundle so {@code nextJob()} can propagate
     * it to each created {@link io.flowtree.jobs.CodingAgentJob}.
     *
     * @param factory the factory to mutate; must not be {@code null}
     */
    public void applyTo(CodingAgentJobFactory factory) {
        if (resolved == null) return;
        factory.setPhaseConfigBundle(resolved);
    }

    /**
     * Applies the new-shape {@code defaultPhaseConfig} / {@code phaseConfigs}
     * JSON fields from {@code body} to {@code workstream}, overlaying them
     * on the workstream's current bundle so that legacy {@code defaultRunner}
     * / {@code runners} / {@code model} / {@code effort} setters applied
     * earlier in the same request are preserved field-by-field when the new
     * shape does not set them.
     *
     * <p>Validation is intentionally limited to syntax-level checks (runner
     * names against {@link AgentRunnerRegistry#available()}, effort values
     * against {@link ClaudeCodeRunner#VALID_EFFORT_LEVELS}) so that a
     * workstream that intentionally sets only {@code model} or {@code effort}
     * — expecting {@code runner} to fall through from a higher precedence
     * level — is not rejected here. Model-vs-runner compatibility is
     * deferred to {@link #resolve(PhaseConfigBundle, PhaseConfigBundle,
     * PhaseConfigBundle)} at submission time, when the full request /
     * workstream / workspace ladder is available.</p>
     *
     * @param workstream the workstream to mutate; must not be {@code null}
     * @param body       the raw request body JSON; may be {@code null} or empty
     * @return {@code null} on success; an error message suitable for a 400
     *         response when the body's bundle fails syntax validation
     */
    public static String applyToWorkstream(Workstream workstream, String body) {
        if (workstream == null) return null;
        JsonNode root = parseBodyRoot(body);
        if (root == null) return null;
        if (!root.has("defaultPhaseConfig") && !root.has("phaseConfigs")) return null;
        PhaseConfigBundle existing = workstream.getPhaseConfigBundle();
        PhaseConfigBundle updated = applyClearingAndOverlay(existing, root);
        String syntaxErr = validateSyntax(updated);
        if (syntaxErr != null) return syntaxErr;
        workstream.setPhaseConfigBundle(updated);
        return null;
    }

    /**
     * Applies the new-shape {@code defaultPhaseConfig} / {@code phaseConfigs}
     * JSON fields from {@code body} to {@code entry}, overlaying them on the
     * workspace entry's existing fields. Mirrors
     * {@link #applyToWorkstream(Workstream, String)} for the workspace-level
     * container — including the syntax-only validation rule that lets a
     * workspace set {@code model}-only overrides which pair with a
     * {@code runner} inherited from a workstream or job.
     *
     * @param entry the workspace entry to mutate; must not be {@code null}
     * @param body  the raw request body JSON; may be {@code null} or empty
     * @return {@code null} on success; an error message on syntax-validation failure
     */
    public static String applyToWorkspace(WorkstreamConfig.WorkspaceEntry entry, String body) {
        if (entry == null) return null;
        JsonNode root = parseBodyRoot(body);
        if (root == null) return null;
        if (!root.has("defaultPhaseConfig") && !root.has("phaseConfigs")) return null;
        PhaseConfigBundle existing = entry.toPhaseConfigBundle();
        PhaseConfigBundle updated = applyClearingAndOverlay(existing, root);
        String syntaxErr = validateSyntax(updated);
        if (syntaxErr != null) return syntaxErr;
        // Write the updated result back as the new-shape fields.
        entry.setDefaultPhaseConfig(updated.defaultPhaseConfig().isEmpty()
                ? null : updated.defaultPhaseConfig());
        Map<String, PhaseConfig> phaseMap = new LinkedHashMap<>();
        for (Map.Entry<Phase, PhaseConfig> e : updated.phaseConfigs().entrySet()) {
            phaseMap.put(e.getKey().wireName(), e.getValue());
        }
        entry.setPhaseConfigs(phaseMap);
        return null;
    }

    /**
     * Syntax-only validation of a single bundle for the workspace and
     * workstream {@code applyTo*} helpers. Validates runner names and
     * effort values without resolving missing runners to the controller
     * default — the way {@link #resolve(PhaseConfigBundle, PhaseConfigBundle,
     * PhaseConfigBundle)} does — so a level that intentionally sets only
     * {@code model} or {@code effort} (expecting {@code runner} to fall
     * through the ladder) is not rejected as "invalid model for runner
     * 'claude'".
     */
    private static String validateSyntax(PhaseConfigBundle bundle) {
        if (bundle == null || bundle.isEmpty()) return null;
        String err = validateRunners(bundle);
        if (err != null) return err;
        err = validateEffort(bundle);
        if (err != null) return err;
        return null;
    }

    /**
     * Appends the {@code defaultPhaseConfig} and {@code phaseConfigs} JSON
     * fragments to {@code json} for the non-empty parts of {@code bundle}.
     * Each emitted object contains only the non-null fields of the
     * underlying {@link PhaseConfig}. Both fragments lead with a comma so
     * they can be appended to an existing JSON object that already has at
     * least one prior field.
     *
     * @param json   the target string builder; must not be {@code null}
     * @param bundle the bundle to serialise; {@code null} or empty appends
     *               nothing
     */
    // TODO(review): leading-comma contract is fragile for future callers — consider a hasPriorFields flag or Jackson serialisation.
    public static void appendBundleJson(StringBuilder json, PhaseConfigBundle bundle) {
        appendBundleJson(json, bundle, "defaultPhaseConfig", "phaseConfigs");
    }

    /**
     * Appends the {@code defaultPhaseConfig} and {@code phaseConfigs} JSON
     * fragments to {@code json} using the provided field name prefixes.
     * Both fragments lead with a comma so they can be appended to an
     * existing JSON object that already has at least one prior field.
     *
     * <p>This overload lets callers emit a bundle under non-default field names
     * (e.g. when a single JSON object must carry more than one bundle under
     * distinct keys). Most callers should use the single-argument
     * {@link #appendBundleJson(StringBuilder, PhaseConfigBundle)}, which uses
     * the canonical {@code "defaultPhaseConfig"} / {@code "phaseConfigs"}.</p>
     *
     * @param json              the target string builder; must not be {@code null}
     * @param bundle            the bundle to serialise; {@code null} or empty appends
     *                          nothing
     * @param defaultFieldName  JSON field name for the default config; may be
     *                          {@code null} to omit the default field
     * @param phasesFieldName   JSON field name for the per-phase map; may be
     *                          {@code null} to omit the phases field
     */
    public static void appendBundleJson(StringBuilder json, PhaseConfigBundle bundle,
                                        String defaultFieldName, String phasesFieldName) {
        if (bundle == null || bundle.isEmpty()) return;
        PhaseConfig def = bundle.defaultPhaseConfig();
        if (def != null && !def.isEmpty() && defaultFieldName != null) {
            json.append(",\"").append(defaultFieldName).append("\":");
            appendPhaseConfigJson(json, def);
        }
        Map<Phase, PhaseConfig> phases = bundle.phaseConfigs();
        if (phases == null || phases.isEmpty() || phasesFieldName == null) return;
        StringBuilder inner = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Phase, PhaseConfig> e : phases.entrySet()) {
            PhaseConfig pc = e.getValue();
            if (pc == null || pc.isEmpty()) continue;
            if (!first) inner.append(",");
            first = false;
            inner.append("\"")
                    .append(JsonFieldExtractor.escapeJson(e.getKey().wireName()))
                    .append("\":");
            appendPhaseConfigJson(inner, pc);
        }
        if (inner.length() > 0) {
            json.append(",\"").append(phasesFieldName).append("\":{").append(inner).append("}");
        }
    }

    /** Appends a single {@link PhaseConfig} as a JSON object, emitting only non-null fields. */
    private static void appendPhaseConfigJson(StringBuilder json, PhaseConfig pc) {
        json.append("{");
        boolean first = true;
        if (pc.runner() != null) {
            json.append("\"runner\":\"")
                    .append(JsonFieldExtractor.escapeJson(pc.runner()))
                    .append("\"");
            first = false;
        }
        if (pc.model() != null) {
            if (!first) json.append(",");
            json.append("\"model\":\"")
                    .append(JsonFieldExtractor.escapeJson(pc.model()))
                    .append("\"");
            first = false;
        }
        if (pc.effort() != null) {
            if (!first) json.append(",");
            json.append("\"effort\":\"")
                    .append(JsonFieldExtractor.escapeJson(pc.effort()))
                    .append("\"");
            first = false;
        }
        if (pc.provider() != null) {
            if (!first) json.append(",");
            json.append("\"provider\":\"")
                    .append(JsonFieldExtractor.escapeJson(pc.provider()))
                    .append("\"");
        }
        json.append("}");
    }

    /**
     * Applies clearing and overlay operations from the parsed {@code root}
     * JSON body onto {@code existing}. Implements the clearing semantics for
     * workstream/workspace stored configs:
     *
     * <ul>
     *   <li>{@code "defaultPhaseConfig": null} or {@code {}} → clears the
     *       stored default config.</li>
     *   <li>{@code "phaseConfigs": {}} → clears all per-phase overrides.</li>
     *   <li>{@code "phaseConfigs": {"review": null}} → clears just that
     *       phase's override.</li>
     *   <li>A non-null, non-empty object overlays field-by-field as usual.</li>
     * </ul>
     *
     * @param existing the current bundle; never {@code null}
     * @param root     the parsed request body JSON object
     * @return the updated bundle; never {@code null}
     */
    private static PhaseConfigBundle applyClearingAndOverlay(
            PhaseConfigBundle existing, JsonNode root) {
        PhaseConfig newDefault = existing.defaultPhaseConfig();
        Map<Phase, PhaseConfig> newPhases = new EnumMap<>(Phase.class);
        newPhases.putAll(existing.phaseConfigs());

        if (root.has("defaultPhaseConfig")) {
            JsonNode node = root.get("defaultPhaseConfig");
            if (isNullOrEmpty(node)) {
                newDefault = PhaseConfig.EMPTY;
            } else if (node.isObject()) {
                PhaseConfig parsed = phaseConfigFromNode(node);
                if (!parsed.isEmpty()) {
                    newDefault = parsed.overlayOn(newDefault);
                }
            }
        }

        if (root.has("phaseConfigs")) {
            JsonNode phaseNode = root.get("phaseConfigs");
            if (isNullOrEmpty(phaseNode)) {
                newPhases.clear();
            } else if (phaseNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = phaseNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    Phase phase;
                    try {
                        phase = Phase.fromWireName(e.getKey());
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    JsonNode val = e.getValue();
                    if (val == null || val.isNull()) {
                        newPhases.remove(phase);
                    } else if (val.isObject()) {
                        PhaseConfig parsed = phaseConfigFromNode(val);
                        if (!parsed.isEmpty()) {
                            PhaseConfig cur = newPhases.get(phase);
                            newPhases.put(phase, cur == null ? parsed : parsed.overlayOn(cur));
                        }
                    }
                }
            }
        }

        return new PhaseConfigBundle(newDefault, newPhases);
    }

    /**
     * Parses {@code body} as a JSON object via {@link #MAPPER} and returns the
     * root node, or {@code null} when the body is {@code null}, empty, malformed,
     * or not an object. Used by {@link #rejectLegacyRequestFields},
     * {@link #applyToWorkstream}, and {@link #applyToWorkspace} to avoid
     * repeating the same try/catch boilerplate in each method.
     */
    private static JsonNode parseBodyRoot(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            return (root != null && root.isObject()) ? root : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Returns {@code true} when {@code node} is null, JSON null, or an empty object. */
    private static boolean isNullOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) return true;
        return node.isObject() && !node.fields().hasNext();
    }

    /**
     * Overlays {@code top} on {@code base} field-by-field: {@code top} wins
     * for the default and for any per-phase entry that is present in both;
     * {@code base} entries not overridden by {@code top} are preserved.
     */
    private static PhaseConfigBundle mergeOverlay(PhaseConfigBundle top, PhaseConfigBundle base) {
        if (base == null || base.isEmpty()) return top;
        PhaseConfig mergedDefault = top.defaultPhaseConfig().overlayOn(base.defaultPhaseConfig());
        Map<Phase, PhaseConfig> mergedPhases = new EnumMap<>(Phase.class);
        mergedPhases.putAll(base.phaseConfigs());
        for (Map.Entry<Phase, PhaseConfig> e : top.phaseConfigs().entrySet()) {
            PhaseConfig existing = mergedPhases.get(e.getKey());
            PhaseConfig merged = existing == null ? e.getValue() : e.getValue().overlayOn(existing);
            mergedPhases.put(e.getKey(), merged);
        }
        return new PhaseConfigBundle(mergedDefault, mergedPhases);
    }
}
