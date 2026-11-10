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
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link WorkspaceResolver} covering workspace path resolution,
 * repository name extraction, and workstream URL resolution.
 */
public class WorkspaceResolverTest extends TestSuiteBase {

	/**
	 * Verifies that a configured workspace path is used as the base when resolving the workspace directory.
	 */
	@Test(timeout = 30000)
	public void usesConfiguredPathFirst() {
		String result = WorkspaceResolver.resolve("/my/path", "https://github.com/owner/repo.git");
		assertEquals("/my/path/owner-repo", result);
	}

	/**
	 * Verifies that resolution falls back to a temp directory suffixed with the repo name when no path is configured.
	 */
	@Test(timeout = 30000)
	public void fallsBackToTempWithRepoName() {
		String result = WorkspaceResolver.resolve(null, "https://github.com/owner/repo.git");
		assertTrue("Expected result to end with 'owner-repo', got: " + result,
			result.endsWith("owner-repo"));
	}

	/**
	 * Verifies that the repository name is correctly extracted from an SSH-style remote URL.
	 */
	@Test(timeout = 30000)
	public void extractsRepoNameFromSSH() {
		String result = WorkspaceResolver.extractRepoName("git@github.com:owner/repo.git");
		assertEquals("owner-repo", result);
	}

	/**
	 * Verifies that the repository name is correctly extracted from an HTTPS remote URL.
	 */
	@Test(timeout = 30000)
	public void extractsRepoNameFromHTTPS() {
		String result = WorkspaceResolver.extractRepoName("https://github.com/owner/repo.git");
		assertEquals("owner-repo", result);
	}

	/**
	 * Verifies that a workstream URL is returned unchanged when no root host replacement is configured.
	 */
	@Test(timeout = 30000)
	public void resolvesWorkstreamUrl() {
		// When FLOWTREE_ROOT_HOST is not set, the URL is returned unchanged
		// because there is no replacement value available.
		String input = "http://10.0.0.1:8080/api";
		String result = WorkspaceResolver.resolveWorkstreamUrl(input);
		assertEquals(input, result);
	}

	/**
	 * Verifies that a job with no workstream URL resolves to {@code null}
	 * rather than failing, since {@code GitManagedJob} delegates here for
	 * jobs that were never bound to a workstream.
	 */
	@Test(timeout = 30000)
	public void resolvesNullWorkstreamUrlToNull() {
		assertNull(WorkspaceResolver.resolveWorkstreamUrl(null));
	}

	/**
	 * Verifies that the controller base URL (scheme + host + port) is extracted
	 * from a typical workstream job URL, stripping the {@code /api/workstreams/...} path.
	 */
	@Test(timeout = 30000)
	public void extractsControllerBaseUrlFromTypicalUrl() {
		assertEquals("http://0.0.0.0:7700",
			WorkspaceResolver.extractControllerBaseUrl(
				"http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc"));
	}

	/**
	 * Verifies that the controller base URL is extracted when the authority has no port.
	 */
	@Test(timeout = 30000)
	public void extractsControllerBaseUrlHostOnly() {
		assertEquals("http://controller.local",
			WorkspaceResolver.extractControllerBaseUrl(
				"http://controller.local/api/workstreams/mystream/jobs/j1"));
	}

	/**
	 * Verifies that controller base URL extraction returns {@code null} when the
	 * URL has no {@code /api/workstreams/} segment.
	 */
	@Test(timeout = 30000)
	public void extractsControllerBaseUrlNoSegmentReturnsNull() {
		assertNull(WorkspaceResolver.extractControllerBaseUrl("http://host:7700/api/submit"));
	}

	/**
	 * Verifies that controller base URL extraction returns {@code null} for an empty string.
	 */
	@Test(timeout = 30000)
	public void extractsControllerBaseUrlEmptyReturnsNull() {
		assertNull(WorkspaceResolver.extractControllerBaseUrl(""));
	}

	/**
	 * Verifies that controller base URL extraction returns {@code null} for {@code null}
	 * input rather than throwing. This pins the behavior the inline copies lacked.
	 */
	@Test(timeout = 30000)
	public void extractsControllerBaseUrlNullReturnsNull() {
		assertNull(WorkspaceResolver.extractControllerBaseUrl(null));
	}

	/**
	 * Verifies that the workstream id is extracted from a typical workstream job URL.
	 */
	@Test(timeout = 30000)
	public void extractsWorkstreamIdFromTypicalUrl() {
		assertEquals("ws-1",
			WorkspaceResolver.extractWorkstreamId(
				"http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc"));
	}

	/**
	 * Verifies that the workstream id is extracted when there is no trailing {@code /jobs/...} segment.
	 */
	@Test(timeout = 30000)
	public void extractsWorkstreamIdNoJobsSegment() {
		assertEquals("mystream",
			WorkspaceResolver.extractWorkstreamId("http://host/api/workstreams/mystream"));
	}

	/**
	 * Verifies that the {@code /workstreams/} marker matches with or without the
	 * {@code /api} prefix.
	 */
	@Test(timeout = 30000)
	public void extractsWorkstreamIdWithoutApiPrefix() {
		assertEquals("ws-9",
			WorkspaceResolver.extractWorkstreamId("http://host/workstreams/ws-9/jobs/j"));
	}

	/**
	 * Verifies that workstream id extraction returns {@code null} when the URL has
	 * no {@code /workstreams/} segment.
	 */
	@Test(timeout = 30000)
	public void extractsWorkstreamIdNoSegmentReturnsNull() {
		assertNull(WorkspaceResolver.extractWorkstreamId("http://host:7700/api/submit"));
	}

	/**
	 * Verifies that workstream id extraction returns {@code null} when the segment is
	 * followed by an empty identifier.
	 */
	@Test(timeout = 30000)
	public void extractsWorkstreamIdEmptyIdReturnsNull() {
		assertNull(WorkspaceResolver.extractWorkstreamId("http://host/api/workstreams/"));
	}

	/**
	 * Verifies that workstream id extraction returns {@code null} for an empty string.
	 */
	@Test(timeout = 30000)
	public void extractsWorkstreamIdEmptyReturnsNull() {
		assertNull(WorkspaceResolver.extractWorkstreamId(""));
	}

	/**
	 * Verifies that workstream id extraction returns {@code null} for {@code null}
	 * input rather than throwing. This pins the behavior the diverged copy lacked.
	 */
	@Test(timeout = 30000)
	public void extractsWorkstreamIdNullReturnsNull() {
		assertNull(WorkspaceResolver.extractWorkstreamId(null));
	}
}
