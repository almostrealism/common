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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
