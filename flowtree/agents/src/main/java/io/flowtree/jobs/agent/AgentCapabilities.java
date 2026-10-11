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

package io.flowtree.jobs.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.JsonFieldExtractor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Declares which optional features an {@link AgentRunner} faithfully reports.
 *
 * <p>The orchestrator and telemetry layer use these flags to decide whether to
 * surface a value (such as cost or turn count) or treat it as not-reported.
 * A runner that does not report cost still produces an {@link AgentRunResult}
 * with {@code costUsd=0}; the {@code reportsCost} flag is what tells consumers
 * whether that zero is meaningful or absent.</p>
 *
 * @param reportsCost                       whether the runner emits a USD cost figure
 * @param reportsTurns                      whether the runner emits an agentic-turn count
 * @param supportsEffortLevel               whether the runner accepts a thinking/effort level
 * @param supportsMaxBudget                 whether the runner accepts a USD budget cap
 * @param supportsMcpHttpTransport          whether the runner supports HTTP-transport MCP servers
 * @param supportsMcpStdioTransport         whether the runner supports stdio MCP servers
 * @param supportsPermissionDenialReporting whether the runner reports tool-permission denials
 * @param supportedModels                   set of recognised model identifiers; empty means unconstrained
 * @param supportedProviders                set of recognised provider identifiers; empty means unconstrained
 */
public record AgentCapabilities(
        boolean reportsCost,
        boolean reportsTurns,
        boolean supportsEffortLevel,
        boolean supportsMaxBudget,
        boolean supportsMcpHttpTransport,
        boolean supportsMcpStdioTransport,
        boolean supportsPermissionDenialReporting,
        Set<String> supportedModels,
        Set<String> supportedProviders) {

    /**
     * Canonical compact constructor that defensively copies the supported-model and
     * supported-provider sets into unmodifiable sets, preventing mutation through
     * the caller's original reference, and substitutes an empty set for {@code null}.
     * The copies keep the iteration order of the caller's sets, so a runner that
     * lists its aliases ahead of full identifiers is advertised in that order.
     */
    public AgentCapabilities {
        supportedModels = supportedModels == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(supportedModels));
        supportedProviders = supportedProviders == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(supportedProviders));
    }

    /**
     * Backwards-compatible eight-argument constructor that defaults
     * {@code supportedProviders} to the empty set. Used by tests and call
     * sites from before the provider-axis was added.
     *
     * @param reportsCost                       whether the runner emits a USD cost figure
     * @param reportsTurns                      whether the runner emits an agentic-turn count
     * @param supportsEffortLevel               whether the runner accepts a thinking/effort level
     * @param supportsMaxBudget                 whether the runner accepts a USD budget cap
     * @param supportsMcpHttpTransport          whether the runner supports HTTP-transport MCP servers
     * @param supportsMcpStdioTransport         whether the runner supports stdio MCP servers
     * @param supportsPermissionDenialReporting whether the runner reports tool-permission denials
     * @param supportedModels                   set of recognised model identifiers
     */
    public AgentCapabilities(boolean reportsCost,
                             boolean reportsTurns,
                             boolean supportsEffortLevel,
                             boolean supportsMaxBudget,
                             boolean supportsMcpHttpTransport,
                             boolean supportsMcpStdioTransport,
                             boolean supportsPermissionDenialReporting,
                             Set<String> supportedModels) {
        this(reportsCost, reportsTurns, supportsEffortLevel, supportsMaxBudget,
                supportsMcpHttpTransport, supportsMcpStdioTransport,
                supportsPermissionDenialReporting, supportedModels, Set.of());
    }

    /**
     * Returns whether {@code model} is among the models these capabilities
     * advertise. An empty {@link #supportedModels()} set means the runner is
     * unconstrained, so every model is advertised.
     *
     * @param model the model identifier to look up
     * @return {@code true} when the model is advertised or the set is unconstrained
     */
    public boolean advertisesModel(String model) {
        return supportedModels.isEmpty() || supportedModels.contains(model);
    }

    /**
     * Returns these capabilities as a JSON object whose field names are the
     * record component names, which is the shape {@code GET /api/agents}
     * advertises to clients choosing a runner.
     *
     * @return a new JSON object; never {@code null}
     */
    public ObjectNode toJson() {
        ObjectNode json = JsonFieldExtractor.MAPPER.createObjectNode();
        json.put("reportsCost", reportsCost);
        json.put("reportsTurns", reportsTurns);
        json.put("supportsEffortLevel", supportsEffortLevel);
        json.put("supportsMaxBudget", supportsMaxBudget);
        json.put("supportsMcpHttpTransport", supportsMcpHttpTransport);
        json.put("supportsMcpStdioTransport", supportsMcpStdioTransport);
        json.put("supportsPermissionDenialReporting", supportsPermissionDenialReporting);
        ArrayNode models = json.putArray("supportedModels");
        supportedModels.forEach(models::add);
        ArrayNode providers = json.putArray("supportedProviders");
        supportedProviders.forEach(providers::add);
        return json;
    }
}
