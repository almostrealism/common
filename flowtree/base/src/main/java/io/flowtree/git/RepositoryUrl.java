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

package io.flowtree.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A git repository URL, parsed into the parts that identify the repository:
 * its {@link #getSlug() owner/repo slug} and the {@link #getHost() host}
 * that serves it.
 *
 * <p>The same repository is routinely written several ways — an SSH remote
 * ({@code git@github.com:owner/repo.git}), an HTTPS clone URL
 * ({@code https://github.com/owner/repo.git}), and the browser URL a CI
 * system reports ({@code https://github.com/owner/repo}) — so comparing the
 * raw strings reports "different repository" for what is in fact one
 * repository. A {@code RepositoryUrl} compares by slug instead
 * ({@link #isSameRepository(RepositoryUrl)}).</p>
 *
 * <p>Any string may be wrapped, including {@code null}; a string that is not
 * a recognised SSH ({@code user@host:owner/repo} or {@code ssh://}), HTTP(S),
 * or {@code git://} repository URL — including one with any other scheme,
 * such as {@code file://} or {@code ftp://} — simply has no slug and no
 * host.</p>
 *
 * @author Michael Murray
 */
public final class RepositoryUrl {

    /** The host of every repository the GitHub API serves. */
    public static final String GITHUB_HOST = "github.com";

    /**
     * Pattern matching an SSH-form repository URL ({@code git@host:owner/repo.git}),
     * capturing the host (group 1) and the {@code owner/repo} path (group 2)
     * with the optional {@code .git} suffix excluded.
     */
    private static final Pattern SSH_PATTERN =
            Pattern.compile("^[^/@]+@([^:/]+):([^/]+/[^/]+?)(?:\\.git)?/?$");

    /**
     * Pattern matching an HTTP(S)-form, {@code ssh://} or {@code git://}
     * repository URL (scheme compared ignoring case, {@code git+ssh://} and
     * {@code ssh+git://} included). Any other scheme — {@code file://},
     * {@code ftp://} — is not a remote this class recognises, so a
     * {@code file://github.com/owner/repo} never passes for a GitHub
     * repository. The authority (after the scheme and optional credentials) is captured as the
     * host (group 1), excluding an optional {@code :port} so a URL with an
     * explicit port reports the same host as one without; a bracketed IPv6
     * literal ({@code [2001:db8::1]}) is captured whole. The {@code owner/repo}
     * path (group 2) is captured with the optional {@code .git} suffix excluded.
     */
    private static final Pattern HTTP_PATTERN =
            Pattern.compile("^(?i:https?|ssh|git|git\\+ssh|ssh\\+git)://(?:[^@/]+@)?(\\[[^\\]]+\\]|[^:/]+)(?::[0-9]+)?/([^/]+/[^/]+?)(?:\\.git)?/?$");

    /** The URL as given; may be {@code null}. */
    private final String url;

    /** The host with its original case, or {@code null} when unrecognised. */
    private final String host;

    /** The {@code owner/repo} slug with its original case, or {@code null} when unrecognised. */
    private final String slug;

    /**
     * Parses a repository URL.
     *
     * @param url the repository URL in SSH, HTTP(S), or {@code git://} form;
     *            may be {@code null}, blank, or unrecognised, in which case
     *            the result has no slug and no host
     */
    public RepositoryUrl(String url) {
        this.url = url;

        Matcher matcher = matcher(url);
        this.host = matcher == null ? null : matcher.group(1);
        this.slug = matcher == null ? null : matcher.group(2);
    }

    /**
     * Returns the matcher for whichever recognised form {@code url} is in.
     *
     * @param url the URL to match; may be {@code null}
     * @return a matcher that has matched, or {@code null} when {@code url}
     *         is {@code null}, blank, or not a recognised repository URL
     */
    private static Matcher matcher(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return null;

        Matcher ssh = SSH_PATTERN.matcher(trimmed);
        if (ssh.matches()) return ssh;

        Matcher http = HTTP_PATTERN.matcher(trimmed);
        if (http.matches()) return http;

        return null;
    }

    /**
     * Returns the canonical {@code owner/repo} slug of this repository. Host
     * and scheme are deliberately not part of the slug: the same repository
     * reached over SSH and HTTPS is one repository.
     *
     * @return the slug with its original case, or {@code null} when this is
     *         not a recognised repository URL
     */
    public String getSlug() {
        return slug;
    }

    /**
     * Returns the host that serves this repository, so that a caller which
     * needs a particular service can reject URLs pointing elsewhere. Because
     * the {@link #getSlug() slug} drops the host, a substring check against
     * the raw URL is not a host check — it accepts a look-alike host such as
     * {@code github.com.evil.example} — whereas this is the exact host.
     *
     * @return the host with its original case and without any port, or
     *         {@code null} when this is not a recognised repository URL
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns whether this repository is hosted on GitHub: its
     * {@link #getHost() host} is exactly {@value #GITHUB_HOST}, compared
     * ignoring case.
     *
     * @return {@code true} for a recognised URL whose host is GitHub
     */
    public boolean isGitHub() {
        return GITHUB_HOST.equalsIgnoreCase(host);
    }

    /**
     * Returns the {@code owner/repo} slug of this repository for use with the
     * GitHub API. A repository hosted elsewhere — or on a look-alike host such
     * as {@code github.com.evil.example} — has no GitHub slug, so it is never
     * mapped onto whichever GitHub repository happens to share its path.
     *
     * @return the slug with its original case, or {@code null} when this is
     *         not a recognised repository URL or is not {@link #isGitHub()
     *         hosted on GitHub}
     */
    public String getGitHubSlug() {
        return isGitHub() ? slug : null;
    }

    /**
     * Determines whether this URL and another name the same repository,
     * comparing them by {@link #getSlug() slug} ignoring case, since a
     * repository is reachable under any capitalisation of its owner and name.
     * URLs that cannot be parsed into a slug fall back to an exact
     * (case-sensitive) string comparison rather than being reported as a
     * match — an unrecognised URL form matches only itself.
     *
     * @param other the other repository URL; may be {@code null}
     * @return {@code true} when both URLs name the same repository; never
     *         when either URL is {@code null}
     */
    public boolean isSameRepository(RepositoryUrl other) {
        if (other == null || url == null || other.url == null) return false;
        if (slug != null && other.slug != null) return slug.equalsIgnoreCase(other.slug);
        return url.equals(other.url);
    }

    /**
     * Returns the URL as given.
     *
     * @return the URL this was parsed from; may be {@code null}
     */
    @Override
    public String toString() {
        return url;
    }
}
