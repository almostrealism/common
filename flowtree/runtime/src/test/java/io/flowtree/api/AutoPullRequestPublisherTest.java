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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowtree.github.GitHubProxyHandler;
import io.flowtree.jobs.DependentRepoPush;
import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link AutoPullRequestPublisher} against a fake GitHub API served on a
 * local {@link HttpServer}. The fake keeps an in-memory list of pull requests
 * and comments and serves the endpoints the companion-PR path exercises:
 * {@code GET /repos/{o}/{r}}, {@code GET .../compare/...},
 * {@code GET .../pulls?head=...}, {@code GET .../commits/{ref}},
 * {@code POST .../pulls}, and {@code POST .../issues/{n}/comments}.
 */
public class AutoPullRequestPublisherTest extends TestSuiteBase {

    /** JSON parser for request bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Clone URL of the primary repository. */
    private static final String PRIMARY_URL = "https://github.com/acme/app.git";

    /** Clone URL of the dependent repository. */
    private static final String DEPENDENT_URL = "https://github.com/widgets/lib.git";

    /** The fake GitHub API. */
    private FakeGitHub github;

    /** The handler under test's collaborator, pointed at the fake API. */
    private GitHubProxyHandler handler;

    /** The publisher under test. */
    private AutoPullRequestPublisher publisher;

    /** Starts the fake GitHub API and wires the publisher to it. */
    @Before
    public void setUp() throws IOException {
        github = new FakeGitHub();
        github.start();
        // Two orgs so token resolution is by org name, not the single-org default.
        handler = new GitHubProxyHandler(
                Map.of("acme", "tok", "widgets", "tok"), github.root());
        publisher = new AutoPullRequestPublisher(handler);
    }

    /** Stops the fake GitHub API. */
    @After
    public void tearDown() {
        if (github != null) github.stop();
    }

    /**
     * A push with {@code autoCreatePr} produces exactly one companion PR in the
     * dependent repo, based on its default branch, whose title and body come
     * from the commit message and whose body links the primary PR. The primary
     * PR receives one comment naming the companion.
     */
    @Test(timeout = 30000)
    public void companionPrOpenedAndLinkedBothWays() {
        github.messageByRef.put("dep123", "Move the helper into lib\n\nShared by both repos.");
        github.messageByRef.put("abc", "Use the moved helper\n\nPrimary change.");
        publisher.register("job-1", context(false));

        JobCompletionEvent event = eventWithPush("abc", "dep123");
        publisher.publish(event);

        assertNotNull("a primary PR must be opened", event.getPullRequestUrl());
        List<FakePr> companions = github.prsFor("widgets/lib");
        assertEquals("exactly one companion PR", 1, companions.size());
        FakePr companion = companions.get(0);
        assertEquals("master", companion.base);
        assertEquals("Move the helper into lib", companion.title);
        assertTrue("companion body must link the primary PR: " + companion.body,
                companion.body.contains(event.getPullRequestUrl()));
        assertEquals(List.of(companion.htmlUrl), event.getCompanionPullRequestUrls());

        List<FakeComment> comments = github.commentsFor("acme/app");
        assertEquals("the primary PR gets one reciprocal comment", 1, comments.size());
        assertTrue("the comment must name the companion: " + comments.get(0).body,
                comments.get(0).body.contains(companion.htmlUrl));
    }

    /** An event with no pushes makes no request to the dependent repo. */
    @Test(timeout = 30000)
    public void noDependentPushCreatesNothing() {
        publisher.register("job-1", context(false));

        JobCompletionEvent event = JobCompletionEvent.success("job-1", "Primary only")
                .withGitInfo("feature/x", "abc", null, null, true);
        publisher.publish(event);

        assertTrue("no request may touch the dependent repo",
                github.requests.stream().noneMatch(r -> r.contains("widgets/lib")));
        assertTrue(event.getCompanionPullRequestUrls().isEmpty());
    }

