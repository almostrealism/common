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

package io.flowtree.slack;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
class GitHubProxyHandler implements ConsoleFeatures {

    /** Maps GitHub organisation names (case-insensitive) to their API access tokens. */
    private Map<String, String> githubOrgTokens;

    /**
     * Constructs a new handler with the supplied per-org token map.
     *
     * @param githubOrgTokens map of GitHub organisation name to API token;
     *                        must not be {@code null}
     */
    GitHubProxyHandler(Map<String, String> githubOrgTokens) {
        this.githubOrgTokens = githubOrgTokens;
    }

    /**
     * Replaces the per-org token map used for proxy requests.
     *
     * @param githubOrgTokens the new map of organisation name to token
     */
    void setGithubOrgTokens(Map<String, String> githubOrgTokens) {
        this.githubOrgTokens = githubOrgTokens;
    }

    /**
     * Handles {@code GET}, {@code POST}, or {@code PUT} requests to
     * {@code /api/github/proxy}.
     *
     * <p>The {@code url} query parameter specifies either a full GitHub API URL
     * starting with {@code https://} or a path such as
     * {@code /repos/owner/repo/pulls}. The request body (for POST/PUT) is
     * forwarded verbatim to the GitHub API. The response is wrapped as:</p>
     * <pre>{@code {"status": 200, "link": "<pagination>", "body": <github-json>}}</pre>
     *
     * @param session  the NanoHTTPD session containing query parameters and body
     * @param method   the HTTP method of the incoming request
     * @param readBody a callback that reads the raw POST/PUT body from the session
     * @param error    a callback that builds a 400 error response
     * @return a NanoHTTPD response wrapping the GitHub API result
     */
    Response handle(IHTTPSession session, Method method,
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
        if (Method.POST.equals(method)) {
            githubMethod = "POST";
        } else if (Method.PUT.equals(method)) {
            githubMethod = "PUT";
        } else {
            githubMethod = "GET";
        }

        // Read body for POST/PUT requests (forwarded to GitHub as-is)
        String payload = null;
        if (Method.POST.equals(method) || Method.PUT.equals(method)) {
            payload = readBody.read(session);
        }

        // Resolve full GitHub API URL
        String fullUrl;
        if (urlOrPath.startsWith("https://")) {
            fullUrl = urlOrPath;
        } else {
            fullUrl = "https://api.github.com" + urlOrPath;
        }

        log("GitHub proxy " + githubMethod + " " + urlOrPath);

        try {
            URL url = URI.create(fullUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(githubMethod);
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

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

            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = "";
            if (is != null) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();
            }

            // Wrap response: {"status":N,"link":"...","body":<raw-github-json>}
            StringBuilder json = new StringBuilder();
            json.append("{\"status\":").append(status);
            json.append(",\"link\":\"")
                .append(escapeJson(linkHeader != null ? linkHeader : ""))
                .append("\"");
            json.append(",\"body\":")
                .append(responseBody.isEmpty() ? "null" : responseBody);
            json.append("}");

            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                    "application/json", json.toString());
        } catch (Exception e) {
            log("GitHub proxy error: " + e.getMessage());
            return error.respond("GitHub proxy error: " + e.getMessage());
        }
    }

    /**
     * Creates a GitHub pull request using the resolved token for the given organisation.
     *
     * @param ownerRepo  the {@code owner/repo} path component
     * @param head       the head (source) branch name
     * @param base       the base (target) branch name
     * @param title      the pull request title
     * @param body       the pull request body text
     * @param token      the GitHub API bearer token
     * @return the HTML URL of the created pull request, or {@code null} on failure
     */
    String createGitHubPullRequest(String ownerRepo, String head, String base,
                                   String title, String body, String token) {
        try {
            String apiUrl = "https://api.github.com/repos/" + ownerRepo + "/pulls";
            String payload = "{\"title\":\"" + escapeJson(title)
                    + "\",\"head\":\"" + escapeJson(head)
                    + "\",\"base\":\"" + escapeJson(base)
                    + "\",\"body\":\"" + escapeJson(body) + "\"}";

            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.close();

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = is != null
                    ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
            if (is != null) is.close();

            if (status == 201) {
                Matcher m = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"").matcher(responseBody);
                if (m.find()) {
                    String prUrl = m.group(1);
                    log("Auto-created PR: " + prUrl);
                    return prUrl;
                }
            }

            log("GitHub PR creation returned HTTP " + status + ": "
                + responseBody.substring(0, Math.min(200, responseBody.length())));
        } catch (IOException e) {
            log("GitHub PR creation failed: " + e.getMessage());
        }
        return null;
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
    String resolveGithubToken(String org) {
        if (org != null && !org.isEmpty()) {
            for (Map.Entry<String, String> entry : githubOrgTokens.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(org)) {
                    return entry.getValue();
                }
            }
        }

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
     * and SSH ({@code git@github.com:owner/repo.git}) formats.</p>
     *
     * @param repoUrl the repository URL
     * @return the {@code owner/repo} string, or {@code null} if not recognised
     */
    static String extractOwnerRepo(String repoUrl) {
        if (repoUrl == null) return null;
        Matcher ssh = Pattern.compile("git@github\\.com:([^/]+/[^/]+?)(?:\\.git)?$").matcher(repoUrl);
        if (ssh.find()) return ssh.group(1);
        Matcher https = Pattern.compile("github\\.com/([^/]+/[^/]+?)(?:\\.git)?$").matcher(repoUrl);
        if (https.find()) return https.group(1);
        return null;
    }

    /**
     * Extracts the GitHub organisation name (the owner portion) from a
     * repository URL.
     *
     * @param repoUrl the repository URL (HTTPS or SSH format)
     * @return the organisation name, or {@code null} if not parseable
     */
    static String extractOrgFromRepoUrl(String repoUrl) {
        String ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return null;
        int slash = ownerRepo.indexOf('/');
        return slash > 0 ? ownerRepo.substring(0, slash) : null;
    }

    /**
     * Escapes a string for safe inclusion in a JSON string literal.
     *
     * @param s the string to escape, or {@code null}
     * @return  the escaped string, or an empty string if {@code s} is {@code null}
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * Functional interface used by {@link #handle} to read the POST/PUT
     * body from a NanoHTTPD session without creating a direct dependency on
     * the session-parsing logic in {@link FlowTreeApiEndpoint}.
     */
    @FunctionalInterface
    interface BodyReader {

        /**
         * Reads and returns the raw body string from the given session.
         *
         * @param session the NanoHTTPD session
         * @return the body string, or {@code null} on error
         */
        String read(IHTTPSession session);
    }

    /**
     * Functional interface used by {@link #handle} to build error responses
     * without coupling this class to the full NanoHTTPD response API.
     */
    @FunctionalInterface
    interface ErrorResponder {

        /**
         * Builds an HTTP error response containing the given message.
         *
         * @param message the human-readable error description
         * @return a NanoHTTPD response with an appropriate error status
         */
        Response respond(String message);
    }
}
