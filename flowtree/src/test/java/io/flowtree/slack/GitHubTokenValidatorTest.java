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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
		private final Map<String, GitHubResponse> responses = new LinkedHashMap<>();

		void stubResponse(String path, int status, String body) {
			responses.put(path, new GitHubResponse(status, body));
		}

		@Override
		GitHubResponse githubGet(String token, String path) throws IOException {
			GitHubResponse resp = responses.get(path);
			if (resp != null) return resp;
			return new GitHubResponse(404, "{\"message\":\"Not Found\"}");
		}
	}

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

	@Test(timeout = 10000)
	public void extractOwnerRepoFromSshUrl() {
		assertEquals("almostrealism/common",
				GitHubTokenValidator.extractOwnerRepo(
						"git@github.com:almostrealism/common.git"));
	}

	@Test(timeout = 10000)
	public void extractOwnerRepoFromHttpsUrl() {
		assertEquals("Plytrix/plytrix-platform",
				GitHubTokenValidator.extractOwnerRepo(
						"https://github.com/Plytrix/plytrix-platform.git"));
	}

	@Test(timeout = 10000)
	public void extractOwnerRepoFromNullReturnsNull() {
		assertNull(GitHubTokenValidator.extractOwnerRepo(null));
		assertNull(GitHubTokenValidator.extractOwnerRepo(""));
	}

	@Test(timeout = 10000)
	public void validateAllWithNoTokens() {
		StubValidator validator = new StubValidator();
		WorkstreamConfig config = new WorkstreamConfig();

		java.util.List<GitHubTokenValidator.TokenValidationResult> results =
				validator.validateAll(config);

		assertTrue("Should have no results when no tokens configured",
				results.isEmpty());
	}
}