    /**
     * With an open PR already on the dependent branch, publishing opens no new
     * PR, adds no comment, and reports the existing PR's URL.
     */
    @Test(timeout = 30000)
    public void existingCompanionPrIsReused() {
        FakePr existing = github.seedOpenPr("widgets/lib", "feature/x");
        publisher.register("job-1", context(false));

        JobCompletionEvent event = eventWithPush("abc", "dep123");
        publisher.publish(event);

        assertTrue("no companion PR may be created",
                github.requests.stream().noneMatch(r -> r.equals("POST /repos/widgets/lib/pulls")));
        assertTrue("no reciprocal comment may be added", github.commentsFor("acme/app").isEmpty());
        assertEquals(List.of(existing.htmlUrl), event.getCompanionPullRequestUrls());
    }

    /** A branch that is not ahead of the default branch opens nothing. */
    @Test(timeout = 30000)
    public void branchEqualToDefaultCreatesNothing() {
        github.aheadBy = 0;
        publisher.register("job-1", context(false));

        JobCompletionEvent event = eventWithPush("abc", "dep123");
        publisher.publish(event);

        assertTrue("no companion PR may be created",
                github.requests.stream().noneMatch(r -> r.equals("POST /repos/widgets/lib/pulls")));
        assertTrue(event.getCompanionPullRequestUrls().isEmpty());
    }

    /**
     * Pushes with no {@code autoCreatePr} context make no {@code POST} requests
     * and leave the unreviewed commits recorded on the event.
     */
    @Test(timeout = 30000)
    public void withoutAutoCreatePrNothingIsOpened() {
        // No register(...) call: the job did not request a pull request.
        JobCompletionEvent event = eventWithPush("abc", "dep123");
        publisher.publish(event);

        assertTrue("no POST request may be made",
                github.requests.stream().noneMatch(r -> r.startsWith("POST ")));
        assertTrue(event.getCompanionPullRequestUrls().isEmpty());
        assertEquals("the unreviewed push is still recorded", 1, event.getDependentRepoPushes().size());
    }

    /**
     * An event that already carries a {@code pullRequestUrl} (the job detected
     * the primary PR) still gets its companion PR and the reciprocal comment.
     * Guards against nesting the companion step inside the primary-PR guard.
     */
    @Test(timeout = 30000)
    public void companionPrOpenedWhenPrimaryAlreadyExists() {
        github.messageByRef.put("dep123", "Move the helper into lib\n\nShared.");
        publisher.register("job-1", context(false));

        JobCompletionEvent event = eventWithPush("abc", "dep123")
                .withPullRequestUrl("https://github.com/acme/app/pull/9");
        publisher.publish(event);

        assertTrue("no new primary PR may be created",
                github.requests.stream().noneMatch(r -> r.equals("POST /repos/acme/app/pulls")));
        List<FakePr> companions = github.prsFor("widgets/lib");
        assertEquals(1, companions.size());
        assertEquals(List.of(companions.get(0).htmlUrl), event.getCompanionPullRequestUrls());
        assertEquals("https://github.com/acme/app/pull/9", event.getPullRequestUrl());

        List<FakeComment> comments = github.commentsFor("acme/app");
        assertEquals(1, comments.size());
        assertEquals(9, comments.get(0).number);
        assertTrue(comments.get(0).body.contains(companions.get(0).htmlUrl));
    }

    /** Builds the auto-PR context for the primary repo. */
    private AutoPrContext context(boolean draft) {
        return new AutoPrContext(PRIMARY_URL, "master", "acme", "Primary work", draft);
    }

    /** Builds a success event carrying one dependent-repo push. */
    private JobCompletionEvent eventWithPush(String primaryHash, String depHash) {
        return JobCompletionEvent.success("job-1", "Primary work")
                .withGitInfo("feature/x", primaryHash, null, null, true)
                .withDependentRepoPushes(List.of(
                        new DependentRepoPush(DEPENDENT_URL, "feature/x", depHash)));
    }

