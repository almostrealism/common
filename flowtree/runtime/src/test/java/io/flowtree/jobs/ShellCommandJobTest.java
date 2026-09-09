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

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ShellCommandJob} execution, serialization, factory dispatch,
 * and message formatting.
 */
public class ShellCommandJobTest extends TestSuiteBase {

	/** Verifies the task-id constructor and the unexecuted default state. */
	@Test(timeout = 30000)
	public void testConstructorWithTaskId() {
		ShellCommandJob job = new ShellCommandJob("test-id");
		assertEquals("test-id", job.getTaskId());
		assertNull(job.getCommand());
		assertEquals(-1, job.getExitCode());
	}

	/** Verifies the task-id-and-command constructor records both values. */
	@Test(timeout = 30000)
	public void testConstructorWithCommand() {
		ShellCommandJob job = new ShellCommandJob("test-id", "echo hi");
		assertEquals("test-id", job.getTaskId());
		assertEquals("echo hi", job.getCommand());
	}

	/** Verifies the command getter and setter round-trip. */
	@Test(timeout = 30000)
	public void testSetGetCommand() {
		ShellCommandJob job = new ShellCommandJob("test-id");
		job.setCommand("ls -la");
		assertEquals("ls -la", job.getCommand());
	}

	/** Executes a simple command and verifies stdout capture and a zero exit code. */
	@Test(timeout = 30000)
	public void testExecuteEcho() {
		ShellCommandJob job = new ShellCommandJob("exec-1", "echo hello-world");
		job.doWork();
		assertEquals(0, job.getExitCode());
		assertTrue(job.getStdout().contains("hello-world"));
		assertTrue(job.getStderr() == null || job.getStderr().isEmpty());
	}

	/** Verifies a command's non-zero exit code is captured. */
	@Test(timeout = 30000)
	public void testExecuteNonZeroExit() {
		ShellCommandJob job = new ShellCommandJob("exec-2", "exit 3");
		job.doWork();
		assertEquals(3, job.getExitCode());
	}

	/** Verifies createEvent reports SUCCESS when the command exits zero. */
	@Test(timeout = 30000)
	public void testCreateEventSuccessOnZeroExit() {
		ShellCommandJob job = new ShellCommandJob("exec-status-1", "exit 0");
		job.doWork();
		JobCompletionEvent event = job.createEvent(null);
		assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
	}

	/**
	 * Verifies createEvent reports FAILED when the command exits non-zero,
	 * even though doWork() itself never throws — this is the fix ensuring a
	 * self-notify wake-up reports the command's real outcome.
	 */
	@Test(timeout = 30000)
	public void testCreateEventFailedOnNonZeroExit() {
		ShellCommandJob job = new ShellCommandJob("exec-status-2", "exit 3");
		job.doWork();
		JobCompletionEvent event = job.createEvent(null);
		assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
	}

	/** Verifies createEvent reports FAILED when a lifecycle exception is passed in. */
	@Test(timeout = 30000)
	public void testCreateEventFailedOnLifecycleException() {
		ShellCommandJob job = new ShellCommandJob("exec-status-3", "echo ok");
		job.doWork();
		JobCompletionEvent event = job.createEvent(new RuntimeException("git failure"));
		assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
		assertEquals("git failure", event.getErrorMessage());
	}

	/** Verifies stderr output is captured separately from stdout. */
	@Test(timeout = 30000)
	public void testExecuteCapturesStderr() {
		ShellCommandJob job = new ShellCommandJob("exec-3", "echo oops 1>&2");
		job.doWork();
		assertEquals(0, job.getExitCode());
		assertTrue(job.getStderr().contains("oops"));
	}

	/** Verifies a missing command yields an error exit code and diagnostic stderr. */
	@Test(timeout = 30000)
	public void testNoCommandConfigured() {
		ShellCommandJob job = new ShellCommandJob("exec-4");
		job.doWork();
		assertEquals(-1, job.getExitCode());
		assertNotNull(job.getStderr());
		assertFalse(job.getStderr().isEmpty());
	}

	/** Verifies the job never reports changes to commit. */
	@Test(timeout = 30000)
	public void testValidateChangesNeverCommits() throws Exception {
		ShellCommandJob job = new ShellCommandJob("exec-5", "echo x");
		assertFalse(job.validateChanges());
	}

	/** Verifies the self-notify flag getter and setter round-trip. */
	@Test(timeout = 30000)
	public void testSetGetSelfNotify() {
		ShellCommandJob job = new ShellCommandJob("exec-6", "echo x");
		assertFalse(job.isSelfNotify());
		job.setSelfNotify(true);
		assertTrue(job.isSelfNotify());
	}

