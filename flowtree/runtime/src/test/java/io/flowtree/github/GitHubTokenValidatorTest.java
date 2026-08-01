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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.flowtree.workstream.WorkstreamConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GitHubTokenValidator} using a stubbed HTTP layer.
 */
public class GitHubTokenValidatorTest extends TestSuiteBase {

	/**
	 * Stub validator that returns preconfigured responses instead of
	 * making real HTTP calls.
	 */
	private static class StubValidator extends GitHubTokenValidator {
		/** Preconfigured stub responses keyed by request path. */
		private final Map<String, GitHubResponse> responses = new LinkedHashMap<>();

		/**
		 * Registers a stub response for the given path.
		 */
		void stubResponse(String path, int status, String body) {
			responses.put(path, new GitHubResponse(status, body));
		}

		/**
		 * Returns the preconfigured stub response for the given path, or a 404 if none is registered.
		 */
		@Override
		GitHubResponse githubGet(String token, String path) throws IOException {
			GitHubResponse resp = responses.get(path);
			if (resp != null) return resp;
			return new GitHubResponse(404, "{\"message\":\"Not Found\"}");
		}
	}

	/**
	 * Verifies that a token with push access to the configured repository is reported as valid.
	 */
	@Test(timeout = 10000)
	public void validTokenWithRepoAccess() {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user",
				200, "{\"login\":\"testbot\"}");
		validator.stubResponse("/repos/myorg/myrepo",
				200, "{\"permissions\":{\"push\":true,\"pull\":true}}");

		Set<String> repos = new LinkedHashSet<>();
		repos.add("myorg/myrepo");

		GitHubTokenValidator.TokenValidationResult result =
				validator.validateToken("test-token", "ghp_test", repos);

