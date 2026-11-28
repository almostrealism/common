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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.CookieHandler;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.api.RequestBodyReader;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the derivation of a pull request title and body from the commit message
 * of the change being proposed.
 *
 * <p>These exist because a recurring job submits every one of its runs with the
 * same description, so describing its pull requests by that description made
 * them indistinguishable. The commit message is written after the work is done
 * and does distinguish them.</p>
 */
public class GitHubProxyHandlerTest extends TestSuiteBase {

    /** A commit message in the conventional subject-blank-line-body shape. */
    private static final String FULL_MESSAGE =
            "fix(collect): correct stride on a reshaped view\n"
            + "\n"
            + "A reshape that changed rank left the old stride in place, so reads\n"
            + "past the first row addressed the wrong element.\n";

    /** The subject line becomes the title. */
    @Test(timeout = 10000)
    public void titleIsTheSubjectLine() {
        assertEquals("fix(collect): correct stride on a reshaped view",
                GitHubProxyHandler.pullRequestTitle(FULL_MESSAGE, "fallback"));
    }

    /** Everything after the subject becomes the body, without the leading blank line. */
    @Test(timeout = 10000)
    public void bodyIsEverythingAfterTheSubject() {
        String body = GitHubProxyHandler.pullRequestBody(FULL_MESSAGE, "fallback");
        assertTrue(body.startsWith("A reshape that changed rank"));
        assertTrue(body.endsWith("addressed the wrong element."));
    }

    /** A message with no commit available falls back to the job description. */
    @Test(timeout = 10000)
    public void nullMessageFallsBackToTheDescription() {
        assertEquals("fallback", GitHubProxyHandler.pullRequestTitle(null, "fallback"));
        assertEquals("fallback", GitHubProxyHandler.pullRequestBody(null, "fallback"));
    }

    /** A subject-only message leaves the body to the job description. */
    @Test(timeout = 10000)
    public void subjectOnlyMessageFallsBackForTheBody() {
        assertEquals("docs: describe the dispatch table",
                GitHubProxyHandler.pullRequestTitle("docs: describe the dispatch table", "fallback"));
        assertEquals("fallback",
                GitHubProxyHandler.pullRequestBody("docs: describe the dispatch table", "fallback"));
    }

    /** Leading blank lines do not become the title. */
    @Test(timeout = 10000)
    public void leadingBlankLinesAreSkipped() {
        String message = "\n\n  perf: reuse the compiled kernel\n\nDetail.\n";
        assertEquals("perf: reuse the compiled kernel",
                GitHubProxyHandler.pullRequestTitle(message, "fallback"));
        assertEquals("Detail.", GitHubProxyHandler.pullRequestBody(message, "fallback"));
    }

    /** A blank message is treated as no message at all. */
    @Test(timeout = 10000)
    public void blankMessageFallsBack() {
        assertEquals("fallback", GitHubProxyHandler.pullRequestTitle("   \n\n  ", "fallback"));
        assertEquals("fallback", GitHubProxyHandler.pullRequestBody("   \n\n  ", "fallback"));
    }

    /** An over-long subject is truncated to what GitHub accepts. */
    @Test(timeout = 10000)
    public void overLongSubjectIsTruncated() {
        String subject = "x".repeat(400);
        String title = GitHubProxyHandler.pullRequestTitle(subject, "fallback");
        assertEquals(256, title.length());
        assertTrue(title.endsWith("..."));
    }

    /** A subject exactly at the limit is left alone. */
    @Test(timeout = 10000)
    public void subjectAtTheLimitIsNotTruncated() {
        String subject = "y".repeat(256);
        assertEquals(subject, GitHubProxyHandler.pullRequestTitle(subject, "fallback"));
    }

    /**
     * A branch name occupies one path segment, so its slash is encoded. Every
     * branch this runs for is of this shape, and an unencoded slash addresses a
     * different endpoint.
     */
    @Test(timeout = 10000)
    public void branchRefIsPercentEncoded() {
        assertEquals("https://api.github.com/repos/almostrealism/common/commits/qa%2Fdefect-20260901-120000",
                GitHubProxyHandler.commitApiUrl("almostrealism/common", "qa/defect-20260901-120000"));
    }

    /** A commit SHA passes through the encoding unchanged. */
    @Test(timeout = 10000)
    public void shaRefIsUnchanged() {
        assertEquals("https://api.github.com/repos/almostrealism/common/commits/8b6d8e8a6",
                GitHubProxyHandler.commitApiUrl("almostrealism/common", "8b6d8e8a6"));
    }

