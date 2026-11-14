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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link CiTrigger}: spec parsing, and starting CI against a fake GitHub
 * API served on a local {@link HttpServer}. The fake serves a branch head
 * commit, the deployments listing and creation, and a workflow's runs listing
 * and dispatch, recording every write so a test can assert exactly what was
 * started.
 */
public class CiTriggerTest extends TestSuiteBase {

    /** JSON parser for request bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The fake GitHub API. */
    private FakeGitHub github;

    /** The handler under test's collaborator, pointed at the fake API. */
    private GitHubProxyHandler handler;

    /** Starts the fake GitHub API. */
    @Before
    public void setUp() throws IOException {
        github = new FakeGitHub();
        github.start();
        handler = new GitHubProxyHandler(Map.of("acme", "tok"), github.root());
    }

    /** Stops the fake GitHub API. */
    @After
    public void tearDown() {
        if (github != null) github.stop();
    }

    /** Both spec forms parse, round-trip through {@link CiTrigger#spec()}, and blank means none. */
    @Test(timeout = 10000)
    public void specsParseAndRoundTrip() {
        CiTrigger deployment = CiTrigger.parse("deployment:test");
        assertEquals(CiTrigger.Kind.DEPLOYMENT, deployment.kind());
        assertEquals("test", deployment.target());
        assertEquals("deployment:test", deployment.spec());

        CiTrigger workflow = CiTrigger.parse(" workflow:tests.yaml ");
        assertEquals(CiTrigger.Kind.WORKFLOW, workflow.kind());
        assertEquals("tests.yaml", workflow.target());
        assertEquals(workflow, CiTrigger.parse(workflow.spec()));

        assertNull(CiTrigger.parse(null));
        assertNull(CiTrigger.parse("  "));
    }

