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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;
import io.flowtree.workstream.WorkspaceEntry;

/**
 * Helper that resolves per-phase agent runner configuration for a submitted
 * coding job and applies it to a {@link CodingAgentJobFactory}.
 *
 * <p>Precedence (highest to lowest):</p>
 * <ol>
 *   <li>Per-job override (the {@code runners} JSON object on the submission;
 *       may also carry an optional {@code "default"} key)</li>
 *   <li>Workstream per-phase map ({@link Workstream#getRunners()})</li>
 *   <li>Workstream default ({@link Workstream#getDefaultRunner()})</li>
 *   <li>Workspace per-phase map
 *       ({@link WorkspaceEntry#getRunners()})</li>
 *   <li>Workspace default
 *       ({@link WorkspaceEntry#getDefaultRunner()})</li>
 *   <li>Controller default ({@code "claude"})</li>
 * </ol>
 *
 * <p>The workspace layer (levels 4 and 5) is consulted only when the
 * workstream has no per-phase entry for the phase <em>and</em> the
 * workstream has no {@code defaultRunner} set — workstream-level config
 * fully shadows the workspace it belongs to. A workstream with no
 * {@code workspaceId} (or whose {@code workspaceId} does not match any
 * configured workspace) skips the workspace lookup and falls through to
 * the controller default.</p>
 */
public final class SubmissionRunnerResolver {

    /** Error message returned when the runner mapping is invalid. */
    private final String error;

    /** Resolved default runner; never {@code null} when {@link #error} is {@code null}. */
    private final String resolvedDefault;

    /** Resolved per-phase runner overrides; empty when nothing differs from the default. */
    private final Map<Phase, String> resolvedPhases;

    /** Private constructor; use {@link #resolve(Map, String, Map)}. */
    private SubmissionRunnerResolver(String error, String defaultRunner,
                                     Map<Phase, String> phases) {
        this.error = error;
        this.resolvedDefault = defaultRunner;
        this.resolvedPhases = phases;
    }

    /** Returns a 400-able error message describing why resolution failed, or {@code null}. */
    public String error() { return error; }

    /**
     * Resolves runner configuration from a submission body and workstream
     * defaults, without consulting a workspace layer. Equivalent to calling
     * {@link #resolve(Map, String, Map, String, Map)} with {@code null}
     * workspace fields. Preserved for callers that have no workspace context.
     *
     * @param requestRunners parsed {@code "runners"} object from the request body
     *                       (keys: phase wire names plus optional {@code "default"})
     * @param workstreamDefault the workstream's default runner, or {@code null}
     * @param workstreamRunners the workstream's per-phase map, keyed by phase
     *                          wire name; may be {@code null} or empty
     * @return a fresh resolver
     */
    public static SubmissionRunnerResolver resolve(Map<String, String> requestRunners,
                                            String workstreamDefault,
                                            Map<String, String> workstreamRunners) {
        return resolve(requestRunners, workstreamDefault, workstreamRunners,
                null, null);
    }