		assertTrue("Token should be valid", result.isValid());
		assertTrue("Should have no errors", result.getErrors().isEmpty());
	}

	/**
	 * Verifies that a 401 response from the GitHub API causes the token to be reported as invalid.
	 */
	@Test(timeout = 10000)
	public void expiredTokenReturns401() {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user", 401,
				"{\"message\":\"Bad credentials\"}");

		Set<String> repos = new LinkedHashSet<>();
		GitHubTokenValidator.TokenValidationResult result =
				validator.validateToken("expired", "ghp_expired", repos);

		assertFalse("Token should be invalid", result.isValid());
		assertEquals(1, result.getErrors().size());
		assertTrue(result.getErrors().get(0).contains("401"));
	}

	/**
	 * Verifies that a 403 response from the GitHub API causes the token to be reported as invalid.
	 */
	@Test(timeout = 10000)
	public void forbiddenTokenReturns403() {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user", 403,
				"{\"message\":\"Forbidden\"}");

		Set<String> repos = new LinkedHashSet<>();
		GitHubTokenValidator.TokenValidationResult result =
				validator.validateToken("forbidden", "ghp_forbidden", repos);

		assertFalse("Token should be invalid", result.isValid());
		assertTrue(result.getErrors().get(0).contains("403"));
	}

	/**
	 * Verifies that a 404 response for a repository causes a validation error naming that repository.
	 */
	@Test(timeout = 10000)
	public void repoNotAccessible() {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user",
				200, "{\"login\":\"testbot\"}");
		validator.stubResponse("/repos/private-org/secret-repo",
				404, "{\"message\":\"Not Found\"}");

		Set<String> repos = new LinkedHashSet<>();
		repos.add("private-org/secret-repo");

		GitHubTokenValidator.TokenValidationResult result =
				validator.validateToken("no-access", "ghp_noaccess", repos);

		assertFalse("Token should be invalid", result.isValid());
		assertTrue(result.getErrors().get(0).contains("private-org/secret-repo"));
		assertTrue(result.getErrors().get(0).contains("not found"));
	}

	/**
	 * Verifies that a repository with push permission set to false causes a validation error.
	 */
	@Test(timeout = 10000)
	public void repoLacksPushPermission() {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user",
				200, "{\"login\":\"testbot\"}");
		validator.stubResponse("/repos/myorg/myrepo",
				200, "{\"permissions\":{\"push\":false,\"pull\":true}}");

		Set<String> repos = new LinkedHashSet<>();
		repos.add("myorg/myrepo");

		GitHubTokenValidator.TokenValidationResult result =
				validator.validateToken("read-only", "ghp_readonly", repos);

		assertFalse("Token should be invalid", result.isValid());
		assertTrue(result.getErrors().get(0).contains("push permission"));
	}

	/**
	 * Verifies that when one of multiple repositories is inaccessible, validation reports exactly one error.
	 */
	@Test(timeout = 10000)
	public void multipleReposPartialFailure() {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user",
				200, "{\"login\":\"testbot\"}");
		validator.stubResponse("/repos/org/repo-a",
				200, "{\"permissions\":{\"push\":true,\"pull\":true}}");
		validator.stubResponse("/repos/org/repo-b",
				404, "{\"message\":\"Not Found\"}");

		Set<String> repos = new LinkedHashSet<>();
		repos.add("org/repo-a");
		repos.add("org/repo-b");

		GitHubTokenValidator.TokenValidationResult result =
				validator.validateToken("partial", "ghp_partial", repos);

		assertFalse("Token should be invalid", result.isValid());
		assertEquals(1, result.getErrors().size());
		assertTrue(result.getErrors().get(0).contains("repo-b"));
	}

	/**
	 * Verifies that the owner/repo pair is correctly extracted from an SSH-format GitHub URL.
	 */
	@Test(timeout = 10000)
	public void extractOwnerRepoFromSshUrl() {
		assertEquals("almostrealism/common",
				GitHubTokenValidator.extractOwnerRepo(
						"git@github.com:almostrealism/common.git"));
	}

	/**
	 * Verifies that the owner/repo pair is correctly extracted from an HTTPS-format GitHub URL.
	 */
	@Test(timeout = 10000)
	public void extractOwnerRepoFromHttpsUrl() {
		assertEquals("Plytrix/plytrix-platform",
				GitHubTokenValidator.extractOwnerRepo(
						"https://github.com/Plytrix/plytrix-platform.git"));
	}

	/**
	 * Verifies that null and empty inputs to extractOwnerRepo return null without throwing.
	 */
	@Test(timeout = 10000)
	public void extractOwnerRepoFromNullReturnsNull() {
		assertNull(GitHubTokenValidator.extractOwnerRepo(null));
		assertNull(GitHubTokenValidator.extractOwnerRepo(""));
	}

	/**
	 * Verifies that validateAll returns an empty list when no tokens are configured.
	 */
	@Test(timeout = 10000)
	public void validateAllWithNoTokens() {
		StubValidator validator = new StubValidator();
		WorkstreamConfig config = new WorkstreamConfig();

		List<GitHubTokenValidator.TokenValidationResult> results =
				validator.validateAll(config);

		assertTrue("Should have no results when no tokens configured",
				results.isEmpty());
	}

	// -------------------------------------------------------------------------
	// Phase 1d: Per-workspace githubOrgs validation tests
	// -------------------------------------------------------------------------

	/**
	 * Verifies that per-workspace GitHub org tokens are included and validated by validateAll.
	 */
	@Test(timeout = 10000)
	public void validateAllIncludesPerWorkspaceOrgTokens() throws Exception {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user", 200, "{\"login\":\"ws-bot\"}");
		validator.stubResponse("/repos/ws-org/ws-repo",
				200, "{\"permissions\":{\"push\":true,\"pull\":true}}");

		String yaml = "slackWorkspaces:\n"
				+ "  - workspaceId: \"T_WS\"\n"
				+ "    botToken: \"xoxb-ws\"\n"
				+ "    appToken: \"xapp-ws\"\n"
				+ "    githubOrgs:\n"
				+ "      ws-org:\n"
				+ "        token: \"ghp_workspace_token\"\n"
				+ "workstreams:\n"
				+ "  - channelId: \"C001\"\n"
				+ "    channelName: \"#ws-channel\"\n"
				+ "    slackWorkspaceId: \"T_WS\"\n"
				+ "    repoUrl: \"https://github.com/ws-org/ws-repo.git\"\n"
				+ "    defaultBranch: \"main\"\n";

		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

		List<GitHubTokenValidator.TokenValidationResult> results =
				validator.validateAll(config);

		assertFalse("Per-workspace org token should be validated", results.isEmpty());
		assertTrue("Workspace org token should be valid", results.get(0).isValid());
		assertTrue("Result label should identify workspace context",
				results.get(0).getLabel().contains("T_WS")
						|| results.get(0).getLabel().contains("ws-org"));
	}

	/**
	 * Verifies that validateAll merges both global and per-workspace org tokens into the result list.
	 */
	@Test(timeout = 10000)
	public void validateAllMergesGlobalAndWorkspaceOrgTokens() throws Exception {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user", 200, "{\"login\":\"bot\"}");

		String yaml = "githubOrgs:\n"
				+ "  global-org:\n"
				+ "    token: \"ghp_global\"\n"
				+ "slackWorkspaces:\n"
				+ "  - workspaceId: \"T111\"\n"
				+ "    botToken: \"xoxb-t111\"\n"
				+ "    appToken: \"xapp-t111\"\n"
				+ "    githubOrgs:\n"
				+ "      workspace-org:\n"
				+ "        token: \"ghp_workspace\"\n"
				+ "workstreams: []\n";

		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

		List<GitHubTokenValidator.TokenValidationResult> results =
				validator.validateAll(config);

		// Both tokens (global + workspace) should appear in validation results
		assertEquals("Both global and workspace org tokens should be validated", 2, results.size());
	}

	/**
	 * Verifies that when a workspace org token has the same org key as a global token, the workspace token overrides it.
	 */
	@Test(timeout = 10000)
	public void validateAllHandlesWorkspaceOrgCollisionWithGlobal() throws Exception {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user", 200, "{\"login\":\"bot\"}");

		String yaml = "githubOrgs:\n"
				+ "  shared-org:\n"
				+ "    token: \"ghp_global\"\n"
				+ "slackWorkspaces:\n"
				+ "  - workspaceId: \"T111\"\n"
				+ "    botToken: \"xoxb-t111\"\n"
				+ "    appToken: \"xapp-t111\"\n"
				+ "    githubOrgs:\n"
				+ "      shared-org:\n"
				+ "        token: \"ghp_workspace_override\"\n"
				+ "workstreams: []\n";

		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

		// Should not throw; collision is a warning, not an error
		List<GitHubTokenValidator.TokenValidationResult> results =
				validator.validateAll(config);

		// The workspace token overrides the global — we expect exactly one token context
		// for "shared-org" since both map to the same org key
		assertEquals("Collision: workspace overrides global, one context per unique token",
				1, results.size());
	}

	/**
	 * Verifies backward-compatible single-workspace mode where no slackWorkspaces block is present.
	 */
	@Test(timeout = 10000)
	public void validateAllBackwardCompatSingleWorkspaceNoSlackWorkspaces() throws Exception {
		StubValidator validator = new StubValidator();
		validator.stubResponse("/user", 200, "{\"login\":\"bot\"}");

		String yaml = "githubOrgs:\n"
				+ "  solo-org:\n"
				+ "    token: \"ghp_solo\"\n"
				+ "workstreams: []\n";

		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

		List<GitHubTokenValidator.TokenValidationResult> results =
				validator.validateAll(config);

		assertEquals("Single-workspace mode: one result for one token", 1, results.size());
		assertEquals("org:solo-org", results.get(0).getLabel());
	}

	/**
	 * Verifies that a workspace entry with no githubOrgs configured produces an empty validation result.
	 */
	@Test(timeout = 10000)
	public void validateAllEmptyGithubOrgsOnWorkspaceEntry() throws Exception {
		StubValidator validator = new StubValidator();

		String yaml = "slackWorkspaces:\n"
				+ "  - workspaceId: \"T_EMPTY\"\n"
				+ "    botToken: \"xoxb-empty\"\n"
				+ "    appToken: \"xapp-empty\"\n"
				+ "workstreams: []\n";

		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

		List<GitHubTokenValidator.TokenValidationResult> results =
				validator.validateAll(config);

		// No tokens configured — should return empty results without throwing
		assertTrue("No GitHub tokens should produce empty results", results.isEmpty());
	}
}
