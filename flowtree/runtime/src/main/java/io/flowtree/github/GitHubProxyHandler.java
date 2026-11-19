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

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.git.RepositoryUrl;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.flowtree.api.FlowTreeApiEndpoint;

/**
 * Handles GitHub API proxy requests for {@link FlowTreeApiEndpoint}.
 *
 * <p>This collaborator is responsible for all {@code /api/github/proxy}
 * routing, per-organization token resolution, and the auto-create-PR
 * feature that is triggered when a job completes successfully.</p>
 *
 * <p>Token lookup follows this priority:</p>
 * <ol>
 *   <li>Explicit {@code ?org=} query parameter</li>
 *   <li>Organization name extracted from the API path ({@code /repos/{org}/{repo}/...})</li>
 *   <li>Single-org default when only one token is configured</li>
 * </ol>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
public class GitHubProxyHandler implements ConsoleFeatures {

    /** The public GitHub REST API root; the default for every production handler. */
    public static final String DEFAULT_API_ROOT = "https://api.github.com";

    /** Maps GitHub organisation names (case-insensitive) to their API access tokens. */
    private Map<String, String> githubOrgTokens;

    /**
     * The REST API root every typed operation builds its URLs from, and the
     * root a proxied path (one that is not already an absolute {@code https://}
     * URL) is resolved against in {@link #handle}. Defaults to
     * {@link #DEFAULT_API_ROOT}; a test points it at a local stub server so the
     * companion-PR operations and the proxy forward — including the PATCH verb —
     * can be exercised without reaching GitHub.
     */
    private final String apiRoot;

    /**
     * Constructs a new handler with the supplied per-org token map, talking to
     * the public GitHub API.
     *
     * @param githubOrgTokens map of GitHub organisation name to API token;
     *                        must not be {@code null}
     */
    public GitHubProxyHandler(Map<String, String> githubOrgTokens) {
        this(githubOrgTokens, DEFAULT_API_ROOT);
    }

    /**
     * Constructs a new handler whose typed operations build their URLs from
     * {@code apiRoot} rather than the public GitHub API. Used by tests to point
     * the handler at a local stub server.
     *
     * @param githubOrgTokens map of GitHub organisation name to API token;
     *                        must not be {@code null}
     * @param apiRoot         the REST API root (no trailing slash), e.g.
     *                        {@code http://127.0.0.1:12345}
     */
    public GitHubProxyHandler(Map<String, String> githubOrgTokens, String apiRoot) {
        this.githubOrgTokens = githubOrgTokens;
        this.apiRoot = apiRoot != null && !apiRoot.isEmpty() ? apiRoot : DEFAULT_API_ROOT;
    }

    /**
     * Replaces the per-org token map used for proxy requests.
     *
     * @param githubOrgTokens the new map of organisation name to token
     */
    public void setGithubOrgTokens(Map<String, String> githubOrgTokens) {
        this.githubOrgTokens = githubOrgTokens;
    }

    /**
     * Handles {@code GET}, {@code POST}, {@code PUT}, or {@code PATCH} requests to
     * {@code /api/github/proxy}.
     *
     * <p>The {@code url} query parameter specifies either a full GitHub API URL
     * starting with {@code https://} or a path such as
     * {@code /repos/owner/repo/pulls}. The request body (for POST/PUT/PATCH) is
     * forwarded verbatim to the GitHub API. A PATCH is sent through
     * {@link HttpClient}, because {@link HttpURLConnection} rejects that verb.
     * The response is wrapped as:</p>
     * <pre>{@code {"status": 200, "link": "<pagination>", "body": <github-json>}}</pre>
     *
     * <p>A request whose method is none of GET/POST/PUT/PATCH is refused with an
     * error response rather than downgraded to a GET, so a caller that asks for a
     * write is never silently answered with a read.</p>
     *
     * @param session  the NanoHTTPD session containing query parameters and body
     * @param method   the HTTP method of the incoming request
     * @param readBody a callback that reads the raw POST/PUT/PATCH body from the session
     * @param error    a callback that builds a 400 error response
     * @return a NanoHTTPD response wrapping the GitHub API result
     */
    public Response handle(IHTTPSession session, Method method,
                    BodyReader readBody, ErrorResponder error) {
        // Resolve org: explicit ?org= param, then extract from URL path
        String org = session.getParms().get("org");
        if (org == null || org.isEmpty()) {
            String urlOrPathParam = session.getParms().get("url");
            if (urlOrPathParam != null) {
                String path = urlOrPathParam.startsWith("https://")
                    ? urlOrPathParam.replaceFirst("https://api\\.github\\.com", "")
                    : urlOrPathParam;
                if (path.startsWith("/repos/")) {
                    String afterRepos = path.substring("/repos/".length());
                    int slash = afterRepos.indexOf('/');
                    if (slash > 0) {
                        org = afterRepos.substring(0, slash);
                    }
                }
            }
        }

        String token = resolveGithubToken(org);
        if (token == null) {
            String detail = (org != null && !org.isEmpty())
                    ? "No GitHub token configured for org '" + org
                      + "' (configured orgs: " + githubOrgTokens.keySet() + ")"
                    : "No GitHub org token available (configured orgs: "
                      + githubOrgTokens.keySet() + ")";
            warn("GitHub proxy token resolution failed: " + detail);
            return error.respond(detail);
        }

        String urlOrPath = session.getParms().get("url");
        if (urlOrPath == null || urlOrPath.isEmpty()) {
            return error.respond("Missing required query parameter: url");
        }

        String githubMethod;
        if (Method.GET.equals(method)) {
            githubMethod = "GET";
        } else if (Method.POST.equals(method)) {
            githubMethod = "POST";
        } else if (Method.PUT.equals(method)) {
            githubMethod = "PUT";
        } else if (Method.PATCH.equals(method)) {
            githubMethod = "PATCH";
        } else {
            // Refuse an unsupported verb rather than downgrade it to a GET.
            return error.respond("Unsupported method for GitHub proxy: " + method
                    + "; supported methods are GET, POST, PUT, PATCH");
        }

        // Read body for POST/PUT/PATCH requests (forwarded to GitHub as-is). A
        // reader may reject a body it will not read (see BodyReader); that
        // rejection becomes a 4xx rather than an unbounded allocation.
        String payload = null;
        if (!"GET".equals(githubMethod)) {
            try {
                payload = readBody.read(session);
            } catch (BodyReader.InvalidRequestBodyException e) {
                warn("Rejecting GitHub proxy " + githubMethod + ": " + e.getMessage());
                return error.respond("Invalid request body: " + e.getMessage());
            }
        }

        // Resolve full GitHub API URL
        // A path resolves against the configured API root; in production that
        // is the public API, so a test can redirect the forward to a stub.
        String fullUrl;
        if (urlOrPath.startsWith("https://")) {
            fullUrl = urlOrPath;
        } else {
            fullUrl = apiRoot + urlOrPath;
        }

        log("GitHub proxy " + githubMethod + " " + urlOrPath);

        if ("PATCH".equals(githubMethod)) {
            return forwardPatch(fullUrl, payload, token, error);
        }

        try {
            URL url = URI.create(fullUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(githubMethod);
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);

            if (("POST".equals(githubMethod) || "PUT".equals(githubMethod))
                    && payload != null && !payload.isEmpty()) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.close();
            }

            int status = conn.getResponseCode();
            String linkHeader = conn.getHeaderField("Link");

            if (isRedirect(status) && "GET".equals(githubMethod)) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) {
                    return error.respond("GitHub proxy error: HTTP " + status
                            + " without a Location header");
                }
                conn = follow(location, token);
                status = conn.getResponseCode();
            }

            String contentType = conn.getContentType();

            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = "";
            if (is != null) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();
            }

            return wrapResponse(status, linkHeader, responseBody, contentType);
        } catch (Exception e) {
            log("GitHub proxy error: " + e.getMessage());
            return error.respond("GitHub proxy error: " + e.getMessage());
        }
    }

    /**
     * Forwards a proxied {@code PATCH} to GitHub and wraps the result like any
     * other proxied response.
     *
     * @param fullUrl the fully-resolved GitHub API URL
     * @param payload the JSON body to forward, or {@code null} for an empty body
     * @param token   the GitHub API bearer token
     * @param error   a callback that builds an error response
     * @return the wrapped GitHub response, or an error response on transport failure
     */
    private Response forwardPatch(String fullUrl, String payload, String token,
                                  ErrorResponder error) {
        try {
            HttpResponse<String> response = sendPatch(fullUrl, payload == null ? "" : payload, token);
            return wrapResponse(response.statusCode(),
                    response.headers().firstValue("Link").orElse(null),
                    response.body() == null ? "" : response.body(),
                    response.headers().firstValue("Content-Type").orElse(null));
        } catch (IOException e) {
            log("GitHub proxy error: " + e.getMessage());
            return error.respond("GitHub proxy error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error.respond("GitHub proxy error: PATCH interrupted");
        }
    }

    /**
     * Wraps a GitHub response in the proxy envelope
     * {@code {"status":N,"link":"...","body":<github-json-or-string>}}.
     *
     * @param status       the HTTP status GitHub returned
     * @param linkHeader   the pagination {@code Link} header, or {@code null}
     * @param responseBody the raw response body
     * @param contentType  the response content type, or {@code null}
     * @return a 200 response carrying the envelope
     */
    private Response wrapResponse(int status, String linkHeader,
                                  String responseBody, String contentType) {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":").append(status);
        json.append(",\"link\":\"")
            .append(JsonFieldExtractor.escapeJson(linkHeader != null ? linkHeader : ""))
            .append("\"");
        json.append(",\"body\":").append(encodeBody(responseBody, contentType));
        json.append("}");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /**
     * Returns whether a status code asks the client to make the request again elsewhere.
     *
     * @param status the HTTP status returned by GitHub
     * @return true if the status carries a {@code Location} to follow
     */
    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    /**
     * Opens a connection to a redirect target, carrying the API credential only when
     * the target is the API itself.
     *
     * <p>Redirects are followed here rather than by {@link HttpURLConnection} so that
     * this distinction can be made. The endpoints that serve a file — Actions job logs,
     * artifact and release downloads — redirect to a storage host with a signature
     * already in the URL, and replaying an {@code Authorization} header to a host
     * outside the API is both unnecessary and a disclosure of the token. Redirects that
     * stay on the API, such as the permanent redirect a renamed repository answers with,
     * still need it.</p>
     *
     * <p>The read timeout is longer than the API's because what arrives through a
     * redirect is a file rather than a document — a job log runs to megabytes.</p>
     *
     * <p>Only a GET is followed, which is why this replays the request as one. A
     * redirect answering a POST or PUT is returned to the caller untouched instead:
     * 307 and 308 preserve the method by definition, so re-issuing one as a GET would
     * discard the payload and quietly turn a write into a read.</p>
     *
     * @param location the URL from the {@code Location} header
     * @param token    the GitHub token for the organisation
     * @return an open connection to the target, its response not yet read
     * @throws IOException if the target cannot be reached
     */
    private static HttpURLConnection follow(String location, String token) throws IOException {
        URL target = URI.create(location).toURL();
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();

        conn.setRequestMethod("GET");
        if ("api.github.com".equalsIgnoreCase(target.getHost())) {
            // Same negotiation as the request that was redirected, so a redirect that
            // stays on the API yields the same envelope a direct response would have.
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(false);

        return conn;
    }

    /**
     * Renders a GitHub response body as the {@code body} member of the proxy envelope.
     *
     * <p>A JSON body is spliced in as it stands, so callers receive the structure GitHub
     * sent. Anything else is a document rather than a structure — job logs and raw file
     * contents are the cases that occur — and becomes a JSON string, since splicing text
     * in unquoted would produce an envelope that does not parse.</p>
     *
     * @param body        the response body, possibly empty
     * @param contentType the response {@code Content-Type}, or null if absent
     * @return the body encoded as a JSON value
     */
    private static String encodeBody(String body, String contentType) {
        if (body.isEmpty()) return "null";
        if (contentType != null && contentType.contains("json")) return body;

        return "\"" + JsonFieldExtractor.escapeJson(body) + "\"";
    }

    /**
     * Creates a GitHub pull request using the resolved token for the given organisation.
     *
     * @param ownerRepo  the {@code owner/repo} path component
     * @param head       the head (source) branch name
     * @param base       the base (target) branch name
     * @param title      the pull request title
     * @param body       the pull request body text
     * @param draft      whether to open the pull request as a draft
     * @param token      the GitHub API bearer token
     * @return the HTML URL of the created pull request, or {@code null} on failure
     */
    public String createGitHubPullRequest(String ownerRepo, String head, String base,
                                   String title, String body, boolean draft, String token) {
        ApiResponse response = apiPost(apiRoot + "/repos/" + ownerRepo + "/pulls",
                pullRequestPayload(title, head, base, body, draft), token);
        if (response == null) {
            return null;
        }
        if (response.status() == 201) {
            Matcher m = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
            if (m.find()) {
                String prUrl = m.group(1);
                log("Auto-created PR: " + prUrl);
                return prUrl;
            }
        }
        log("GitHub PR creation returned HTTP " + response.status() + ": " + response.excerpt());
        return null;
    }

    /**
     * Builds the JSON body of a create-pull-request request.
     *
     * <p>The {@code draft} member is emitted only when the pull request is
     * opened as a draft: GitHub defaults a pull request to ready-for-review,
     * so a {@code false} is the same as omitting it, and omitting it keeps the
     * body byte-identical to what every non-draft caller sent before.</p>
     *
     * @param title the pull request title
     * @param head  the head (source) branch name
     * @param base  the base (target) branch name
     * @param body  the pull request body text
     * @param draft whether the pull request should be opened as a draft
     * @return the request body as a JSON object string
     */
    static String pullRequestPayload(String title, String head, String base,
                                   String body, boolean draft) {
        return "{\"title\":\"" + JsonFieldExtractor.escapeJson(title)
                + "\",\"head\":\"" + JsonFieldExtractor.escapeJson(head)
                + "\",\"base\":\"" + JsonFieldExtractor.escapeJson(base)
                + "\",\"body\":\"" + JsonFieldExtractor.escapeJson(body) + "\""
                + (draft ? ",\"draft\":true" : "")
                + "}";
    }

    /**
     * Maximum length GitHub accepts for a pull request title.
     */
    private static final int MAX_TITLE_LENGTH = 256;

    /**
     * Parses GitHub API responses. Jackson rather than a regex because the
     * commit payload nests the message among many other string fields.
     */
    private static final ObjectMapper GITHUB_MAPPER = new ObjectMapper();

    /**
     * Creates a pull request whose title and body describe the commit on the
     * head branch rather than the job that produced it.
     *
     * <p>The pull request is opened after the agent has pushed, so the commit
     * message — which the agent wrote to describe what it actually did — is
     * already available and is a far better description than the job's
     * submission-time intent. Two jobs submitted with the same description
     * would otherwise open indistinguishable pull requests.</p>
     *
     * <p>Falls back to {@code fallbackDescription} for the title whenever the
     * commit cannot be read or carries no message, which preserves the previous
     * behaviour rather than opening an untitled pull request.</p>
     *
     * @param ownerRepo           the {@code owner/repo} path component
     * @param head                the head (source) branch name
     * @param base                the base (target) branch name
     * @param commitRef           commit SHA, or a branch name for its head commit
     * @param fallbackDescription description to use when no commit message is available
     * @param draft               whether to open the pull request as a draft
     * @param token               the GitHub API bearer token
     * @return the HTML URL of the created pull request, or {@code null} on failure
     */
    public String createPullRequestFromCommit(String ownerRepo, String head, String base,
                                   String commitRef, String fallbackDescription, boolean draft, String token) {
        return createPullRequestFromCommit(ownerRepo, head, base, commitRef,
                fallbackDescription, draft, token, null);
    }

    /**
     * Creates a pull request whose title and body describe the commit on the
     * head branch, with {@code bodySuffix} appended to the derived body.
     *
     * <p>The companion-PR path uses the suffix to append a line linking the
     * primary pull request, so the companion says what it belongs to. A
     * {@code null} or empty suffix behaves exactly like
     * {@link #createPullRequestFromCommit(String, String, String, String, String, boolean, String)}.</p>
     *
     * @param ownerRepo           the {@code owner/repo} path component
     * @param head                the head (source) branch name
     * @param base                the base (target) branch name
     * @param commitRef           commit SHA, or a branch name for its head commit
     * @param fallbackDescription description to use when no commit message is available
     * @param draft               whether to open the pull request as a draft
     * @param token               the GitHub API bearer token
     * @param bodySuffix          text appended to the derived body, or {@code null}
     * @return the HTML URL of the created pull request, or {@code null} on failure
     */
    public String createPullRequestFromCommit(String ownerRepo, String head, String base,
                                   String commitRef, String fallbackDescription, boolean draft,
                                   String token, String bodySuffix) {
        String commitMessage = commitRef == null ? null
                : fetchCommitMessage(ownerRepo, commitRef, token);
        String body = pullRequestBody(commitMessage, fallbackDescription);
        if (bodySuffix != null && !bodySuffix.isEmpty()) {
            body = body + "\n\n" + bodySuffix;
        }
        return createGitHubPullRequest(ownerRepo, head, base,
                pullRequestTitle(commitMessage, fallbackDescription), body, draft, token);
    }

    /**
     * Re-derives {@code existing}'s title and body from the commit on its head
     * branch and updates the pull request when they have drifted — the case
     * where a companion pull request was first opened from a prompt-fallback
     * message, or from an older commit than the branch now carries.
     *
     * <p>A companion pull request is reused across a job's rounds, but its title
     * and body are set only when it is first opened; without this, a title taken
     * from a truncated task prompt sticks to the pull request forever. The update
     * is skipped when the derived title and body already match, so a pull request
     * a human has since titled correctly is not rewritten on every reuse.</p>
     *
     * <p>The refresh is also skipped entirely when the commit's own message
     * cannot be fetched — a transient GitHub/API failure, or a commit that
     * carries no message. Without that message there is nothing better than the
     * generic fallback to refresh toward, and PATCHing the fallback would
     * overwrite the pull request's existing title and body (including a human's
     * edits) with a worse description; a later completion refreshes it instead.</p>
     *
     * @param ownerRepo           the {@code owner/repo} path component
     * @param existing            the open pull request being reused
     * @param commitRef           commit SHA whose message describes the change
     * @param fallbackDescription description used when the commit message yields
     *                            no usable title
     * @param bodySuffix          text appended to the derived body, or {@code null}
     * @param token               the GitHub API bearer token
     */
    public void refreshPullRequestFromCommit(String ownerRepo, PullRequest existing, String commitRef,
                                   String fallbackDescription, String bodySuffix, String token) {
        String commitMessage = commitRef == null ? null
                : fetchCommitMessage(ownerRepo, commitRef, token);
        if (commitMessage == null) {
            log("Not refreshing companion PR #" + existing.number() + " in " + ownerRepo
                    + ": no commit message available for " + commitRef);
            return;
        }
        String desiredTitle = pullRequestTitle(commitMessage, fallbackDescription);
        String desiredBody = pullRequestBody(commitMessage, fallbackDescription);
        if (bodySuffix != null && !bodySuffix.isEmpty()) {
            desiredBody = desiredBody + "\n\n" + bodySuffix;
        }
        if (desiredTitle.equals(existing.title()) && desiredBody.equals(existing.body())) {
            return;
        }
        if (updatePullRequest(ownerRepo, existing.number(), desiredTitle, desiredBody, token)) {
            log("Refreshed companion PR #" + existing.number() + " in " + ownerRepo
                    + " from its own commit");
        }
    }

    /**
     * Updates the title and body of an existing pull request.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param number    the pull request number
     * @param title     the new title
     * @param body      the new body (Markdown)
     * @param token     the GitHub API bearer token
     * @return {@code true} when GitHub accepted the update
     */
    public boolean updatePullRequest(String ownerRepo, int number, String title, String body,
                                   String token) {
        String payload = "{\"title\":\"" + JsonFieldExtractor.escapeJson(title)
                + "\",\"body\":\"" + JsonFieldExtractor.escapeJson(body) + "\"}";
        ApiResponse response = apiPatch(apiRoot + "/repos/" + ownerRepo + "/pulls/" + number,
                payload, token);
        if (response == null) {
            return false;
        }
        if (response.status() == 200) {
            return true;
        }
        log("GitHub PR update #" + number + " returned HTTP " + response.status()
                + ": " + response.excerpt());
        return false;
    }

    /**
     * Builds the commit lookup URL for {@code ref}.
     *
     * <p>The ref occupies a single path segment, so a branch name is percent
     * encoded: the branches this is used for are named {@code qa/defect-...}
     * and {@code project/plan-...}, and an unencoded slash would address a
     * different endpoint entirely. A commit SHA is unaffected by the
     * encoding.</p>
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param ref       commit SHA, branch name, or tag
     * @return the GitHub API URL for the commit
     */
    static String commitApiUrl(String ownerRepo, String ref) {
        return commitApiUrl(DEFAULT_API_ROOT, ownerRepo, ref);
    }

    /**
     * Builds the commit lookup URL for {@code ref} against a specific API root.
     * See {@link #commitApiUrl(String, String)} for the ref-encoding rationale;
     * the root is explicit here so an instance can build the URL from its own
     * configured {@link #apiRoot} and a test can point it at a stub server.
     *
     * @param apiRoot   the REST API root (no trailing slash)
     * @param ownerRepo the {@code owner/repo} path component
     * @param ref       commit SHA, branch name, or tag
     * @return the commit API URL
     */
    static String commitApiUrl(String apiRoot, String ownerRepo, String ref) {
        return apiRoot + "/repos/" + ownerRepo + "/commits/"
                + URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Returns every pull request on the given repository, newest-updated
     * first. Used by the workstream listing's PR enrichment: one call per
     * repository is enough because GitHub's {@code head} filter is a single
     * repository-wide scan, and any workstream's PR can be looked up
     * client-side from the response. Missing token or transport failure
     * returns {@code null} rather than throwing, so the listing can fall
     * back to "no PR data" for the affected rows.
     *
     * @param ownerRepo the {@code owner/repo} path component (e.g.
     *                  {@code "almostrealism/common"})
     * @return the GitHub response as a parsed Jackson array, or {@code null}
     */
    // TODO(review): single page, per_page=100, no pagination — a repo with >100 PRs
    // (state=all) can silently miss an old branch's merged/closed PR. See review-followup memory.
    public JsonNode listPullRequestsByRepo(String ownerRepo) {
        return queryPullRequests(ownerRepo, "state=all&per_page=100&sort=updated&direction=desc");
    }

    /**
     * Returns every pull request, open or closed, whose head is {@code branch}
     * in {@code ownerRepo} itself (not a fork), newest first. Unlike
     * {@link #listPullRequestsByRepo}, the branch is filtered by GitHub, so an
     * old branch's pull request is found however many others the repository
     * has. Missing token or transport failure returns {@code null}.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param branch    the head branch name
     * @return the branch's pull requests (empty when it has none), or
     *         {@code null} when GitHub could not be asked
     */
    public List<PullRequest> listPullRequestsForBranch(String ownerRepo, String branch) {
        if (ownerRepo == null || branch == null || branch.isEmpty()) return null;
        String owner = ownerRepo.substring(0, Math.max(0, ownerRepo.indexOf('/')));
        JsonNode listing = queryPullRequests(ownerRepo, "state=all&per_page=100&head="
                + URLEncoder.encode(owner + ":" + branch, StandardCharsets.UTF_8));
        if (listing == null || !listing.isArray()) return null;
        List<PullRequest> prs = new ArrayList<>();
        listing.forEach(pr -> prs.add(new PullRequest(pr)));
        return prs;
    }

    /**
     * Lists pull requests on {@code ownerRepo} matching the {@code query} string
     * of GitHub's list-pulls endpoint.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param query     the URL query, already encoded
     * @return the parsed array, or {@code null} without a token or on failure
     */
    private JsonNode queryPullRequests(String ownerRepo, String query) {
        if (ownerRepo == null || ownerRepo.isEmpty()) return null;
        String org = extractOrgFromRepoUrl("https://github.com/" + ownerRepo);
        String token = resolveGithubToken(org);
        if (token == null) {
            log("No GitHub token configured for org '" + org
                + "'; PR lookup for " + ownerRepo + " will be skipped");
            return null;
        }
        return apiGet(apiRoot + "/repos/" + ownerRepo + "/pulls?" + query, token);
    }

    /**
     * Reads the commit message for {@code ref}.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param ref       commit SHA, branch name, or tag
     * @param token     the GitHub API bearer token
     * @return the commit message, or {@code null} when it cannot be read
     */
    public String fetchCommitMessage(String ownerRepo, String ref, String token) {
        JsonNode root = apiGet(commitApiUrl(apiRoot, ownerRepo, ref), token);
        if (root == null) {
            return null;
        }
        JsonNode message = root.path("commit").path("message");
        if (!message.isMissingNode() && !message.asText().isBlank()) {
            return message.asText();
        }
        log("Commit " + ref + " carries no message; falling back to the job description");
        return null;
    }

    /**
     * Returns the repository's default branch — the branch a companion pull
     * request targets.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param token     the GitHub API bearer token
     * @return the {@code default_branch}, or {@code null} when it cannot be read
     */
    public String fetchDefaultBranch(String ownerRepo, String token) {
        JsonNode root = apiGet(apiRoot + "/repos/" + ownerRepo, token);
        if (root == null) {
            return null;
        }
        return root.path("default_branch").asText(null);
    }

    /**
     * Reports whether {@code head} is ahead of {@code base} — the test for
     * "the branch differs from the default branch". A branch that is not ahead
     * carries no commits the base lacks, so no companion pull request is opened
     * for it.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param base      the base branch (typically the repo's default branch)
     * @param head      the head branch (the job's target branch)
     * @param token     the GitHub API bearer token
     * @return {@code true} when GitHub reports {@code ahead_by > 0}; {@code false}
     *         when it is not ahead or the comparison could not be read
     */
    public boolean isAhead(String ownerRepo, String base, String head, String token) {
        JsonNode root = apiGet(apiRoot + "/repos/" + ownerRepo + "/compare/"
                + URLEncoder.encode(base, StandardCharsets.UTF_8).replace("+", "%20") + "..."
                + URLEncoder.encode(head, StandardCharsets.UTF_8).replace("+", "%20"), token);
        return root != null && root.path("ahead_by").asInt(0) > 0;
    }

    /**
     * Returns the open pull request whose head is {@code branch} in
     * {@code ownerRepo}, or {@code null} when none is open. Built on
     * {@link #listPullRequestsForBranch}, so it reuses, rather than duplicates,
     * an already-open companion pull request.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param branch    the head branch name
     * @return the first open pull request on {@code branch}, or {@code null}
     */
    public PullRequest findOpenPullRequest(String ownerRepo, String branch) {
        List<PullRequest> prs = listPullRequestsForBranch(ownerRepo, branch);
        if (prs == null) {
            return null;
        }
        for (PullRequest pr : prs) {
            if (pr.isOpen()) {
                return pr;
            }
        }
        return null;
    }

    /**
     * Posts a comment on a pull request's conversation.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param number    the pull request number
     * @param body      the comment body (Markdown)
     * @param token     the GitHub API bearer token
     * @return {@code true} when GitHub accepted the comment
     */
    public boolean commentOnPullRequest(String ownerRepo, int number, String body, String token) {
        String url = apiRoot + "/repos/" + ownerRepo + "/issues/" + number + "/comments";
        String payload = "{\"body\":\"" + JsonFieldExtractor.escapeJson(body) + "\"}";
        ApiResponse response = apiPost(url, payload, token);
        if (response == null) {
            return false;
        }
        if (response.status() == 201) {
            return true;
        }
        log("GitHub comment on PR #" + number + " returned HTTP " + response.status()
                + ": " + response.excerpt());
        return false;
    }

    /**
     * Returns the commit SHA at the head of {@code branch}.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param branch    the branch name
     * @param token     the GitHub API bearer token
     * @return the head commit SHA, or {@code null} when it cannot be read
     */
    public String fetchHeadSha(String ownerRepo, String branch, String token) {
        JsonNode root = apiGet(commitApiUrl(apiRoot, ownerRepo, branch), token);
        return root == null ? null : root.path("sha").asText(null);
    }

    /**
     * Reports whether a deployment of commit {@code sha} to {@code environment}
     * already exists. A deployment that exists has already started whatever
     * workflow the {@code deployment} event triggers for that commit, so a
     * caller that wants one run per commit creates no second deployment.
     *
     * @param ownerRepo   the {@code owner/repo} path component
     * @param sha         the commit SHA
     * @param environment the deployment environment name
     * @param token       the GitHub API bearer token
     * @return {@code true} when GitHub lists at least one such deployment;
     *         {@code false} when it lists none or the listing could not be read
     */
    public boolean hasDeployment(String ownerRepo, String sha, String environment, String token) {
        JsonNode root = apiGet(apiRoot + "/repos/" + ownerRepo + "/deployments?sha=" + encodeQuery(sha)
                + "&environment=" + encodeQuery(environment) + "&per_page=1", token);
        return root != null && root.isArray() && root.size() > 0;
    }

    /**
     * Creates a deployment of {@code ref} to {@code environment}, which starts
     * every workflow the repository triggers on the {@code deployment} event.
     *
     * <p>The deployment never merges the default branch into {@code ref}
     * ({@code auto_merge} off) and does not wait for the commit's statuses
     * ({@code required_contexts} empty): what it deploys is the branch head as
     * it is, and deciding whether that head is good is the triggered
     * workflow's job. The environment is transient, so an older deployment of
     * the same branch goes inactive when a newer one succeeds.</p>
     *
     * <p>Must be called with a personal or app token: GitHub does not start
     * workflows from a deployment created with a workflow's own token.</p>
     *
     * @param ownerRepo   the {@code owner/repo} path component
     * @param ref         the branch (or SHA) to deploy
     * @param environment the deployment environment name
     * @param description a short description recorded on the deployment
     * @param token       the GitHub API bearer token
     * @return {@code true} when GitHub created the deployment
     */
    public boolean createDeployment(String ownerRepo, String ref, String environment,
                                    String description, String token) {
        String payload = "{\"ref\":\"" + JsonFieldExtractor.escapeJson(ref)
                + "\",\"environment\":\"" + JsonFieldExtractor.escapeJson(environment)
                + "\",\"description\":\"" + JsonFieldExtractor.escapeJson(description)
                + "\",\"auto_merge\":false,\"required_contexts\":[]"
                + ",\"transient_environment\":true,\"production_environment\":false}";
        ApiResponse response = apiPost(apiRoot + "/repos/" + ownerRepo + "/deployments", payload, token);
        if (response == null) {
            return false;
        }
        if (response.status() == 201) {
            log("Created " + environment + " deployment of " + ownerRepo + "@" + ref);
            return true;
        }
        log("GitHub deployment of " + ownerRepo + "@" + ref + " returned HTTP "
                + response.status() + ": " + response.excerpt());
        return false;
    }

    /**
     * Reports whether {@code workflowFile} already has a run for commit
     * {@code sha}, in any state.
     *
     * @param ownerRepo    the {@code owner/repo} path component
     * @param workflowFile the workflow file name, e.g. {@code tests.yaml}
     * @param sha          the commit SHA
     * @param token        the GitHub API bearer token
     * @return {@code true} when GitHub lists at least one run; {@code false}
     *         when it lists none or the listing could not be read
     */
    public boolean hasWorkflowRun(String ownerRepo, String workflowFile, String sha, String token) {
        JsonNode root = apiGet(workflowApiUrl(ownerRepo, workflowFile) + "/runs?head_sha="
                + encodeQuery(sha) + "&per_page=1", token);
        return root != null && root.path("total_count").asInt(0) > 0;
    }

    /**
     * Starts {@code workflowFile} on {@code ref} through its
     * {@code workflow_dispatch} trigger.
     *
     * @param ownerRepo    the {@code owner/repo} path component
     * @param workflowFile the workflow file name, e.g. {@code tests.yaml}
     * @param ref          the branch to run the workflow on
     * @param token        the GitHub API bearer token
     * @return {@code true} when GitHub accepted the dispatch
     */
    public boolean dispatchWorkflow(String ownerRepo, String workflowFile, String ref, String token) {
        String payload = "{\"ref\":\"" + JsonFieldExtractor.escapeJson(ref) + "\"}";
        ApiResponse response = apiPost(workflowApiUrl(ownerRepo, workflowFile) + "/dispatches",
                payload, token);
        if (response == null) {
            return false;
        }
        if (response.status() == 204) {
            log("Dispatched " + workflowFile + " on " + ownerRepo + "@" + ref);
            return true;
        }
        log("GitHub dispatch of " + workflowFile + " on " + ownerRepo + "@" + ref
                + " returned HTTP " + response.status() + ": " + response.excerpt());
        return false;
    }

    /**
     * Builds the REST URL of a workflow, addressed by its file name.
     *
     * @param ownerRepo    the {@code owner/repo} path component
     * @param workflowFile the workflow file name
     * @return the workflow's API URL (no trailing slash)
     */
    private String workflowApiUrl(String ownerRepo, String workflowFile) {
        return apiRoot + "/repos/" + ownerRepo + "/actions/workflows/"
                + URLEncoder.encode(workflowFile, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Percent-encodes a query parameter value.
     *
     * @param value the raw value
     * @return the encoded value
     */
    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Assembles the pull-request facts a cap-reached remediation ping lists for
     * the open pull request on {@code branch}: its page, its unresolved review
     * threads, and the TODO lines the branch added. Best-effort — a lookup that
     * cannot be made (no token, no open PR, a transport failure) contributes
     * nothing rather than failing the ping, so a person is still told the cap was
     * reached.
     *
     * @param repoUrl the repository URL the workstream names
     * @param branch  the workstream's branch (the pull request head)
     * @return the escalation facts, or {@link PullRequestEscalation#NONE} when no
     *         open pull request could be resolved
     */
    public PullRequestEscalation escalationFor(String repoUrl, String branch) {
        String ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null || branch == null || branch.isEmpty()) {
            return PullRequestEscalation.NONE;
        }
        String token = resolveGithubToken(extractOrgFromRepoUrl(repoUrl));
        if (token == null) return PullRequestEscalation.NONE;
        PullRequest pr = findOpenPullRequest(ownerRepo, branch);
        if (pr == null) return PullRequestEscalation.NONE;
        int number = pr.number();
        String headSha = pr.json().path("head").path("sha").asText(null);
        return new PullRequestEscalation(pr.htmlUrl(),
                unresolvedReviewThreads(ownerRepo, number, token),
                branchAddedTodos(ownerRepo, number, headSha, token));
    }

    /**
     * Lists the pull request's unresolved review threads, each anchored to the
     * file and line of its first comment and carrying that comment's URL. Uses
     * the GraphQL API, the only one that reports a thread's resolved state.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param number    the pull request number
     * @param token     the GitHub API bearer token
     * @return the unresolved threads, newest-anchored first; empty on any failure
     */
    private List<PullRequestEscalation.Finding> unresolvedReviewThreads(
            String ownerRepo, int number, String token) {
        List<PullRequestEscalation.Finding> findings = new ArrayList<>();
        int slash = ownerRepo.indexOf('/');
        if (slash < 0) return findings;
        String owner = ownerRepo.substring(0, slash);
        String name = ownerRepo.substring(slash + 1);
        String query = "{\"query\":\"query{repository(owner:\\\"" + owner + "\\\",name:\\\"" + name
            + "\\\"){pullRequest(number:" + number + "){reviewThreads(first:100){nodes{isResolved "
            + "comments(first:1){nodes{path line url body}}}}}}}\"}";
        JsonNode root = apiGraphQl(query, token);
        if (root == null) return findings;
        JsonNode nodes = root.path("data").path("repository").path("pullRequest")
                .path("reviewThreads").path("nodes");
        if (!nodes.isArray()) return findings;
        for (JsonNode thread : nodes) {
            if (thread.path("isResolved").asBoolean(false)) continue;
            JsonNode comment = thread.path("comments").path("nodes").path(0);
            if (comment.isMissingNode()) continue;
            findings.add(PullRequestEscalation.Finding.reviewComment(
                    comment.path("path").asText(""),
                    comment.path("line").asInt(0),
                    comment.path("url").asText(null),
                    comment.path("body").asText("")));
        }
        return findings;
    }

    /**
     * Lists the TODO lines the branch added, from the unified-diff patches in the
     * pull request's files listing. A renamed file reports its patch under its new
     * name, so a TODO moved into a renamed file is attributed to the new path.
     *
     * @param ownerRepo the {@code owner/repo} path component
     * @param number    the pull request number
     * @param headSha   the pull request head SHA, for stable blob links; may be {@code null}
     * @param token     the GitHub API bearer token
     * @return the added TODO lines; empty on any failure
     */
    private List<PullRequestEscalation.Finding> branchAddedTodos(
            String ownerRepo, int number, String headSha, String token) {
        List<PullRequestEscalation.Finding> todos = new ArrayList<>();
        JsonNode files = apiGet(apiRoot + "/repos/" + ownerRepo + "/pulls/" + number
                + "/files?per_page=100", token);
        if (files == null || !files.isArray()) return todos;
        for (JsonNode file : files) {
            String path = file.path("filename").asText("");
            String patch = file.path("patch").asText("");
            if (path.isEmpty() || patch.isEmpty()) continue;
            String blobUrl = headSha != null
                    ? "https://github.com/" + ownerRepo + "/blob/" + headSha + "/" + path
                    : null;
            todos.addAll(PullRequestEscalation.Finding.addedTodos(path, patch, blobUrl));
        }
        return todos;
    }

    /**
     * Issues a GraphQL {@code POST} and returns the parsed body on a {@code 200},
     * or {@code null} otherwise. The GraphQL endpoint sits at {@code /graphql}
     * beside the REST root, so a test's stub root is honoured.
     *
     * @param body  the already-built GraphQL request body
     * @param token the GitHub API bearer token
     * @return the parsed response body, or {@code null}
     */
    private JsonNode apiGraphQl(String body, String token) {
        try {
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(apiRoot + "/graphql").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = is != null
                    ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
            if (is != null) is.close();
            if (status == 200) return GITHUB_MAPPER.readTree(responseBody);
            log("GitHub GraphQL returned HTTP " + status + ": "
                + responseBody.substring(0, Math.min(200, responseBody.length())));
        } catch (IOException e) {
            log("GitHub GraphQL failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * The status and body of a REST call that completed with any HTTP status.
     *
     * @param status the HTTP status code
     * @param body   the response body, empty when there was none
     */
    private record ApiResponse(int status, String body) {

        /**
         * Returns the start of the body, for a log line about an unexpected
         * status.
         *
         * @return at most the first 200 characters of the body
         */
        String excerpt() {
            return body.substring(0, Math.min(200, body.length()));
        }
    }

    /**
     * Sends a JSON {@code POST} to the GitHub REST API. Shared by the typed
     * write operations so each reads as what it changes rather than repeating
     * the connection boilerplate.
     *
     * @param url     the fully-built request URL
     * @param payload the JSON request body
     * @param token   the GitHub API bearer token
     * @return the response, whatever its status; {@code null} on a transport
     *         failure, which is logged
     */
    private ApiResponse apiPost(String url, String payload, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = "";
            if (is != null) {
                try (InputStream in = is) {
                    responseBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new ApiResponse(status, responseBody);
        } catch (IOException e) {
            log("GitHub POST " + url + " failed: " + e.getMessage());
            return null;
        }
    }

    /** Client for the PATCH verb, which {@link HttpURLConnection} cannot send. */
    private static final HttpClient PATCH_CLIENT = HttpClient.newHttpClient();

    /**
     * Sends a JSON {@code PATCH} to the GitHub REST API. {@link HttpURLConnection}
     * rejects {@code PATCH} as an unsupported method, so this one write verb goes
     * through {@link HttpClient} rather than the shared {@link #apiPost} path.
     *
     * @param url     the fully-built request URL
     * @param payload the JSON request body
     * @param token   the GitHub API bearer token
     * @return the response, whatever its status; {@code null} on a transport
     *         failure, which is logged
     */
    private ApiResponse apiPatch(String url, String payload, String token) {
        try {
            HttpResponse<String> response = sendPatch(url, payload, token);
            return new ApiResponse(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (IOException e) {
            log("GitHub PATCH " + url + " failed: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("GitHub PATCH " + url + " interrupted");
            return null;
        }
    }

    /**
     * Sends a JSON {@code PATCH} to the GitHub REST API through {@link HttpClient}.
     *
     * @param url     the fully-built request URL
     * @param payload the JSON request body
     * @param token   the GitHub API bearer token
     * @return the response, whatever its status
     * @throws IOException          on a transport failure
     * @throws InterruptedException if the calling thread is interrupted
     */
    private HttpResponse<String> sendPatch(String url, String payload, String token)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token.trim())
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return PATCH_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Issues a {@code GET} against the GitHub API and returns the parsed body on
     * a {@code 200}, or {@code null} on any non-200 or transport failure. Shared
     * by the typed {@code GET} operations so each reads as what it fetches rather
     * than repeating the connection boilerplate.
     *
     * @param url   the fully-built request URL
     * @param token the GitHub API bearer token
     * @return the parsed response body, or {@code null}
     */
    private JsonNode apiGet(String url, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = is != null
                    ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
            if (is != null) is.close();

            if (status == 200) {
                return GITHUB_MAPPER.readTree(responseBody);
            }
            log("GitHub GET " + url + " returned HTTP " + status + ": "
                + responseBody.substring(0, Math.min(200, responseBody.length())));
        } catch (IOException e) {
            log("GitHub GET " + url + " failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Derives a pull request title from a commit message.
     *
     * <p>The subject line of a commit message is written to be read on its own,
     * which is exactly what a title needs to be. Over-long subjects are
     * truncated to what GitHub accepts.</p>
     *
     * @param commitMessage the commit message; may be {@code null} or blank
     * @param fallback      title to use when no subject line can be derived
     * @return the pull request title
     */
    static String pullRequestTitle(String commitMessage, String fallback) {
        if (commitMessage != null) {
            for (String line : commitMessage.split("\n", -1)) {
                String subject = line.trim();
                if (!subject.isEmpty()) {
                    return subject.length() <= MAX_TITLE_LENGTH ? subject
                            : subject.substring(0, MAX_TITLE_LENGTH - 3) + "...";
                }
            }
        }
        return fallback;
    }

    /**
     * Derives a pull request body from a commit message.
     *
     * <p>Everything after the subject line, which is the explanation the agent
     * wrote for the change. When the message is a subject alone the job
     * description is used instead, so the pull request still says what kind of
     * work produced it.</p>
     *
     * @param commitMessage the commit message; may be {@code null} or blank
     * @param fallback      body to use when the message carries no explanation
     * @return the pull request body
     */
    static String pullRequestBody(String commitMessage, String fallback) {
        if (commitMessage != null) {
            String[] lines = commitMessage.split("\n", -1);
            int subject = -1;
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].trim().isEmpty()) {
                    subject = i;
                    break;
                }
            }
            if (subject >= 0) {
                String body = String.join("\n",
                        Arrays.copyOfRange(lines, subject + 1, lines.length)).strip();
                if (!body.isEmpty()) return body;
            }
        }
        return fallback;
    }

    /**
     * Resolves the GitHub API token for the given organisation.
     *
     * <p>Performs a case-insensitive lookup in the configured org-token map.
     * When no {@code org} is specified but exactly one org is configured,
     * that token is returned as the default.</p>
     *
     * @param org the GitHub organisation name; may be {@code null}
     * @return the resolved token, or {@code null} if none is available
     */
    public String resolveGithubToken(String org) {
        String token = GitHubOrgs.lookup(githubOrgTokens, org);
        if (token != null) return token;

        // When there is exactly one configured org, use it as the default
        if (githubOrgTokens.size() == 1) {
            return githubOrgTokens.values().iterator().next();
        }

        return null;
    }

    /**
     * Extracts the GitHub {@code owner/repo} path from a repository URL.
     *
     * <p>Supports both HTTPS ({@code https://github.com/owner/repo.git})
     * and SSH ({@code git@github.com:owner/repo.git}) formats; the parsing
     * is {@link RepositoryUrl#getGitHubSlug()}, so a repository
     * hosted anywhere but {@code github.com} yields {@code null} rather than
     * a slug that would address an unrelated GitHub repository.</p>
     *
     * @param repoUrl the repository URL
     * @return the {@code owner/repo} string, or {@code null} if it is not a
     *         GitHub repository URL
     */
    public static String extractOwnerRepo(String repoUrl) {
        return new RepositoryUrl(repoUrl).getGitHubSlug();
    }

    /**
     * Extracts the GitHub organisation name (the owner portion) from a
     * repository URL.
     *
     * @param repoUrl the repository URL (HTTPS or SSH format)
     * @return the organisation name, or {@code null} if not parseable
     */
    public static String extractOrgFromRepoUrl(String repoUrl) {
        String ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return null;
        int slash = ownerRepo.indexOf('/');
        return slash > 0 ? ownerRepo.substring(0, slash) : null;
    }

    /**
     * Functional interface used by {@link #handle} to read the POST/PUT
     * body from a NanoHTTPD session without creating a direct dependency on
     * the session-parsing logic in {@link FlowTreeApiEndpoint}.
     */
    @FunctionalInterface
    public interface BodyReader {

        /**
         * Reads and returns the raw body string from the given session.
         *
         * @param session the NanoHTTPD session
         * @return the body string, or {@code null} on error
         * @throws InvalidRequestBodyException when the request declares a body
         *         the reader will not read — see that type
         */
        String read(IHTTPSession session);

        /**
         * Signals that a request declared a body the proxy refuses to read: a
         * {@code Content-Length} that is missing, unparseable, negative, or
         * above the reader's size limit. {@link GitHubProxyHandler#handle}
         * converts it into a 4xx error response, so an abusive or malformed
         * declaration is rejected rather than allocated for. Unchecked so a
         * reader that never rejects keeps the plain {@link #read} contract.
         */
        class InvalidRequestBodyException extends RuntimeException {

            /**
             * @param message the human-readable reason the body was rejected
             */
            public InvalidRequestBodyException(String message) {
                super(message);
            }
        }
    }

    /**
     * Functional interface used by {@link #handle} to build error responses
     * without coupling this class to the full NanoHTTPD response API.
     */
    @FunctionalInterface
    public interface ErrorResponder {

        /**
         * Builds an HTTP error response containing the given message.
         *
         * @param message the human-readable error description
         * @return a NanoHTTPD response with an appropriate error status
         */
        Response respond(String message);
    }
}