    /** A pull request as the fake stored it. */
    private static final class FakePr {
        /** The {@code owner/repo} this PR belongs to. */
        private final String ownerRepo;
        /** The PR number. */
        private final int number;
        /** The head branch. */
        private final String head;
        /** The base branch. */
        private final String base;
        /** The PR title. */
        private final String title;
        /** The PR body. */
        private final String body;
        /** Whether the PR is open. */
        private final boolean open;
        /** The PR's HTML URL. */
        private final String htmlUrl;

        /**
         * Stores one pull request.
         *
         * @param ownerRepo the {@code owner/repo}
         * @param number    the PR number
         * @param head      the head branch
         * @param base      the base branch
         * @param title     the PR title
         * @param body      the PR body
         * @param open      whether the PR is open
         * @param htmlUrl   the PR's HTML URL
         */
        private FakePr(String ownerRepo, int number, String head, String base,
                String title, String body, boolean open, String htmlUrl) {
            this.ownerRepo = ownerRepo;
            this.number = number;
            this.head = head;
            this.base = base;
            this.title = title;
            this.body = body;
            this.open = open;
            this.htmlUrl = htmlUrl;
        }
    }

    /** A comment as the fake stored it. */
    private static final class FakeComment {
        /** The {@code owner/repo} the commented PR belongs to. */
        private final String ownerRepo;
        /** The commented PR's number. */
        private final int number;
        /** The comment body. */
        private final String body;

        /**
         * Stores one comment.
         *
         * @param ownerRepo the commented PR's {@code owner/repo}
         * @param number    the commented PR's number
         * @param body      the comment body
         */
        private FakeComment(String ownerRepo, int number, String body) {
            this.ownerRepo = ownerRepo;
            this.number = number;
            this.body = body;
        }
    }

    /** An in-memory GitHub REST API sufficient for the companion-PR path. */
    private static final class FakeGitHub {
        /** The HTTP server. */
        private HttpServer server;
        /** Every request as {@code METHOD /path} (query stripped), in order. */
        private final List<String> requests = new ArrayList<>();
        /** Stored pull requests. */
        private final List<FakePr> prs = new ArrayList<>();
        /** Stored comments. */
        private final List<FakeComment> comments = new ArrayList<>();
        /** Commit messages by ref. */
        private final Map<String, String> messageByRef = new HashMap<>();
        /** The {@code ahead_by} every compare reports. */
        private int aheadBy = 3;
        /** The {@code default_branch} every repo reports. */
        private String defaultBranch = "master";
        /** Assigns PR numbers. */
        private final AtomicInteger nextNumber = new AtomicInteger(1);

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

        /**
         * Seeds an already-open pull request so reuse can be exercised.
         *
         * @param ownerRepo the {@code owner/repo}
         * @param head      the head branch
         * @return the seeded pull request
         */
        private FakePr seedOpenPr(String ownerRepo, String head) {
            int n = nextNumber.getAndIncrement();
            FakePr pr = new FakePr(ownerRepo, n, head, defaultBranch, "existing", "existing",
                    true, root() + "/" + ownerRepo + "/pull/" + n);
            prs.add(pr);
            return pr;
        }

        /**
         * Returns the pull requests stored for a repo.
         *
         * @param ownerRepo the {@code owner/repo}
         * @return the matching pull requests
         */
        private List<FakePr> prsFor(String ownerRepo) {
            List<FakePr> result = new ArrayList<>();
            for (FakePr pr : prs) {
                if (pr.ownerRepo.equals(ownerRepo)) result.add(pr);
            }
            return result;
        }

        /**
         * Returns the comments stored for a repo.
         *
         * @param ownerRepo the {@code owner/repo}
         * @return the matching comments
         */
        private List<FakeComment> commentsFor(String ownerRepo) {
            List<FakeComment> result = new ArrayList<>();
            for (FakeComment c : comments) {
                if (c.ownerRepo.equals(ownerRepo)) result.add(c);
            }
            return result;
        }

