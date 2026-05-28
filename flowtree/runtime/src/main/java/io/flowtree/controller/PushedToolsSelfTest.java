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

package io.flowtree.controller;

import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import io.flowtree.api.FlowTreeApiEndpoint;

/**
 * Startup self-test for the controller's pushed-tools serving path.
 *
 * <p>After {@link FlowTreeController#registerPushedTools()} has populated
 * the {@link FlowTreeApiEndpoint}'s tool registry, this self-test makes a
 * loopback HTTP request to each mandatory tool's
 * {@code /api/tools/{name}} URL and asserts the response is HTTP 200 with
 * a non-empty body. The check exists because the prior failure mode was
 * silent: a controller image without the bundled source file would come
 * up "healthy" but emit an empty {@code pushedToolsConfig}, and agents
 * would launch without their expected MCP servers. Failing the self-test
 * here causes container startup to abort so deploys break loudly rather
 * than quietly degrading.</p>
 *
 * @author Michael Murray
 */
final class PushedToolsSelfTest implements ConsoleFeatures {

    /** Tools that MUST be served. Failure for any of these aborts startup. */
    private static final String[] MANDATORY = { "ar-secrets" };

    /**
     * Runs the self-test against the controller's already-bound API port.
     *
     * @param port the controller API endpoint's listening port; must be
     *             positive (a non-positive value is treated as a fatal
     *             misconfiguration)
     * @throws IllegalStateException if the port is non-positive, or if
     *                               any mandatory tool returns anything
     *                               other than HTTP 200 with a non-empty
     *                               body
     */
    void run(int port) {
        if (port <= 0) {
            throw new IllegalStateException(
                "API endpoint has no listening port; cannot self-test pushed tools.");
        }
        for (String name : MANDATORY) {
            verifyServed(name, port);
        }
    }

    /**
     * Performs a single loopback fetch and validates the response.
     *
     * @param name tool server name
     * @param port endpoint listening port
     */
    private void verifyServed(String name, int port) {
        String url = "http://127.0.0.1:" + port + "/api/tools/" + name;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException(
                    "Pushed-tool self-test failed for " + name + ": HTTP "
                    + code + " from " + url
                    + " — agents will not receive this tool.");
            }
            int len;
            try (InputStream is = conn.getInputStream()) {
                len = is.readAllBytes().length;
            }
            if (len <= 0) {
                throw new IllegalStateException(
                    "Pushed-tool self-test for " + name + " returned an empty body.");
            }
            log("Pushed-tool self-test: " + name + " OK ("
                + len + " bytes from " + url + ")");
        } catch (IOException e) {
            throw new IllegalStateException(
                "Pushed-tool self-test failed for " + name + ": " + e.getMessage(), e);
        }
    }
}
