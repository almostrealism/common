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

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link JobCompletionEvent} covering factory methods,
 * builder-pattern setters, default values, and toString output.
 */
public class JobCompletionEventTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void successEventHasCorrectStatus() {
		JobCompletionEvent event = JobCompletionEvent.success("job-1", "Test job");
		assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
	}

	@Test(timeout = 30000)
	public void failedEventCapturesError() {
		RuntimeException cause = new RuntimeException("cause");
		JobCompletionEvent event = JobCompletionEvent.failed("job-2", "Failing job", "msg", cause);

		assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
		assertEquals("msg", event.getErrorMessage());
		assertNotNull(event.getException());
		assertEquals(cause, event.getException());
	}

	@Test(timeout = 30000)
	public void withGitInfoSetsAllFields() {
		List<String> staged = Arrays.asList("file1.java", "file2.java");
		List<String> skipped = Arrays.asList("README.md");

		JobCompletionEvent event = JobCompletionEvent.success("job-3", "Git job")
				.withGitInfo("feature-branch", "abc123", staged, skipped, true);

		assertEquals("feature-branch", event.getTargetBranch());
		assertEquals("abc123", event.getCommitHash());
		assertEquals(staged, event.getStagedFiles());
		assertEquals(skipped, event.getSkippedFiles());
		assertTrue(event.isPushed());
	}

	@Test(timeout = 30000)
	public void withPullRequestUrlSetsUrl() {
		JobCompletionEvent event = JobCompletionEvent.success("job-4", "PR job")
				.withPullRequestUrl("https://github.com/org/repo/pull/1");

		assertEquals("https://github.com/org/repo/pull/1", event.getPullRequestUrl());
	}

	@Test(timeout = 30000)
	public void defaultListsAreEmpty() {
		JobCompletionEvent event = JobCompletionEvent.success("job-5", "Default lists");

		assertNotNull(event.getStagedFiles());
		assertTrue(event.getStagedFiles().isEmpty());
		assertNotNull(event.getSkippedFiles());
		assertTrue(event.getSkippedFiles().isEmpty());
	}

	@Test(timeout = 30000)
	public void toStringIncludesKeyFields() {
		JobCompletionEvent event = JobCompletionEvent.success("job-6", "ToString job")
				.withGitInfo("main", "def456", null, null, false);

		String str = event.toString();
		assertTrue("toString should contain jobId", str.contains("job-6"));
		assertTrue("toString should contain status name", str.contains("SUCCESS"));
		assertTrue("toString should contain commitHash", str.contains("def456"));
	}
}
