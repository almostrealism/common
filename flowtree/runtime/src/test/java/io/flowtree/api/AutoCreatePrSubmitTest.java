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
import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.workstream.Workstream;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * HTTP-level tests for the {@code autoCreatePr} submission field of
 * {@link FlowTreeApiEndpoint#handleSubmit}: a request for a pull request on success must reach
 * the queued job factory and be echoed in the submission response, so a caller can confirm a
 * pull request will be opened without waiting for the job to finish.
 */
public class AutoCreatePrSubmitTest extends FlowTreeApiSubmitTestBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Server that captures the factories the endpoint queues. */
    private QueueCapturingServer server;

    /** Backs the endpoint with a server reporting one connected peer, and registers a workstream. */
    @Before
    public void attachServer() throws IOException {
        server = new QueueCapturingServer();
        endpoint.setServer(server);
        Workstream ws = new Workstream("ws-autopr", "C_autopr", "#autopr");
        ws.setDefaultBranch("project/task-1");
        ws.setGitUserName("Test User");
        ws.setGitUserEmail("test@example.com");
        notifier.registerWorkstream(ws);
    }

    /** A requested pull request is recorded on the queued factory and reported in the response. */
    @Test(timeout = 15000)
    public void requestedPullRequestIsQueuedAndEchoed() throws IOException {
        JsonNode response = submitToWorkstream(",\"autoCreatePr\":true");

        assertTrue(response.toString(), response.get("ok").asBoolean());
        assertTrue(response.get("autoCreatePr").asBoolean());
        assertTrue(queued().isAutoCreatePr());
        // A plain auto-PR request opens the PR ready for review, not as a draft.
        assertFalse(response.get("createPrAsDraft").asBoolean());
        assertFalse(queued().isCreatePrAsDraft());
    }

    /** A draft request is recorded on the queued factory and reported in the response. */
    @Test(timeout = 15000)
    public void requestedDraftPullRequestIsQueuedAndEchoed() throws IOException {
        JsonNode response = submitToWorkstream(",\"autoCreatePr\":true,\"createPrAsDraft\":true");

        assertTrue(response.toString(), response.get("ok").asBoolean());
        assertTrue(response.get("autoCreatePr").asBoolean());
        assertTrue(response.get("createPrAsDraft").asBoolean());
        assertTrue(queued().isAutoCreatePr());
        assertTrue(queued().isCreatePrAsDraft());
    }

    /** Without the field no pull request is requested, and the response says so. */
    @Test(timeout = 15000)
    public void absentFieldRequestsNoPullRequest() throws IOException {
        JsonNode response = submitToWorkstream("");

        assertTrue(response.toString(), response.get("ok").asBoolean());
        assertFalse(response.get("autoCreatePr").asBoolean());
        assertFalse(queued().isAutoCreatePr());
    }

    /** Submits a planning prompt to the registered workstream, with {@code extraFields} appended. */
    private JsonNode submitToWorkstream(String extraFields) throws IOException {
        HttpURLConnection conn = openPost("/api/submit",
                "{\"prompt\":\"Plan the next task.\",\"workstreamId\":\"ws-autopr\"" + extraFields + "}");
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** The single coding-agent factory the endpoint queued. */
    private CodingAgentJobFactory queued() {
        assertEquals(1, server.queued.size());
        return (CodingAgentJobFactory) server.queued.get(0);
    }
}
