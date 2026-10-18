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

package io.flowtree.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.agent.AgentCapabilities;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;

/**
 * Handles {@code GET /api/agents} requests for {@link FlowTreeApiEndpoint}.
 *
 * <p>Returns a JSON document enumerating the registered agent runners
 * ({@link AgentRunnerRegistry}) and their {@link AgentCapabilities},
 * every {@link Phase} entry, the model identifiers the built-in default
 * runner advertises through its {@link AgentCapabilities#supportedModels()},
 * and the name of that default runner. Each runner's capabilities and each
 * phase describe themselves ({@link AgentCapabilities#toJson()},
 * {@link Phase#toJson()}); this handler only assembles the document.
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
        ObjectNode json = JsonFieldExtractor.MAPPER.createObjectNode();
        json.put("ok", true);
        ArrayNode runners = json.putArray("runners");
        for (String name : AgentRunnerRegistry.available()) {
            ObjectNode runner = runners.addObject();
            runner.put("name", name);
            runner.set("capabilities", AgentRunnerRegistry.get(name).capabilities().toJson());
        }
        ArrayNode phases = json.putArray("phases");
        for (Phase phase : Phase.values()) {
            phases.add(phase.toJson());
        }
        ArrayNode models = json.putArray("models");
        AgentRunnerRegistry.get(AgentRunnerRegistry.CLAUDE).capabilities()
                .supportedModels().forEach(models::add);
        json.put("defaultRunner", AgentRunnerRegistry.CLAUDE);
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
    }
}
