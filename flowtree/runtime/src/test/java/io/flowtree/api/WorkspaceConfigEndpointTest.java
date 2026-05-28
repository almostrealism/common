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
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;
import io.flowtree.slack.SlackListener;
import io.flowtree.slack.SlackNotifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the {@code POST /api/workspaces/{id}/config}
 * endpoint, exercising {@link WorkspaceConfigHandler} through a live
 * NanoHTTPD endpoint.
 */
public class WorkspaceConfigEndpointTest extends TestSuiteBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Workspace ID used for tests; matches the entry created in setUp(). */
    private static final String WORKSPACE_ID = "T1234567890";

    /** The configured workspace entry under test; mutated by the endpoint. */
    private WorkstreamConfig.WorkspaceEntry workspaceEntry;
    /** Loaded config used to back the workspace lookup and persistence. */
    private WorkstreamConfig config;
    /** YAML file backing the listener's persistence; tests verify content lands here. */
    private File configFile;
    /** Live API endpoint under test. */
    private FlowTreeApiEndpoint endpoint;
    /** Slack listener wired into the endpoint so persistConfig() reaches disk. */
    private SlackListener listener;
    /** Listening port assigned by NanoHTTPD. */
    private int port;

    @Before
    public void setUp() throws Exception {
        config = new WorkstreamConfig();
        workspaceEntry = new WorkstreamConfig.WorkspaceEntry();
        workspaceEntry.setId(WORKSPACE_ID);
        workspaceEntry.setSlackTeamId(WORKSPACE_ID);
        workspaceEntry.setName("Initial Name");
        workspaceEntry.setDefaultChannel("C-initial");
        config.getSlackWorkspaces().add(workspaceEntry);

        configFile = File.createTempFile("workspace-config-test", ".yaml");
        configFile.deleteOnExit();
        config.saveToYaml(configFile);

        SlackNotifier notifier = new SlackNotifier(null);
        listener = new SlackListener(notifier);
        listener.setWorkstreamConfig(config, configFile);

        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.setWorkspaceLookup(config::findSlackWorkspace);
        endpoint.setWorkspaceRenameHook(config::renameWorkspace);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();
    }

    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
        if (configFile != null && configFile.exists()) configFile.delete();
    }

    @Test(timeout = 10000)
    public void testUpdateNameAndDefaultChannel() throws Exception {
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"name\":\"Acme Inc\",\"defaultChannel\":\"C9999\"}");
        assertTrue(response.get("ok").asBoolean());
        assertEquals("Acme Inc", workspaceEntry.getName());
        assertEquals("C9999", workspaceEntry.getDefaultChannel());
    }

    @Test(timeout = 10000)
    public void testRejectsLegacyRunnersField() throws Exception {
        HttpURLConnection conn = openPost("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"runners\":{\"deduplication\":\"opencode\"}}");
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue("error must name the removed runners field and the replacement",
                response.get("error").asText().contains("runners")
                        && response.get("error").asText().contains("phaseConfigs"));
    }

    @Test(timeout = 10000)
    public void testRejectsLegacyDefaultRunnerField() throws Exception {
        HttpURLConnection conn = openPost("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"defaultRunner\":\"opencode\"}");
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue("error must name the removed defaultRunner field",
                response.get("error").asText().contains("defaultRunner"));
    }

    @Test(timeout = 10000)
    public void testUpdateUnknownWorkspaceReturns404() throws Exception {
        HttpURLConnection conn = openPost(
                "/api/workspaces/T-unknown/config",
                "{\"name\":\"x\"}");
        assertEquals(404, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue(response.get("error").asText().contains("Unknown workspace"));
    }

    @Test(timeout = 10000)
    public void testRejectsLegacyModelAndEffortFields() throws Exception {
        for (String field : new String[] {"model", "effort"}) {
            HttpURLConnection conn = openPost(
                    "/api/workspaces/" + WORKSPACE_ID + "/config",
                    "{\"" + field + "\":\"x\"}");
            assertEquals("legacy " + field + " must be rejected with 400",
                    400, conn.getResponseCode());
            JsonNode response = MAPPER.readTree(readErrorBody(conn));
            assertFalse(response.get("ok").asBoolean());
            assertTrue("error must name the removed " + field + " field",
                    response.get("error").asText().contains(field));
        }
    }

    @Test(timeout = 10000)
    public void testUpdateRejectsUnknownRunner() throws Exception {
        HttpURLConnection conn = openPost(
                "/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"phaseConfigs\":{\"primary\":{\"runner\":\"not-a-runner\"}}}");
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue(response.get("error").asText().contains("Unknown runner"));
    }

    @Test(timeout = 10000)
    public void testEmptyBodyLeavesEntryUnchanged() throws Exception {
        String initialName = workspaceEntry.getName();
        String initialChannel = workspaceEntry.getDefaultChannel();
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config", "{}");
        assertTrue(response.get("ok").asBoolean());
        assertEquals(initialName, workspaceEntry.getName());
        assertEquals(initialChannel, workspaceEntry.getDefaultChannel());
    }

    @Test(timeout = 10000)
    public void testUpdatePersistsToDiskAndSurvivesReload() throws Exception {
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"name\":\"Persisted Name\",\"defaultChannel\":\"C-persisted\","
                + "\"defaultPhaseConfig\":{\"runner\":\"opencode\"},"
                + "\"phaseConfigs\":{\"primary\":{\"runner\":\"opencode\"}}}");
        assertTrue(response.get("ok").asBoolean());

        // Reload the file to confirm persistence rather than relying on the
        // shared in-memory reference. The per-phase shape (not the removed
        // legacy runner fields) is what survives the round-trip.
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(configFile);
        WorkstreamConfig.WorkspaceEntry reloadedEntry =
                reloaded.findSlackWorkspace(WORKSPACE_ID);
        assertNotNull(reloadedEntry);
        assertEquals("Persisted Name", reloadedEntry.getName());
        assertEquals("C-persisted", reloadedEntry.getDefaultChannel());
        assertEquals("opencode", reloadedEntry.getDefaultPhaseConfig().runner());
        assertEquals("opencode", reloadedEntry.getPhaseConfigs().get("primary").runner());
    }

    @Test(timeout = 10000)
    public void testCredentialFieldsAreNotSettable() throws Exception {
        workspaceEntry.setBotToken("xoxb-original");
        workspaceEntry.setTokensFile("/etc/secrets.json");
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"botToken\":\"xoxb-evil\",\"tokensFile\":\"/etc/evil.json\","
                + "\"appToken\":\"xapp-evil\",\"name\":\"OK\"}");
        assertTrue(response.get("ok").asBoolean());
        // The name field updates (allowed); credential fields are ignored.
        assertEquals("OK", workspaceEntry.getName());
        assertEquals("xoxb-original", workspaceEntry.getBotToken());
        assertEquals("/etc/secrets.json", workspaceEntry.getTokensFile());
        assertNull(workspaceEntry.getAppToken());
    }

    @Test(timeout = 10000)
    public void testResponseEchoesUpdatedFields() throws Exception {
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"name\":\"Echoed\",\"defaultPhaseConfig\":{\"runner\":\"opencode\"},"
                + "\"phaseConfigs\":{\"primary\":{\"runner\":\"opencode\"}}}");
        assertEquals(WORKSPACE_ID, response.get("workspaceId").asText());
        assertEquals("Echoed", response.get("name").asText());
        assertEquals("opencode", response.get("defaultPhaseConfig").get("runner").asText());
        assertEquals("opencode", response.get("phaseConfigs").get("primary").get("runner").asText());
    }

    @Test(timeout = 10000)
    public void testRenameWorkspaceViaNewId() throws Exception {
        // Seed a workstream that references the workspace by its current ID.
        Workstream ws = new Workstream("ws-1", "C-x", "#x");
        ws.setWorkspaceId(WORKSPACE_ID);
        WorkstreamConfig.WorkstreamEntry wsEntry =
                new WorkstreamConfig.WorkstreamEntry();
        wsEntry.setChannelId("C-x");
        wsEntry.setChannelName("#x");
        wsEntry.setWorkspaceId(WORKSPACE_ID);
        wsEntry.setDefaultBranch("main");
        config.getWorkstreams().add(wsEntry);

        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"newId\":\"almostrealism\"}");
        assertTrue(response.get("ok").asBoolean());
        assertEquals("almostrealism", response.get("workspaceId").asText());

        // Workspace ID changed; referencing workstreams updated.
        assertNotNull(config.findWorkspace("almostrealism"));
        assertNull(config.findWorkspace(WORKSPACE_ID));
        assertEquals("almostrealism",
                config.getWorkstreams().get(0).getWorkspaceId());
        // Slack team binding survives the rename.
        assertEquals(WORKSPACE_ID,
                config.findWorkspace("almostrealism").getSlackTeamId());
    }

    @Test(timeout = 10000)
    public void testSlackTeamIdEmptyClearsConnection() throws Exception {
        assertEquals(WORKSPACE_ID, workspaceEntry.getSlackTeamId());
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"slackTeamId\":\"\"}");
        assertTrue(response.get("ok").asBoolean());
        assertNull(workspaceEntry.getSlackTeamId());
    }

    @Test(timeout = 10000)
    public void testSlackTeamIdNonEmptyRebinds() throws Exception {
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"slackTeamId\":\"TZZZZ\"}");
        assertTrue(response.get("ok").asBoolean());
        assertEquals("TZZZZ", workspaceEntry.getSlackTeamId());
    }

    @Test(timeout = 10000)
    public void testPhaseConfigsPersistToDiskAndSurviveReload() throws Exception {
        // Exercise the full round-trip: POST defaultPhaseConfig + phaseConfigs,
        // confirm the live in-memory entry has them, then re-load the YAML
        // file from disk and confirm the new-shape fields survived.
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"defaultPhaseConfig\":{\"runner\":\"claude\","
                + "\"model\":\"claude-opus-4-7\",\"effort\":\"high\"},"
                + "\"phaseConfigs\":{\"review\":{\"runner\":\"claude\","
                + "\"model\":\"claude-haiku-4-5-20251001\"}}}");
        assertTrue(response.get("ok").asBoolean());
        // In-memory state.
        assertNotNull(workspaceEntry.getDefaultPhaseConfig());
        assertEquals("claude", workspaceEntry.getDefaultPhaseConfig().runner());
        assertEquals("claude-opus-4-7", workspaceEntry.getDefaultPhaseConfig().model());
        assertEquals("high", workspaceEntry.getDefaultPhaseConfig().effort());
        assertNotNull(workspaceEntry.getPhaseConfigs().get("review"));
        assertEquals("claude-haiku-4-5-20251001",
                workspaceEntry.getPhaseConfigs().get("review").model());

        // Reload from disk: this is the actual persistence check.  The
        // gap-fix sessions only verified in-memory state, which would pass
        // even if persistConfig() dropped the new fields on the floor.
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(configFile);
        WorkstreamConfig.WorkspaceEntry reloadedEntry =
                reloaded.findSlackWorkspace(WORKSPACE_ID);
        assertNotNull(reloadedEntry);
        assertNotNull("defaultPhaseConfig must survive YAML round-trip",
                reloadedEntry.getDefaultPhaseConfig());
        assertEquals("claude", reloadedEntry.getDefaultPhaseConfig().runner());
        assertEquals("claude-opus-4-7", reloadedEntry.getDefaultPhaseConfig().model());
        assertEquals("high", reloadedEntry.getDefaultPhaseConfig().effort());
        assertNotNull("phaseConfigs[review] must survive YAML round-trip",
                reloadedEntry.getPhaseConfigs().get("review"));
        assertEquals("claude",
                reloadedEntry.getPhaseConfigs().get("review").runner());
        assertEquals("claude-haiku-4-5-20251001",
                reloadedEntry.getPhaseConfigs().get("review").model());
    }

    @Test(timeout = 10000)
    public void testResponseEchoesPhaseConfigFields() throws Exception {
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"defaultPhaseConfig\":{\"runner\":\"claude\",\"effort\":\"high\"},"
                + "\"phaseConfigs\":{\"review\":{\"runner\":\"claude\","
                + "\"model\":\"claude-haiku-4-5-20251001\"}}}");
        assertTrue(response.get("ok").asBoolean());
        // The response must echo the new-shape fields so operators can see
        // what they just set (gap from the legacy-only echo behaviour).
        assertNotNull("Response must echo defaultPhaseConfig",
                response.get("defaultPhaseConfig"));
        assertEquals("claude",
                response.get("defaultPhaseConfig").get("runner").asText());
        assertEquals("high",
                response.get("defaultPhaseConfig").get("effort").asText());
        assertNotNull("Response must echo phaseConfigs",
                response.get("phaseConfigs"));
        assertEquals("claude-haiku-4-5-20251001",
                response.get("phaseConfigs").get("review").get("model").asText());
    }

    @Test(timeout = 10000)
    public void testPhaseConfigModelOnlyOverrideIsAcceptedAtWorkspaceLevel() throws Exception {
        // Reviewer feedback (PR #238): the workspace endpoint must accept a
        // model-only override that pairs with a runner inherited from
        // workstream/job. Pre-fix this returned 400 because applyToWorkspace
        // forced the missing runner to resolve to "claude" and then
        // validated the (resolved-runner, requested-model) pair eagerly.
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"phaseConfigs\":{\"review\":{\"model\":\"qwen3-coder-30b\"}}}");
        assertTrue(response.get("ok").asBoolean());
        assertEquals("qwen3-coder-30b",
                workspaceEntry.getPhaseConfigs().get("review").model());
        assertNull("runner stays null so the resolver can fall through",
                workspaceEntry.getPhaseConfigs().get("review").runner());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private JsonNode postJson(String path, String body) throws IOException {
        HttpURLConnection conn = openPost(path, body);
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(readBody(conn));
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
