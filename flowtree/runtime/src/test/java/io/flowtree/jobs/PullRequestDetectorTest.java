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

// TODO(review): validateOwnerRepo no longer exists; update this javadoc and docs/pull-request-detection.md to describe GitOperations.repositorySlug
/**
 * Tests for {@link PullRequestDetector} covering the static utility methods
 * {@code extractOwnerRepo} and {@code validateOwnerRepo}, and verifying
 * that {@link PullRequestDetector#detect(String, String, String)} returns
 * empty when preconditions are not met. No real HTTP calls are made.
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
		// validateOwnerRepo is private, so we verify through extractOwnerRepo
		// which delegates to validateOwnerRepo internally.
		// A well-formed GitHub URL with exactly owner/repo should return non-null.
		String result = PullRequestDetector.extractOwnerRepo("https://github.com/owner/repo.git");
		assertNotNull(result);
		assertEquals("owner/repo", result);
	}

	/** Verifies that URLs lacking a valid owner/repo structure or using a non-GitHub host are rejected. */
	@Test(timeout = 30000)
	public void validateOwnerRepoRejectsInvalid() {
		// A URL with no valid owner/repo structure should return null.
		// extractOwnerRepo returns null when validateOwnerRepo rejects the path.
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

	/** Verifies that a trailing slash is not carried into the slug used to build the API path. */
	@Test(timeout = 30000)
	public void extractsOwnerRepoFromUrlWithTrailingSlash() {
		assertEquals("owner/repo", PullRequestDetector.extractOwnerRepo("https://github.com/owner/repo/"));
	}
}
