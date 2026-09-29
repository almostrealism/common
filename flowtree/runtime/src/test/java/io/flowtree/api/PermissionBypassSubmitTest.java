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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.NodeGroup;
import io.flowtree.workstream.Workstream;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * HTTP-level tests for the {@code bypassAgentPermissionPrompts} submission field of
 * {@link FlowTreeApiEndpoint#handleSubmit}: the per-job grant must reach the job factory the
 * endpoint queues and be echoed in the submission response, and the absence of the field must
 * neither grant the bypass nor revoke a grant made by the workstream's branch policy.
 *
 * <p>These tests cover the handoff between the ar-manager submit tool (which only builds the
 * request body) and {@link Workstream#applyCapabilities(CodingAgentJobFactory, String, boolean)}
 * (which only sees the parsed flag), so a change to the field name or its parsing in the endpoint
 * fails here rather than silently dropping the grant.</p>
 */
public class PermissionBypassSubmitTest extends FlowTreeApiSubmitTestBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Server that captures the factories the endpoint queues. */
    private QueueCapturingServer server;

    /** Backs the endpoint with a server reporting one connected peer. */
    @Before
    public void attachServer() throws IOException {
        server = new QueueCapturingServer();
        endpoint.setServer(server);
    }

    /**
     * An explicit grant on a branch the workstream's policy does not cover reaches the queued
     * factory and the response, and is not stored on the workstream.
     */
    @Test(timeout = 15000)
    public void explicitGrantReachesQueuedFactoryAndResponse() throws IOException {
        Workstream ws = register("ws-pb-grant");

        JsonNode response = submit("{\"prompt\":\"Register the hooks.\",\"workstreamId\":\"ws-pb-grant\","
                + "\"targetBranch\":\"feature/tooling\",\"bypassAgentPermissionPrompts\":true}");

        assertTrue(response.toString(), response.get("ok").asBoolean());
        assertTrue(response.get("bypassAgentPermissionPrompts").asBoolean());
        assertTrue(queuedFactory().isBypassAgentPermissionPrompts());
        assertTrue(ws.getAgentPermissionBypassBranches().isEmpty());
    }

    /** Without the field, a job on a branch the policy does not cover gets no bypass. */
    @Test(timeout = 15000)
    public void absentFieldGrantsNothing() throws IOException {
        register("ws-pb-absent");

        JsonNode response = submit("{\"prompt\":\"Fix the bug.\",\"workstreamId\":\"ws-pb-absent\","
                + "\"targetBranch\":\"feature/tooling\"}");

        assertTrue(response.toString(), response.get("ok").asBoolean());
        assertFalse(response.get("bypassAgentPermissionPrompts").asBoolean());
        assertFalse(queuedFactory().isBypassAgentPermissionPrompts());
    }

    /** An explicit {@code false} cannot revoke what the workstream's branch policy grants. */
    @Test(timeout = 15000)
    public void explicitFalseKeepsBranchPolicyGrant() throws IOException {
        Workstream ws = register("ws-pb-policy");
        ws.setAgentPermissionBypassBranches(Collections.singletonList("ci/"));

        JsonNode response = submit("{\"prompt\":\"Update the workflow.\",\"workstreamId\":\"ws-pb-policy\","
                + "\"targetBranch\":\"ci/workflow\",\"bypassAgentPermissionPrompts\":false}");

        assertTrue(response.toString(), response.get("ok").asBoolean());
        assertTrue(response.get("bypassAgentPermissionPrompts").asBoolean());
        assertTrue(queuedFactory().isBypassAgentPermissionPrompts());
    }

    /** Registers a workstream with the git identity a coding-agent submission requires. */
    private Workstream register(String id) {
        Workstream ws = new Workstream(id, "C_" + id, "#" + id);
        ws.setDefaultBranch("feature/" + id);
        ws.setGitUserName("Test User");
        ws.setGitUserEmail("test@example.com");
        notifier.registerWorkstream(ws);
        return ws;
    }

    /** Posts {@code body} to {@code /api/submit} and parses the (successful) response. */
    private JsonNode submit(String body) throws IOException {
        HttpURLConnection conn = openPost("/api/submit", body);
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Returns the single coding-agent factory the endpoint queued. */
    private CodingAgentJobFactory queuedFactory() {
        assertEquals(1, server.queued.size());
        return (CodingAgentJobFactory) server.queued.get(0);
    }

    /**
     * Server double that reports one connected peer, so the endpoint dispatches, and captures
     * every queued factory instead of distributing it.
     */
    private static class QueueCapturingServer extends Server {
        /** Factories passed to {@link #addTask}, in submission order. */
        final List<JobFactory> queued = new ArrayList<>();
        /** Node group reporting a single (placeholder) peer. */
        private final NodeGroup group;

        /** Constructs the server without binding a listening socket (port 0). */
        QueueCapturingServer() throws IOException {
            super(ephemeralProperties());
            group = new NodeGroup(ephemeralProperties(), this) {
                @Override
                public NodeProxy[] getServers() {
                    return new NodeProxy[1];
                }
            };
        }

        @Override
        public NodeGroup getNodeGroup() {
            return group;
        }

        @Override
        public boolean addTask(JobFactory task) {
            queued.add(task);
            return true;
        }

        /** Properties that skip server-socket binding and start no child nodes. */
        private static Properties ephemeralProperties() {
            Properties p = new Properties();
            p.setProperty("server.port", "0");
            p.setProperty("nodes.initial", "0");
            return p;
        }
    }
}
