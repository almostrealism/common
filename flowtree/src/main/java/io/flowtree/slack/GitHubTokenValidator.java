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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates GitHub API tokens at controller startup.
 *
 * <p>Checks that each configured token is valid and has the required
 * permissions for the repositories referenced by workstreams. This
 * provides fast-fail behavior so broken tokens are caught immediately
 * rather than causing silent failures hours into an agent session.</p>
 *
 * <p>Validation checks:</p>
 * <ol>
 *   <li><b>Authentication:</b> {@code GET /user} returns 200</li>
 *   <li><b>Repository access:</b> {@code GET /repos/{owner}/{repo}}
 *       returns 200 for each repo that uses the token, confirming
 *       the token can see the repository</li>
 *   <li><b>Permissions:</b> The {@code permissions} object on the
 *       repo response includes {@code pull_requests: write} (needed
 *       for creating and commenting on PRs)</li>
 * </ol>
 *
 * @author Michael Murray
 * @see FlowTreeController
 */
public class GitHubTokenValidator implements ConsoleFeatures {

	/** Base URL for all GitHub API calls. */
	private static final String GITHUB_API = "https://api.github.com";
	/** Shared Jackson mapper for parsing GitHub API JSON responses. */
	private static final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Represents the result of validating a single token.
	 */
	public static class TokenValidationResult {
		/** Human-readable label identifying the token (e.g., "org:my-org"). */
		private final String label;
		/** Whether the token passed all validation checks. */
		private final boolean valid;
		/** Error messages describing each check that failed; empty when valid. */
		private final List<String> errors;

		/**
		 * Creates a new validation result.
		 *
		 * @param label  a human-readable label for the token (e.g., "org:my-org")
		 * @param valid  whether the token passed all checks
		 * @param errors list of error messages (empty if valid)
		 */
		public TokenValidationResult(String label, boolean valid, List<String> errors) {
			this.label = label;
			this.valid = valid;
			this.errors = errors;
		}

		/** Returns the label identifying which token this result is for. */
		public String getLabel() { return label; }

		/** Returns whether the token passed all validation checks. */
		public boolean isValid() { return valid; }

		/** Returns the list of error messages. */
		public List<String> getErrors() { return errors; }
	}

	/**
	 * Validates all GitHub tokens referenced by the given configuration.
	 *
	 * <p>Collects per-organization tokens from the {@code githubOrgs}
	 * config section and validates each one. Repositories are grouped
	 * by the token that will be used to access them.</p>
	 *
	 * @param config the loaded workstream configuration
	 * @return list of validation results, one per unique token
	 */
	public List<TokenValidationResult> validateAll(WorkstreamConfig config) {
		List<TokenValidationResult> results = new ArrayList<>();

		// Collect all token → repos mappings
		Map<String, TokenContext> tokenContexts = collectTokenContexts(config);

		for (Map.Entry<String, TokenContext> entry : tokenContexts.entrySet()) {
			String token = entry.getKey();
			TokenContext ctx = entry.getValue();
			results.add(validateToken(ctx.label, token, ctx.repos));
		}

		return results;
	}

	/**
	 * Validates a single token against the GitHub API.
	 *
	 * @param label the human-readable label for this token
	 * @param token the GitHub personal access token
	 * @param repos the set of "owner/repo" strings this token must access
	 * @return the validation result
	 */
	TokenValidationResult validateToken(String label, String token, Set<String> repos) {
		List<String> errors = new ArrayList<>();

		// Check 1: Token is valid (GET /user)
		String username = checkAuthentication(token, errors);
		if (username == null) {
			return new TokenValidationResult(label, false, errors);
		}

		log("GitHub token [" + label + "] authenticated as: " + username);

		// Check 2: Repository access and permissions
		for (String ownerRepo : repos) {
			checkRepoAccess(token, ownerRepo, errors);
		}

		return new TokenValidationResult(label, errors.isEmpty(), errors);
	}

