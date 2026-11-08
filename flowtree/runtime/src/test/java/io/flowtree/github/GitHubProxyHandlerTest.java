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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
     * An added line carrying a {@code TODO} marker is reported at its new-file
     * line number, tracked from the hunk header. A removed line does not advance
     * the counter; a context line does.
     */
    @Test(timeout = 10000)
    public void addedTodoLineNumbersCountsFromTheHunkHeader() {
        String patch = "@@ -10,2 +10,4 @@\n"
                + " context line\n"
                + "-removed line\n"
                + "+// TODO(review): first\n"
                + "+plain added line\n"
                + "+// TODO: second\n";
        List<int[]> todos = GitHubProxyHandler.addedTodoLineNumbers(patch);
        assertEquals(2, todos.size());
        assertEquals(11, todos.get(0)[0]);
        assertEquals(13, todos.get(1)[0]);
    }

    /**
     * A word that merely contains the letters {@code TODO} is not a {@code TODO}
     * marker, matching {@code extract_branch_todos.py}'s word-boundary match.
     */
    @Test(timeout = 10000)
    public void addedTodoLineNumbersRequiresAWordBoundary() {
        String patch = "@@ -1,0 +1,2 @@\n"
                + "+private TODOList items;\n"
                + "+// TODO: real one\n";
        List<int[]> todos = GitHubProxyHandler.addedTodoLineNumbers(patch);
        assertEquals(1, todos.size());
        assertEquals(2, todos.get(0)[0]);
    }

    /**
     * A {@code "\ No newline at end of file"} marker is neither an added nor a
     * context line, so it does not advance the new-file counter.
     */
    @Test(timeout = 10000)
    public void addedTodoLineNumbersIgnoresTheNoNewlineMarker() {
        String patch = "@@ -1 +1,2 @@\n"
                + " context\n"
                + "\\ No newline at end of file\n"
                + "+// TODO: after the marker\n";
        List<int[]> todos = GitHubProxyHandler.addedTodoLineNumbers(patch);
        assertEquals(1, todos.size());
        assertEquals(2, todos.get(0)[0]);
    }

    /**
     * A GitHub file-patch has no {@code +++} file header, so an added line whose
     * content itself begins with {@code ++} is a real added line and its TODO is
     * reported rather than discarded as a header.
     */
    @Test(timeout = 10000)
    public void addedTodoLineNumbersExaminesContentStartingWithPlusPlus() {
        String patch = "@@ -1,0 +1,2 @@\n"
                + "+++counter; // TODO: fix the increment\n"
                + "+plain added line\n";
        List<int[]> todos = GitHubProxyHandler.addedTodoLineNumbers(patch);
        assertEquals(1, todos.size());
        assertEquals(1, todos.get(0)[0]);
    }

    /** A patch with no added TODO line reports none. */
    @Test(timeout = 10000)
    public void addedTodoLineNumbersReportsNoneWhenAbsent() {
        String patch = "@@ -1,2 +1,2 @@\n"
                + " unchanged\n"
                + "+a plain added line\n";
        assertTrue(GitHubProxyHandler.addedTodoLineNumbers(patch).isEmpty());
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
}
