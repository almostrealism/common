# Pull Request Detection

This document describes the `PullRequestDetector` class and the pull request detection mechanism used by Flowtree jobs to discover open GitHub pull requests associated with the branch they are working on.

---

## Table of Contents

1. [Overview](#overview)
2. [PullRequestDetector Class Reference](#pullrequestdetector-class-reference)
3. [GitHub API Integration](#github-api-integration)
4. [Token Resolution](#token-resolution)
5. [Owner/Repo Extraction](#ownerrepo-extraction)
6. [Controller Proxy Endpoint Fallback](#controller-proxy-endpoint-fallback)
7. [Error Handling and Fail-Safe Behavior](#error-handling-and-fail-safe-behavior)
8. [Integration with GitManagedJob](#integration-with-gitmanagedob)

---

## Overview

After a Flowtree job pushes changes to a branch, it is useful to know whether an open pull request already exists for that branch. If a PR exists, the job can include its URL in the completion event, which allows the Slack notification to link directly to the PR for review.

The `PullRequestDetector` class encapsulates this detection logic. It queries the GitHub REST API for open pull requests whose head branch matches the job's target branch. The detection is best-effort: if the query fails for any reason, the job proceeds normally without a PR URL.

There are two implementations of this logic:
- **`PullRequestDetector`**: A standalone, reusable class (extracted from `GitManagedJob`)
- **`GitManagedJob.detectPullRequestUrl()`**: The original inline implementation in the base class

Both implementations follow the same algorithm and produce the same results.

---

## PullRequestDetector Class Reference

### Class Declaration

```java
public class PullRequestDetector implements ConsoleFeatures
```

The class implements `ConsoleFeatures` for structured logging. It holds no mutable state beyond the `ObjectMapper` instance used for JSON parsing, making it safe for concurrent use.

### Constructor

```java
public PullRequestDetector()
```

Creates a new detector with a default Jackson `ObjectMapper` for JSON parsing.

### detect()

```java
public Optional<String> detect(String remoteUrl, String targetBranch, String workstreamUrl)
```

Detects an open pull request for the specified branch on a GitHub repository.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `remoteUrl` | `String` | The git remote URL (SSH or HTTPS format). Must contain `github.com` for detection to proceed. |
| `targetBranch` | `String` | The branch name to search for an open PR. |
| `workstreamUrl` | `String` | The workstream URL for controller proxy fallback, or `null` if unavailable. |

**Returns:** An `Optional<String>` containing the PR's `html_url` if an open PR was found, or `Optional.empty()` otherwise.

**Behavior:**
1. Returns empty if `remoteUrl` is null or does not contain `github.com`
2. Extracts `owner/repo` from the remote URL
3. Attempts authentication via `GITHUB_TOKEN` or `GH_TOKEN` environment variable
4. Falls back to controller proxy if no token is available but `workstreamUrl` is set
5. Returns empty if neither authentication method is available
6. Queries the GitHub API for open PRs matching the target branch
7. Parses the response and validates the PR URL format
8. Returns the first matching PR URL

### extractOwnerRepo() (package-private)

```java
static String extractOwnerRepo(String remoteUrl)
```

Extracts the `owner/repo` string from a GitHub remote URL. Supports both SSH and HTTPS formats.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `remoteUrl` | `String` | The git remote URL |

**Returns:** The `owner/repo` string, or `null` if the URL cannot be parsed into a valid owner/repo pair.

**Note:** This method has package-private visibility, making it accessible to tests in the same package (`io.flowtree.jobs`).

### queryGitHubDirectly() (private)

```java
private String queryGitHubDirectly(String apiPath, String token)
```

Queries the GitHub API directly using a bearer token.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `apiPath` | `String` | The API path (e.g., `/repos/owner/repo/pulls?...`) |
| `token` | `String` | The GitHub API token |

**Returns:** The response body as a string, or `null` on failure.

### queryViaProxy() (private)

```java
private String queryViaProxy(String apiPath, String workstreamUrl)
```

Queries the GitHub API through the controller's proxy endpoint.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `apiPath` | `String` | The GitHub API path to proxy |
| `workstreamUrl` | `String` | The workstream URL used to resolve the controller base |

**Returns:** The unwrapped response body, or `null` on failure.

### parsePrUrl() (private)

```java
private Optional<String> parsePrUrl(String responseBody)
```

Parses a GitHub PR list response and extracts the first PR's `html_url`.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `responseBody` | `String` | The raw JSON response body |

**Returns:** An `Optional<String>` containing the PR URL, or empty if the response is null, empty, or contains no valid PR.

---

## GitHub API Integration

### API Endpoint

The detector queries the GitHub REST API's pull request list endpoint:

```
GET https://api.github.com/repos/{owner}/{repo}/pulls?head={owner}:{branch}&state=open&per_page=1
```

**Query parameters:**
- `head`: Filters PRs by the head branch, qualified with the owner name (e.g., `owner:feature/my-branch`)
- `state`: Only open PRs are queried
- `per_page`: Limited to 1 result since only the first (most recent) PR is needed

### Direct API Access

When a `GITHUB_TOKEN` or `GH_TOKEN` environment variable is available, the detector calls the GitHub API directly:

```java
HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
conn.setRequestMethod("GET");
conn.setRequestProperty("Authorization", "Bearer " + token);
conn.setRequestProperty("Accept", "application/vnd.github+json");
conn.setConnectTimeout(10000);
conn.setReadTimeout(10000);
```

Both the connect timeout and read timeout are set to 10 seconds. A non-200 response code causes the method to return `null` (no PR found) rather than throwing an exception.

### Response Parsing

The GitHub API returns a JSON array of PR objects. The detector parses the first element and extracts the `html_url` field using Jackson:

```java
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
```

The URL is validated to ensure it starts with `https://github.com/` and contains `/pull/` before being returned. This prevents injection of arbitrary URLs from a compromised API response.

---

## Token Resolution

The detector follows a two-step token resolution strategy:

```java
String token = System.getenv("GITHUB_TOKEN");
if (token == null || token.isEmpty()) {
    token = System.getenv("GH_TOKEN");
}
```

### GITHUB_TOKEN

The standard GitHub Actions token. In CI environments, this is typically the `${{ secrets.GITHUB_TOKEN }}` or `${{ github.token }}` automatically provided by GitHub Actions. It has permissions scoped to the repository.

### GH_TOKEN

A fallback environment variable commonly used by the GitHub CLI (`gh`). This is checked second, providing compatibility with environments where the GitHub CLI is configured but the standard `GITHUB_TOKEN` is not set.

### No Token Available

When neither `GITHUB_TOKEN` nor `GH_TOKEN` is set, the detector attempts to use the controller proxy endpoint as a fallback (if a workstream URL is available). If no proxy is available either, the detection is skipped entirely with a log message:

```
No GITHUB_TOKEN and no workstream URL, cannot query GitHub API for PR
```

This is not an error condition. The job proceeds normally without a PR URL in the completion event.

---

## Owner/Repo Extraction

The `extractOwnerRepo()` method converts a git remote URL into the `owner/repo` format required by the GitHub API.

### SSH Format

```
Input:  git@github.com:owner/repo.git
Output: owner/repo
```

The method detects the SSH format by looking for the `git@github.com:` prefix. It extracts the substring after the colon and strips the `.git` suffix.

### HTTPS Format

```
Input:  https://github.com/owner/repo.git
Output: owner/repo
```

The method detects the HTTPS format by looking for `github.com/` in the URL. It extracts the substring after `github.com/` and strips the `.git` suffix.

### Validation

After extraction, the path is validated by the private `validateOwnerRepo()` method:

```java
private static String validateOwnerRepo(String path) {
    String[] parts = path.split("/");
    if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
        return path;
    }
    return null;
}
```

The validation ensures exactly two non-empty segments separated by a single slash. Paths with more or fewer segments (e.g., `org/team/repo` or just `repo`) are rejected, causing `extractOwnerRepo()` to return `null`.

### Non-GitHub URLs

For URLs that do not contain `github.com`, `extractOwnerRepo()` returns `null`. The `detect()` method checks for this early and returns `Optional.empty()` without attempting any API calls.

---

## Controller Proxy Endpoint Fallback

When no GitHub token is available locally but a workstream URL is configured, the detector routes the GitHub API request through the controller's proxy endpoint. This allows agents running in environments without direct GitHub API access to still detect PRs.

### Proxy URL Construction

The controller base URL is derived from the workstream URL by stripping the `/api/workstreams/...` suffix:

```java
String resolvedUrl = WorkspaceResolver.resolveWorkstreamUrl(workstreamUrl);
int wsIdx = resolvedUrl.indexOf("/api/workstreams/");
if (wsIdx < 0) {
    return null;  // Cannot derive controller base
}
String controllerBase = resolvedUrl.substring(0, wsIdx);
String proxyUrl = controllerBase + "/api/github/proxy?url="
    + URLEncoder.encode(apiPath, StandardCharsets.UTF_8);
```

For example, given a workstream URL of `http://10.0.0.5:7780/api/workstreams/ws1/jobs/j1`, the proxy URL becomes:

```
http://10.0.0.5:7780/api/github/proxy?url=%2Frepos%2Fowner%2Frepo%2Fpulls%3Fhead%3Downer%3Abranch%26state%3Dopen%26per_page%3D1
```

Note that the workstream URL is first resolved via `WorkspaceResolver.resolveWorkstreamUrl()` to replace the `0.0.0.0` placeholder with the actual controller host from `FLOWTREE_ROOT_HOST`.

### Proxy Response Format

The controller's GitHub proxy endpoint wraps the GitHub API response in an envelope:

```json
{
  "status": 200,
  "body": [
    {
      "html_url": "https://github.com/owner/repo/pull/42",
      "number": 42,
      "title": "Feature: my work",
      "state": "open"
    }
  ]
}
```

The detector extracts the `status` field to check if the GitHub API call succeeded, then extracts the `body` field for parsing:

```java
JsonNode proxyRoot = objectMapper.readTree(proxyResponse);
int ghStatus = proxyRoot.has("status") ? proxyRoot.get("status").asInt() : 0;
if (ghStatus != 200) {
    return null;
}
JsonNode bodyNode = proxyRoot.get("body");
if (bodyNode != null) {
    return bodyNode.toString();
}
```

The unwrapped body is then parsed by the same `parsePrUrl()` method used for direct API responses.

---

## Error Handling and Fail-Safe Behavior

PR detection is entirely optional and designed to fail silently. Every method in the detection chain handles exceptions gracefully:

### Network Errors

Both `queryGitHubDirectly()` and `queryViaProxy()` catch all exceptions and return `null`:

```java
} catch (Exception e) {
    log("GitHub API request failed: " + e.getMessage());
    return null;
}
```

### Parse Errors

The `parsePrUrl()` method catches JSON parsing exceptions and returns `Optional.empty()`:

```java
} catch (Exception e) {
    log("Failed to parse PR response: " + e.getMessage());
}
return Optional.empty();
```

### Top-Level Safety

The `detect()` method wraps the entire detection logic in a try-catch that returns `Optional.empty()` on any unhandled exception:

```java
public Optional<String> detect(String remoteUrl, String targetBranch, String workstreamUrl) {
    try {
        // ... detection logic ...
    } catch (Exception e) {
        log("Could not detect PR URL: " + e.getMessage());
        return Optional.empty();
    }
}
```

### No Side Effects on Failure

A failed detection never:
- Throws an exception to the caller
- Blocks or delays the job
- Modifies any state
- Causes the job to fail

The worst case is a log message indicating why detection failed and a completion event without a PR URL.

---

## Integration with GitManagedJob

The `GitManagedJob` class calls `detectPullRequestUrl()` at the end of `handleGitOperations()`, after the push succeeds:

```java
private void handleGitOperations() throws IOException, InterruptedException {
    // ... branch checkout, staging, commit, push ...

    // Detect open PR for the target branch
    pullRequestUrl = detectPullRequestUrl();
    if (pullRequestUrl != null) {
        log("Open PR: " + pullRequestUrl);
    }

    gitOperationsSuccessful = true;
}
```

The detected PR URL is stored in the `pullRequestUrl` field and included in the `JobCompletionEvent` via `fireJobCompleted()`:

```java
protected void fireJobCompleted(Exception error) {
    JobCompletionEvent event = createEvent(error);
    event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles, pushed);
    if (pullRequestUrl != null) {
        event.withPullRequestUrl(pullRequestUrl);
    }
    postStatusEvent(event);
}
```

The `SlackNotifier` on the controller side receives this event and formats the Slack notification to include a link to the PR when available, making it easy for team members to review the agent's changes.

The `PullRequestDetector` standalone class provides the same functionality in a reusable form. It can be used by code that does not extend `GitManagedJob` but still needs PR detection, or by unit tests that want to test the detection logic in isolation.
