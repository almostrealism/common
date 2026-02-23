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
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link WorkspaceResolver} covering workspace path resolution,
 * repository name extraction, and workstream URL resolution.
 */
public class WorkspaceResolverTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void usesConfiguredPathFirst() {
		String result = WorkspaceResolver.resolve("/my/path", "https://github.com/owner/repo.git");
		assertEquals("/my/path", result);
	}

	@Test(timeout = 30000)
	public void fallsBackToTempWithRepoName() {
		String result = WorkspaceResolver.resolve(null, "https://github.com/owner/repo.git");
		assertTrue("Expected result to end with 'owner-repo', got: " + result,
			result.endsWith("owner-repo"));
	}

	@Test(timeout = 30000)
	public void extractsRepoNameFromSSH() {
		String result = WorkspaceResolver.extractRepoName("git@github.com:owner/repo.git");
		assertEquals("owner-repo", result);
	}

	@Test(timeout = 30000)
	public void extractsRepoNameFromHTTPS() {
		String result = WorkspaceResolver.extractRepoName("https://github.com/owner/repo.git");
		assertEquals("owner-repo", result);
	}

	@Test(timeout = 30000)
	public void resolvesWorkstreamUrl() {
		// When FLOWTREE_ROOT_HOST is not set, the URL is returned unchanged
		// because there is no replacement value available.
		String input = "http://10.0.0.1:8080/api";
		String result = WorkspaceResolver.resolveWorkstreamUrl(input);
		assertEquals(input, result);
	}
}
