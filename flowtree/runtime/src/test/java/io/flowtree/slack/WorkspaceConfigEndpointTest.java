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
import java.util.LinkedHashMap;
import java.util.Map;

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
    private WorkstreamConfig.SlackWorkspaceEntry workspaceEntry;
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
        workspaceEntry = new WorkstreamConfig.SlackWorkspaceEntry();
        workspaceEntry.setWorkspaceId(WORKSPACE_ID);
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
        endpoint.setSlackWorkspaceLookup(config::findSlackWorkspace);
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
    public void testUpdateRunnersReplacesEntry() throws Exception {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put("primary", "claude");
        workspaceEntry.setRunners(seed);

        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"runners\":{\"deduplication\":\"opencode\"}}");
        assertTrue(response.get("ok").asBoolean());
        assertEquals("opencode", workspaceEntry.getRunners().get("deduplication"));
        assertFalse("primary mapping must be replaced, not merged",
                workspaceEntry.getRunners().containsKey("primary"));
    }

    @Test(timeout = 10000)
    public void testUpdateRunnersDefaultKey() throws Exception {
        JsonNode response = postJson("/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"runners\":{\"default\":\"opencode\"}}");
        assertTrue(response.get("ok").asBoolean());
        assertEquals("opencode", workspaceEntry.getDefaultRunner());
    }

    @Test(timeout = 10000)
    public void testUpdateUnknownWorkspaceReturns404() throws Exception {
        HttpURLConnection conn = openPost(
                "/api/workspaces/T-unknown/config",
                "{\"name\":\"x\"}");
        assertEquals(404, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue(response.get("error").asText().contains("Unknown Slack workspace"));
    }

    @Test(timeout = 10000)
    public void testUpdateRejectsUnknownPhase() throws Exception {
        HttpURLConnection conn = openPost(
                "/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"runners\":{\"not-a-phase\":\"claude\"}}");
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue(response.get("error").asText().contains("Unknown phase"));
    }

    @Test(timeout = 10000)
    public void testUpdateRejectsUnknownRunner() throws Exception {
        HttpURLConnection conn = openPost(
                "/api/workspaces/" + WORKSPACE_ID + "/config",
                "{\"runners\":{\"primary\":\"not-a-runner\"}}");
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
                + "\"runners\":{\"default\":\"opencode\",\"primary\":\"opencode\"}}");
        assertTrue(response.get("ok").asBoolean());

        // Reload the file to confirm persistence rather than relying on the
        // shared in-memory reference. Note saveToYaml uses NON_EMPTY so
        // legacy single-element list fields may be omitted; we only check
        // the fields we set.
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(configFile);
        WorkstreamConfig.SlackWorkspaceEntry reloadedEntry =
                reloaded.findSlackWorkspace(WORKSPACE_ID);
        assertNotNull(reloadedEntry);
        assertEquals("Persisted Name", reloadedEntry.getName());
        assertEquals("C-persisted", reloadedEntry.getDefaultChannel());
        assertEquals("opencode", reloadedEntry.getDefaultRunner());
        assertEquals("opencode", reloadedEntry.getRunners().get("primary"));
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
                "{\"name\":\"Echoed\",\"runners\":{\"default\":\"opencode\","
                + "\"primary\":\"opencode\"}}");
        assertEquals(WORKSPACE_ID, response.get("workspaceId").asText());
        assertEquals("Echoed", response.get("name").asText());
        assertEquals("opencode", response.get("defaultRunner").asText());
        assertEquals("opencode", response.get("runners").get("primary").asText());
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
