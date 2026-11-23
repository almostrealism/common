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

import com.sun.net.httpserver.HttpServer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
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
 * Tests {@link RunnerMonitor}: it caches the listing across a burst of requests,
 * maps a preference order of fleet labels onto the known fleets, and skips an
 * unknown label rather than failing.
 */
public class RunnerMonitorTest extends TestSuiteBase {

    /** A listing with one idle CUDA runner and one idle macOS runner, both carrying ar-build. */
    private static final String LISTING = "{\"total_count\":2,\"runners\":["
            + "{\"name\":\"cuda-1\",\"status\":\"online\",\"busy\":false,\"labels\":["
            + "{\"name\":\"self-hosted\"},{\"name\":\"linux\"},{\"name\":\"ar-ci-cuda\"},{\"name\":\"ar-build\"}]},"
            + "{\"name\":\"macos-1\",\"status\":\"online\",\"busy\":false,\"labels\":["
            + "{\"name\":\"self-hosted\"},{\"name\":\"macos\"},{\"name\":\"ar-ci\"},{\"name\":\"ar-build\"}]}"
            + "]}";

    /** Builds a monitor over a stub server, counting the requests it receives. */
    private static RunnerMonitor monitorOver(HttpServer server, long cacheMillis, AtomicInteger requests) {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = LISTING.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        GitHubProxyHandler handler = new GitHubProxyHandler(Map.of("almostrealism", "tok"),
                "http://127.0.0.1:" + server.getAddress().getPort());
        return new RunnerMonitor(handler, cacheMillis, RunnerMonitor.DEFAULT_FLEETS,
                RunnerMonitor.DEFAULT_PREFERENCE);
    }

    /** A burst of requests within the cache window hits GitHub once. */
    @Test(timeout = 30000)
    public void cachesTheListingAcrossABurst() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        RunnerMonitor monitor = monitorOver(server, 60_000L, requests);
        try {
            RunnerSelection first = monitor.select("almostrealism", "ar-build", null);
            RunnerSelection second = monitor.select("almostrealism", "ar-build", null);

            assertEquals("ar-ci-cuda", first.fleet());
            assertEquals("ar-ci-cuda", second.fleet());
            assertEquals("the listing was fetched once and reused", 1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /** An explicit preference order is honoured, and an unknown fleet label is skipped, not fatal. */
    @Test(timeout = 30000)
    public void honoursAPreferenceOverrideAndSkipsUnknownLabels() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        RunnerMonitor monitor = monitorOver(server, 60_000L, requests);
        try {
            RunnerSelection selection = monitor.select("almostrealism", "ar-build",
                    List.of("ar-ci-gaudi", "macos", "ar-ci-cuda"));

            assertFalse(selection.hosted());
            Assert.assertEquals("the unknown ar-ci-gaudi is skipped; macOS is next and has an idle runner",
                    "macos", selection.fleet());
            assertEquals(List.of("self-hosted", "macos", "ar-build"), selection.runsOn());
        } finally {
            server.stop(0);
        }
    }

    /** The default preference order lists macOS last. */
    @Test(timeout = 10000)
    public void defaultPreferenceEndsWithMacos() {
        assertEquals("macos", RunnerMonitor.DEFAULT_PREFERENCE.get(
                RunnerMonitor.DEFAULT_PREFERENCE.size() - 1));
        assertEquals("ar-ci-cuda", RunnerMonitor.DEFAULT_PREFERENCE.get(0));
        assertTrue(RunnerMonitor.splitLabels("ar-ci, ar-build ,").contains("ar-build"));
    }
}
