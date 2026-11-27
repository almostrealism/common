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
 * HTTP-level tests for the resource limits of {@link FlowTreeApiEndpoint#handleSubmit}:
 * a submission's {@code maxTurns} and {@code maxBudgetUsd} are held to the
 * workstream's, the response reports what the job got and what was clamped,
 * and a silence budget reaches the queued factory.
 */
public class ResourceLimitSubmitTest extends FlowTreeApiSubmitTestBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Workstream every submission in this class targets. */
    private static final String WORKSTREAM_ID = "ws-limits";

    /** Server that captures the factories the endpoint queues. */
    private QueueCapturingServer server;

    /** Backs the endpoint with a capturing server and registers a workstream with known limits. */
    @Before
    public void attachServer() throws IOException {
        server = new QueueCapturingServer();
        endpoint.setServer(server);
        Workstream ws = new Workstream(WORKSTREAM_ID, "C_limits", "#limits");
        ws.setDefaultBranch("feature/limits");
        ws.setGitUserName("Test User");
        ws.setGitUserEmail("test@example.com");
        ws.setMaxTurns(200);
        ws.setMaxBudgetUsd(20.0);
        notifier.registerWorkstream(ws);
    }

    /** Over-limit caps are clamped on the queued factory and reported in the response. */
    @Test(timeout = 15000)
    public void overLimitCapsAreClamped() throws IOException {
        JsonNode response = submit(",\"maxTurns\":5000,\"maxBudgetUsd\":99.0");

        CodingAgentJobFactory factory = (CodingAgentJobFactory) server.queued.get(0);
        assertEquals(200, factory.getMaxTurns());
        assertEquals(20.0, factory.getMaxBudgetUsd(), 0.0);
        assertEquals(200, response.get("maxTurns").asInt());
        assertEquals(20.0, response.get("maxBudgetUsd").asDouble(), 0.0);
        assertEquals("200", response.get("clampedLimits").get("maxTurns").asText());
        assertEquals("20.0", response.get("clampedLimits").get("maxBudgetUsd").asText());
    }

    /** In-limit caps are honoured and nothing is reported as clamped. */
    @Test(timeout = 15000)
    public void inLimitCapsAreHonoured() throws IOException {
        JsonNode response = submit(",\"maxTurns\":80,\"maxBudgetUsd\":5.5");

        CodingAgentJobFactory factory = (CodingAgentJobFactory) server.queued.get(0);
        assertEquals(80, factory.getMaxTurns());
        assertEquals(5.5, factory.getMaxBudgetUsd(), 0.0);
        assertFalse(response.toString(), response.has("clampedLimits"));
    }

    /** A silence budget reaches the queued factory and is echoed; none is echoed when absent. */
    @Test(timeout = 15000)
    public void silenceBudgetIsQueuedAndEchoed() throws IOException {
        JsonNode response = submit(",\"inactivityTimeoutSeconds\":600");
        assertEquals(Integer.valueOf(600),
                ((CodingAgentJobFactory) server.queued.get(0)).getInactivityTimeoutSeconds());
        assertEquals(600, response.get("inactivityTimeoutSeconds").asInt());

        assertFalse(submit("").has("inactivityTimeoutSeconds"));
    }

    /** A silence budget that is not positive is refused with a 400 and nothing is queued. */
    @Test(timeout = 15000)
    public void nonPositiveSilenceBudgetIsRefused() throws IOException {
        HttpURLConnection conn = post(",\"inactivityTimeoutSeconds\":0");
        assertEquals(400, conn.getResponseCode());
        assertTrue(readErrorBody(conn).contains("inactivityTimeoutSeconds"));
        assertTrue("nothing may be queued for a refused submission", server.queued.isEmpty());
    }

    /**
     * Submits to the registered workstream and parses a successful response.
     *
     * @param extraFields JSON members to append, each with a leading comma
     * @return the response body
     * @throws IOException if the request cannot be sent
     */
    private JsonNode submit(String extraFields) throws IOException {
        HttpURLConnection conn = post(extraFields);
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
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
                "{\"prompt\":\"Do the work.\",\"workstreamId\":\"" + WORKSTREAM_ID + "\"" + extraFields + "}");
    }
}
