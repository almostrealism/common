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

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.agent.AgentCapabilities;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;

/**
 * Handles {@code GET /api/agents} requests for {@link FlowTreeApiEndpoint}.
 *
 * <p>Returns a JSON document enumerating the registered agent runners
 * ({@link AgentRunnerRegistry}) and their {@link AgentCapabilities},
 * the eight {@link Phase} entries, the accepted model identifiers from
 * {@link CodingAgentJob#VALID_MODELS}, and the built-in default runner.
 * This metadata is read-only and is what MCP clients use to populate
 * runner / phase / model pickers before submitting a job or configuring
 * a workstream.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see AgentRunnerRegistry
 */
class AgentsQueryHandler {

    /**
     * Handles a {@code GET /api/agents} request.
     *
     * @return an HTTP response containing {@code ok}, {@code runners},
     *         {@code phases}, {@code models}, and {@code defaultRunner}
     */
    Response handle() {
        StringBuilder j = new StringBuilder("{\"ok\":true,\"runners\":[");
        String sc = "";
        for (String n : AgentRunnerRegistry.available()) {
            AgentCapabilities c = AgentRunnerRegistry.get(n).capabilities();
            j.append(sc).append("{\"name\":").append(FlowTreeApiEndpoint.escapeJsonValue(n))
             .append(",\"capabilities\":{\"reportsCost\":").append(c.reportsCost())
             .append(",\"reportsTurns\":").append(c.reportsTurns())
             .append(",\"supportsEffortLevel\":").append(c.supportsEffortLevel())
             .append(",\"supportsMaxBudget\":").append(c.supportsMaxBudget())
             .append(",\"supportsMcpHttpTransport\":").append(c.supportsMcpHttpTransport())
             .append(",\"supportsMcpStdioTransport\":").append(c.supportsMcpStdioTransport())
             .append(",\"supportsPermissionDenialReporting\":").append(c.supportsPermissionDenialReporting())
             .append(",\"supportedModels\":[");
            String sm = "";
            for (String m : c.supportedModels()) {
                j.append(sm).append(FlowTreeApiEndpoint.escapeJsonValue(m));
                sm = ",";
            }
            j.append("]}}");
            sc = ",";
        }
        j.append("],\"phases\":[");
        String sp = "";
        for (Phase p : Phase.values()) {
            j.append(sp).append("{\"name\":").append(FlowTreeApiEndpoint.escapeJsonValue(p.wireName()))
             .append(",\"description\":").append(FlowTreeApiEndpoint.escapeJsonValue(p.description())).append("}");
            sp = ",";
        }
        j.append("],\"models\":[");
        String se = "";
        for (String m : CodingAgentJob.VALID_MODELS) {
            j.append(se).append(FlowTreeApiEndpoint.escapeJsonValue(m));
            se = ",";
        }
        j.append("],\"defaultRunner\":")
         .append(FlowTreeApiEndpoint.escapeJsonValue(AgentRunnerRegistry.CLAUDE)).append("}");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", j.toString());
    }
}