    /** A spec with an unknown kind or no target is refused rather than ignored. */
    @Test(timeout = 10000)
    public void malformedSpecsAreRefused() {
        for (String spec : List.of("deploy:test", "deployment:", "deployment", ":test", "test")) {
            try {
                CiTrigger.parse(spec);
                fail("expected '" + spec + "' to be refused");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains(spec));
            }
        }
    }

    /**
     * A deployment trigger deploys the branch (not the SHA), with the default
     * branch never merged in and no status contexts required, and does nothing
     * the second time for the same head commit.
     */
    @Test(timeout = 30000)
    public void deploymentStartsOncePerCommit() {
        CiTrigger trigger = CiTrigger.parse("deployment:test");

        assertEquals(CiTrigger.Outcome.STARTED, trigger.start(handler, "acme/app", "feature/x", "tok"));
        assertEquals(1, github.deployments.size());
        JsonNode created = github.deployments.get(0);
        assertEquals("feature/x", created.path("ref").asText());
        assertEquals("test", created.path("environment").asText());
        assertFalse(created.path("auto_merge").asBoolean(true));
        assertTrue(created.path("required_contexts").isArray());
        assertEquals(0, created.path("required_contexts").size());

        assertEquals(CiTrigger.Outcome.ALREADY_STARTED, trigger.start(handler, "acme/app", "feature/x", "tok"));
        assertEquals("no second deployment for the same commit", 1, github.deployments.size());
    }

    /** A new head commit is a new commit to test, even with an earlier deployment on the branch. */
    @Test(timeout = 30000)
    public void deploymentStartsAgainForANewHead() {
        CiTrigger trigger = CiTrigger.parse("deployment:test");
        trigger.start(handler, "acme/app", "feature/x", "tok");
        github.headSha = "def456";

        assertEquals(CiTrigger.Outcome.STARTED, trigger.start(handler, "acme/app", "feature/x", "tok"));
        assertEquals(2, github.deployments.size());
    }

    /** A deployment to another environment does not count as this trigger having started. */
    @Test(timeout = 30000)
    public void deploymentToAnotherEnvironmentDoesNotCount() {
        CiTrigger.parse("deployment:staging").start(handler, "acme/app", "feature/x", "tok");

        assertEquals(CiTrigger.Outcome.STARTED,
                CiTrigger.parse("deployment:test").start(handler, "acme/app", "feature/x", "tok"));
        assertEquals(2, github.deployments.size());
    }

    /** A workflow trigger dispatches on the branch once, and not when the commit already has a run. */
    @Test(timeout = 30000)
    public void workflowDispatchesOncePerCommit() {
        CiTrigger trigger = CiTrigger.parse("workflow:tests.yaml");

        assertEquals(CiTrigger.Outcome.STARTED, trigger.start(handler, "acme/app", "feature/x", "tok"));
        assertEquals(List.of("tests.yaml@feature/x"), github.dispatches);

        github.runsForHead = 1;
        assertEquals(CiTrigger.Outcome.ALREADY_STARTED, trigger.start(handler, "acme/app", "feature/x", "tok"));
        assertEquals(1, github.dispatches.size());
    }

    /** When the branch head cannot be read nothing is started. */
    @Test(timeout = 30000)
    public void unreadableHeadStartsNothing() {
        github.headSha = null;

        assertEquals(CiTrigger.Outcome.FAILED,
                CiTrigger.parse("deployment:test").start(handler, "acme/app", "gone", "tok"));
        assertTrue(github.deployments.isEmpty());
    }

    /**
     * A minimal fake of the GitHub REST endpoints {@link CiTrigger} uses.
     * Deployments are stored with the SHA the branch head had when they were
     * created, which is what the real API records.
     */
    private static final class FakeGitHub {
        /** The HTTP server. */
        private HttpServer server;
        /** The branch head every commit lookup reports; {@code null} answers 404. */
        private String headSha = "abc123";
        /** Created deployments' request bodies, each with the head SHA added as {@code sha}. */
        private final List<JsonNode> deployments = new ArrayList<>();
        /** Dispatches as {@code file@ref}. */
        private final List<String> dispatches = new ArrayList<>();
        /** The {@code total_count} every workflow-runs listing reports. */
        private int runsForHead;

        /** Binds the server to a free loopback port and starts it. */
        private void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        /** Stops the server. */
        private void stop() {
            server.stop(0);
        }

        /** Returns the API root the handler should be pointed at. */
        private String root() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** Routes one request and writes its reply. */
        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            int status = 404;
            String reply = "{}";
            if (path.contains("/commits/") && headSha != null) {
                status = 200;
                reply = "{\"sha\":\"" + headSha + "\"}";
            } else if (path.endsWith("/deployments") && method.equals("GET")) {
                status = 200;
                reply = deploymentsMatching(query);
            } else if (path.endsWith("/deployments") && method.equals("POST")) {
                ObjectNode body = (ObjectNode) MAPPER.readTree(read(exchange));
                body.put("sha", headSha);
                deployments.add(body);
                status = 201;
                reply = "{\"id\":" + deployments.size() + "}";
            } else if (path.endsWith("/runs")) {
                status = 200;
                reply = "{\"total_count\":" + runsForHead + "}";
            } else if (path.endsWith("/dispatches")) {
                String file = path.split("/actions/workflows/")[1].split("/")[0];
                dispatches.add(file + "@" + MAPPER.readTree(read(exchange)).path("ref").asText());
                status = 204;
                reply = null;
            }
            if (reply == null) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        /** Lists the stored deployments matching the {@code sha} and {@code environment} query. */
        private String deploymentsMatching(String query) {
            String sha = null;
            String environment = null;
            for (String p : query.split("&")) {
                if (p.startsWith("sha=")) sha = p.substring(4);
                if (p.startsWith("environment=")) environment = p.substring(12);
            }
            List<String> matching = new ArrayList<>();
            for (JsonNode d : deployments) {
                if (d.path("sha").asText().equals(sha) && d.path("environment").asText().equals(environment)) {
                    matching.add("{\"sha\":\"" + sha + "\"}");
                }
            }
            return "[" + String.join(",", matching) + "]";
        }

        /** Reads a request body. */
        private static String read(HttpExchange exchange) throws IOException {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
