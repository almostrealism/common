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

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link PushedToolsSelfTest}. Uses an in-process
 * {@link HttpServer} to mimic the controller's
 * {@code /api/tools/{name}} endpoint and confirm the self-test passes
 * when the server returns 200 with a body, and aborts startup when it
 * returns 404 or an empty 200.
 */
public class PushedToolsSelfTestTest {

    /**
     * Starts an in-process HTTP stub that maps tool names to response codes and bodies.
     */
    private static HttpServer startStub(Map<String, Integer> codes, Map<String, String> bodies)
            throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/tools/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/api/tools/".length());
            int code = codes.getOrDefault(name, 404);
            String body = bodies.getOrDefault(name, "");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
            exchange.close();
        });
        http.start();
        return http;
    }

    /**
     * Verifies that the self-test passes when the mandatory tool endpoint returns HTTP 200 with a non-empty body.
     */
    @Test(timeout = 30000)
    public void passesWhenMandatoryToolReturns200WithBody() throws Exception {
        Map<String, Integer> codes = new HashMap<>();
        codes.put("ar-secrets", 200);
        Map<String, String> bodies = new HashMap<>();
        bodies.put("ar-secrets", "#!/usr/bin/env python3\nprint('hi')\n");
        HttpServer http = startStub(codes, bodies);
        try {
            new PushedToolsSelfTest().run(http.getAddress().getPort());
        } finally {
            http.stop(0);
        }
    }

    /**
     * Verifies that the self-test throws {@link IllegalStateException} naming the failing tool when the endpoint returns 404.
     */
    @Test(timeout = 30000)
    public void failsWhenMandatoryToolReturns404() throws Exception {
        HttpServer http = startStub(new HashMap<>(), new HashMap<>());
        try {
            new PushedToolsSelfTest().run(http.getAddress().getPort());
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("Message must name the failing tool: " + expected.getMessage(),
                expected.getMessage().contains("ar-secrets"));
        } finally {
            http.stop(0);
        }
    }

    /**
     * Verifies that the self-test throws {@link IllegalStateException} mentioning an empty body when the endpoint returns 200 with no content.
     */
    @Test(timeout = 30000)
    public void failsWhenMandatoryToolReturnsEmptyBody() throws Exception {
        Map<String, Integer> codes = new HashMap<>();
        codes.put("ar-secrets", 200);
        Map<String, String> bodies = new HashMap<>();
        bodies.put("ar-secrets", "");
        HttpServer http = startStub(codes, bodies);
        try {
            new PushedToolsSelfTest().run(http.getAddress().getPort());
            fail("Expected IllegalStateException on empty body");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("Message should mention empty body: " + expected.getMessage(),
                expected.getMessage().toLowerCase().contains("empty"));
        } finally {
            http.stop(0);
        }
    }

    /**
     * Verifies that the self-test throws {@link IllegalStateException} when called with port zero (unassigned port).
     */
    @Test(timeout = 30000)
    public void failsWhenPortIsZero() {
        try {
            new PushedToolsSelfTest().run(0);
            fail("Expected IllegalStateException when port is not assigned");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
