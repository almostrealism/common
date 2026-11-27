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
import io.flowtree.Server;
import io.flowtree.jobs.agent.AgentCapabilities;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.NodeGroup;

/**
 * Handles {@code GET /api/agents} requests for {@link FlowTreeApiEndpoint}.
 *
 * <p>Returns a JSON document enumerating the registered agent runners
 * ({@link AgentRunnerRegistry}) and their {@link AgentCapabilities},
 * every {@link Phase} entry, the model identifiers the built-in default
 * runner advertises through its {@link AgentCapabilities#supportedModels()},
 * the name of that default runner, and the connected {@code nodes} with the
 * {@link io.flowtree.node.NodeStatusReport routing status} (labels and busy
 * state) each last reported. Each runner's capabilities, each phase, and each
 * node describe themselves ({@link AgentCapabilities#toJson()},
 * {@link Phase#toJson()}, {@link io.flowtree.msg.NodeProxy#toJson()});
 * this handler only assembles the document. This metadata is read-only and is
 * what MCP clients use to populate runner / phase / model pickers, and to tell
 * where a job with given {@code requiredLabels} would land, before submitting a
 * job or configuring a workstream.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see AgentRunnerRegistry
 */
class AgentsQueryHandler {

    /**
     * Handles a {@code GET /api/agents} request.
     *
     * @param server  the local FlowTree server whose connected peers are enumerated as
     *                {@code nodes}, or {@code null} when none is configured (then
     *                {@code nodes} is empty).
     * @return an HTTP response containing {@code ok}, {@code runners},
     *         {@code phases}, {@code models}, {@code defaultRunner}, and {@code nodes}
     */
    Response handle(Server server) {
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
        ArrayNode nodes = json.putArray("nodes");
        NodeGroup group = server == null ? null : server.getNodeGroup();
        if (group != null) {
            for (NodeProxy peer : group.getServers()) {
                nodes.add(peer.toJson());
            }
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
    }
}