    /**
     * A draft pull request carries {@code "draft":true} so GitHub opens it as a
     * plan awaiting approval rather than ready for review.
     */
    @Test(timeout = 10000)
    public void draftPayloadRequestsADraft() {
        assertEquals(
                "{\"title\":\"Plan the next task\",\"head\":\"project/task-1\","
                    + "\"base\":\"master\",\"body\":\"Details.\",\"draft\":true}",
                GitHubProxyHandler.pullRequestPayload(
                        "Plan the next task", "project/task-1", "master", "Details.", true));
    }

    /**
     * A non-draft pull request omits the {@code draft} member entirely, keeping
     * the body byte-identical to what callers sent before the flag existed.
     */
    @Test(timeout = 10000)
    public void nonDraftPayloadOmitsTheDraftMember() {
        assertEquals(
                "{\"title\":\"Plan the next task\",\"head\":\"project/task-1\","
                    + "\"base\":\"master\",\"body\":\"Details.\"}",
                GitHubProxyHandler.pullRequestPayload(
                        "Plan the next task", "project/task-1", "master", "Details.", false));
    }

    /** Title and body are JSON-escaped so a quote in either cannot break the body. */
    @Test(timeout = 10000)
    public void payloadEscapesTitleAndBody() {
        assertEquals(
                "{\"title\":\"a \\\"quoted\\\" title\",\"head\":\"h\",\"base\":\"master\","
                    + "\"body\":\"line\\nbreak\"}",
                GitHubProxyHandler.pullRequestPayload(
                        "a \"quoted\" title", "h", "master", "line\nbreak", false));
    }

    /** The default root is the public API; the two-argument form keeps it. */
    @Test(timeout = 10000)
    public void defaultRootIsThePublicApi() {
        assertEquals("https://api.github.com/repos/almostrealism/common/commits/8b6d8e8a6",
                GitHubProxyHandler.commitApiUrl("almostrealism/common", "8b6d8e8a6"));
    }

    /** A custom API root replaces the public host so a test can target a stub server. */
    @Test(timeout = 10000)
    public void customRootReplacesThePublicHost() {
        assertEquals("http://127.0.0.1:8080/repos/almostrealism/common/commits/8b6d8e8a6",
                GitHubProxyHandler.commitApiUrl("http://127.0.0.1:8080", "almostrealism/common", "8b6d8e8a6"));
    }

