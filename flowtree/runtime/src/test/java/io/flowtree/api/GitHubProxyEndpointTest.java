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

import fi.iki.elonen.NanoHTTPD;
import io.flowtree.slack.SlackNotifier;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Endpoint-level tests for the routing of {@code /api/github/proxy} in
 * {@link FlowTreeApiEndpoint#serve}.
 *
 * <p>{@code GitHubProxyHandlerTest} drives the proxy handler directly, so it
 * cannot notice the endpoint failing to route a method to the handler. These
 * tests start a real endpoint on an ephemeral port and send real HTTP requests.
 * No request reaches GitHub: each one stops at a refusal the handler (or the
 * endpoint's body reader) makes before forwarding, and the text of that refusal
 * shows which code path answered.</p>
 */
public class GitHubProxyEndpointTest extends TestSuiteBase {

    /** A proxied pull-request path whose org is resolved from the URL. */
    private static final String PROXY_URI =
            "/api/github/proxy?url=/repos/acme/demo/pulls/7";

    /** Live API endpoint under test. */
    private FlowTreeApiEndpoint endpoint;

    /** Listening port assigned by NanoHTTPD. */
    private int port;

    /** Starts the API endpoint on an ephemeral port. */
    @Before
    public void setUp() throws Exception {
        endpoint = new FlowTreeApiEndpoint(0, new SlackNotifier(null));
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();
    }

    /** Stops the endpoint. */
    @After
    public void tearDown() {
        if (endpoint != null) endpoint.stop();
    }

    /**
     * A PATCH to the proxy reaches {@code GitHubProxyHandler}: with no token
     * configured for the org, the handler's token refusal is returned rather
     * than the endpoint's {@code 404 Not found} fallback.
     */
    @Test(timeout = 15000)
    public void patchIsRoutedToTheProxyHandler() throws Exception {
        HttpResponse<String> response = send("PATCH", "{\"title\":\"t\"}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body(),
                response.body().contains("No GitHub token configured for org 'acme'"));
    }

    /**
     * A method the proxy does not support is not routed to the handler, so the
     * endpoint answers with its fallback; this is the contrast that makes
     * {@link #patchIsRoutedToTheProxyHandler()} meaningful.
     */
    @Test(timeout = 15000)
    public void deleteIsNotRoutedToTheProxyHandler() throws Exception {
        HttpResponse<String> response = send("DELETE", null);
        assertEquals(404, response.statusCode());
        assertEquals("Not found", response.body());
    }

    /**
     * With a token configured, the endpoint reads a PATCH body through its
     * {@link RequestBodyReader}: an over-limit {@code Content-Length} is refused
     * with a 400 before any body is read or anything is forwarded to GitHub.
     */
    @Test(timeout = 15000)
    public void patchBodyIsReadThroughTheEndpointBodyReader() throws Exception {
        endpoint.setGithubOrgTokens(Map.of("acme", "test-token"));
        long declared = RequestBodyReader.MAX_BODY_BYTES + 1L;
        String response = sendRaw("PATCH " + PROXY_URI + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + declared + "\r\n"
                + "Connection: close\r\n\r\n");
        assertTrue(response, response.startsWith("HTTP/1.1 400"));
        assertTrue(response, response.contains("Invalid request body: Content-Length "
                + declared + " is outside the accepted range"));
    }

    /**
     * Sends a request to {@link #PROXY_URI} with the given method.
     *
     * @param method the HTTP method
     * @param body   the JSON body, or {@code null} for none
     * @return the response
     */
    private HttpResponse<String> send(String method, String body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + PROXY_URI))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        return HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Writes a raw HTTP request and returns the whole response, so a request
     * can declare a {@code Content-Length} its body does not carry.
     *
     * @param request the request head (and body, if any)
     * @return the raw response text
     */
    private String sendRaw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
