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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests {@link DependentRepoPush#repoName()}, which derives the short,
 * human-readable repository name used in push summaries.
 */
public class DependentRepoPushTest extends TestSuiteBase {

	/** An {@code https} clone URL yields its last path segment without {@code .git}. */
	@Test(timeout = 10000)
	public void repoNameFromHttpsUrl() {
		assertEquals("common", push("https://github.com/almostrealism/common.git").repoName());
	}

	/** A URL without a trailing {@code .git} still yields its last path segment. */
	@Test(timeout = 10000)
	public void repoNameWithoutGitSuffix() {
		assertEquals("lib", push("https://github.com/acme/lib").repoName());
	}

	/** An scp-style {@code git@host:org/repo.git} URL yields the segment after the slash. */
	@Test(timeout = 10000)
	public void repoNameFromScpStyleUrlWithPath() {
		assertEquals("repo", push("git@github.com:org/repo.git").repoName());
	}

	/** An scp-style URL whose path is only {@code host:repo.git} yields the segment after the colon. */
	@Test(timeout = 10000)
	public void repoNameFromScpStyleUrlWithoutSlash() {
		assertEquals("repo", push("git@github.com:repo.git").repoName());
	}

	/** A null or empty URL is returned unchanged rather than throwing. */
	@Test(timeout = 10000)
	public void repoNameHandlesNullAndEmpty() {
		assertNull(push(null).repoName());
		assertEquals("", push("").repoName());
	}

	/**
	 * Builds a push targeting {@code repoUrl}; the branch and hash are irrelevant
	 * to {@link DependentRepoPush#repoName()}.
	 *
	 * @param repoUrl the clone URL under test
	 * @return a push carrying that URL
	 */
	private static DependentRepoPush push(String repoUrl) {
		return new DependentRepoPush(repoUrl, "feature/x", "abc123");
	}
}
