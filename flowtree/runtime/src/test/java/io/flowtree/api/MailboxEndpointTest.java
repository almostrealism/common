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
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@code GET /api/workstreams/{id}/mailbox} in
 * {@link FlowTreeApiEndpoint}, and for the way {@code POST .../messages}
 * feeds it — the two halves of agent-to-agent conversation.
 *
 * <p>Each test spins up a real {@link FlowTreeApiEndpoint} on an ephemeral
 * port and drives it with real HTTP requests, following the same pattern as
 * {@link AgentsEndpointTest}.</p>
 */
public class MailboxEndpointTest extends TestSuiteBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Live API endpoint under test. */
    private FlowTreeApiEndpoint endpoint;
    /** Slack notifier for the workstream. */
    private SlackNotifier notifier;
    /** Listening port assigned by NanoHTTPD. */
    private int port;
    /** The workstream messages are exchanged on. */
    private Workstream workstream;

    /** Starts the API endpoint on an ephemeral port and registers a workstream. */
    @Before
    public void setUp() throws Exception {
        notifier = new SlackNotifier(null);
        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();

        workstream = new Workstream(null, "w-mailbox-test");
        workstream.setDefaultBranch("feature/mailbox-test");
        notifier.registerWorkstream(workstream);
    }

    /** Stops the endpoint. */
    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
    }

    /** Reading the mailbox of a workstream that was never registered is rejected. */
    @Test(timeout = 10000)
    public void testUnknownWorkstreamIsRejected() throws Exception {
        HttpURLConnection conn = openGet("/api/workstreams/does-not-exist/mailbox");
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
    }

    /** A message posted via the messages endpoint is readable from the mailbox. */
    @Test(timeout = 10000)
    public void testPostedMessageIsReadableFromTheMailbox() throws Exception {
        JsonNode posted = postMessage("{\"text\":\"host is ready\",\"sender\":\"job:a\"}");
        assertTrue(posted.get("ok").asBoolean());
        long seq = posted.get("seq").asLong();
        assertTrue("seq must be assigned", seq > 0);

        JsonNode delivery = getMailbox("since=0");
        assertTrue(delivery.get("ok").asBoolean());
        JsonNode messages = delivery.get("messages");
        assertEquals(1, messages.size());
        assertEquals("host is ready", messages.get(0).get("text").asText());
        assertEquals("job:a", messages.get(0).get("sender").asText());
        assertEquals(seq, messages.get(0).get("seq").asLong());
        assertEquals(seq, delivery.get("nextSince").asLong());
    }

    /** A sender's own messages are omitted by {@code exclude} but still advance the cursor. */
    @Test(timeout = 10000)
    public void testExcludeFiltersTheNamedSender() throws Exception {
        postMessage("{\"text\":\"mine\",\"sender\":\"job:a\"}");
        JsonNode fromB = postMessage("{\"text\":\"theirs\",\"sender\":\"job:b\"}");

        JsonNode delivery = getMailbox("since=0&exclude=job:a");
        JsonNode messages = delivery.get("messages");
        assertEquals(1, messages.size());
        assertEquals("theirs", messages.get(0).get("text").asText());
        assertEquals(fromB.get("seq").asLong(), delivery.get("nextSince").asLong());
    }

    /** With no {@code since} at all, the read starts from the current head rather than replaying history. */
    @Test(timeout = 10000)
    public void testAbsentSinceStartsAtTheHeadRatherThanReplayingHistory() throws Exception {
        postMessage("{\"text\":\"already sent\",\"sender\":\"job:a\"}");

        JsonNode delivery = getMailbox(null);
        assertTrue(delivery.get("ok").asBoolean());
        assertEquals(0, delivery.get("messages").size());
    }

    /**
     * A {@code since} value that is not a number falls back to the default
     * (start at the head) instead of failing the request. Query parameters
     * arrive as text from outside the process, so {@link RequestParameters#number}
     * treats an unparsable value the same as an absent one.
     */
    @Test(timeout = 10000)
    public void testNonNumericSinceFallsBackToTheDefault() throws Exception {
        postMessage("{\"text\":\"already sent\",\"sender\":\"job:a\"}");

        JsonNode delivery = getMailbox("since=not-a-number");
        assertTrue(delivery.get("ok").asBoolean());
        assertEquals(0, delivery.get("messages").size());
    }

    /** {@code since=0} replays the whole conversation, which is how a later job catches up. */
    @Test(timeout = 10000)
    public void testSinceZeroReplaysTheWholeConversation() throws Exception {
        postMessage("{\"text\":\"first\",\"sender\":\"job:a\"}");
        postMessage("{\"text\":\"second\",\"sender\":\"job:a\"}");

        JsonNode delivery = getMailbox("since=0");
        JsonNode messages = delivery.get("messages");
        assertEquals(2, messages.size());
        assertEquals("first", messages.get(0).get("text").asText());
        assertEquals("second", messages.get(1).get("text").asText());
    }

    /** A read that already has something to deliver returns promptly rather than waiting out {@code wait}. */
    @Test(timeout = 10000)
    public void testReadWithAvailableMessageDoesNotWaitOutTheTimeout() throws Exception {
        postMessage("{\"text\":\"already here\",\"sender\":\"job:a\"}");

        long start = System.currentTimeMillis();
        JsonNode delivery = getMailbox("since=0&wait=30");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, delivery.get("messages").size());
        assertTrue("a satisfiable read must not wait out its timeout: " + elapsed, elapsed < 10000);
    }

    /**
     * A reader blocked on an empty mailbox is woken by a message posted from
     * another request, proving the wait is wired end to end through the HTTP
     * layer rather than only at the {@code WorkstreamMailbox} unit level.
     */
    @Test(timeout = 10000)
    public void testBlockedReadIsWokenByALaterPost() throws Exception {
        Thread sender = new Thread(() -> {
            try {
                Thread.sleep(200);
                postMessage("{\"text\":\"late arrival\",\"sender\":\"job:b\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        long start = System.currentTimeMillis();
        sender.start();
        JsonNode delivery = getMailbox("since=0&wait=30");
        long elapsed = System.currentTimeMillis() - start;
        sender.join();

        assertEquals(1, delivery.get("messages").size());
        assertEquals("late arrival", delivery.get("messages").get(0).get("text").asText());
        assertTrue("read must be woken by the post rather than waiting the full timeout",
                elapsed < 30000);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** POSTs a message to the test workstream and parses the JSON response. */
    private JsonNode postMessage(String body) throws IOException {
        HttpURLConnection conn = openPost(
                "/api/workstreams/" + workstream.getWorkstreamId() + "/messages", body);
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(readBody(conn));
    }

    /**
     * GETs the test workstream's mailbox with the given raw query string and
     * parses the JSON response.
     *
     * @param query the query string (without a leading {@code ?}), or
     *              {@code null} to send no query string at all
     */
    private JsonNode getMailbox(String query) throws IOException {
        String path = "/api/workstreams/" + URLEncoder.encode(
                workstream.getWorkstreamId(), StandardCharsets.UTF_8) + "/mailbox";
        HttpURLConnection conn = openGet(query == null ? path : path + "?" + query);
        assertEquals(200, conn.getResponseCode());
        return MAPPER.readTree(readBody(conn));
    }

    /** Open a GET connection to the local endpoint. */
    private HttpURLConnection openGet(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("GET");
        return conn;
    }

    /** Open a POST connection with JSON body to the local endpoint. */
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

    /** Read the response body from an HTTP connection. */
    private static String readBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Read the error stream from an HTTP connection. */
    private static String readErrorBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