	/**
	 * Verifies the token is valid by calling {@code GET /user}.
	 *
	 * @param token  the GitHub token
	 * @param errors list to append error messages to
	 * @return the authenticated username, or null if authentication failed
	 */
	private String checkAuthentication(String token, List<String> errors) {
		try {
			GitHubResponse resp = githubGet(token, "/user");
			if (resp.status == 401) {
				errors.add("Token is invalid or expired (401 Unauthorized)");
				return null;
			}
			if (resp.status == 403) {
				errors.add("Token is forbidden (403) — may be revoked or IP-restricted");
				return null;
			}
			if (resp.status != 200) {
				errors.add("GET /user returned unexpected status " + resp.status);
				return null;
			}

			JsonNode body = mapper.readTree(resp.body);
			return body.has("login") ? body.get("login").asText() : "unknown";
		} catch (IOException e) {
			errors.add("Failed to connect to GitHub API: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Verifies the token can access a specific repository and has
	 * the required permissions for PR operations.
	 *
	 * @param token     the GitHub token
	 * @param ownerRepo the "owner/repo" string
	 * @param errors    list to append error messages to
	 */
	private void checkRepoAccess(String token, String ownerRepo, List<String> errors) {
		try {
			GitHubResponse resp = githubGet(token, "/repos/" + ownerRepo);
			if (resp.status == 404) {
				errors.add("Repository " + ownerRepo
						+ " not found (token may lack access)");
				return;
			}
			if (resp.status != 200) {
				errors.add("GET /repos/" + ownerRepo
						+ " returned status " + resp.status);
				return;
			}

			JsonNode body = mapper.readTree(resp.body);

			// Check permissions (available for authenticated requests)
			JsonNode permissions = body.get("permissions");
			if (permissions != null) {
				boolean canPush = permissions.has("push")
						&& permissions.get("push").asBoolean();
				boolean canPull = permissions.has("pull")
						&& permissions.get("pull").asBoolean();

				if (!canPush) {
					errors.add("Repository " + ownerRepo
							+ ": token lacks push permission (needed for PRs)");
				}
				if (!canPull) {
					errors.add("Repository " + ownerRepo
							+ ": token lacks pull permission");
				}
			}
		} catch (IOException e) {
			errors.add("Failed to check repo " + ownerRepo + ": " + e.getMessage());
		}
	}

	/**
	 * Collects all unique tokens and maps them to the repositories they
	 * need to access.
	 *
	 * <p>Token resolution uses only per-org tokens from the
	 * {@code githubOrgs} section of workstreams.yaml. Each workstream's
	 * repo is matched to an org token via the {@code githubOrg} field
	 * or by extracting the owner from the repo URL.</p>
	 *
	 * @param config the workstream configuration
	 * @return map of token string to its context (label and repos)
	 */
	private Map<String, TokenContext> collectTokenContexts(WorkstreamConfig config) {
		Map<String, TokenContext> contexts = new LinkedHashMap<>();

		// Per-org tokens (the ONLY source of GitHub tokens)
		Map<String, String> orgTokens = new LinkedHashMap<>();
		if (config.getGithubOrgs() != null) {
			for (Map.Entry<String, WorkstreamConfig.GitHubOrgEntry> entry
					: config.getGithubOrgs().entrySet()) {
				String orgToken = entry.getValue().getToken();
				if (orgToken != null && !orgToken.isEmpty()) {
					orgTokens.put(entry.getKey(), orgToken);
					contexts.computeIfAbsent(orgToken,
							k -> new TokenContext("org:" + entry.getKey()));
				}
			}
		}

		// Scan workstreams to determine which token handles which repo
		for (WorkstreamConfig.WorkstreamEntry ws : config.getWorkstreams()) {
			String ownerRepo = extractOwnerRepo(ws.getRepoUrl());
			if (ownerRepo == null) continue;

			// Determine which token this workstream will use at runtime
			String wsToken = resolveWorkstreamToken(ws, orgTokens);
			if (wsToken == null) continue;

			// Find or create the context for this token
			TokenContext ctx = contexts.get(wsToken);
			if (ctx == null) {
				String label = "workstream:" + ws.getChannelName();
				ctx = new TokenContext(label);
				contexts.put(wsToken, ctx);
			}
			ctx.repos.add(ownerRepo);
		}

		return contexts;
	}

	/**
	 * Resolves the token that a workstream will use at runtime.
	 *
	 * <p>Uses only per-org tokens from workstreams.yaml. The org is
	 * determined from the workstream's {@code githubOrg} field or by
	 * extracting the owner from the repo URL.</p>
	 *
	 * @param ws        the workstream entry
	 * @param orgTokens map of org name to token
	 * @return the resolved token, or null if none available
	 */
	private String resolveWorkstreamToken(WorkstreamConfig.WorkstreamEntry ws,
										  Map<String, String> orgTokens) {
		// Per-org token via explicit githubOrg field
		if (ws.getGithubOrg() != null && orgTokens.containsKey(ws.getGithubOrg())) {
			return orgTokens.get(ws.getGithubOrg());
		}

		// Infer org from repo URL owner
		String ownerRepo = extractOwnerRepo(ws.getRepoUrl());
		if (ownerRepo != null) {
			int slash = ownerRepo.indexOf('/');
			if (slash > 0) {
				String owner = ownerRepo.substring(0, slash);
				if (orgTokens.containsKey(owner)) {
					return orgTokens.get(owner);
				}
			}
		}

		return null;
	}

	/**
	 * Extracts "owner/repo" from a repository URL (HTTPS or SSH format).
	 *
	 * @param repoUrl the repository URL
	 * @return the "owner/repo" string, or null if not parseable
	 */
	static String extractOwnerRepo(String repoUrl) {
		if (repoUrl == null || repoUrl.isEmpty()) return null;

		// SSH: git@github.com:owner/repo.git
		if (repoUrl.contains("@") && repoUrl.contains(":")) {
			int colon = repoUrl.lastIndexOf(':');
			String path = repoUrl.substring(colon + 1);
			if (path.endsWith(".git")) {
				path = path.substring(0, path.length() - 4);
			}
			return path;
		}

		// HTTPS: https://github.com/owner/repo.git
		if (repoUrl.contains("github.com/")) {
			int idx = repoUrl.indexOf("github.com/") + "github.com/".length();
			String path = repoUrl.substring(idx);
			if (path.endsWith(".git")) {
				path = path.substring(0, path.length() - 4);
			}
			return path;
		}

		return null;
	}

	/**
	 * Makes a GET request to the GitHub API.
	 *
	 * @param token the authorization token
	 * @param path  the API path (e.g., "/user")
	 * @return the response status and body
	 * @throws IOException if the connection fails
	 */
	GitHubResponse githubGet(String token, String path) throws IOException {
		URL url = URI.create(GITHUB_API + path).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Authorization", "Bearer " + token.trim());
		conn.setRequestProperty("Accept", "application/vnd.github+json");
		conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
		conn.setConnectTimeout(10000);
		conn.setReadTimeout(10000);

		int status = conn.getResponseCode();
		InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
		String body = "";
		if (is != null) {
			body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			is.close();
		}

		return new GitHubResponse(status, body);
	}

	/**
	 * Simple holder for a GitHub API response.
	 */
	static class GitHubResponse {
		/** HTTP status code returned by the GitHub API. */
		final int status;
		/** Response body as a UTF-8 string; may be an error body for 4xx/5xx responses. */
		final String body;

		/**
		 * Creates a new response holder.
		 *
		 * @param status the HTTP status code
		 * @param body   the response body (UTF-8)
		 */
		GitHubResponse(int status, String body) {
			this.status = status;
			this.body = body;
		}
	}

	/**
	 * Groups a display label with the set of "owner/repo" strings
	 * that a single GitHub token must be able to access.
	 */
	private static class TokenContext {
		/** Display label used in log messages and validation results. */
		final String label;
		/** Set of "owner/repo" strings the token must access, in insertion order. */
		final Set<String> repos = new LinkedHashSet<>();

		/**
		 * Creates a context for the given display label.
		 *
		 * @param label the human-readable label for this token context
		 */
		TokenContext(String label) {
			this.label = label;
		}
	}
}
