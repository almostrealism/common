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
import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the workstream archive / unarchive / delete
 * endpoints in {@link FlowTreeApiEndpoint} (handled by
 * {@link WorkstreamLifecycleHandler}).
 *
 * <p>Each test spins up a NanoHTTPD endpoint on an ephemeral port and
 * makes real HTTP requests against the controller. The Slack channel
 * archive side effect is exercised in {@link #archiveSucceedsWithoutSlackClient()}
 * which deliberately uses a notifier without a bot token so the
 * Slack-API path is skipped.</p>
 */
public class WorkstreamLifecycleEndpointTest extends TestSuiteBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowTreeApiEndpoint endpoint;
    private SlackNotifier notifier;
    private int port;

    /** Spins up the endpoint on an ephemeral port. */
    @Before
    public void setUp() throws Exception {
        notifier = new SlackNotifier(null);
        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();
    }

    /** Stops the endpoint. */
    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
    }

    /** Archive sets the flag and is reflected in subsequent list responses. */
    @Test(timeout = 10000)
    public void archiveHidesWorkstreamFromDefaultList() throws Exception {
        Workstream ws = registerBare("feature/keep", "keep");
        Workstream gone = registerBare("feature/gone", "gone");

        // Default list includes both
        JsonNode listed = getJson("/api/workstreams");
        assertEquals(2, listed.size());

        JsonNode archive = postJson("/api/workstreams/"
                + gone.getWorkstreamId() + "/archive", "{}");
        assertTrue(archive.get("ok").asBoolean());
        assertEquals(gone.getWorkstreamId(),
                archive.get("workstreamId").asText());
        assertNotNull(archive.get("archivedAt"));

        // Default list now excludes the archived one
        JsonNode after = getJson("/api/workstreams");
        assertEquals(1, after.size());
        assertEquals(ws.getWorkstreamId(),
                after.get(0).get("workstreamId").asText());

        // includeArchived=true brings it back with archived: true
        JsonNode withArchived = getJson("/api/workstreams?includeArchived=true");
        assertEquals(2, withArchived.size());
        boolean foundArchived = false;
        for (JsonNode entry : withArchived) {
            if (gone.getWorkstreamId().equals(entry.get("workstreamId").asText())) {
                assertTrue("archived flag must be present",
                        entry.has("archived") && entry.get("archived").asBoolean());
                foundArchived = true;
            }
        }
        assertTrue("Archived workstream must appear when includeArchived=true",
                foundArchived);
    }

    /** Archive without a Slack client returns slackChannelArchived=false. */
    @Test(timeout = 10000)
    public void archiveSucceedsWithoutSlackClient() throws Exception {
        Workstream ws = registerBare("feature/no-client", "no-client");
        JsonNode result = postJson("/api/workstreams/"
                + ws.getWorkstreamId() + "/archive", "{}");
        assertTrue(result.get("ok").asBoolean());
        // The notifier was constructed without a bot token, so even though
        // archive_slack_channel defaults to true the Slack call returns
        // an "no Slack client configured" error path.
        assertFalse(result.get("slackChannelArchived").asBoolean());
    }

    /** Archive bypasses the Slack call when archiveSlackChannel=false. */
    @Test(timeout = 10000)
    public void archiveSkipsSlackWhenOptedOut() throws Exception {
        Workstream ws = registerBare("feature/skip-slack", "skip-slack");
        JsonNode result = postJson("/api/workstreams/"
                        + ws.getWorkstreamId() + "/archive",
                "{\"archiveSlackChannel\":false}");
        assertTrue(result.get("ok").asBoolean());
        assertFalse(result.get("slackChannelArchived").asBoolean());
        // No slackChannelArchiveError when we didn't try
        assertFalse(result.has("slackChannelArchiveError"));
    }

    /** Archive is rejected when a job is still active (STARTED). */
    @Test(timeout = 10000)
    public void archiveRejectedWhenActiveJobs() throws Exception {
        Workstream ws = registerBare("feature/busy", "busy");
        notifier.onJobSubmitted(ws.getWorkstreamId(),
                JobCompletionEvent.started("job-busy-1", "in-flight work"));
        HttpURLConnection conn = openPost("/api/workstreams/"
                + ws.getWorkstreamId() + "/archive", "{}");
        assertEquals(400, conn.getResponseCode());
        JsonNode err = MAPPER.readTree(readErrorBody(conn));
        assertFalse(err.get("ok").asBoolean());
        String msg = err.get("error").asText();
        assertTrue("Error must mention active job count: " + msg,
                msg.contains("1 active job"));
        assertTrue("Error must mention the active job ID: " + msg,
                msg.contains("job-busy-1"));
        assertFalse("Workstream must remain unarchived after rejection",
                notifier.getWorkstream(ws.getWorkstreamId()).isArchived());
    }

    /** Unarchive restores visibility in the default list. */
    @Test(timeout = 10000)
    public void unarchiveRestoresVisibility() throws Exception {
        Workstream ws = registerBare("feature/back", "back");
        postJson("/api/workstreams/" + ws.getWorkstreamId() + "/archive", "{}");
        assertTrue(notifier.getWorkstream(ws.getWorkstreamId()).isArchived());

        JsonNode unArchive = postJson("/api/workstreams/"
                + ws.getWorkstreamId() + "/unarchive", "{}");
        assertTrue(unArchive.get("ok").asBoolean());
        assertFalse(notifier.getWorkstream(ws.getWorkstreamId()).isArchived());

        JsonNode listed = getJson("/api/workstreams");
        assertEquals(1, listed.size());
    }

    /** Delete is rejected when the workstream is not archived and force is absent. */
    @Test(timeout = 10000)
    public void deleteRejectsNonArchivedWithoutForce() throws Exception {
        Workstream ws = registerBare("feature/live", "live");
        HttpURLConnection conn = openPost("/api/workstreams/"
                + ws.getWorkstreamId() + "/delete", "{}");
        assertEquals(400, conn.getResponseCode());
        JsonNode err = MAPPER.readTree(readErrorBody(conn));
        assertFalse(err.get("ok").asBoolean());
        assertTrue("Error must steer caller toward archive-first",
                err.get("error").asText().contains("not archived"));
        assertNotNull("Workstream must still exist after refused delete",
                notifier.getWorkstream(ws.getWorkstreamId()));
    }

    /** Delete with force=true bypasses the archive-first check. */
    @Test(timeout = 10000)
    public void deleteWithForceBypassesArchiveCheck() throws Exception {
        Workstream ws = registerBare("feature/force", "force");
        JsonNode result = postJson("/api/workstreams/"
                + ws.getWorkstreamId() + "/delete", "{\"force\":true}");
        assertTrue(result.get("ok").asBoolean());
        // Workstream is gone from the notifier
        assertNull("Workstream must be removed from the notifier",
                notifier.getWorkstream(ws.getWorkstreamId()));
    }

    /** Delete is rejected on active jobs regardless of force=true. */
    @Test(timeout = 10000)
    public void deleteRejectedWhenActiveJobsEvenWithForce() throws Exception {
        Workstream ws = registerBare("feature/busy-delete", "busy-delete");
        notifier.onJobSubmitted(ws.getWorkstreamId(),
                JobCompletionEvent.started("job-d-1", "in-flight"));
        HttpURLConnection conn = openPost("/api/workstreams/"
                + ws.getWorkstreamId() + "/delete", "{\"force\":true}");
        assertEquals(400, conn.getResponseCode());
        JsonNode err = MAPPER.readTree(readErrorBody(conn));
        assertTrue(err.get("error").asText().contains("active job"));
        assertNotNull("Workstream must still exist after refused delete",
                notifier.getWorkstream(ws.getWorkstreamId()));
    }

    /** Archive then delete succeeds and removes the workstream entirely. */
    @Test(timeout = 10000)
    public void archiveThenDeleteRemovesWorkstream() throws Exception {
        Workstream ws = registerBare("feature/twostep", "twostep");
        postJson("/api/workstreams/" + ws.getWorkstreamId() + "/archive", "{}");
        JsonNode result = postJson("/api/workstreams/"
                + ws.getWorkstreamId() + "/delete", "{}");
        assertTrue(result.get("ok").asBoolean());
        assertNull("Workstream must be removed",
                notifier.getWorkstream(ws.getWorkstreamId()));
        assertEquals(0, getJson("/api/workstreams?includeArchived=true").size());
    }

    /** Archive on a missing workstream returns 400. */
    @Test(timeout = 10000)
    public void archiveUnknownReturns400() throws IOException {
        HttpURLConnection conn = openPost(
                "/api/workstreams/no-such-id/archive", "{}");
        assertEquals(400, conn.getResponseCode());
        JsonNode err = MAPPER.readTree(readErrorBody(conn));
        assertTrue(err.get("error").asText().contains("Unknown workstream"));
    }

    private Workstream registerBare(String branch, String channelName) {
        Workstream ws = new Workstream(null, channelName);
        ws.setDefaultBranch(branch);
        notifier.registerWorkstream(ws);
        return ws;
    }

    private JsonNode getJson(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(
                new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private JsonNode postJson(String path, String body) throws IOException {
        HttpURLConnection conn = openPost(path, body);
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(
                new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
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

    private static String readErrorBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
