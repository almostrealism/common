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
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.jobs.agent.AgentCapabilities;
import io.flowtree.jobs.agent.AgentRunner;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.ClaudeCodeRunner;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkspaceEntry;
import io.flowtree.workstream.WorkstreamConfig;

/**
 * Resolves per-phase agent configuration ({@link PhaseConfig}) by walking
 * the unified precedence ladder:
 *
 * <ol>
 *   <li>Per-job per-phase override</li>
 *   <li>Per-job default ({@code defaultPhaseConfig})</li>
 *   <li>Workstream per-phase override</li>
 *   <li>Workstream default</li>
 *   <li>Per-job fallback ({@code fallbackPhaseConfig} / {@code fallbackPhaseConfigs})
 *       — applied only to phases the workstream leaves unset</li>
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
 * <p>The fallback level is the one exception to field-by-field resolution:
 * it applies <em>as a whole entry</em>, and only to phases the workstream
 * leaves entirely unset. A phase "counts as set by the workstream" when its
 * workstream-level {@code phaseConfigs[phase]} has any field, or the
 * workstream carries a {@code defaultPhaseConfig} — in either case the
 * fallback is ignored for that phase and resolution proceeds as it did
 * before the fallback existed. This gate keeps a fallback {@code model} from
 * being mixed with a workstream {@code runner} into an invalid combination:
 * when the workstream sets the phase, the fallback contributes nothing. A
 * fallback entry that applies still overlays field-by-field onto the lower
 * (workspace / controller) levels, and the resulting runner / provider /
 * model combination is validated at submission exactly as any other.</p>
 *
 * <p>When no fallback is supplied (the common case), resolution is identical
 * to the request / workstream / workspace / controller ladder that preceded
 * it — a submission without a fallback resolves exactly as it did before.</p>
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
    /**
     * Per-job fallback bundle; applied only to phases the workstream leaves
     * unset (see {@link PhaseConfigBundle#setsPhase(Phase)}).
     * Preserved for {@link #forPhase(Phase)} and the fallback-resolution echo.
     */
    private final PhaseConfigBundle fallbackBundle;
    /** Workspace-level bundle; preserved for {@link #forPhase(Phase)}. */
    private final PhaseConfigBundle workspaceBundle;

    /** Private constructor; use {@link #resolve(PhaseConfigBundle, PhaseConfigBundle, PhaseConfigBundle, PhaseConfigBundle)}. */
    private PhaseConfigResolver(String error, PhaseConfigBundle resolved,
                                PhaseConfigBundle requestBundle,
                                PhaseConfigBundle workstreamBundle,
                                PhaseConfigBundle fallbackBundle,
                                PhaseConfigBundle workspaceBundle) {
        this.error = error;
        this.resolved = resolved;
        this.requestBundle = requestBundle != null ? requestBundle : PhaseConfigBundle.EMPTY;
        this.workstreamBundle = workstreamBundle != null ? workstreamBundle : PhaseConfigBundle.EMPTY;
        this.fallbackBundle = fallbackBundle != null ? fallbackBundle : PhaseConfigBundle.EMPTY;
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
     * Returns the per-job fallback bundle as submitted, before the gate that
     * applies it only to phases the workstream leaves unset. Never
     * {@code null}; returns {@link PhaseConfigBundle#EMPTY} when the request
     * specified no fallback.
     */
    public PhaseConfigBundle fallbackBundle() {
        return fallbackBundle;
    }

    /**
     * Appends a {@code ,"fallbackResolution":{...}} fragment to {@code json}
     * reporting, per phase, the resolved {@code runner} / {@code model} /
     * {@code effort} / {@code provider} and whether the per-job fallback was
     * used or ignored and why. Nothing is appended when no fallback was
     * supplied, so a submission without a fallback produces exactly the
     * response it did before. The fragment leads with a comma, so the caller
     * must have written at least one prior field.
     *
     * @param json the target string builder; must not be {@code null}
     */
    public void appendFallbackResolutionJson(StringBuilder json) {
        if (fallbackBundle.isEmpty()) return;
        json.append(",\"fallbackResolution\":{");
        boolean first = true;
        for (Phase phase : Phase.values()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"")
                    .append(JsonFieldExtractor.escapeJson(phase.wireName()))
                    .append("\":");
            appendPhaseFallbackStatusJson(json, phase);
        }
        json.append("}");
    }

    /**
     * Appends a single phase's resolved config plus its fallback status
     * ({@code fallbackUsed} and a human-readable {@code reason}) as a JSON
     * object. The fallback is reported used for a phase only when it actually
     * affects the effective config: the workstream leaves the phase unset, the
     * fallback has a non-empty entry for it, and the config it resolves to
     * differs from the config the phase would resolve to without the fallback.
     *
     * <p>When a non-empty fallback has no net effect, the {@code reason}
     * distinguishes <em>why</em>: {@code "request overrides <phase>"} when a
     * higher-precedence per-job request supplies every field the fallback would
     * have contributed (so the request, not the fallback, decides the config),
     * and {@code "fallback matches resolved <phase> config"} when the request
     * does not cover the fallback yet the workspace / controller levels already
     * resolve to the same values the fallback carries. Reporting the request as
     * the cause in the latter case would be misleading, since the request
     * contributed nothing.</p>
     */
    private void appendPhaseFallbackStatusJson(StringBuilder json, Phase phase) {
        PhaseConfig resolved = forPhase(phase);
        boolean workstreamSets = workstreamBundle.setsPhase(phase);
        boolean fallbackContributes = !fallbackBundle.forPhase(phase).isEmpty();
        boolean noNetEffect = fallbackContributes && !workstreamSets
                && resolved.equals(resolveWithoutFallback(phase));
        boolean used = fallbackContributes && !workstreamSets && !noNetEffect;
        String reason;
        if (workstreamSets) {
            reason = "workstream sets " + phase.wireName();
        } else if (!fallbackContributes) {
            reason = "fallback has no entry for this phase";
        } else if (used) {
            reason = "fallback applied";
        } else if (requestBundle.forPhase(phase).covers(fallbackBundle.forPhase(phase))) {
            reason = "request overrides " + phase.wireName();
        } else {
            reason = "fallback matches resolved " + phase.wireName() + " config";
        }
        json.append("{");
        appendResolvedField(json, true, "runner", resolved.runner());
        appendResolvedField(json, false, "model", resolved.model());
        appendResolvedField(json, false, "effort", resolved.effort());
        appendResolvedField(json, false, "provider", resolved.provider());
        json.append(",\"fallbackUsed\":").append(used);
        json.append(",\"reason\":\"").append(JsonFieldExtractor.escapeJson(reason)).append("\"");
        json.append("}");
    }

    /**
     * Appends a {@code "name":"value"} (or {@code "name":null}) member. When
     * {@code firstMember} is false the member is preceded by a comma.
     */
    private static void appendResolvedField(StringBuilder json, boolean firstMember,
                                            String name, String value) {
        if (!firstMember) json.append(",");
        json.append("\"").append(name).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append("\"").append(JsonFieldExtractor.escapeJson(value)).append("\"");
        }
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
        PhaseConfig middle = workstreamBundle.setsPhase(phase)
                ? workstreamBundle.forPhase(phase)
                : fallbackBundle.forPhase(phase);
        return requestBundle.forPhase(phase)
                .overlayOnClearingInheritedProvider(middle)
                .overlayOnClearingInheritedProvider(workspaceBundle.forPhase(phase))
                .overlayOn(CONTROLLER_DEFAULT);
    }

    /** Source label: a per-phase entry in the per-job request bundle. */
    public static final String SOURCE_JOB_PHASE = "job per-phase";
    /** Source label: the default of the per-job request bundle. */
    public static final String SOURCE_JOB_DEFAULT = "job default";
    /** Source label: a per-phase entry in the workstream bundle. */
    public static final String SOURCE_WORKSTREAM_PHASE = "workstream per-phase";
    /** Source label: the default of the workstream bundle. */
    public static final String SOURCE_WORKSTREAM_DEFAULT = "workstream default";
    /** Source label: a per-phase entry in the per-job fallback bundle. */
    public static final String SOURCE_FALLBACK_PHASE = "fallback per-phase";
    /** Source label: the default of the per-job fallback bundle. */
    public static final String SOURCE_FALLBACK_DEFAULT = "fallback default";
    /** Source label: a per-phase entry in the workspace bundle. */
    public static final String SOURCE_WORKSPACE_PHASE = "workspace per-phase";
    /** Source label: the default of the workspace bundle. */
    public static final String SOURCE_WORKSPACE_DEFAULT = "workspace default";
    /** Source label: the built-in {@link #CONTROLLER_DEFAULT}. */
    public static final String SOURCE_CONTROLLER_DEFAULT = "controller default";
    /** Source label: no level supplied a value (the field resolves to {@code null}). */
    public static final String SOURCE_NONE = "none";

    /**
     * The resolved value of one {@link PhaseConfig} field together with the
     * precedence level that supplied it.
     *
     * @param value  the resolved field value, or {@code null} when no level set it
     * @param source one of the {@code SOURCE_*} labels naming the level that
     *               supplied {@code value} ({@link #SOURCE_NONE} when {@code value}
     *               is {@code null})
     */
    public record FieldResolution(String value, String source) {
    }

    /**
     * The resolved per-phase configuration with each field attributed to the
     * precedence level that supplied it. The {@code value} of each
     * {@link FieldResolution} matches the corresponding field of
     * {@link #forPhase(Phase)} for the same phase.
     *
     * @param runner   resolution of the {@code runner} field
     * @param model    resolution of the {@code model} field
     * @param effort   resolution of the {@code effort} field
     * @param provider resolution of the {@code provider} field
     */
    public record PhaseResolution(FieldResolution runner, FieldResolution model,
                                  FieldResolution effort, FieldResolution provider) {
    }

    /** One labelled precedence level contributing to a phase's resolution. */
    private record SourceLayer(String label, PhaseConfig config) {
    }

    /**
     * Returns the resolved configuration for {@code phase} with each field
     * attributed to the precedence level that supplied it. The resolved values
     * are identical to {@link #forPhase(Phase)}; this method additionally reports
     * <em>where</em> each value came from — one of the {@code SOURCE_*} labels
     * ({@code job per-phase}, {@code job default}, {@code workstream per-phase},
     * {@code workstream default}, {@code fallback per-phase},
     * {@code fallback default}, {@code workspace per-phase},
     * {@code workspace default}, {@code controller default}, or {@code none}).
     *
     * <p>A field is attributed to the highest-precedence layer whose value for
     * that field equals the resolved value. Because the non-provider fields
     * resolve by "highest non-null wins", that layer is the one that set the
     * value; the provider field accounts for the runner-aware provider clearing
     * of {@link PhaseConfig#overlayOnClearingInheritedProvider(PhaseConfig)} by
     * matching against the surviving (post-clearing) resolved provider, so a
     * provider suppressed by a runner mismatch is reported as {@code none}
     * rather than attributed to the level that was cleared.</p>
     *
     * @param phase the phase to resolve; {@code null} resolves the default
     * @return the per-field resolution with sources, never {@code null}
     */
    public PhaseResolution resolutionOf(Phase phase) {
        PhaseConfig resolved = forPhase(phase);
        List<SourceLayer> layers = sourceLayers(phase);
        return new PhaseResolution(
                new FieldResolution(resolved.runner(),
                        sourceForField(layers, resolved.runner(), PhaseConfig::runner)),
                new FieldResolution(resolved.model(),
                        sourceForField(layers, resolved.model(), PhaseConfig::model)),
                new FieldResolution(resolved.effort(),
                        sourceForField(layers, resolved.effort(), PhaseConfig::effort)),
                new FieldResolution(resolved.provider(),
                        sourceForField(layers, resolved.provider(), PhaseConfig::provider)));
    }

    /**
     * Builds the ordered (highest precedence first) list of labelled single-config
     * layers that resolve {@code phase}, mirroring the overlay chain of
     * {@link #forPhase(Phase)}: the per-job per-phase entry and default, then the
     * workstream per-phase entry and default when the workstream sets the phase
     * (else the fallback per-phase entry and default), then the workspace
     * per-phase entry and default, and finally the controller default.
     */
    private List<SourceLayer> sourceLayers(Phase phase) {
        List<SourceLayer> layers = new ArrayList<>();
        layers.add(new SourceLayer(SOURCE_JOB_PHASE, entryOrEmpty(requestBundle, phase)));
        layers.add(new SourceLayer(SOURCE_JOB_DEFAULT, requestBundle.defaultPhaseConfig()));
        if (workstreamBundle.setsPhase(phase)) {
            layers.add(new SourceLayer(SOURCE_WORKSTREAM_PHASE, entryOrEmpty(workstreamBundle, phase)));
            layers.add(new SourceLayer(SOURCE_WORKSTREAM_DEFAULT, workstreamBundle.defaultPhaseConfig()));
        } else {
            layers.add(new SourceLayer(SOURCE_FALLBACK_PHASE, entryOrEmpty(fallbackBundle, phase)));
            layers.add(new SourceLayer(SOURCE_FALLBACK_DEFAULT, fallbackBundle.defaultPhaseConfig()));
        }
        layers.add(new SourceLayer(SOURCE_WORKSPACE_PHASE, entryOrEmpty(workspaceBundle, phase)));
        layers.add(new SourceLayer(SOURCE_WORKSPACE_DEFAULT, workspaceBundle.defaultPhaseConfig()));
        layers.add(new SourceLayer(SOURCE_CONTROLLER_DEFAULT, CONTROLLER_DEFAULT));
        return layers;
    }

    /** Returns {@code bundle}'s per-phase entry for {@code phase}, or {@link PhaseConfig#EMPTY}. */
    private static PhaseConfig entryOrEmpty(PhaseConfigBundle bundle, Phase phase) {
        if (phase == null) return PhaseConfig.EMPTY;
        PhaseConfig entry = bundle.phaseConfigs().get(phase);
        return entry == null ? PhaseConfig.EMPTY : entry;
    }

    /**
     * Returns the {@code SOURCE_*} label of the highest-precedence layer whose
     * {@code field} value equals {@code resolvedValue}, or {@link #SOURCE_NONE}
     * when {@code resolvedValue} is {@code null}.
     */
    private static String sourceForField(List<SourceLayer> layers, String resolvedValue,
                                         Function<PhaseConfig, String> field) {
        if (resolvedValue == null) return SOURCE_NONE;
        for (SourceLayer layer : layers) {
            if (resolvedValue.equals(field.apply(layer.config()))) {
                return layer.label();
            }
        }
        return SOURCE_NONE;
    }

    /**
     * Appends a {@code ,"phaseConfigResolution":{...}} fragment to {@code json}
     * reporting, for every {@link Phase}, each resolved field's value and the
     * precedence level ({@code source}) that supplied it. The fragment leads
     * with a comma, so the caller must have written at least one prior field.
     *
     * <p>Unlike {@link #appendFallbackResolutionJson(StringBuilder)}, this is
     * always emitted — it is the full per-phase resolution map, not just the
     * fallback status.</p>
     *
     * @param json the target string builder; must not be {@code null}
     */
    public void appendPhaseConfigResolutionJson(StringBuilder json) {
        json.append(",\"phaseConfigResolution\":{");
        boolean first = true;
        for (Phase phase : Phase.values()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"")
                    .append(JsonFieldExtractor.escapeJson(phase.wireName()))
                    .append("\":");
            appendPhaseResolutionJson(json, resolutionOf(phase));
        }
        json.append("}");
    }

    /** Appends one {@link PhaseResolution} as a JSON object of {@code field:{value,source}} members. */
    private static void appendPhaseResolutionJson(StringBuilder json, PhaseResolution resolution) {
        json.append("{");
        appendFieldResolutionJson(json, true, "runner", resolution.runner());
        appendFieldResolutionJson(json, false, "model", resolution.model());
        appendFieldResolutionJson(json, false, "effort", resolution.effort());
        appendFieldResolutionJson(json, false, "provider", resolution.provider());
        json.append("}");
    }

    /** Appends a {@code "name":{"value":...,"source":"..."}} member, comma-prefixed unless first. */
    private static void appendFieldResolutionJson(StringBuilder json, boolean firstMember,
                                                  String name, FieldResolution field) {
        if (!firstMember) json.append(",");
        json.append("\"").append(name).append("\":{\"value\":");
        if (field.value() == null) {
            json.append("null");
        } else {
            json.append("\"").append(JsonFieldExtractor.escapeJson(field.value())).append("\"");
        }
        json.append(",\"source\":\"")
                .append(JsonFieldExtractor.escapeJson(field.source()))
                .append("\"}");
    }

    /**
     * Returns warnings for resolved phases whose configuration sets a capability
     * the resolved runner does not honour. Currently a single check: an
     * {@code effort} level set for a runner whose
     * {@link AgentCapabilities#supportsEffortLevel()} is {@code false} — the
     * runner silently ignores the effort at run time, which a submitter rarely
     * intends. Unlike model and provider mismatches (which are hard submission
     * errors), these are advisory because the job still runs correctly; they
     * surface through the phase-config diagnostic rather than failing the submit.
     *
     * @return the list of human-readable warnings, in {@link Phase} declaration
     *         order; never {@code null}, empty when every resolved capability is
     *         honoured
     */
    public List<String> capabilityWarnings() {
        List<String> warnings = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            appendCapabilityWarning(warnings, phase.wireName(), forPhase(phase));
        }
        return warnings;
    }

    /** Adds an effort-capability warning for {@code config} under {@code phaseLabel} when applicable. */
    private static void appendCapabilityWarning(List<String> warnings, String phaseLabel,
                                                PhaseConfig config) {
        String effort = config.effort();
        String runner = config.runner();
        if (effort == null || effort.isEmpty() || runner == null || runner.isEmpty()) return;
        if (!AgentRunnerRegistry.available().contains(runner)) return;
        AgentRunner instance;
        try {
            instance = AgentRunnerRegistry.get(runner);
        } catch (Exception e) {
            return;
        }
        if (instance == null) return;
        if (!instance.capabilities().supportsEffortLevel()) {
            warnings.add("phase " + phaseLabel + ": runner '" + runner
                    + "' does not support effort levels, so effort='" + effort
                    + "' will be ignored at run time");
        }
    }

    /**
     * Resolves {@code phase} as if no per-job fallback were supplied: the
     * fallback is dropped from the middle layer, so a phase the workstream
     * leaves unset falls straight through to the workspace / controller levels.
     * Used only by {@link #appendPhaseFallbackStatusJson(StringBuilder, Phase)}
     * to decide whether the fallback actually affected the effective config —
     * when the result equals {@link #forPhase(Phase)} the fallback had no net
     * effect and is reported unused (the {@code reason} then names whether a
     * request override or a coincident workspace / controller default is the
     * cause).
     *
     * @param phase the phase to resolve; never {@code null} in callers
     * @return the config this phase would resolve to without the fallback
     */
    private PhaseConfig resolveWithoutFallback(Phase phase) {
        PhaseConfig middle = workstreamBundle.setsPhase(phase)
                ? workstreamBundle.forPhase(phase)
                : PhaseConfig.EMPTY;
        return requestBundle.forPhase(phase)
                .overlayOnClearingInheritedProvider(middle)
                .overlayOnClearingInheritedProvider(workspaceBundle.forPhase(phase))
                .overlayOn(CONTROLLER_DEFAULT);
    }

    /**
     * Resolves the per-phase configuration from the request, workstream and
     * workspace levels, with no per-job fallback. Equivalent to
     * {@link #resolve(PhaseConfigBundle, PhaseConfigBundle, PhaseConfigBundle,
     * PhaseConfigBundle)} with an {@linkplain PhaseConfigBundle#EMPTY empty}
     * fallback, so a submission that supplies no fallback resolves exactly as
     * it did before the fallback level existed.
     *
     * @param requestBundle    per-job bundle (request body); may be {@code null}
     * @param workstreamBundle workstream-level bundle; may be {@code null}
     * @param workspaceBundle  workspace-level bundle; may be {@code null}
     * @return a fresh resolver
     */
    public static PhaseConfigResolver resolve(PhaseConfigBundle requestBundle,
                                              PhaseConfigBundle workstreamBundle,
                                              PhaseConfigBundle workspaceBundle) {
        return resolve(requestBundle, workstreamBundle, PhaseConfigBundle.EMPTY, workspaceBundle);
    }

    /**
     * Resolves the per-phase configuration across the request, workstream,
     * per-job fallback and workspace levels. Validates every non-null runner
     * name and every per-phase effort value; returns a resolver carrying an
     * {@link #error()} message on validation failure.
     *
     * <p>The fallback sits between the workstream and the workspace, but
     * unlike the other levels it applies as a whole entry and only to phases
     * the workstream leaves unset (see {@link PhaseConfigBundle#setsPhase(Phase)}).
     * A phase the workstream sets ignores the fallback entirely; a phase the
     * workstream leaves unset takes the fallback, which then overlays
     * field-by-field onto the workspace / controller levels. The resulting
     * runner / provider / model combination is validated below like any other,
     * so a fallback that completes into an invalid combination (for example a
     * fallback {@code provider} pairing with an inherited {@code runner=claude})
     * is rejected at submission. Runner-aware overlay keeps a provider
     * configured for one runner at a lower level from leaking into a phase that
     * resolves to a different runner.</p>
     *
     * <p>The resolved bundle's {@link PhaseConfigBundle#defaultPhaseConfig()
     * default} is built <em>without</em> the fallback — the fallback is applied
     * per phase and every phase that takes it is stored as an explicit entry.
     * This is deliberate: {@link PhaseConfigBundle#forPhase(Phase)} re-overlays a
     * stored entry on the bundle default, so baking the fallback's
     * {@code model} / {@code effort} into that default would leak those fields
     * into a workstream-set phase whose entry leaves them {@code null} (a phase
     * the resolver correctly excluded the fallback from). Keeping the fallback
     * out of the default makes the resolved bundle's own {@code forPhase}
     * reproduce {@link #forPhase(Phase)} here exactly, which is what the worker
     * and {@code AgentRunRequestFactory} ultimately read.</p>
     *
     * @param requestBundle    per-job bundle (request body); may be {@code null}
     * @param workstreamBundle workstream-level bundle; may be {@code null}
     * @param fallbackBundle   per-job fallback bundle; may be {@code null}
     * @param workspaceBundle  workspace-level bundle; may be {@code null}
     * @return a fresh resolver
     */
    public static PhaseConfigResolver resolve(PhaseConfigBundle requestBundle,
                                              PhaseConfigBundle workstreamBundle,
                                              PhaseConfigBundle fallbackBundle,
                                              PhaseConfigBundle workspaceBundle) {
        PhaseConfigBundle req = requestBundle != null ? requestBundle : PhaseConfigBundle.EMPTY;
        PhaseConfigBundle ws = workstreamBundle != null ? workstreamBundle : PhaseConfigBundle.EMPTY;
        PhaseConfigBundle fb = fallbackBundle != null ? fallbackBundle : PhaseConfigBundle.EMPTY;
        PhaseConfigBundle wsp = workspaceBundle != null ? workspaceBundle : PhaseConfigBundle.EMPTY;

        // Validate runner names referenced anywhere.
        String runnerErr = validateRunners(req);
        if (runnerErr != null) return fail(runnerErr);
        runnerErr = validateRunners(ws);
        if (runnerErr != null) return fail(runnerErr);
        runnerErr = validateRunners(fb);
        if (runnerErr != null) return fail(runnerErr);
        runnerErr = validateRunners(wsp);
        if (runnerErr != null) return fail(runnerErr);

        // Validate effort values referenced anywhere.
        String effortErr = validateEffort(req);
        if (effortErr != null) return fail(effortErr);
        effortErr = validateEffort(ws);
        if (effortErr != null) return fail(effortErr);
        effortErr = validateEffort(fb);
        if (effortErr != null) return fail(effortErr);
        effortErr = validateEffort(wsp);
        if (effortErr != null) return fail(effortErr);

        // Resolved default EXCLUDES the fallback (applied per phase below).
        PhaseConfig resolvedDefault = req.defaultPhaseConfig()
                .overlayOnClearingInheritedProvider(ws.defaultPhaseConfig())
                .overlayOnClearingInheritedProvider(wsp.defaultPhaseConfig())
                .overlayOn(CONTROLLER_DEFAULT);

        // Middle layer is the workstream when it sets the phase, else the fallback.
        Map<Phase, PhaseConfig> phases = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) {
            PhaseConfig middle = ws.setsPhase(phase)
                    ? ws.forPhase(phase)
                    : fb.forPhase(phase);
            PhaseConfig perPhase = req.forPhase(phase)
                    .overlayOnClearingInheritedProvider(middle)
                    .overlayOnClearingInheritedProvider(wsp.forPhase(phase))
                    .overlayOn(CONTROLLER_DEFAULT);
            // Record an entry only when it differs from the resolved default.
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
        return new PhaseConfigResolver(null, resolved, req, ws, fb, wsp);
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
     * {@code defaultPhaseConfig} and {@code phaseConfigs} JSON fields. These
     * are the only per-phase configuration inputs accepted on a request — there
     * is no legacy job-level {@code model} / {@code effort} / {@code runners}
     * shortcut.
     *
     * @param body the full request body JSON; may be {@code null}
     * @return a fresh bundle; {@link PhaseConfigBundle#EMPTY} when the body
     *         carries no per-phase configuration
     */
    public static PhaseConfigBundle bundleFromRequest(String body) {
        JsonNode root = parseBodyRoot(body);
        if (root == null) return PhaseConfigBundle.EMPTY;
        return bundleFromNodes(root.get("defaultPhaseConfig"), root.get("phaseConfigs"));
    }

    /**
     * Builds a {@link PhaseConfigBundle} from a request body's per-job
     * fallback fields ({@code fallbackPhaseConfig} and
     * {@code fallbackPhaseConfigs}). These mirror
     * {@code defaultPhaseConfig} / {@code phaseConfigs} but feed the fallback
     * level of the ladder (applied only to phases the workstream leaves unset)
     * rather than the per-job override level.
     *
     * @param body the full request body JSON; may be {@code null}
     * @return a fresh bundle; {@link PhaseConfigBundle#EMPTY} when the body
     *         carries no fallback configuration
     */
    public static PhaseConfigBundle fallbackBundleFromRequest(String body) {
        JsonNode root = parseBodyRoot(body);
        if (root == null) return PhaseConfigBundle.EMPTY;
        return bundleFromNodes(root.get("fallbackPhaseConfig"), root.get("fallbackPhaseConfigs"));
    }

    /**
     * Shared implementation of {@link #bundleFromRequest(String)} and
     * {@link #fallbackBundleFromRequest(String)}, building a bundle from an
     * already-extracted default node and per-phase map node.
     *
     * @param defaultNode      the bundle-default JSON node, or {@code null}
     * @param phaseConfigsNode the per-phase map JSON node, or {@code null}
     * @return a fresh bundle; {@link PhaseConfigBundle#EMPTY} when both nodes
     *         are absent or empty
     */
    private static PhaseConfigBundle bundleFromNodes(JsonNode defaultNode,
                                                     JsonNode phaseConfigsNode) {
        PhaseConfigBundle result = PhaseConfigBundle.EMPTY;
        if (defaultNode != null && defaultNode.isObject()) {
            PhaseConfig parsed = phaseConfigFromNode(defaultNode);
            if (!parsed.isEmpty()) {
                result = result.withDefault(parsed);
            }
        }
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
     * Validates the resolved model for {@code config} by asking the resolved
     * runner whether it would accept it
     * ({@link AgentRunner#isModelSupported(String)}).
     *
     * <p>The runner is asked rather than its
     * {@link AgentCapabilities#supportedModels()} set compared against,
     * because a runner may accept more than it advertises: a CLI that
     * resolves its own tier aliases and gains models with each release
     * knows what it will run, and this layer does not. The advertised set
     * remains what {@code GET /api/agents} shows an operator choosing a
     * model. A runner that declares nothing specific still behaves as
     * before — unconstrained — through the interface's default.</p>
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
        AgentRunner instance;
        try {
            instance = AgentRunnerRegistry.get(runner);
        } catch (Exception e) {
            // Runners that fail to instantiate (e.g. opencode without a
            // configured binary) should not block validation; treat as
            // unconstrained.
            return null;
        }
        // A registered supplier may also yield null rather than throw; that
        // is the same "cannot ask this runner" case.
        if (instance == null) return null;
        if (instance.isModelSupported(model)) return null;
        Set<String> advertised = instance.capabilities().supportedModels();
        return "Invalid model '" + model + "' for runner '" + runner
                + "' (phase " + phaseLabel + "). Expected one of " + advertised
                + ", or another identifier that runner accepts";
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
        return new PhaseConfigResolver(message, null, null, null, null, null);
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
    public static String applyToWorkspace(WorkspaceEntry entry, String body) {
        if (entry == null) return null;
        JsonNode root = parseBodyRoot(body);
        if (root == null) return null;
        if (!root.has("defaultPhaseConfig") && !root.has("phaseConfigs")) return null;
        PhaseConfigBundle existing = entry.toPhaseConfigBundle();
        PhaseConfigBundle updated = applyClearingAndOverlay(existing, root);
        String syntaxErr = validateSyntax(updated);
        if (syntaxErr != null) return syntaxErr;
        updated.applyTo(entry);
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
                    newDefault = parsed.overlayOnClearingInheritedProvider(newDefault);
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
                            newPhases.put(phase, cur == null ? parsed : parsed.overlayOnClearingInheritedProvider(cur));
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
