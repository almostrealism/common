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

package io.flowtree.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link GitHubProxyHandler#listOrganizationRunners}: it follows
 * pagination, and it reports a permission refusal as a failed listing rather
 * than an empty one.
 */
public class OrganizationRunnersTest extends TestSuiteBase {

    /** One runner object in the array shape GitHub returns. */
    private static String runnerJson(String name, String status, boolean busy, String... labels) {
        StringBuilder labelJson = new StringBuilder("[");
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) labelJson.append(',');
            labelJson.append("{\"name\":\"").append(labels[i]).append("\"}");
        }
        labelJson.append(']');
        return "{\"name\":\"" + name + "\",\"status\":\"" + status + "\",\"busy\":" + busy
                + ",\"labels\":" + labelJson + "}";
    }

    /** The listing collects runners across every page the paginator returns. */
    @Test(timeout = 30000)
    public void followsPagination() throws IOException {
        String page1 = "{\"total_count\":3,\"runners\":["
                + runnerJson("cuda-1", "online", false, "self-hosted", "linux", "ar-ci-cuda", "ar-build")
                + "," + runnerJson("cuda-2", "online", true, "self-hosted", "linux", "ar-ci-cuda")
                + "]}";
        String page2 = "{\"total_count\":3,\"runners\":["
                + runnerJson("macos-1", "online", false, "self-hosted", "macos", "ar-ci", "ar-build")
                + "]}";
        AtomicInteger pagesServed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            pagesServed.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            respond(exchange, 200, query != null && query.contains("page=2") ? page2 : page1);
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            RunnerListing listing = handler.listOrganizationRunners("almostrealism");

            assertTrue(listing.ok());
            assertEquals(3, listing.runners().size());
            assertEquals("both pages were fetched", 2, pagesServed.get());
            assertEquals("cuda-1", listing.runners().get(0).name());
            assertTrue(listing.runners().get(0).idle());
            assertFalse("cuda-2 is busy", listing.runners().get(1).idle());
            assertEquals(List.of("self-hosted", "macos", "ar-ci", "ar-build"),
                    listing.runners().get(2).labels());
        } finally {
            server.stop(0);
        }
    }

    /** A 403 is a failed listing whose reason names the missing permission, not an empty list. */
    @Test(timeout = 30000)
    public void permissionRefusalIsAFailedListing() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 403,
                "{\"message\":\"Resource not accessible by integration\"}"));
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            RunnerListing listing = handler.listOrganizationRunners("almostrealism");

            assertFalse("a 403 is not a successful listing", listing.ok());
            assertTrue(listing.runners().isEmpty());
            assertTrue("the reason names the permission",
                    listing.error().contains("Self-hosted runners"));
        } finally {
            server.stop(0);
        }
    }

    /** With no token configured, nothing is requested and the listing fails with a clear reason. */
    @Test(timeout = 10000)
    public void missingTokenFailsWithoutARequest() {
        GitHubProxyHandler handler = new GitHubProxyHandler(Map.of(), "http://127.0.0.1:1");
        RunnerListing listing = handler.listOrganizationRunners("almostrealism");
        assertFalse(listing.ok());
        assertTrue(listing.error().contains("no GitHub token"));
    }

    /** Writes a JSON body with the given status. */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