        /** Records the request and writes the routed reply. */
        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            requests.add(method + " " + path);
            String reply = route(exchange, method, path);
            int status = reply == null ? 404 : (method.equals("POST") ? 201 : 200);
            byte[] bytes = (reply == null ? "{}" : reply).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        /**
         * Routes a request to the right in-memory response, or {@code null} for
         * an unknown path.
         *
         * @param exchange the HTTP exchange
         * @param method   the request method
         * @param path     the request path
         * @return the JSON reply, or {@code null} for a 404
         */
        private String route(HttpExchange exchange, String method, String path) throws IOException {
            // /repos/{o}/{r}/...
            String rest = path.substring("/repos/".length());
            String[] seg = rest.split("/");
            String ownerRepo = seg[0] + "/" + seg[1];

            if (seg.length == 2 && method.equals("GET")) {
                return "{\"default_branch\":\"" + defaultBranch + "\"}";
            }
            if (seg.length >= 3 && seg[2].equals("compare")) {
                return "{\"ahead_by\":" + aheadBy + "}";
            }
            if (seg.length >= 3 && seg[2].equals("commits")) {
                String ref = seg[3];
                String message = messageByRef.getOrDefault(ref, "Default subject\n\nDefault body.");
                return "{\"commit\":{\"message\":" + quote(message) + "}}";
            }
            if (seg.length >= 3 && seg[2].equals("pulls") && method.equals("GET")) {
                return pullsListing(ownerRepo, exchange.getRequestURI().getQuery());
            }
            if (seg.length >= 3 && seg[2].equals("pulls") && method.equals("POST")) {
                return createPull(ownerRepo, body(exchange));
            }
            if (seg.length >= 4 && seg[2].equals("issues") && seg[3].matches("\\d+")) {
                comments.add(new FakeComment(ownerRepo, Integer.parseInt(seg[3]),
                        MAPPER.readTree(body(exchange)).path("body").asText("")));
                return "{\"id\":1}";
            }
            return null;
        }

        /**
         * Serves the pull-request listing for a repo, filtered by the
         * {@code head=} query parameter when present.
         *
         * @param ownerRepo the {@code owner/repo}
         * @param query     the request query string, or {@code null}
         * @return the JSON array of matching pull requests
         */
        private String pullsListing(String ownerRepo, String query) {
            String head = null;
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith("head=")) {
                        String value = URLDecoder.decode(
                                p.substring("head=".length()), StandardCharsets.UTF_8);
                        int colon = value.indexOf(':');
                        head = colon >= 0 ? value.substring(colon + 1) : value;
                    }
                }
            }
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (FakePr pr : prs) {
                if (!pr.ownerRepo.equals(ownerRepo)) continue;
                if (head != null && !head.equals(pr.head)) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"number\":").append(pr.number)
                        .append(",\"state\":\"").append(pr.open ? "open" : "closed")
                        .append("\",\"html_url\":").append(quote(pr.htmlUrl))
                        .append(",\"head\":{\"ref\":").append(quote(pr.head)).append("}}");
            }
            return sb.append("]").toString();
        }

        /**
         * Stores a newly-created pull request and returns its creation response.
         *
         * @param ownerRepo the {@code owner/repo}
         * @param body      the create-PR request body
         * @return the JSON creation response
         */
        private String createPull(String ownerRepo, String body) throws IOException {
            JsonNode node = MAPPER.readTree(body);
            int n = nextNumber.getAndIncrement();
            String url = root() + "/" + ownerRepo + "/pull/" + n;
            prs.add(new FakePr(ownerRepo, n, node.path("head").asText(""),
                    node.path("base").asText(""), node.path("title").asText(""),
                    node.path("body").asText(""), true, url));
            return "{\"number\":" + n + ",\"html_url\":" + quote(url) + "}";
        }

        /** Reads the request body as a UTF-8 string. */
        private static String body(HttpExchange exchange) throws IOException {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        /** JSON-quotes a string value. */
        private static String quote(String value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