    /**
     * {@code findOpenPullRequest} returns the open pull request on a branch and
     * passes over a closed one, so a reused companion PR is never a stale closed
     * one. The listing is served by a local stub against a custom API root.
     */
    @Test(timeout = 30000)
    public void findOpenPullRequestSkipsClosedPulls() throws IOException {
        String listing = "[{\"number\":4,\"state\":\"closed\",\"html_url\":\"http://x/pull/4\","
                + "\"head\":{\"ref\":\"feature/x\"}},"
                + "{\"number\":5,\"state\":\"open\",\"html_url\":\"http://x/pull/5\","
                + "\"head\":{\"ref\":\"feature/x\"}}]";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, listing));
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            PullRequest open = handler.findOpenPullRequest("almostrealism/common", "feature/x");
            assertEquals(5, open.number());

            PullRequest none = handler.findOpenPullRequest("almostrealism/common", "no/such");
            assertNull("no open PR on a branch with none", none);
        } finally {
            server.stop(0);
        }
    }

    /**
     * Every GitHub URL form a workstream or CI system supplies reduces to the
     * same {@code owner/repo} slug: SSH, HTTPS, {@code ssh://}, credentialed,
     * suffix-less, trailing-slash, uppercase-host and explicit-port forms.
     */
    @Test(timeout = 10000)
    public void extractOwnerRepoParsesGitHubUrlForms() {
        String[] urls = {
                "git@github.com:almostrealism/common.git",
                "git@github.com:almostrealism/common",
                "https://github.com/almostrealism/common.git",
                "https://github.com/almostrealism/common/",
                "ssh://git@github.com/almostrealism/common.git",
                "https://x-access-token:secret@github.com/almostrealism/common.git",
                "https://GITHUB.COM/almostrealism/common.git",
                "https://github.com:443/almostrealism/common.git"
        };

        for (String url : urls) {
            assertEquals("almostrealism/common", GitHubProxyHandler.extractOwnerRepo(url));
        }

        assertNull(GitHubProxyHandler.extractOwnerRepo(null));
        assertNull(GitHubProxyHandler.extractOwnerRepo(""));
        assertNull(GitHubProxyHandler.extractOwnerRepo("not-a-url"));
    }

    /**
     * A repository hosted anywhere but {@code github.com} has no GitHub slug.
     * The slug is used to query and write to the GitHub API — listing pull
     * requests for a workstream, opening a companion pull request — so a
     * {@code gitlab.com} repository, or a look-alike host such as
     * {@code github.com.evil.example}, must not be mapped onto whichever GitHub
     * repository happens to share its {@code owner/repo} path.
     */
    @Test(timeout = 10000)
    public void extractOwnerRepoRejectsNonGitHubHost() {
        assertNull(GitHubProxyHandler.extractOwnerRepo("https://gitlab.com/almostrealism/common.git"));
        assertNull(GitHubProxyHandler.extractOwnerRepo("git@gitlab.com:almostrealism/common.git"));
        assertNull(GitHubProxyHandler.extractOwnerRepo("https://github.com.evil.example/almostrealism/common.git"));
        assertNull(GitHubProxyHandler.extractOwnerRepo("git@github.com.evil.example:almostrealism/common.git"));
    }

    /**
     * The GitHub organisation is the owner of a GitHub repository URL, and a
     * repository hosted elsewhere has none, so it never selects a GitHub token
     * or a workspace by organisation.
     */
    @Test(timeout = 10000)
    public void extractOrgFromRepoUrlRequiresGitHubHost() {
        assertEquals("almostrealism",
                GitHubProxyHandler.extractOrgFromRepoUrl("git@github.com:almostrealism/common.git"));
        assertEquals("almostrealism",
                GitHubProxyHandler.extractOrgFromRepoUrl("https://github.com/almostrealism/common"));
        assertNull(GitHubProxyHandler.extractOrgFromRepoUrl("https://gitlab.com/almostrealism/common.git"));
        assertNull(GitHubProxyHandler.extractOrgFromRepoUrl("https://github.com.evil.example/almostrealism/common"));
        assertNull(GitHubProxyHandler.extractOrgFromRepoUrl("not-a-url"));
    }

    /**
     * A proxied PATCH is forwarded to GitHub as a PATCH, carrying the body the
     * {@link RequestBodyReader} read from the session, and the GitHub response
     * is returned in the proxy envelope. The handler's API root is pointed at a
     * local stub that records the method and body it actually received, so the
     * forward is observed on the wire rather than inferred.
     */
    @Test(timeout = 30000)
    public void patchIsForwardedAsPatchWithItsBody() throws IOException {
        String payload = "{\"title\":\"A new title\"}";
        AtomicReference<String> seenMethod = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"number\":5,\"title\":\"A new title\"}");
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            IHTTPSession session = new FakeSession(Method.PATCH,
                    Map.of("url", "/repos/almostrealism/common/pulls/5"),
                    Map.of("content-length", String.valueOf(bytes.length)),
                    new ByteArrayInputStream(bytes));

            Response response = handler.handle(session, Method.PATCH,
                    new RequestBodyReader(), GitHubProxyHandlerTest::errorResponse);

            assertEquals("PATCH", seenMethod.get());
            assertEquals(payload, seenBody.get());
            String envelope = bodyOf(response);
            assertTrue(envelope.contains("\"status\":200"));
            assertTrue(envelope.contains("\"number\":5"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * A write whose body carries a private Claude session link is refused with
     * the owner's block message and never forwarded to GitHub — even though it
     * reached the proxy, which is the choke point every write body passes
     * through. The refusal message is the block message, not the link. A PATCH
     * exercises the body-reading path the fake session delivers.
     */
    @Test(timeout = 30000)
    public void writeWithPrivateLinkInBodyIsRefusedAndNotForwarded() throws IOException {
        String session = "session_" + "fakefakefake1234";
        String link = "https://" + "claude" + ".ai/code/" + session;
        String payload = "{\"body\":\"done " + link + "\"}";
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, "{\"number\":9}");
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            IHTTPSession session1 = new FakeSession(Method.PATCH,
                    Map.of("url", "/repos/almostrealism/common/pulls/5"),
                    Map.of("content-length", String.valueOf(bytes.length)),
                    new ByteArrayInputStream(bytes));

            Response response = handler.handle(session1, Method.PATCH,
                    new RequestBodyReader(), GitHubProxyHandlerTest::errorResponse);

            String body = bodyOf(response);
            assertTrue(body, body.toLowerCase().contains("malice"));
            assertFalse("the refusal must not echo the link", body.contains(session));
            assertEquals("the write must not reach GitHub", 0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * {@link RequestBodyReader} — the reader {@code FlowTreeApiEndpoint} uses for
     * every proxied body — reads a PATCH body straight from the session's input
     * stream, bounded by {@code content-length}: exactly that many bytes, and no
     * trailing bytes beyond it.
     */
    @Test(timeout = 10000)
    public void requestBodyReaderReadsPatchBodyBoundedByContentLength() {
        String body = "{\"title\":\"New title\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        InputStream stream = new ByteArrayInputStream(
                (body + "TRAILING").getBytes(StandardCharsets.UTF_8));
        IHTTPSession session = new FakeSession(Method.PATCH, Map.of(),
                Map.of("content-length", String.valueOf(bytes.length)), stream);

        assertEquals(body, new RequestBodyReader().read(session));
    }

    /**
     * A malformed {@code Content-Length} is rejected, not parsed and trusted to
     * size an allocation. {@link RequestBodyReader} throws rather than reading.
     */
    @Test(timeout = 10000)
    public void patchWithMalformedContentLengthIsRejected() {
        IHTTPSession session = new FakeSession(Method.PATCH, Map.of(),
                Map.of("content-length", "not-a-number"),
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        try {
            new RequestBodyReader().read(session);
            fail("a malformed Content-Length must be rejected, not read");
        } catch (GitHubProxyHandler.BodyReader.InvalidRequestBodyException expected) {
            assertTrue(expected.getMessage().contains("Content-Length"));
        }
    }

    /**
     * A body shorter than its declared {@code Content-Length} — a client that
     * closed the stream early — is rejected rather than forwarded as truncated
     * JSON. {@link RequestBodyReader} throws instead of returning the partial
     * bytes.
     */
    @Test(timeout = 10000)
    public void patchWithShortBodyIsRejected() {
        byte[] partial = "{\"title\":".getBytes(StandardCharsets.UTF_8);
        IHTTPSession session = new FakeSession(Method.PATCH, Map.of(),
                Map.of("content-length", String.valueOf(partial.length + 16)),
                new ByteArrayInputStream(partial));
        try {
            new RequestBodyReader().read(session);
            fail("a body shorter than its Content-Length must be rejected, not read");
        } catch (GitHubProxyHandler.BodyReader.InvalidRequestBodyException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }

    /**
     * A PATCH whose body ends early is refused with a 4xx and no request reaches
     * GitHub, so a truncated update is never attempted. The stub counts every
     * request it receives, proving the short body was never forwarded.
     */
    @Test(timeout = 30000)
    public void patchWithShortBodyDoesNotReachGitHub() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, "{}");
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            byte[] partial = "{\"title\":".getBytes(StandardCharsets.UTF_8);
            IHTTPSession session = new FakeSession(Method.PATCH,
                    Map.of("url", "/repos/almostrealism/common/pulls/5"),
                    Map.of("content-length", String.valueOf(partial.length + 16)),
                    new ByteArrayInputStream(partial));

            Response response = handler.handle(session, Method.PATCH,
                    new RequestBodyReader(), GitHubProxyHandlerTest::errorResponse);

            assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
            assertTrue(bodyOf(response).contains("truncated"));
            assertEquals("a truncated body never reaches GitHub", 0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * A {@code Content-Length} above the reader's bound is refused with a 4xx
     * before any buffer is allocated, and no request reaches GitHub. The stub
     * counts every request it receives, proving the over-size body was never
     * forwarded.
     */
    @Test(timeout = 30000)
    public void patchOverSizeLimitIsRejectedWithoutReaching() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, "{}");
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            long overLimit = (long) RequestBodyReader.MAX_BODY_BYTES + 1;
            IHTTPSession session = new FakeSession(Method.PATCH,
                    Map.of("url", "/repos/almostrealism/common/pulls/5"),
                    Map.of("content-length", String.valueOf(overLimit)),
                    new ByteArrayInputStream(new byte[0]));

            Response response = handler.handle(session, Method.PATCH,
                    new RequestBodyReader(), GitHubProxyHandlerTest::errorResponse);

            assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
            assertTrue(bodyOf(response).contains("Content-Length"));
            assertEquals("an over-limit body never reaches GitHub", 0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * A method the proxy does not support (DELETE, OPTIONS) is refused with an
     * error, never silently answered as a GET. The stub counts every request it
     * receives, proving no downgraded read is forwarded.
     */
    @Test(timeout = 30000)
    public void unsupportedMethodIsRefusedNotDowngradedToGet() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, "{}");
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            for (Method method : new Method[] {Method.DELETE, Method.OPTIONS}) {
                IHTTPSession session = new FakeSession(method,
                        Map.of("url", "/repos/almostrealism/common/pulls/5"),
                        Map.of(), new ByteArrayInputStream(new byte[0]));

                Response response = handler.handle(session, method,
                        new RequestBodyReader(), GitHubProxyHandlerTest::errorResponse);

                assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
                assertTrue(bodyOf(response).contains("Unsupported method"));
            }
            assertEquals("no request reaches GitHub for an unsupported method", 0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * A PATCH whose body stream fails mid-read is rejected rather than read as a
     * {@code null} body. {@link RequestBodyReader} converts the {@link IOException}
     * into an {@link io.flowtree.github.GitHubProxyHandler.BodyReader.InvalidRequestBodyException},
     * so the handler never mistakes a failed read for an empty payload and
     * forwards a PATCH that would clear the pull request's fields.
     */
    @Test(timeout = 10000)
    public void patchWhoseBodyReadFailsIsRejected() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stream broke");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("stream broke");
            }
        };
        IHTTPSession session = new FakeSession(Method.PATCH, Map.of(),
                Map.of("content-length", "32"), failing);
        try {
            new RequestBodyReader().read(session);
            fail("a PATCH body that cannot be read must be rejected, not returned as null");
        } catch (GitHubProxyHandler.BodyReader.InvalidRequestBodyException expected) {
            assertTrue(expected.getMessage().contains("could not read PATCH request body"));
        }
    }

    /**
     * A PATCH whose body stream fails is refused with a 4xx and no request
     * reaches GitHub, so a read failure is never forwarded as an empty update.
     * The stub counts every request it receives, proving nothing was forwarded.
     */
    @Test(timeout = 30000)
    public void patchWhoseBodyReadFailsDoesNotReachGitHub() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, "{}");
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(
                    Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            InputStream failing = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("stream broke");
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    throw new IOException("stream broke");
                }
            };
            IHTTPSession session = new FakeSession(Method.PATCH,
                    Map.of("url", "/repos/almostrealism/common/pulls/5"),
                    Map.of("content-length", "32"), failing);

            Response response = handler.handle(session, Method.PATCH,
                    new RequestBodyReader(), GitHubProxyHandlerTest::errorResponse);

            assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
            assertTrue(bodyOf(response).contains("could not read PATCH request body"));
            assertEquals("a failed body read never reaches GitHub", 0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * A malformed caller-supplied {@code url} makes the PATCH forward's
     * {@link java.net.URI#create} throw an unchecked exception. The handler
     * routes it through the error responder — as the GET/POST/PUT path does —
     * so the caller receives the normal 4xx rather than an exception escaping
     * {@code handle}.
     */
    @Test(timeout = 10000)
    public void patchWithMalformedUrlReturnsErrorResponse() {
        GitHubProxyHandler handler = new GitHubProxyHandler(Map.of("almostrealism", "tok"));
        byte[] bytes = "{\"title\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        IHTTPSession session = new FakeSession(Method.PATCH,
                Map.of("url", "https://api.github.com/repos/almostrealism/common/pulls/5 bad"),
                Map.of("content-length", String.valueOf(bytes.length)),
                new ByteArrayInputStream(bytes));

        Response response = handler.handle(session, Method.PATCH,
                new RequestBodyReader(), GitHubProxyHandlerTest::errorResponse);

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
    }

    /**
     * An absolute {@code url} on any host other than the API root is refused for
     * every supported verb, so the org's bearer token is never sent to a
     * caller-chosen host. Look-alike hosts and a userinfo prefix that only begin
     * with the API root's text are refused as well. The body reader must not be
     * consulted, proving the refusal happens before anything is forwarded.
     */
    @Test(timeout = 10000)
    public void absoluteUrlOutsideApiRootIsRefusedForEveryVerb() throws IOException {
        GitHubProxyHandler handler = new GitHubProxyHandler(Map.of("almostrealism", "tok"));
        String[] urls = {
                "https://evil.example/repos/almostrealism/common/pulls/5",
                "https://api.github.com.evil.example/repos/almostrealism/common/pulls/5",
                "https://api.github.com@evil.example/repos/almostrealism/common/pulls/5",
                "https://api.github.com"
        };
        AtomicInteger bodyReads = new AtomicInteger();
        GitHubProxyHandler.BodyReader countingReader = s -> {
            bodyReads.incrementAndGet();
            return "{\"title\":\"x\"}";
        };
        for (Method method : new Method[] {Method.GET, Method.POST, Method.PUT, Method.PATCH}) {
            for (String url : urls) {
                IHTTPSession session = new FakeSession(method, Map.of("url", url),
                        Map.of(), new ByteArrayInputStream(new byte[0]));

                Response response = handler.handle(session, method,
                        countingReader, GitHubProxyHandlerTest::errorResponse);

                assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
                assertTrue(method + " " + url,
                        bodyOf(response).contains("must be a path or a URL under https://api.github.com"));
            }
        }
        assertEquals("a refused request never reads its body", 0, bodyReads.get());
    }

    /**
     * A relative {@code url} is appended to the API root, so one that does not
     * start with {@code /} could extend the root's host and send the org token
     * elsewhere ({@code .evil.example/...} resolves to
     * {@code https://api.github.com.evil.example/...}). Such a value is refused
     * before the body is read.
     */
    @Test(timeout = 10000)
    public void relativeUrlWithoutLeadingSlashIsRefused() throws IOException {
        GitHubProxyHandler handler = new GitHubProxyHandler(Map.of("almostrealism", "tok"));
        String[] urls = {
                ".evil.example/repos/almostrealism/common/pulls/5",
                "@evil.example/repos/almostrealism/common/pulls/5",
                "repos/almostrealism/common/pulls/5"
        };
        AtomicInteger bodyReads = new AtomicInteger();
        GitHubProxyHandler.BodyReader countingReader = s -> {
            bodyReads.incrementAndGet();
            return "{\"title\":\"x\"}";
        };
        for (String url : urls) {
            IHTTPSession session = new FakeSession(Method.PATCH, Map.of("url", url),
                    Map.of(), new ByteArrayInputStream(new byte[0]));

            Response response = handler.handle(session, Method.PATCH,
                    countingReader, GitHubProxyHandlerTest::errorResponse);

            assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
            assertTrue(url, bodyOf(response).contains("must be a path or a URL under"));
        }
        assertEquals("a refused request never reads its body", 0, bodyReads.get());
    }

    /**
     * The host check compares against the configured API root, not a fixed
     * host. With an {@code https} root on a local port, an absolute URL under
     * that root passes the check and is forwarded (the attempt fails at the
     * transport because nothing speaks TLS on the port, so the error is the
     * proxy's transport error rather than the host refusal), while the public
     * API URL, now outside the root, is refused.
     */
    @Test(timeout = 30000)
    public void hostCheckFollowsConfiguredApiRoot() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, "{}"));
        server.start();
        try {
            String root = "https://127.0.0.1:" + server.getAddress().getPort();
            GitHubProxyHandler handler = new GitHubProxyHandler(Map.of("almostrealism", "tok"), root);

            IHTTPSession inside = new FakeSession(Method.PATCH,
                    Map.of("url", root + "/repos/almostrealism/common/pulls/5"),
                    Map.of(), new ByteArrayInputStream(new byte[0]));
            Response forwarded = handler.handle(inside, Method.PATCH,
                    s -> "{}", GitHubProxyHandlerTest::errorResponse);
            String forwardedBody = bodyOf(forwarded);
            assertTrue(forwardedBody, forwardedBody.contains("GitHub proxy error"));
            assertFalse(forwardedBody, forwardedBody.contains("must be a path or a URL under"));

            IHTTPSession outside = new FakeSession(Method.PATCH,
                    Map.of("url", "https://api.github.com/repos/almostrealism/common/pulls/5"),
                    Map.of(), new ByteArrayInputStream(new byte[0]));
            Response refused = handler.handle(outside, Method.PATCH,
                    s -> "{}", GitHubProxyHandlerTest::errorResponse);
            assertEquals(Response.Status.BAD_REQUEST, refused.getStatus());
            assertTrue(bodyOf(refused).contains("must be a path or a URL under " + root));
        } finally {
            server.stop(0);
        }
    }

    /**
     * An Actions artifact download answers with a redirect to a storage host that
     * serves a zip archive. The archive's bytes are not UTF-8, so the proxy must
     * carry them base64-encoded and say so; decoding them as text would corrupt the
     * archive. The decoded body must equal the bytes the storage host served.
     */
    @Test(timeout = 30000)
    public void binaryBodyBehindARedirectIsCarriedAsBase64() throws IOException {
        byte[] archive = {0x50, 0x4b, 0x03, 0x04, (byte) 0xff, (byte) 0xfe, 0x00, (byte) 0x80, 0x41};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String storage = "http://127.0.0.1:%d/blob/artifact.zip";
        server.createContext("/repos/", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    String.format(storage, server.getAddress().getPort()));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/blob/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, archive.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(archive);
            }
        });
        server.start();
        try {
            GitHubProxyHandler handler = new GitHubProxyHandler(Map.of("almostrealism", "tok"),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            IHTTPSession session = new FakeSession(Method.GET,
                    Map.of("url", "/repos/almostrealism/common/actions/artifacts/7/zip"),
                    Map.of(), new ByteArrayInputStream(new byte[0]));

            String envelope = bodyOf(handler.handle(session, Method.GET,
                    s -> null, GitHubProxyHandlerTest::errorResponse));

            assertTrue(envelope, envelope.contains("\"status\":200"));
            assertTrue(envelope, envelope.contains("\"encoding\":\"base64\""));
            String encoded = envelope.replaceFirst("(?s).*\"body\":\"([^\"]*)\".*", "$1");
            assertArrayEquals(archive, Base64.getDecoder().decode(encoded));
        } finally {
            server.stop(0);
        }
    }

    /**
     * A text body keeps its existing envelope: a JSON body is spliced in, any other
     * text is quoted, and neither carries an {@code encoding} member.
     */
    @Test(timeout = 10000)
    public void textBodiesAreNotBase64Encoded() {
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] log = "line one\nline é".getBytes(StandardCharsets.UTF_8);

        assertEquals("{\"status\":200,\"link\":\"\",\"body\":{\"a\":1}}",
                new GitHubProxyEnvelope(200, null, json, "application/json").toJson());
        String text = new GitHubProxyEnvelope(200, null, log, "text/plain").toJson();
        assertTrue(text, text.contains("\"body\":\"line one\\nline é\""));
        assertFalse(text, text.contains("encoding"));
        assertEquals("{\"status\":204,\"link\":\"\",\"body\":null}",
                new GitHubProxyEnvelope(204, null, new byte[0], null).toJson());
    }

    /**
     * A reliably-read, untruncated listing is complete, lists every unresolved
     * thread anchored at its first comment, and omits resolved threads.
     */
    @Test(timeout = 10000)
    public void reviewThreadListingIsCompleteWhenFullyRead() throws IOException {
        JsonNode root = new ObjectMapper().readTree(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                + "\"pageInfo\":{\"hasNextPage\":false},\"nodes\":["
                + "{\"isResolved\":false,\"comments\":{\"nodes\":["
                + "{\"path\":\"A.java\",\"line\":4,\"url\":\"https://x/c1\",\"body\":\"open one\"}]}},"
                + "{\"isResolved\":true,\"comments\":{\"nodes\":["
                + "{\"path\":\"B.java\",\"line\":7,\"url\":\"https://x/c2\",\"body\":\"resolved\"}]}}"
                + "]}}}}}");
        GitHubProxyHandler.ReviewThreadListing listing = GitHubProxyHandler.ReviewThreadListing.from(root);
        assertTrue("a fully read listing is complete", listing.complete());
        assertEquals("the resolved thread is omitted", 1, listing.findings().size());
        assertEquals("https://x/c1", listing.findings().get(0).url());
    }

    /**
     * A listing truncated at the page limit ({@code hasNextPage}) is incomplete,
     * so the repeat check cannot mistake a thread beyond the first page for none.
     */
    @Test(timeout = 10000)
    public void reviewThreadListingTruncatedAtThePageLimitIsIncomplete() throws IOException {
        JsonNode root = new ObjectMapper().readTree(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                + "\"pageInfo\":{\"hasNextPage\":true},\"nodes\":["
                + "{\"isResolved\":false,\"comments\":{\"nodes\":["
                + "{\"path\":\"A.java\",\"line\":4,\"url\":\"https://x/c1\",\"body\":\"open one\"}]}}"
                + "]}}}}}");
        GitHubProxyHandler.ReviewThreadListing listing = GitHubProxyHandler.ReviewThreadListing.from(root);
        assertFalse("a truncated listing is incomplete", listing.complete());
    }

    /**
     * An unresolved thread whose first comment is absent cannot be anchored, so
     * the thread is dropped from the findings and the whole listing is marked
     * incomplete — the repeat check must fail open, not treat the remaining
     * threads as the complete set.
     */
    @Test(timeout = 10000)
    public void reviewThreadListingWithAMissingFirstCommentIsIncomplete() throws IOException {
        JsonNode root = new ObjectMapper().readTree(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                + "\"pageInfo\":{\"hasNextPage\":false},\"nodes\":["
                + "{\"isResolved\":false,\"comments\":{\"nodes\":["
                + "{\"path\":\"A.java\",\"line\":4,\"url\":\"https://x/c1\",\"body\":\"open one\"}]}},"
                + "{\"isResolved\":false,\"comments\":{\"nodes\":[]}}"
                + "]}}}}}");
        GitHubProxyHandler.ReviewThreadListing listing = GitHubProxyHandler.ReviewThreadListing.from(root);
        assertFalse("a thread without a first comment makes the listing incomplete", listing.complete());
        assertEquals("the unanchored thread is omitted", 1, listing.findings().size());
    }

    /**
     * A failed query ({@code null} root) and a malformed response (non-array
     * {@code nodes}) are both incomplete with no findings, so neither is read as
     * "no open threads".
     */
    @Test(timeout = 10000)
    public void reviewThreadListingFromAnUnreadableResponseIsIncomplete() throws IOException {
        GitHubProxyHandler.ReviewThreadListing failed = GitHubProxyHandler.ReviewThreadListing.from(null);
        assertFalse("a failed query is incomplete", failed.complete());
        assertTrue(failed.findings().isEmpty());

        JsonNode malformed = new ObjectMapper().readTree(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{\"nodes\":\"oops\"}}}}}");
        GitHubProxyHandler.ReviewThreadListing listing = GitHubProxyHandler.ReviewThreadListing.from(malformed);
        assertFalse("a non-array nodes is incomplete", listing.complete());
        assertTrue(listing.findings().isEmpty());
    }

    /**
     * A GraphQL response served with HTTP 200 but carrying an {@code errors}
     * array is a partial result, so the listing is incomplete even when its
     * {@code nodes} array is readable — the repeat check must fail open rather
     * than treat the partial threads as the complete set.
     */
    @Test(timeout = 10000)
    public void reviewThreadListingWithGraphQlErrorsIsIncomplete() throws IOException {
        JsonNode root = new ObjectMapper().readTree(
                "{\"errors\":[{\"message\":\"Something went wrong\"}],"
                + "\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                + "\"pageInfo\":{\"hasNextPage\":false},\"nodes\":["
                + "{\"isResolved\":false,\"comments\":{\"nodes\":["
                + "{\"path\":\"A.java\",\"line\":4,\"url\":\"https://x/c1\",\"body\":\"open one\"}]}}"
                + "]}}}}}");
        GitHubProxyHandler.ReviewThreadListing listing = GitHubProxyHandler.ReviewThreadListing.from(root);
        assertFalse("a response with a GraphQL errors array is incomplete", listing.complete());
    }

    /**
     * A response whose {@code pageInfo.hasNextPage} is absent or not a boolean
     * leaves the pagination state unknown, so the listing is incomplete: a
     * missing or malformed field must not be read as "not truncated" and let a
     * partial listing suppress a pass.
     */
    @Test(timeout = 10000)
    public void reviewThreadListingWithoutHasNextPageIsIncomplete() throws IOException {
        JsonNode absent = new ObjectMapper().readTree(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                + "\"nodes\":["
                + "{\"isResolved\":false,\"comments\":{\"nodes\":["
                + "{\"path\":\"A.java\",\"line\":4,\"url\":\"https://x/c1\",\"body\":\"open one\"}]}}"
                + "]}}}}}");
        assertFalse("an absent hasNextPage is incomplete",
                GitHubProxyHandler.ReviewThreadListing.from(absent).complete());

        JsonNode malformed = new ObjectMapper().readTree(
                "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                + "\"pageInfo\":{\"hasNextPage\":\"nope\"},\"nodes\":[]}}}}}");
        assertFalse("a non-boolean hasNextPage is incomplete",
                GitHubProxyHandler.ReviewThreadListing.from(malformed).complete());
    }

    /** Builds the 400 error envelope the proxy endpoint returns, for the handler's responder. */
    private static Response errorResponse(String message) {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST,
                "application/json", "{\"ok\":false,\"error\":\"" + message + "\"}");
    }

    /** Reads a NanoHTTPD response body back as a string. */
    private static String bodyOf(Response response) throws IOException {
        return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Writes a JSON body, filtering an empty {@code head=} match to an empty listing. */
    private static void respond(HttpExchange exchange, String listing) throws IOException {
        // getQuery() returns the decoded query, so the branch reads as "no/such".
        String query = exchange.getRequestURI().getQuery();
        String body = query != null && query.contains("no/such") ? "[]" : listing;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * A minimal {@link IHTTPSession} serving the method, query parameters,
     * headers and input stream a test configures. The proxy handler and
     * {@link RequestBodyReader} read only these; every other session member is
     * unused and answers with an inert default.
     */
    private static final class FakeSession implements IHTTPSession {

        /** The HTTP method of the request. */
        private final Method method;
        /** The decoded query parameters. */
        private final Map<String, String> parms;
        /** The request headers, keyed by lower-case name. */
        private final Map<String, String> headers;
        /** The request body stream. */
        private final InputStream input;

        /**
         * @param method  the HTTP method
         * @param parms   the query parameters
         * @param headers the request headers (lower-case keys)
         * @param input   the request body stream
         */
        private FakeSession(Method method, Map<String, String> parms,
                            Map<String, String> headers, InputStream input) {
            this.method = method;
            this.parms = parms;
            this.headers = headers;
            this.input = input;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Map<String, String> getParms() {
            return parms;
        }

        @Override
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public void parseBody(Map<String, String> files) {
        }

        @Override
        public void execute() {
        }

        @Override
        public CookieHandler getCookies() {
            return null;
        }

        @Override
        public Map<String, List<String>> getParameters() {
            return Map.of();
        }

        @Override
        public String getQueryParameterString() {
            return null;
        }

        @Override
        public String getUri() {
            return "/api/github/proxy";
        }

        @Override
        public String getRemoteIpAddress() {
            return "127.0.0.1";
        }

        @Override
        public String getRemoteHostName() {
            return "localhost";
        }
    }
}
