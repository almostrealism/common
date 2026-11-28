/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.Assert.assertNull;

/**
 * Tests {@link PullRequest}'s URL parsers, which recover a pull request's
 * number and its repository's {@code owner/repo} from a pull-request URL when
 * no richer context (an auto-PR context, a listing's JSON) is available.
 */
public class PullRequestTest extends TestSuiteBase {

    /** The pull-request number is read from the trailing {@code /pull/{n}}. */
    @Test(timeout = 30000)
    public void numberFromUrlReadsTrailingNumber() {
        assertEquals(123, PullRequest.numberFromUrl("https://github.com/acme/app/pull/123"));
        assertEquals(9, PullRequest.numberFromUrl("https://github.com/acme/app/pulls/9"));
        assertEquals(9, PullRequest.numberFromUrl("https://github.com/acme/app/pull/9/"));
    }

    /** A URL carrying no pull-request number yields {@code -1}, and so does {@code null}. */
    @Test(timeout = 30000)
    public void numberFromUrlRejectsUrlsWithoutANumber() {
        assertEquals(-1, PullRequest.numberFromUrl(null));
        assertEquals(-1, PullRequest.numberFromUrl("https://github.com/acme/app"));
        assertEquals(-1, PullRequest.numberFromUrl("https://github.com/acme/app/pull/x"));
    }

    /** The {@code owner/repo} is recovered from both the HTML and API PR URL forms. */
    @Test(timeout = 30000)
    public void ownerRepoFromUrlRecoversSlug() {
        assertEquals("acme/app", PullRequest.ownerRepoFromUrl("https://github.com/acme/app/pull/9"));
        assertEquals("acme/app", PullRequest.ownerRepoFromUrl("https://github.com/acme/app/pulls/123"));
        assertEquals("acme/app", PullRequest.ownerRepoFromUrl("https://github.com/acme/app/pull/9/"));
    }

    /**
     * A repository literally named {@code pull} is not confused with the
     * {@code /pull/{n}} separator: the last occurrence delimits the number.
     */
    @Test(timeout = 30000)
    public void ownerRepoFromUrlHandlesRepoNamedPull() {
        assertEquals("owner/pull", PullRequest.ownerRepoFromUrl("https://github.com/owner/pull/pull/9"));
    }

    /**
     * A non-GitHub host, a look-alike host, and a URL with no pull-request
     * number all yield {@code null} rather than a slug that would address an
     * unrelated repository.
     */
    @Test(timeout = 30000)
    public void ownerRepoFromUrlRejectsNonGitHubAndNumberlessUrls() {
        assertNull(PullRequest.ownerRepoFromUrl(null));
        assertNull(PullRequest.ownerRepoFromUrl("https://github.com/acme/app"));
        assertNull(PullRequest.ownerRepoFromUrl("https://gitlab.com/acme/app/pull/9"));
        assertNull(PullRequest.ownerRepoFromUrl("https://github.com.evil.example/acme/app/pull/9"));
    }
}
