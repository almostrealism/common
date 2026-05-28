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
import fi.iki.elonen.NanoHTTPD;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import io.flowtree.workstream.Workstream;
import io.flowtree.slack.SlackNotifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the {@code GET /api/agents} endpoint in
 * {@link FlowTreeApiEndpoint}.
 *
 * <p>Each test spins up a NanoHTTPD endpoint on an ephemeral port and makes
 * a real HTTP GET request to {@code /api/agents}, then verifies the response
 * shape, runner list contents, phase list size, and presence of capability flags.</p>
 */
public class AgentsEndpointTest extends TestSuiteBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowTreeApiEndpoint endpoint;
    private SlackNotifier notifier;
    private int port;

    @Before
    public void setUp() throws Exception {
        notifier = new SlackNotifier(null);
        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();
    }

    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
    }

    @Test(timeout = 10000)
    public void testResponseIsValidJson() throws Exception {
        HttpURLConnection conn = openGet("/api/agents");
        assertEquals(200, conn.getResponseCode());
        assertTrue("Content-Type must start with application/json",
                conn.getContentType().startsWith("application/json"));
        String body = readBody(conn);
        assertNotNull("Response body must not be null", body);
        assertFalse("Response body must not be empty", body.isEmpty());
        JsonNode root = MAPPER.readTree(body);
        assertTrue("Response must be a JSON object", root.isObject());
    }

    @Test(timeout = 10000)
    public void testResponseContainsTopLevelFields() throws Exception {
        JsonNode root = getAgentsJson();
        assertTrue("Response must contain 'ok' field", root.has("ok"));
        assertTrue("'ok' must be true", root.get("ok").asBoolean());
        assertTrue("Response must contain 'runners' field", root.has("runners"));
        assertTrue("Response must contain 'phases' field", root.has("phases"));
        assertTrue("Response must contain 'models' field", root.has("models"));
        assertTrue("Response must contain 'defaultRunner' field", root.has("defaultRunner"));
    }

    @Test(timeout = 10000)
    public void testRunnersListContainsClaudeAndOpencode() throws Exception {
        JsonNode root = getAgentsJson();
        JsonNode runners = root.get("runners");
        assertTrue("runners must be an array", runners.isArray());
        assertTrue("runners must contain at least 2 entries", runners.size() >= 2);

        boolean foundClaude = false;
        boolean foundOpencode = false;
        for (JsonNode runner : runners) {
            String name = runner.get("name").asText();
            if (AgentRunnerRegistry.CLAUDE.equals(name)) foundClaude = true;
            if (AgentRunnerRegistry.OPENCODE.equals(name)) foundOpencode = true;
        }
        assertTrue("runners must contain 'claude'", foundClaude);
        assertTrue("runners must contain 'opencode'", foundOpencode);
    }

    @Test(timeout = 10000)
    public void testRunnersHaveCapabilityFlags() throws Exception {
        JsonNode root = getAgentsJson();
        for (JsonNode runner : root.get("runners")) {
            String name = runner.get("name").asText();
            assertTrue("Runner '" + name + "' must have 'capabilities' field",
                    runner.has("capabilities"));
            JsonNode caps = runner.get("capabilities");
            assertTrue("capabilities must be an object for runner " + name, caps.isObject());
            assertTrue("capabilities must have 'reportsCost' for runner " + name,
                    caps.has("reportsCost"));
            assertTrue("capabilities must have 'reportsTurns' for runner " + name,
                    caps.has("reportsTurns"));
            assertTrue("capabilities must have 'supportsEffortLevel' for runner " + name,
                    caps.has("supportsEffortLevel"));
            assertTrue("capabilities must have 'supportsMaxBudget' for runner " + name,
                    caps.has("supportsMaxBudget"));
            assertTrue("capabilities must have 'supportsMcpHttpTransport' for runner " + name,
                    caps.has("supportsMcpHttpTransport"));
            assertTrue("capabilities must have 'supportsMcpStdioTransport' for runner " + name,
                    caps.has("supportsMcpStdioTransport"));
            assertTrue("capabilities must have 'supportsPermissionDenialReporting' for runner " + name,
                    caps.has("supportsPermissionDenialReporting"));
            assertTrue("capabilities must have 'supportedModels' for runner " + name,
                    caps.has("supportedModels"));
            assertTrue("supportedModels must be an array for runner " + name,
                    caps.get("supportedModels").isArray());
        }
    }

    @Test(timeout = 10000)
    public void testPhaseListHasAllPhases() throws Exception {
        JsonNode root = getAgentsJson();
        JsonNode phases = root.get("phases");
        assertTrue("phases must be an array", phases.isArray());
        assertEquals("phases must contain exactly " + Phase.values().length + " entries",
                Phase.values().length, phases.size());
    }

    @Test(timeout = 10000)
    public void testPhaseEntriesHaveNameAndDescription() throws Exception {
        JsonNode root = getAgentsJson();
        for (JsonNode phase : root.get("phases")) {
            assertTrue("Each phase must have 'name'", phase.has("name"));
            assertTrue("Each phase must have 'description'", phase.has("description"));
            assertFalse("name must not be empty",
                    phase.get("name").asText().isEmpty());
            assertFalse("description must not be empty",
                    phase.get("description").asText().isEmpty());
        }
    }

    @Test(timeout = 10000)
    public void testPhaseNamesMatchEnum() throws Exception {
        JsonNode root = getAgentsJson();
        for (Phase phase : Phase.values()) {
            boolean found = false;
            for (JsonNode phaseNode : root.get("phases")) {
                if (phase.wireName().equals(phaseNode.get("name").asText())) {
                    found = true;
                    break;
                }
            }
            assertTrue("Phase '" + phase.wireName() + "' must appear in the phases list", found);
        }
    }

    @Test(timeout = 10000)
    public void testDefaultRunnerIsClaude() throws Exception {
        JsonNode root = getAgentsJson();
        assertEquals(AgentRunnerRegistry.CLAUDE, root.get("defaultRunner").asText());
    }

    @Test(timeout = 10000)
    public void testModelsListIsNonEmpty() throws Exception {
        JsonNode root = getAgentsJson();
        JsonNode models = root.get("models");
        assertTrue("models must be an array", models.isArray());
        assertTrue("models must be non-empty", models.size() > 0);
    }

    // ----------------------------------------------------------------
    // Register/update round-trip tests for per-phase configuration
    // ----------------------------------------------------------------

    @Test(timeout = 10000)
    public void testRegisterPersistsPerPhaseConfigs() throws Exception {
        String body = "{\"defaultBranch\":\"feature/register-runners\","
                + "\"channelName\":\"w-register-runners\","
                + "\"phaseConfigs\":{\"primary\":{\"runner\":\"opencode\"},"
                + "\"deduplication\":{\"runner\":\"opencode\"}}}";
        JsonNode response = postJson("/api/workstreams", body);
        assertTrue(response.get("ok").asBoolean());
        Workstream ws = lookupWorkstream(response.get("workstreamId").asText());
        PhaseConfigBundle bundle = ws.getPhaseConfigBundle();
        assertEquals("opencode", bundle.forPhase(Phase.PRIMARY).runner());
        assertEquals("opencode", bundle.forPhase(Phase.DEDUPLICATION).runner());
    }

    @Test(timeout = 10000)
    public void testRegisterPersistsDefaultViaDefaultPhaseConfig() throws Exception {
        String body = "{\"defaultBranch\":\"feature/register-default\","
                + "\"channelName\":\"w-register-default\","
                + "\"defaultPhaseConfig\":{\"runner\":\"opencode\"}}";
        JsonNode response = postJson("/api/workstreams", body);
        assertTrue(response.get("ok").asBoolean());
        Workstream ws = lookupWorkstream(response.get("workstreamId").asText());
        assertEquals("opencode", ws.getPhaseConfigBundle().defaultPhaseConfig().runner());
    }

    @Test(timeout = 10000)
    public void testRegisterRejectsLegacyRunnersField() throws Exception {
        String body = "{\"defaultBranch\":\"feature/register-legacy\","
                + "\"channelName\":\"w-register-legacy\","
                + "\"runners\":{\"primary\":\"opencode\"}}";
        HttpURLConnection conn = openPost("/api/workstreams", body);
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue("error must name the removed runners field and the replacement",
                response.get("error").asText().contains("runners")
                        && response.get("error").asText().contains("phaseConfigs"));
    }

    @Test(timeout = 10000)
    public void testRegisterRejectsUnknownRunner() throws Exception {
        String body = "{\"defaultBranch\":\"feature/register-bad-runner\","
                + "\"channelName\":\"w-register-bad-runner\","
                + "\"phaseConfigs\":{\"primary\":{\"runner\":\"not-a-runner\"}}}";
        HttpURLConnection conn = openPost("/api/workstreams", body);
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue(response.get("error").asText().contains("Unknown runner"));
    }

    @Test(timeout = 10000)
    public void testUpdateAppliesPhaseConfigs() throws Exception {
        Workstream ws = registerBareWorkstream("feature/update-runners", "w-update-runners");

        String body = "{\"defaultPhaseConfig\":{\"runner\":\"opencode\"},"
                + "\"phaseConfigs\":{\"deduplication\":{\"runner\":\"opencode\"}}}";
        JsonNode response = postJson("/api/workstreams/" + ws.getWorkstreamId() + "/update", body);
        assertTrue(response.get("ok").asBoolean());

        PhaseConfigBundle bundle = ws.getPhaseConfigBundle();
        assertEquals("opencode", bundle.defaultPhaseConfig().runner());
        assertEquals("opencode", bundle.forPhase(Phase.DEDUPLICATION).runner());
    }

    @Test(timeout = 10000)
    public void testUpdateRejectsLegacyModelField() throws Exception {
        Workstream ws = registerBareWorkstream("feature/update-skip", "w-update-skip");
        // The legacy job-level model param is rejected on the update endpoint.
        String body = "{\"model\":\"sonnet\"}";
        HttpURLConnection conn = openPost(
                "/api/workstreams/" + ws.getWorkstreamId() + "/update", body);
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue(response.get("error").asText().contains("model"));
    }

    @Test(timeout = 10000)
    public void testUpdateWithoutPhaseConfigLeavesConfigUntouched() throws Exception {
        Workstream ws = registerBareWorkstream("feature/update-keep", "w-update-keep");
        ws.setPhaseConfigBundle(PhaseConfigBundle.EMPTY.withDefaultRunner("opencode"));

        // Update touching only a non-config field — phase config must survive.
        String body = "{\"baseBranch\":\"develop\"}";
        JsonNode response = postJson("/api/workstreams/" + ws.getWorkstreamId() + "/update", body);
        assertTrue(response.get("ok").asBoolean());

        assertEquals("opencode", ws.getPhaseConfigBundle().defaultPhaseConfig().runner());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private JsonNode getAgentsJson() throws Exception {
        HttpURLConnection conn = openGet("/api/agents");
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(readBody(conn));
    }

    private JsonNode postJson(String path, String body) throws IOException {
        HttpURLConnection conn = openPost(path, body);
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(readBody(conn));
    }

    private Workstream lookupWorkstream(String workstreamId) {
        Workstream ws = notifier.getWorkstream(workstreamId);
        assertNotNull("Workstream " + workstreamId + " must be registered on the notifier", ws);
        return ws;
    }

    private Workstream registerBareWorkstream(String branch, String channelName) {
        Workstream ws = new Workstream(null, channelName);
        ws.setDefaultBranch(branch);
        notifier.registerWorkstream(ws);
        return ws;
    }

    private HttpURLConnection openGet(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("GET");
        return conn;
    }

    private HttpURLConnection openPost(String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readErrorBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
