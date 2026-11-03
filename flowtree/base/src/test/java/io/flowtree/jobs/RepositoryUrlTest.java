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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link RepositoryUrl}: the slug and host it parses, whether it is
 * hosted on GitHub, and repository identity across URL forms.
 */
public class RepositoryUrlTest extends TestSuiteBase {

    /** Every recognised URL form yields its slug and its exact host. */
    @Test(timeout = 10000)
    public void parsesSlugAndHost() {
        RepositoryUrl ssh = new RepositoryUrl("git@github.com:almostrealism/common.git");
        Assert.assertEquals("almostrealism/common", ssh.getSlug());
        Assert.assertEquals("github.com", ssh.getHost());

        RepositoryUrl port = new RepositoryUrl("https://gitlab.example:8443/acme/repo/");
        Assert.assertEquals("acme/repo", port.getSlug());
        Assert.assertEquals("gitlab.example", port.getHost());

        RepositoryUrl ipv6 = new RepositoryUrl("https://[2001:db8::1]:443/owner/repo.git");
        Assert.assertEquals("owner/repo", ipv6.getSlug());
        Assert.assertEquals("[2001:db8::1]", ipv6.getHost());

        for (String unrecognised : new String[] { null, "", "   ", "not-a-url", "https://github.com/almostrealism" }) {
            RepositoryUrl url = new RepositoryUrl(unrecognised);
            Assert.assertNull(url.getSlug());
            Assert.assertNull(url.getHost());
            Assert.assertFalse(url.isGitHub());
        }
    }

    /**
     * A GitHub repository has a GitHub slug in every URL form, whatever the
     * case of its host and whether or not a port is given; a repository on
     * another host, a look-alike host, or an unrecognised URL has none.
     */
    @Test(timeout = 10000)
    public void gitHubSlugRequiresTheGitHubHost() {
        Assert.assertEquals("almostrealism/common",
                new RepositoryUrl("git@github.com:almostrealism/common.git").getGitHubSlug());
        Assert.assertEquals("almostrealism/common",
                new RepositoryUrl("https://GitHub.com/almostrealism/common").getGitHubSlug());
        Assert.assertEquals("almostrealism/common",
                new RepositoryUrl("ssh://git@github.com:22/almostrealism/common.git").getGitHubSlug());
        Assert.assertEquals("almostrealism/common",
                new RepositoryUrl("https://x-access-token:secret@github.com/almostrealism/common.git").getGitHubSlug());

        Assert.assertNull(new RepositoryUrl("https://gitlab.com/almostrealism/common.git").getGitHubSlug());
        Assert.assertNull(new RepositoryUrl("git@gitlab.com:almostrealism/common.git").getGitHubSlug());
        Assert.assertNull(new RepositoryUrl("https://github.com.evil.example/almostrealism/common.git").getGitHubSlug());
        Assert.assertNull(new RepositoryUrl("https://[2001:db8::1]/almostrealism/common.git").getGitHubSlug());
        Assert.assertNull(new RepositoryUrl("https://github.com/almostrealism").getGitHubSlug());
        Assert.assertNull(new RepositoryUrl(null).getGitHubSlug());
        Assert.assertNull(new RepositoryUrl("   ").getGitHubSlug());
    }

    /**
     * Only git remote schemes are recognised: a {@code file://} or
     * {@code ftp://} URL naming {@code github.com} has no slug, no host, and
     * so no GitHub slug, while every supported scheme is accepted in any case.
     */
    @Test(timeout = 10000)
    public void onlyGitRemoteSchemesAreRecognised() {
        for (String scheme : new String[] { "http", "https", "HTTPS", "ssh", "git", "git+ssh", "ssh+git" }) {
            RepositoryUrl url = new RepositoryUrl(scheme + "://github.com/almostrealism/common.git");
            Assert.assertEquals(scheme, "almostrealism/common", url.getGitHubSlug());
        }

        for (String unsupported : new String[] {
                "file://github.com/almostrealism/common.git",
                "ftp://github.com/almostrealism/common.git",
                "ftps://github.com/almostrealism/common.git",
                "svn+ssh://github.com/almostrealism/common.git",
                "xhttps://github.com/almostrealism/common.git" }) {
            RepositoryUrl url = new RepositoryUrl(unsupported);
            Assert.assertNull(unsupported, url.getSlug());
            Assert.assertNull(unsupported, url.getHost());
            Assert.assertNull(unsupported, url.getGitHubSlug());
            Assert.assertFalse(unsupported, url.isGitHub());
        }
    }

    /**
     * Forms of one repository are the same repository ignoring case; an
     * unrecognised URL matches only itself, and an absent URL matches nothing.
     */
    @Test(timeout = 10000)
    public void sameRepositoryComparesBySlug() {
        RepositoryUrl ssh = new RepositoryUrl("git@github.com:almostrealism/common.git");
        Assert.assertTrue(ssh.isSameRepository(new RepositoryUrl("https://github.com/AlmostRealism/Common")));
        Assert.assertFalse(ssh.isSameRepository(new RepositoryUrl("https://github.com/almostrealism/other")));
        Assert.assertFalse(ssh.isSameRepository(null));
        Assert.assertFalse(ssh.isSameRepository(new RepositoryUrl(null)));
        Assert.assertFalse(new RepositoryUrl(null).isSameRepository(new RepositoryUrl(null)));

        Assert.assertTrue(new RepositoryUrl("not-a-url").isSameRepository(new RepositoryUrl("not-a-url")));
        Assert.assertFalse(new RepositoryUrl("not-a-url").isSameRepository(new RepositoryUrl("also-not-a-url")));
    }
}
