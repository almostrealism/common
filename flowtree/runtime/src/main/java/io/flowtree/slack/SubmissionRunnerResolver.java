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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helper that resolves per-phase agent runner configuration for a submitted
 * coding job and applies it to a {@link CodingAgentJobFactory}.
 *
 * <p>Precedence (highest to lowest): per-job override → workstream per-phase
 * map → workstream default → {@code "claude"}. The per-job {@code runners}
 * JSON object may carry an optional {@code "default"} key whose value
 * supersedes the workstream default for that job.</p>
 */
final class SubmissionRunnerResolver {

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
    String error() { return error; }

    /**
     * Resolves runner configuration from a submission body and workstream
     * defaults. Returns a resolver whose {@link #error()} is non-null on
     * validation failure; otherwise call {@link #applyTo(CodingAgentJobFactory)}.
     *
     * @param requestRunners parsed {@code "runners"} object from the request body
     *                       (keys: phase wire names plus optional {@code "default"})
     * @param workstreamDefault the workstream's default runner, or {@code null}
     * @param workstreamRunners the workstream's per-phase map, keyed by phase
     *                          wire name; may be {@code null} or empty
     * @return a fresh resolver
     */
    static SubmissionRunnerResolver resolve(Map<String, String> requestRunners,
                                            String workstreamDefault,
                                            Map<String, String> workstreamRunners) {
        Map<String, String> req = requestRunners != null ? requestRunners : new LinkedHashMap<>();
        Map<String, String> wsMap = workstreamRunners != null ? workstreamRunners : new LinkedHashMap<>();

        // 1. Resolve effective default: request "default" > workstream default > "claude".
        String requestedDefault = req.get("default");
        String effectiveDefault;
        if (requestedDefault != null && !requestedDefault.isEmpty()) {
            effectiveDefault = requestedDefault;
        } else if (workstreamDefault != null && !workstreamDefault.isEmpty()) {
            effectiveDefault = workstreamDefault;
        } else {
            effectiveDefault = AgentRunnerRegistry.CLAUDE;
        }
        if (!AgentRunnerRegistry.available().contains(effectiveDefault)) {
            return fail("Unknown runner: '" + effectiveDefault + "'. Available: "
                    + AgentRunnerRegistry.available());
        }

        // 2. Walk every phase, picking req[phase] then ws[phase], converting
        //    string phase names to Phase via fromWireName.
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
    static String applyToWorkstream(Workstream workstream,
                                    Map<String, String> requestRunners) {
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
        if (newDefault != null) workstream.setDefaultRunner(newDefault);
        workstream.setRunners(phaseMap);
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
    void applyTo(CodingAgentJobFactory factory) {
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
