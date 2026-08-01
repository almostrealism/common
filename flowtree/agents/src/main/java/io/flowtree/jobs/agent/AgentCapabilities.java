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
     * supported-provider sets (via {@link Set#copyOf}), preventing mutation through
     * the caller's original reference, and substitutes an empty set for {@code null}.
     */
    public AgentCapabilities {
        supportedModels = supportedModels == null ? Set.of() : Set.copyOf(supportedModels);
        supportedProviders = supportedProviders == null ? Set.of() : Set.copyOf(supportedProviders);
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
}
