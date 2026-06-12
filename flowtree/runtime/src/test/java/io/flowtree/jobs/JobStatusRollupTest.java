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

import io.flowtree.jobs.agent.AgentRunResult;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the job-status rollup in
 * {@link CodingAgentJob#createEvent(Exception)}.
 *
 * <p>The operator-reported bug: a primary phase that exited non-zero in 0s
 * (an immediate hard failure that did no work) was being reported as
 * {@link JobCompletionEvent.Status#SUCCESS} at the top level on
 * {@code workstream_get_job}. The per-phase {@code harness_status} messages
 * in {@code workstream_context} correctly showed the primary as
 * {@code "failed (exit N) in 0s"}, but the rollup was silently rescued by
 * a successful {@code enforce_changes} retry that overwrote
 * {@link CodingAgentJob#getExitCode()} with a zero.</p>
 *
 * <p>These tests pin the contract: a hard primary failure is terminal at
 * the rollup level, independent of retry outcomes; a normal primary failure
 * remains recoverable by a successful retry; a successful primary stays
 * {@code SUCCESS}.</p>
 */
public class JobStatusRollupTest extends TestSuiteBase {

	/** Temporary working directory for the job under test. */
	private Path workDir;

	/** Creates a fresh empty working directory before each test. */
	@Before
	public void setUp() throws IOException {
		workDir = Files.createTempDirectory("job-status-rollup-");
	}

	/** Removes the temporary working directory after each test. */
	@After
	public void tearDown() {
		if (workDir == null) return;
		try {
			if (!Files.exists(workDir)) return;
			try (Stream<Path> stream = Files.walk(workDir)) {
				stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
						.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
			}
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}

	/**
	 * Test subclass that exposes the protected {@link CodingAgentJob#createEvent}
	 * for direct invocation, and that can be configured to simulate any sequence
	 * of {@link CodingAgentJob#executeSingleRun()} outcomes without spawning a
	 * real subprocess.
	 */
	static class RollupTestJob extends CodingAgentJob {
		/** Pre-recorded results, consumed one per {@link #executeSingleRun()} call. */
		final List<AgentRunResult> scripted;
		/** Pre-recorded uncommitted-changes flag for each {@link #executeSingleRun()} call. */
		final List<Boolean> committedPerCall;
		/** Index of the next scripted result. */
		final AtomicInteger callIndex = new AtomicInteger();

		/**
		 * Creates a job whose {@link #executeSingleRun()} returns the next
		 * scripted result and whose {@link #hasAgentCommitted()} returns the
		 * next scripted committed flag.
		 *
		 * @param taskId           the job identifier
		 * @param prompt           the prompt (unused by the spy)
		 * @param scripted         ordered list of results; one consumed per call
		 * @param committedPerCall ordered list of committed flags; one per call
		 */
		RollupTestJob(String taskId, String prompt, List<AgentRunResult> scripted,
				List<Boolean> committedPerCall) {
			super(taskId, prompt);
			this.scripted = List.copyOf(scripted);
			this.committedPerCall = List.copyOf(committedPerCall);
		}

		/** Delegates to the protected {@link CodingAgentJob#createEvent}. */
		JobCompletionEvent createEventNow(Exception error) { return createEvent(error); }

		/** Returns the last scripted committed-flag, or {@code false} if none. */
		@Override
		protected boolean hasAgentCommitted() {
			int i = Math.max(0, callIndex.get() - 1);
			if (i >= committedPerCall.size()) return false;
			return committedPerCall.get(i);
		}

		/** Absorbs the next scripted result instead of dispatching a real agent. */
		@Override
		void executeSingleRun() {
			int i = callIndex.getAndIncrement();
			if (i >= scripted.size()) {
				throw new IllegalStateException("No scripted result for call " + i);
			}
			AgentRunResult r = scripted.get(i);
			setWasKilledForInactivity(r.killedForInactivity());
			absorbResult(r);
		}
	}

	/** Hard failure result: non-zero exit, 0s duration, not killed for inactivity. */
	private static AgentRunResult hardFailure(int exitCode) {
		return new AgentRunResult(
				exitCode, false, "", null,
				0L, 0L, 0, 0.0,
				"error", true,
				Collections.emptyList(),
				Collections.emptyMap());
	}

	/** Successful result: exit 0, some duration, success stop reason. */
	private static AgentRunResult success(long durationMs) {
		return new AgentRunResult(
				0, false, "{\"type\":\"result\"}", "sess-ok",
				durationMs, durationMs / 2, 1, 0.01,
				"success", false,
				Collections.emptyList(),
				Collections.emptyMap());
	}

	// ── The reported bug ──────────────────────────────────────────────────

	/**
	 * Primary phase exits 1 in 0s, then an {@code enforce_changes} retry
	 * succeeds (exit 0). The rollup must report {@code FAILED} — the
	 * per-phase {@code harness_status} truth is that the primary did no work.
	 *
	 * <p>Before the rollup fix this test failed: the rollup said
	 * {@code SUCCESS} because the retry's successful exit code had
	 * overwritten the primary's non-zero exit code in
	 * {@link CodingAgentJob#getExitCode()}.</p>
	 */
	@Test(timeout = 30000)
	public void hardPrimaryFailureThenRecoveryRetryRollsUpToFailed() throws Exception {
		RollupTestJob job = new RollupTestJob("hard-then-recover", "do the work",
				List.of(
						hardFailure(1),   // primary: crash in 0s
						success(1500L)), // enforce_changes retry: commits
				List.of(false, true));   // primary did not commit, retry committed
		job.setWorkingDirectory(workDir.toString());
		job.setEnforceChanges(true);

		// Mirror doWork(): run the primary, capture the hard-failure flag
		// while exitCode still reflects the primary, then run the enforcement
		// rules (which will retry and succeed, overwriting exitCode).
		job.executeSingleRun();
		job.setPrimaryPhaseHardFailed(job.isHardPrimaryFailure());
		job.runEnforcementRules();

		JobCompletionEvent event = job.createEventNow(null);
		assertNotNull(event);
		assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
		assertNotNull("FAILED rollup must carry a diagnostic message",
				event.getErrorMessage());
		assertTrue("Diagnostic should mention the hard-failure signature, got: "
						+ event.getErrorMessage(),
				event.getErrorMessage().contains("hard-failed")
						|| event.getErrorMessage().contains("0s"));
	}

	// ── Non-regression: legitimate outcomes still work ────────────────────

	/**
	 * A primary that succeeds must still roll up to {@code SUCCESS}. This is
	 * the most common path and must not be disturbed by the fix.
	 */
	// TODO(review): test never calls executeSingleRun() — it verifies the default field state
	// (exitCode=0, primaryPhaseHardFailed=false), not the actual execution path through
	// absorbResult/isHardPrimaryFailure. Consider adding executeSingleRun()+flag-capture
	// before createEventNow(), mirroring normalPrimaryFailureWithRecoveryRollsUpToSuccess.
	@Test(timeout = 30000)
	public void primarySuccessRollsUpToSuccess() throws Exception {
		RollupTestJob job = new RollupTestJob("ok", "do the work",
				List.of(success(2000L)),
				List.of(true));
		job.setWorkingDirectory(workDir.toString());

		JobCompletionEvent event = job.createEventNow(null);
		assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
	}

	/**
	 * A normal primary failure (non-zero exit, but the process did some
	 * work — durationMs &gt; 0) that is then rescued by a successful retry
	 * must still roll up to {@code SUCCESS}. This guards the existing
	 * retry/auto-resolve semantics the operator said must not be broken.
	 */
	@Test(timeout = 30000)
	public void normalPrimaryFailureWithRecoveryRollsUpToSuccess() throws Exception {
		AgentRunResult normalFail = new AgentRunResult(
				1, false, "partial output", "sess-fail",
				5000L, 4000L, 3, 0.05,   // did real work before failing
				"error", true,
				Collections.emptyList(),
				Collections.emptyMap());
		RollupTestJob job = new RollupTestJob("normal-fail-recover", "do the work",
				List.of(normalFail, success(2000L)),
				List.of(false, true));
		job.setWorkingDirectory(workDir.toString());
		job.setEnforceChanges(true);

		// Run the primary, capture the flag (false, since durationMs > 0),
		// then run the enforcement rules.
		job.executeSingleRun();
		assertTrue("Normal primary failure (non-zero, 5000ms) must not be flagged as hard",
				!job.isHardPrimaryFailure());
		job.setPrimaryPhaseHardFailed(job.isHardPrimaryFailure());
		job.runEnforcementRules();

		JobCompletionEvent event = job.createEventNow(null);
		assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
	}

	/**
	 * A hard primary failure (non-zero exit, 0s duration) with NO successful
	 * recovery must roll up to {@code FAILED}. The existing
	 * {@code exitCode != 0} branch in {@link CodingAgentJob#createEvent}
	 * already handles this when the retry does not run; the new branch in
	 * the rollup handles it when the retry does run but also fails. This
	 * test pins the simpler no-retry case.
	 */
	@Test(timeout = 30000)
	public void hardPrimaryFailureNoRetryRollsUpToFailed() throws Exception {
		RollupTestJob job = new RollupTestJob("hard-no-retry", "do the work",
				List.of(hardFailure(1)),
				List.of(false));
		job.setWorkingDirectory(workDir.toString());

		// Run the primary, capture the flag.
		job.executeSingleRun();
		job.setPrimaryPhaseHardFailed(job.isHardPrimaryFailure());

		JobCompletionEvent event = job.createEventNow(null);
		assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
	}

	// ── isHardPrimaryFailure() predicate ──────────────────────────────────

	/**
	 * The hard-failure predicate must reject three near-miss cases that
	 * should NOT be treated as terminal at the rollup level: a zero exit,
	 * any non-zero duration, and an inactivity-killed process.
	 */
	@Test(timeout = 30000)
	public void hardFailurePredicateRejectsNearMisses() throws Exception {
		// Zero exit with zero duration is just a quick success, not a hard failure.
		assertHardFailurePredicate(false, 0, 0L, false);
		// Non-zero exit with non-zero duration: the agent did work, then failed.
		// This is a normal failure, not a hard failure, and is recoverable.
		assertHardFailurePredicate(false, 1, 5000L, false);
		// Non-zero exit and zero duration but killed by the inactivity watchdog:
		// the watchdog killed it, so the rollup should not treat it as a
		// terminal hard primary crash.
		assertHardFailurePredicate(false, 137, 0L, true);
		// The actual hard failure: non-zero exit, zero duration, not killed.
		assertHardFailurePredicate(true, 1, 0L, false);
	}

	/** Drives one scenario through the predicate and asserts the result. */
	private static void assertHardFailurePredicate(boolean expected, int exitCode,
			long durationMs, boolean killedForInactivity) {
		RollupTestJob job = new RollupTestJob("predicate", "x",
				List.of(new AgentRunResult(
						exitCode, killedForInactivity, "", null,
						durationMs, 0L, 0, 0.0,
						"x", exitCode != 0,
						Collections.emptyList(),
						Collections.emptyMap())),
				List.of(false));
		job.executeSingleRun();
		boolean actual = job.isHardPrimaryFailure();
		if (expected != actual) {
			throw new AssertionError("predicate for exit=" + exitCode
					+ " durationMs=" + durationMs + " killed=" + killedForInactivity
					+ " expected=" + expected + " actual=" + actual);
		}
	}
}