    /**
     * Resolves runner configuration from a submission body, workstream
     * defaults, and workspace defaults. Returns a resolver whose
     * {@link #error()} is non-null on validation failure; otherwise call
     * {@link #applyTo(CodingAgentJobFactory)}.
     *
     * <p>The workspace layer is only consulted for a phase when (a) the
     * workstream has no per-phase entry for that phase, and (b) the
     * workstream has no {@code defaultRunner} set (which would otherwise
     * apply to every otherwise-unmapped phase). Workspace per-phase entries
     * referencing unknown phase wire names are skipped; the strict load-time
     * validation in {@link WorkstreamConfig#validateWorkspaceRunners()}
     * already rejects them, and this skip is the same defensive treatment
     * the resolver applies to workstream-side entries.</p>
     *
     * @param requestRunners    parsed {@code "runners"} object from the request body
     *                          (keys: phase wire names plus optional {@code "default"})
     * @param workstreamDefault the workstream's default runner, or {@code null}
     * @param workstreamRunners the workstream's per-phase map, keyed by phase
     *                          wire name; may be {@code null} or empty
     * @param workspaceDefault  the owning workspace's default runner, or
     *                          {@code null} when no workspace is configured
     *                          or no default is set
     * @param workspaceRunners  the owning workspace's per-phase map; may be
     *                          {@code null} or empty
     * @return a fresh resolver
     */
    public static SubmissionRunnerResolver resolve(Map<String, String> requestRunners,
                                            String workstreamDefault,
                                            Map<String, String> workstreamRunners,
                                            String workspaceDefault,
                                            Map<String, String> workspaceRunners) {
        Map<String, String> req = requestRunners != null ? requestRunners : new LinkedHashMap<>();
        Map<String, String> wsMap = workstreamRunners != null ? workstreamRunners : new LinkedHashMap<>();
        Map<String, String> workspaceMap = workspaceRunners != null
                ? workspaceRunners : new LinkedHashMap<>();
        boolean hasWorkstreamDefault =
                workstreamDefault != null && !workstreamDefault.isEmpty();
        boolean hasWorkspaceDefault =
                workspaceDefault != null && !workspaceDefault.isEmpty();

        // 1. Resolve effective default: request "default" > workstream default
        //    > workspace default > controller default ("claude").
        String requestedDefault = req.get("default");
        String effectiveDefault;
        if (requestedDefault != null && !requestedDefault.isEmpty()) {
            effectiveDefault = requestedDefault;
        } else if (hasWorkstreamDefault) {
            effectiveDefault = workstreamDefault;
        } else if (hasWorkspaceDefault) {
            effectiveDefault = workspaceDefault;
        } else {
            effectiveDefault = AgentRunnerRegistry.CLAUDE;
        }
        if (!AgentRunnerRegistry.available().contains(effectiveDefault)) {
            return fail("Unknown runner: '" + effectiveDefault + "'. Available: "
                    + AgentRunnerRegistry.available());
        }

        // 2. Walk every phase, picking req[phase] then ws[phase], then
        //    (only when the workstream has no defaultRunner) workspace[phase].
        Map<Phase, String> phases = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : req.entrySet()) {
            if ("default".equals(e.getKey())) continue;
            Phase phase;
            try { phase = Phase.fromWireName(e.getKey()); }
            catch (IllegalArgumentException ex) {
                return fail("Unknown phase in runners: '" + e.getKey() + "'");
            }
            String runner = e.getValue();
            if (runner == null || runner.isEmpty()) continue;
            if (!AgentRunnerRegistry.available().contains(runner)) {
                return fail("Unknown runner: '" + runner + "'. Available: "
                        + AgentRunnerRegistry.available());
            }
            phases.put(phase, runner);
        }
        for (Map.Entry<String, String> e : wsMap.entrySet()) {
            String runner = e.getValue();
            if (runner == null || runner.isEmpty()) continue;
            Phase phase;
            try { phase = Phase.fromWireName(e.getKey()); }
            catch (IllegalArgumentException ex) {
                // Workstream-side bad entries are skipped (config-side data
                // should not be able to brick a submission).
                continue;
            }
            String previous = phases.putIfAbsent(phase, runner);
            if (previous == null && !AgentRunnerRegistry.available().contains(runner)) {
                return fail("Unknown runner: '" + runner + "'. Available: "
                        + AgentRunnerRegistry.available());
            }
        }
        // Workspace per-phase entries fall below the workstream default —
        // when the workstream sets a default, every phase not in the
        // workstream's per-phase map already resolves to that default, so the
        // workspace's per-phase entries must not override it.
        if (!hasWorkstreamDefault) {
            for (Map.Entry<String, String> e : workspaceMap.entrySet()) {
                String runner = e.getValue();
                if (runner == null || runner.isEmpty()) continue;
                Phase phase;
                try { phase = Phase.fromWireName(e.getKey()); }
                catch (IllegalArgumentException ex) {
                    continue;
                }
                String previous = phases.putIfAbsent(phase, runner);
                if (previous == null && !AgentRunnerRegistry.available().contains(runner)) {
                    return fail("Unknown runner: '" + runner + "'. Available: "
                            + AgentRunnerRegistry.available());
                }
            }
        }
        return new SubmissionRunnerResolver(null, effectiveDefault, phases);
    }

    /** Returns a failed resolver carrying the given error message. */
    private static SubmissionRunnerResolver fail(String msg) {
        return new SubmissionRunnerResolver(msg, null, null);
    }

    /**
     * Applies a parsed {@code runners} object to a workstream as its
     * persistent runner configuration. The {@code "default"} key, when
     * present, becomes {@link Workstream#setDefaultRunner(String)};
     * remaining keys (which must be valid {@link Phase} wire names) replace
     * the workstream's {@link Workstream#setRunners(Map) per-phase map}.
     *
     * <p>An empty or {@code null} {@code requestRunners} leaves the
     * workstream untouched so callers can update unrelated fields without
     * disturbing existing runner config.</p>
     *
     * @param workstream     the workstream to mutate
     * @param requestRunners the parsed {@code runners} object from the body,
     *                       or {@code null}
     * @return {@code null} on success, or a 400-able error message on
     *         unknown phase name or unknown runner name
     */
    public static String applyToWorkstream(Workstream workstream,
                                    Map<String, String> requestRunners) {
        return applyRunnerConfig(requestRunners,
                workstream::setDefaultRunner, workstream::setRunners);
    }

    /**
     * Applies a parsed {@code runners} object to a workspace as its
     * persistent runner configuration. The {@code "default"} key, when
     * present, becomes
     * {@link WorkspaceEntry#setDefaultRunner(String)};
     * remaining keys (which must be valid {@link Phase} wire names) replace
     * the workspace's per-phase map.
     *
     * <p>An empty or {@code null} {@code requestRunners} leaves the workspace
     * untouched so callers can update unrelated fields without disturbing
     * existing runner config.</p>
     *
     * @param entry          the workspace entry to mutate
     * @param requestRunners the parsed {@code runners} object from the body,
     *                       or {@code null}
     * @return {@code null} on success, or a 400-able error message on
     *         unknown phase name or unknown runner name
     */
    public static String applyToWorkspace(WorkspaceEntry entry,
                                   Map<String, String> requestRunners) {
        return applyRunnerConfig(requestRunners,
                entry::setDefaultRunner, entry::setRunners);
    }

    /**
     * Validates and applies a parsed {@code runners} object. Iterates over
     * every entry, validates the runner name against
     * {@link AgentRunnerRegistry#available()} and the phase name against
     * {@link Phase#fromWireName(String)}, then passes the resolved default
     * runner and per-phase map to the provided consumers.
     *
     * @param requestRunners the runners map to apply; {@code null} or empty
     *                       returns {@code null} immediately without calling
     *                       either consumer
     * @param setDefault     called with the {@code "default"} runner when
     *                       a default entry is present
     * @param setRunners     called with the fully-validated per-phase map
     * @return {@code null} on success, or a 400-able error message
     */
    private static String applyRunnerConfig(Map<String, String> requestRunners,
                                            Consumer<String> setDefault,
                                            Consumer<Map<String, String>> setRunners) {
        if (requestRunners == null || requestRunners.isEmpty()) return null;
        String newDefault = null;
        Map<String, String> phaseMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : requestRunners.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (value == null || value.isEmpty()) continue;
            if (!AgentRunnerRegistry.available().contains(value)) {
                return "Unknown runner: '" + value + "'. Available: "
                        + AgentRunnerRegistry.available();
            }
            if ("default".equals(key)) {
                newDefault = value;
            } else {
                try {
                    Phase.fromWireName(key);
                } catch (IllegalArgumentException ex) {
                    return "Unknown phase in runners: '" + key + "'";
                }
                phaseMap.put(key, value);
            }
        }
        if (newDefault != null) setDefault.accept(newDefault);
        setRunners.accept(phaseMap);
        return null;
    }

    /**
     * Applies the resolved configuration to the given factory. No-ops when the
     * effective default and every phase entry already match
     * {@link AgentRunnerRegistry#CLAUDE}, so byte-identical wire format is
     * preserved for legacy submissions.
     *
     * @param factory the factory to configure
     */
    public void applyTo(CodingAgentJobFactory factory) {
        if (resolvedDefault != null
                && !AgentRunnerRegistry.CLAUDE.equals(resolvedDefault)) {
            factory.setDefaultRunner(resolvedDefault);
        }
        for (Map.Entry<Phase, String> e : resolvedPhases.entrySet()) {
            // Skip entries that match the resolved default to keep the encoded
            // wire format compact.
            if (e.getValue().equals(resolvedDefault)) continue;
            factory.setRunnerForPhase(e.getKey(), e.getValue());
        }
    }
}
