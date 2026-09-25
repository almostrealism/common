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

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Shared scaffolding for HTTP-level tests against a live {@link FlowTreeApiEndpoint} bound to an
 * ephemeral port, with no workstream registered and no peer connected -- the shape every
 * submission-validation test needs, since the checks under test all run before workstream
 * resolution or peer connectivity.
 */
public abstract class FlowTreeApiSubmitTestBase extends TestSuiteBase {

    /** Live API endpoint under test. */
    protected FlowTreeApiEndpoint endpoint;
    /** Listening port assigned by NanoHTTPD. */
    protected int port;

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

    /** Open a POST connection with a JSON body to the local endpoint. */
    protected HttpURLConnection openPost(String path, String body) throws IOException {
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

    /** Read the error stream from an HTTP connection. */
    protected static String readErrorBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
