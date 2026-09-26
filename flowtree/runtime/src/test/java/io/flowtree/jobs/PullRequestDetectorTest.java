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
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PullRequestDetector} covering the static
 * {@code extractOwnerRepo} utility — which parses a GitHub remote URL into an
 * {@code owner/repo} slug via {@link io.flowtree.jobs.GitOperations#repositorySlug(String)}
 * after checking the host is exactly {@code github.com} — and verifying that
 * {@link PullRequestDetector#detect(String, String, String)} returns empty when
 * preconditions are not met. No real HTTP calls are made.
 */
public class PullRequestDetectorTest extends TestSuiteBase {

	/** Verifies that extractOwnerRepo correctly parses an SSH-format GitHub remote URL. */
	@Test(timeout = 30000)
	public void extractsOwnerRepoFromSSH() {
		String result = PullRequestDetector.extractOwnerRepo("git@github.com:owner/repo.git");
		assertEquals("owner/repo", result);
	}

	/** Verifies that extractOwnerRepo correctly parses an HTTPS-format GitHub remote URL. */
	@Test(timeout = 30000)
	public void extractsOwnerRepoFromHTTPS() {
		String result = PullRequestDetector.extractOwnerRepo("https://github.com/owner/repo.git");
		assertEquals("owner/repo", result);
	}

	/** Verifies that extractOwnerRepo returns null for a non-GitHub remote URL. */
	@Test(timeout = 30000)
	public void returnsEmptyForNonGitHub() {
		String result = PullRequestDetector.extractOwnerRepo("https://gitlab.com/owner/repo.git");
		assertNull(result);
	}

	/** Verifies that a well-formed GitHub URL with a valid owner/repo path is accepted. */
	@Test(timeout = 30000)
	public void validateOwnerRepoAcceptsValid() {
		// A well-formed GitHub URL with exactly owner/repo should return non-null.
		String result = PullRequestDetector.extractOwnerRepo("https://github.com/owner/repo.git");
		assertNotNull(result);
		assertEquals("owner/repo", result);
	}

	/** Verifies that URLs lacking a valid owner/repo structure or using a non-GitHub host are rejected. */
	@Test(timeout = 30000)
	public void validateOwnerRepoRejectsInvalid() {
		// A URL with no valid owner/repo structure returns null: repositorySlug
		// requires exactly two path segments.
		String resultNoSlash = PullRequestDetector.extractOwnerRepo("https://github.com/noslash.git");
		assertNull("Expected null for path without owner/repo slash", resultNoSlash);

		// Non-GitHub URLs also return null
		String resultNull = PullRequestDetector.extractOwnerRepo("https://gitlab.com/owner/repo.git");
		assertNull("Expected null for non-GitHub URL", resultNull);
	}

	/** Verifies that detect returns an empty Optional when the remote URL is null. */
	@Test(timeout = 30000)
	public void detectReturnsEmptyForNullRemoteUrl() {
		PullRequestDetector detector = new PullRequestDetector();
		Optional<String> result = detector.detect(null, "branch", null);
		assertFalse("Expected empty optional for null remote URL", result.isPresent());
	}

	/** Verifies the GitHub URL forms, other than the {@code .git}-suffixed ones, that yield a slug. */
	@Test(timeout = 30000)
	public void extractsOwnerRepoFromOtherGitHubForms() {
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("git@github.com:owner/repo"));
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("https://github.com/owner/repo"));
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("ssh://git@github.com/owner/repo.git"));
		assertEquals("owner/repo",
				PullRequestDetector.extractOwnerRepo("https://x-access-token:secret@github.com/owner/repo.git"));
	}

	/** Verifies that GitHub URLs naming something other than a repository are rejected. */
	@Test(timeout = 30000)
	public void rejectsGitHubUrlsThatAreNotRepositories() {
		assertNull(PullRequestDetector.extractOwnerRepo("https://github.com/owner/repo/pull/3"));
		assertNull(PullRequestDetector.extractOwnerRepo("git@github.com:owner"));
		assertNull(PullRequestDetector.extractOwnerRepo("not-a-url"));
	}

	/**
	 * Verifies that a look-alike host is rejected: the extraction must match the
	 * host exactly, not merely contain the {@code github.com} substring, so that
	 * {@code detect} never queries GitHub for a remote hosted elsewhere.
	 */
	@Test(timeout = 30000)
	public void rejectsLookAlikeGitHubHost() {
		assertNull(PullRequestDetector.extractOwnerRepo("https://github.com.evil.example/owner/repo.git"));
		assertNull(PullRequestDetector.extractOwnerRepo("git@github.com.evil.example:owner/repo.git"));
	}

	/** Verifies that a trailing slash is not carried into the slug used to build the API path. */
	@Test(timeout = 30000)
	public void extractsOwnerRepoFromUrlWithTrailingSlash() {
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("https://github.com/owner/repo/"));
	}

	/**
	 * Verifies that an uppercase GitHub host is accepted: hostnames are
	 * case-insensitive, and the host guard compares them with
	 * {@code equalsIgnoreCase}, so a URL whose host is written in a different
	 * case still yields the slug rather than being silently dropped.
	 */
	@Test(timeout = 30000)
	public void extractsOwnerRepoFromUppercaseHost() {
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("https://GITHUB.COM/owner/repo.git"));
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("git@GitHub.com:owner/repo.git"));
	}

	/**
	 * Verifies that an explicit port on the GitHub host is accepted: the port is
	 * not part of the host, so {@code https://github.com:443/owner/repo.git}
	 * yields the same slug as the port-less form rather than being rejected.
	 */
	@Test(timeout = 30000)
	public void extractsOwnerRepoFromHostWithPort() {
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("https://github.com:443/owner/repo.git"));
	}
}
