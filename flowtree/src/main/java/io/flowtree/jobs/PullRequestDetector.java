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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.io.ConsoleFeatures;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Detects open GitHub pull requests for a given branch by querying
 * the GitHub REST API.
 *
 * <p>This class extracts PR detection logic that was previously
 * embedded in {@link GitManagedJob}, making it reusable across
 * different job types and contexts.</p>
 *
 * <p>Authentication is resolved in two ways:</p>
 * <ol>
 *   <li>A {@code GITHUB_TOKEN} or {@code GH_TOKEN} environment variable
 *       for direct GitHub API access</li>
 *   <li>A controller proxy endpoint derived from the workstream URL,
 *       which forwards requests through the controller's GitHub
 *       integration</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PullRequestDetector detector = new PullRequestDetector();
 * Optional<String> prUrl = detector.detect(remoteUrl, "feature/my-branch", workstreamUrl);
 * prUrl.ifPresent(url -> log("Open PR: " + url));
 * }</pre>
 *
 * @author Michael Murray
 */
public class PullRequestDetector implements ConsoleFeatures {

    private final ObjectMapper objectMapper;

    /**
     * Creates a new {@link PullRequestDetector} with a default
     * {@link ObjectMapper} for JSON parsing.
     */
    public PullRequestDetector() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Detects an open pull request for the specified branch on a
     * GitHub repository.
     *
     * <p>The method first checks whether the remote URL points to
     * GitHub. If not, it returns {@link Optional#empty()}. It then
     * extracts the {@code owner/repo} from the URL and attempts to
     * query the GitHub REST API for an open PR whose head matches
     * the target branch.</p>
     *
     * <p>If a {@code GITHUB_TOKEN} or {@code GH_TOKEN} environment
     * variable is available, the API is called directly. Otherwise,
     * if a {@code workstreamUrl} is provided, the request is proxied
     * through the controller's GitHub proxy endpoint.</p>
     *
     * @param remoteUrl     the git remote URL (SSH or HTTPS format)
     * @param targetBranch  the branch name to search for an open PR
     * @param workstreamUrl the workstream URL for controller proxy
     *                      fallback, or {@code null} if unavailable
     * @return an {@link Optional} containing the PR's {@code html_url}
     *         if an open PR was found, or empty otherwise
     */
    public Optional<String> detect(String remoteUrl, String targetBranch, String workstreamUrl) {
        try {
            if (remoteUrl == null || !remoteUrl.contains("github.com")) {
                return Optional.empty();
            }

            String ownerRepo = extractOwnerRepo(remoteUrl);
            if (ownerRepo == null) {
                log("Could not extract owner/repo from remote URL: " + remoteUrl);
                return Optional.empty();
            }

            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isEmpty()) {
                token = System.getenv("GH_TOKEN");
            }

            String apiPath = "/repos/" + ownerRepo +
                "/pulls?head=" + ownerRepo.split("/")[0] + ":" + targetBranch +
                "&state=open&per_page=1";

            String responseBody = null;

            if (token != null && !token.isEmpty()) {
                responseBody = queryGitHubDirectly(apiPath, token);
            } else if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
                responseBody = queryViaProxy(apiPath, workstreamUrl);
            } else {
                log("No GITHUB_TOKEN and no workstream URL, cannot query GitHub API for PR");
                return Optional.empty();
            }

            return parsePrUrl(responseBody);
        } catch (Exception e) {
            log("Could not detect PR URL: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Queries the GitHub API directly using a bearer token.
     *
     * @param apiPath the API path (e.g., {@code /repos/owner/repo/pulls?...})
     * @param token   the GitHub API token
     * @return the response body, or {@code null} on failure
     */
    private String queryGitHubDirectly(String apiPath, String token) {
        try {
            String apiUrl = "https://api.github.com" + apiPath;
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log("GitHub API returned " + responseCode + " for PR query");
                return null;
            }

            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log("GitHub API request failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Queries the GitHub API through the controller's proxy endpoint.
     *
     * <p>The controller base URL is derived from the workstream URL by
     * stripping the {@code /api/workstreams/...} suffix. The proxy
     * response wraps the GitHub response as
     * {@code {"status":200,"body":[...]}}.</p>
     *
     * @param apiPath       the GitHub API path to proxy
     * @param workstreamUrl the workstream URL used to resolve the
     *                      controller base
     * @return the unwrapped response body, or {@code null} on failure
     */
    private String queryViaProxy(String apiPath, String workstreamUrl) {
        try {
            String resolvedUrl = WorkspaceResolver.resolveWorkstreamUrl(workstreamUrl);

            int wsIdx = resolvedUrl.indexOf("/api/workstreams/");
            if (wsIdx < 0) {
                log("Cannot derive controller base from workstream URL: " + resolvedUrl);
                return null;
            }

            String controllerBase = resolvedUrl.substring(0, wsIdx);
            String proxyUrl = controllerBase + "/api/github/proxy?url="
                + URLEncoder.encode(apiPath, StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(proxyUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log("GitHub proxy returned " + responseCode + " for PR query");
                return null;
            }

            String proxyResponse = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode proxyRoot = objectMapper.readTree(proxyResponse);

            int ghStatus = proxyRoot.has("status") ? proxyRoot.get("status").asInt() : 0;
            if (ghStatus != 200) {
                log("GitHub proxy: GitHub returned status " + ghStatus);
                return null;
            }

            JsonNode bodyNode = proxyRoot.get("body");
            if (bodyNode != null) {
                return bodyNode.toString();
            }

            return null;
        } catch (Exception e) {
            log("GitHub proxy request failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses a GitHub PR list response and extracts the first PR's
     * {@code html_url}.
     *
     * <p>The response is expected to be a JSON array. The first element's
     * {@code html_url} field is validated to ensure it looks like a
     * legitimate GitHub pull request URL before being returned.</p>
     *
     * @param responseBody the raw JSON response body
     * @return an {@link Optional} containing the PR URL, or empty if
     *         the response is null, empty, or contains no valid PR
     */
    private Optional<String> parsePrUrl(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.isArray() && root.size() > 0) {
                JsonNode firstPr = root.get(0);
                JsonNode htmlUrlNode = firstPr.get("html_url");
                if (htmlUrlNode != null && htmlUrlNode.isTextual()) {
                    String prUrl = htmlUrlNode.asText();
                    if (prUrl.startsWith("https://github.com/") && prUrl.contains("/pull/")) {
                        return Optional.of(prUrl);
                    }
                }
            }
        } catch (Exception e) {
            log("Failed to parse PR response: " + e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Extracts the {@code owner/repo} string from a GitHub remote URL.
     *
     * <p>Supports both SSH ({@code git@github.com:owner/repo.git}) and
     * HTTPS ({@code https://github.com/owner/repo.git}) formats. The
     * {@code .git} suffix is stripped before validation.</p>
     *
     * @param remoteUrl the git remote URL
     * @return the {@code owner/repo} string, or {@code null} if the
     *         URL cannot be parsed into a valid owner/repo pair
     */
    static String extractOwnerRepo(String remoteUrl) {
        // SSH format: git@github.com:owner/repo.git
        if (remoteUrl.contains("git@github.com:")) {
            String path = remoteUrl.substring(remoteUrl.indexOf("git@github.com:") + 15);
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String validated = validateOwnerRepo(path);
            if (validated != null) return validated;
        }

        // HTTPS format: https://github.com/owner/repo.git
        if (remoteUrl.contains("github.com/")) {
            String path = remoteUrl.substring(remoteUrl.indexOf("github.com/") + 11);
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String validated = validateOwnerRepo(path);
            if (validated != null) return validated;
        }

        return null;
    }

    /**
     * Validates that a path is exactly {@code owner/repo} -- two
     * non-empty parts separated by a single slash.
     *
     * @param path the candidate owner/repo string
     * @return the path if valid, or {@code null}
     */
    private static String validateOwnerRepo(String path) {
        String[] parts = path.split("/");
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return path;
        }
        return null;
    }
}
