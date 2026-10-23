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
import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.workstream.Workstream;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HTTP-level tests for the {@code enforceChanges} submission fields of
 * {@link FlowTreeApiEndpoint#handleSubmit}. The re-run cap
 * ({@code maxEnforceChangesRetries}) must reach the queued job factory and be
 * echoed in the response. That includes a cap of 0, which must not be mistaken
 * for an absent field. A negative cap is refused.
 */
public class EnforceChangesSubmitTest extends FlowTreeApiSubmitTestBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Workstream every submission in this class targets. */
    private static final String WORKSTREAM_ID = "ws-enforce";

    /** Server that captures the factories the endpoint queues. */
    private QueueCapturingServer server;

    /** Backs the endpoint with a capturing server and registers the target workstream. */
    @Before
    public void attachServer() throws IOException {
        server = new QueueCapturingServer();
        endpoint.setServer(server);
        Workstream ws = new Workstream(WORKSTREAM_ID, "C_enforce", "#enforce");
        ws.setDefaultBranch("qa/retro-1");
        ws.setGitUserName("Test User");
        ws.setGitUserEmail("test@example.com");
        notifier.registerWorkstream(ws);
    }

    /** A cap of 0 reaches the queued factory and the response alongside {@code enforceChanges}. */
    @Test(timeout = 15000)
    public void capOfZeroIsQueuedAndEchoed() throws IOException {
        HttpURLConnection conn = post(",\"enforceChanges\":true,\"maxEnforceChangesRetries\":0");
        assertEquals(200, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        assertTrue(response.toString(), response.get("enforceChanges").asBoolean());
        assertEquals(0, response.get("maxEnforceChangesRetries").asInt());
        CodingAgentJobFactory factory = (CodingAgentJobFactory) server.queued.get(0);
        assertTrue(factory.isEnforceChanges());
        assertEquals(0, factory.getMaxEnforceChangesRetries());
    }

    /** Without the cap field the default applies, and the response reports it. */
    @Test(timeout = 15000)
    public void absentCapKeepsTheDefault() throws IOException {
        HttpURLConnection conn = post(",\"enforceChanges\":true");
        assertEquals(200, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        assertEquals(CodingAgentJob.DEFAULT_MAX_RULE_RETRIES, response.get("maxEnforceChangesRetries").asInt());
        assertEquals(CodingAgentJob.DEFAULT_MAX_RULE_RETRIES,
                ((CodingAgentJobFactory) server.queued.get(0)).getMaxEnforceChangesRetries());
    }

    /** A negative cap is refused with a 400 and nothing is queued. */
    @Test(timeout = 15000)
    public void negativeCapIsRefused() throws IOException {
        HttpURLConnection conn = post(",\"enforceChanges\":true,\"maxEnforceChangesRetries\":-1");
        assertEquals(400, conn.getResponseCode());
        assertTrue(readErrorBody(conn).contains("maxEnforceChangesRetries"));
        assertTrue("nothing may be queued for a refused submission", server.queued.isEmpty());
    }

    /**
     * Posts a submission to the registered workstream with {@code extraFields}
     * appended to the body.
     *
     * @param extraFields JSON members to append, each with a leading comma
     * @return the open connection
     * @throws IOException if the request cannot be sent
     */
    private HttpURLConnection post(String extraFields) throws IOException {
        return openPost("/api/submit",
                "{\"prompt\":\"Write the report.\",\"workstreamId\":\"" + WORKSTREAM_ID + "\"" + extraFields + "}");
    }
}
