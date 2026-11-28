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

import com.fasterxml.jackson.databind.JsonNode;
import io.flowtree.git.RepositoryUrl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pull request as GitHub's REST API renders it in a pull-request listing.
 *
 * <p>GitHub reports a merged pull request as {@code state: "closed"} with a
 * {@code merged_at} time, so "closed" alone does not say whether the work
 * landed. This type answers that question in one place.</p>
 *
 * @param json one element of a {@code GET /repos/{owner}/{repo}/pulls} response
 */
public record PullRequest(JsonNode json) {

    /** Matches the trailing {@code /pull/{number}} (or {@code /pulls/{number}}) of a PR URL. */
    private static final Pattern URL_NUMBER = Pattern.compile(".*/pulls?/(\\d+)/?$");

    /**
     * Extracts the pull-request number from a GitHub pull-request URL, such as
     * {@code https://github.com/owner/repo/pull/123}. This is the number as it
     * appears in the HTML URL ({@link #htmlUrl()}), the counterpart to
     * {@link #number()} which reads it from a listing's JSON.
     *
     * @param url the pull-request URL, or {@code null}
     * @return the number, or {@code -1} when the URL carries none
     */
    public static int numberFromUrl(String url) {
        if (url == null) {
            return -1;
        }
        Matcher matcher = URL_NUMBER.matcher(url);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * Extracts the {@code owner/repo} slug of the repository a GitHub
     * pull-request URL belongs to, such as {@code acme/app} from
     * {@code https://github.com/acme/app/pull/123}. This is the repository the
     * {@link #numberFromUrl(String) number} identifies a pull request within,
     * so when only a primary pull request's URL is known its owner/repo (and,
     * through that, the org whose token addresses it) can be recovered without
     * the auto-PR context that would otherwise carry them.
     *
     * <p>The trailing {@code /pull/{n}} (or {@code /pulls/{n}}) is stripped and
     * the remainder is parsed by {@link RepositoryUrl}, so a URL hosted
     * anywhere but {@code github.com} — or on a look-alike host — yields
     * {@code null} rather than a slug that would address an unrelated
     * repository.</p>
     *
     * @param url the pull-request URL, or {@code null}
     * @return the {@code owner/repo} slug, or {@code null} when the URL carries
     *         no pull-request number or is not a GitHub repository URL
     */
    public static String ownerRepoFromUrl(String url) {
        if (numberFromUrl(url) < 0) {
            return null;
        }
        String repoUrl = url.substring(0, url.lastIndexOf("/pull"));
        return new RepositoryUrl(repoUrl).getGitHubSlug();
    }

    /**
     * Reports whether the pull request was merged.
     *
     * @return {@code true} when the pull request has a merge time
     */
    public boolean isMerged() {
        JsonNode mergedAt = json.path("merged_at");
        return !mergedAt.isMissingNode() && !mergedAt.isNull() && !mergedAt.asText().isEmpty();
    }

    /**
     * Reports whether the pull request is still open.
     *
     * @return {@code true} when GitHub reports the state {@code open}
     */
    public boolean isOpen() {
        return "open".equals(json.path("state").asText(""));
    }

    /**
     * Reports whether the pull request was closed without being merged.
     *
     * @return {@code true} when it is closed and has no merge time
     */
    public boolean isClosedUnmerged() {
        return "closed".equals(json.path("state").asText("")) && !isMerged();
    }

    /**
     * Returns the pull request's number.
     *
     * @return the number, or {@code 0} when the listing omits it
     */
    public int number() {
        return json.path("number").asInt();
    }

    /**
     * Returns the pull request's HTML URL — the page a human opens.
     *
     * @return the {@code html_url}, or {@code null} when the listing omits it
     */
    public String htmlUrl() {
        return json.path("html_url").asText(null);
    }

    /**
     * Returns the pull request's title.
     *
     * @return the {@code title}, or an empty string when the listing omits it
     */
    public String title() {
        return json.path("title").asText("");
    }

    /**
     * Returns the pull request's body.
     *
     * @return the {@code body}, or an empty string when the listing omits it
     */
    public String body() {
        return json.path("body").asText("");
    }
}