	/** Verifies populateEventDetails stamps the self-notify flag onto the completion event. */
	@Test(timeout = 30000)
	public void testPopulateEventDetailsCarriesSelfNotify() {
		ShellCommandJob job = new ShellCommandJob("exec-7", "echo x");
		job.setSelfNotify(true);
		JobCompletionEvent event = job.createEvent(null);
		job.populateEventDetails(event);
		assertTrue(event.isSelfNotify());
	}

	/** Verifies the completion message includes the task id, exit code, and stdout. */
	@Test(timeout = 30000)
	public void testBuildOutputMessage() {
		ShellCommandJob job = new ShellCommandJob("msg-1", "echo formatted");
		job.doWork();
		String message = job.buildOutputMessage();
		assertTrue(message.contains("msg-1"));
		assertTrue(message.contains("exit code 0"));
		assertTrue(message.contains("STDOUT"));
		assertTrue(message.contains("formatted"));
	}

	/** Verifies short and null inputs are returned unchanged by truncate. */
	@Test(timeout = 30000)
	public void testTruncateShortUnchanged() {
		assertEquals("short", ShellCommandJob.truncate("short", 100));
		assertEquals("", ShellCommandJob.truncate(null, 100));
	}

	/** Verifies long input is truncated to the limit with a suffix marker. */
	@Test(timeout = 30000)
	public void testTruncateLongShortened() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 500; i++) {
			sb.append('a');
		}
		String truncated = ShellCommandJob.truncate(sb.toString(), 100);
		assertEquals(100, truncated.length());
		assertTrue(truncated.endsWith(" [truncated]"));
	}

	/** Verifies small and non-positive limits are handled without overflow. */
	@Test(timeout = 30000)
	public void testTruncateSmallLimit() {
		assertEquals("abc", ShellCommandJob.truncate("abcdef", 3));
		assertEquals("", ShellCommandJob.truncate("abcdef", 0));
		assertEquals("", ShellCommandJob.truncate("abcdef", -5));
	}

	/** Verifies command summaries collapse whitespace, truncate, and handle null. */
	@Test(timeout = 30000)
	public void testSummarizeCommand() {
		assertEquals("(no command)", ShellCommandJob.summarizeCommand(null));
		assertEquals("(no command)", ShellCommandJob.summarizeCommand(""));
		assertEquals("echo hi", ShellCommandJob.summarizeCommand("echo   hi"));
		StringBuilder sb = new StringBuilder("run ");
		for (int i = 0; i < 200; i++) {
			sb.append('x');
		}
		String summary = ShellCommandJob.summarizeCommand(sb.toString());
		assertTrue(summary.length() <= 60);
	}

	/** Verifies the job wire encoding preserves the command and all git fields. */
	@Test(timeout = 30000)
	public void testJobEncodeRoundTripPreservesAllFields() {
		ShellCommandJob job = new ShellCommandJob("rt-1", "echo serialize");
		job.setRepoUrl("git@github.com:almostrealism/common.git");
		job.setTargetBranch("feature/x");
		job.setWorkstreamUrl("http://0.0.0.0:8080/api/workstreams/ws-1/jobs/rt-1");

		ShellCommandJob restored = (ShellCommandJob) roundTrip(job);
		assertEquals("rt-1", restored.getTaskId());
		assertEquals("echo serialize", restored.getCommand());
		assertEquals("git@github.com:almostrealism/common.git", restored.getRepoUrl());
		assertEquals("feature/x", restored.getTargetBranch());
		assertEquals("http://0.0.0.0:8080/api/workstreams/ws-1/jobs/rt-1",
				restored.getWorkstreamUrl());
	}

	/** Verifies the job wire encoding preserves the default workspace path. */
	@Test(timeout = 30000)
	public void testJobEncodeRoundTripPreservesDefaultWorkspacePath() {
		ShellCommandJob job = new ShellCommandJob("rt-2", "echo serialize");
		job.setDefaultWorkspacePath("/workspace/project");

		ShellCommandJob restored = (ShellCommandJob) roundTrip(job);
		assertEquals("/workspace/project", restored.getDefaultWorkspacePath());
	}

	/** Verifies the job wire encoding preserves the self-notify flag. */
	@Test(timeout = 30000)
	public void testJobEncodeRoundTripPreservesSelfNotify() {
		ShellCommandJob job = new ShellCommandJob("rt-3", "echo serialize");
		job.setSelfNotify(true);

		ShellCommandJob restored = (ShellCommandJob) roundTrip(job);
		assertTrue(restored.isSelfNotify());
	}

	/** Verifies an unset self-notify flag round-trips as false (not emitted on the wire). */
	@Test(timeout = 30000)
	public void testJobEncodeRoundTripDefaultsSelfNotifyFalse() {
		ShellCommandJob job = new ShellCommandJob("rt-4", "echo serialize");

		ShellCommandJob restored = (ShellCommandJob) roundTrip(job);
		assertFalse(restored.isSelfNotify());
	}

	/** Verifies the factory configures and dispatches exactly one job. */
	@Test(timeout = 30000)
	public void testFactoryNextJobProducesConfiguredJob() {
		ShellCommandJob.Factory factory = new ShellCommandJob.Factory("echo factory");
		factory.setRepoUrl("git@github.com:almostrealism/common.git");
		factory.setTargetBranch("feature/y");
		factory.setWorkstreamUrl("http://0.0.0.0:8080/api/workstreams/ws-2/jobs/x");

		assertEquals(0.0, factory.getCompleteness(), 1e-9);
		Job next = factory.nextJob();
		assertNotNull(next);
		ShellCommandJob job = (ShellCommandJob) next;
		assertEquals("echo factory", job.getCommand());
		assertEquals("git@github.com:almostrealism/common.git", job.getRepoUrl());
		assertEquals("feature/y", job.getTargetBranch());
		assertEquals("http://0.0.0.0:8080/api/workstreams/ws-2/jobs/x",
				job.getWorkstreamUrl());

		assertNull(factory.nextJob());
		assertEquals(1.0, factory.getCompleteness(), 1e-9);
	}

	/** Verifies the factory propagates the default workspace path and self-notify flag onto the dispatched job. */
	@Test(timeout = 30000)
	public void testFactoryNextJobPropagatesWorkspacePathAndSelfNotify() {
		ShellCommandJob.Factory factory = new ShellCommandJob.Factory("echo factory");
		factory.setDefaultWorkspacePath("/workspace/project");
		factory.setSelfNotify(true);

		Job next = factory.nextJob();
		assertNotNull(next);
		ShellCommandJob job = (ShellCommandJob) next;
		assertEquals("/workspace/project", job.getDefaultWorkspacePath());
		assertTrue(job.isSelfNotify());
	}

	/** Verifies the factory wire encoding preserves the command and repo URL. */
	@Test(timeout = 30000)
	public void testFactoryEncodeRoundTrip() {
		ShellCommandJob.Factory factory = new ShellCommandJob.Factory("echo round::trip");
		factory.setRepoUrl("git@github.com:almostrealism/common.git");

		ShellCommandJob.Factory restored = new ShellCommandJob.Factory();
		applyEncoded(factory.encode(), restored);
		assertEquals("echo round::trip", restored.getCommand());
		assertEquals("git@github.com:almostrealism/common.git", restored.getRepoUrl());
	}

	/** Verifies the factory wire encoding preserves the default workspace path and self-notify flag. */
	@Test(timeout = 30000)
	public void testFactoryEncodeRoundTripPreservesWorkspacePathAndSelfNotify() {
		ShellCommandJob.Factory factory = new ShellCommandJob.Factory("echo round::trip");
		factory.setDefaultWorkspacePath("/workspace/project");
		factory.setSelfNotify(true);

		ShellCommandJob.Factory restored = new ShellCommandJob.Factory();
		applyEncoded(factory.encode(), restored);
		assertEquals("/workspace/project", restored.getDefaultWorkspacePath());
		assertTrue(restored.isSelfNotify());
	}

	/**
	 * Round-trips a job through its wire encoding by replaying the encoded
	 * {@code key:=value} tokens onto a fresh instance, mirroring how the
	 * {@code JobClassLoader} reconstructs jobs on remote nodes.
	 *
	 * @param job the job to encode and reconstruct
	 * @return the reconstructed job
	 */
	private static Job roundTrip(Job job) {
		ShellCommandJob restored = new ShellCommandJob();
		applyEncoded(job.encode(), restored);
		return restored;
	}

	/**
	 * Replays the {@code key:=value} tokens of an encoded string onto the given
	 * target, skipping the leading fully-qualified class name token.
	 *
	 * @param encoded the encoded string
	 * @param target  the job or factory to populate
	 */
	private static void applyEncoded(String encoded, Object target) {
		String[] tokens = encoded.split(JobFactory.ENTRY_SEPARATOR);
		for (int i = 1; i < tokens.length; i++) {
			int k = tokens[i].indexOf(JobFactory.KEY_VALUE_SEPARATOR);
			if (k < 0) continue;
			String key = tokens[i].substring(0, k);
			String value = tokens[i].substring(k + JobFactory.KEY_VALUE_SEPARATOR.length());
			if (target instanceof Job) {
				((Job) target).set(key, value);
			} else {
				((JobFactory) target).set(key, value);
			}
		}
	}
}
