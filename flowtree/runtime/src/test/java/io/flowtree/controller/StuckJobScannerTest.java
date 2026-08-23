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

package io.flowtree.controller;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link StuckJobScanner} and the heartbeat plumbing it reads.
 *
 * <p>The behaviour under test is the one the rest of the system cannot
 * provide: turning a job that stopped reporting into a terminal event, so a
 * completion listener waiting behind it is released. These tests pin both
 * directions — that a silent job is failed, and that a job still reporting is
 * left alone — because a scanner that terminates live jobs would be worse than
 * no scanner at all.</p>
 */
public class StuckJobScannerTest extends TestSuiteBase {

	/** Records the terminal events a scan dispatches. */
	private final List<JobCompletionEvent> dispatched = new ArrayList<>();

	/** Workstream IDs the dispatched events were keyed by. */
	private final List<String> dispatchedWorkstreams = new ArrayList<>();

	/**
	 * Creates and initialises a store backed by a fresh temporary database.
	 *
	 * @return the initialised store
	 * @throws Exception if the temporary directory cannot be created
	 */
	private JobStatsStore newStore() throws Exception {
		File tempDir = Files.createTempDirectory("stuck-job-test").toFile();
		tempDir.deleteOnExit();
		JobStatsStore store = new JobStatsStore(new File(tempDir, "stats").getAbsolutePath());
		store.initialize();
		return store;
	}

	/**
	 * Creates a scanner over {@code store} that records what it dispatches.
	 *
	 * @param store   the store to scan
	 * @param ceiling the wall-clock ceiling the threshold derives from
	 * @return the scanner
	 */
	private StuckJobScanner newScanner(JobStatsStore store, Duration ceiling) {
		return new StuckJobScanner(store, ceiling, (ws, event) -> {
			dispatchedWorkstreams.add(ws);
			dispatched.add(event);
		});
	}

	/**
	 * A job whose last heartbeat is older than the threshold is failed, and the
	 * synthesized event is dispatched so listeners waiting on it are released.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void silentJobIsFailedAndDispatched() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-08-22T12:00:00Z");
		store.recordJobStarted("job-silent", "ws-1", "implement the thing",
				now.minus(Duration.ofHours(20)));
		store.recordHeartbeat("job-silent", now.minus(Duration.ofHours(19)));

		StuckJobScanner scanner = newScanner(store, Duration.ofHours(6));
		List<String> failed = scanner.scanOnce(now);

		assertEquals("the silent job should be failed", 1, failed.size());
		Assert.assertEquals("job-silent", failed.get(0));
		assertEquals("a terminal event must be dispatched", 1, dispatched.size());
		Assert.assertEquals("dispatched under the job's workstream",
				"ws-1", dispatchedWorkstreams.get(0));
		Assert.assertEquals("a stalled job is FAILED, not DEGRADED",
				JobCompletionEvent.Status.FAILED, dispatched.get(0).getStatus());
		assertTrue("the error should name the silence, so the operator can tell"
						+ " a stall from a crash: " + dispatched.get(0).getErrorMessage(),
				dispatched.get(0).getErrorMessage().contains("no heartbeat"));
		store.close();
	}

	/**
	 * A job that is still reporting is left alone even when it has been running
	 * far longer than the wall-clock ceiling. Running long is not the same as
	 * being stuck, and only the second justifies destroying the job.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void jobStillReportingIsNotFailed() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-08-22T12:00:00Z");
		store.recordJobStarted("job-busy", "ws-1", "long but alive",
				now.minus(Duration.ofHours(30)));
		store.recordHeartbeat("job-busy", now.minus(Duration.ofMinutes(2)));

		StuckJobScanner scanner = newScanner(store, Duration.ofHours(6));

		assertTrue("a job past its ceiling but still reporting must survive",
				scanner.scanOnce(now).isEmpty());
		assertTrue("nothing should be dispatched for a live job", dispatched.isEmpty());
		store.close();
	}

	/**
	 * A job that died before sending its first heartbeat is still detected: the
	 * staleness clock falls back to the start time. Without that fallback such
	 * a job would look permanently fresh and never be recovered.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void jobWithNoHeartbeatFallsBackToStartTime() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-08-22T12:00:00Z");
		store.recordJobStarted("job-stillborn", "ws-1", "died early",
				now.minus(Duration.ofHours(20)));

		StuckJobScanner scanner = newScanner(store, Duration.ofHours(6));

		assertEquals("a job that never sent a heartbeat must still be recoverable",
				1, scanner.scanOnce(now).size());
		store.close();
	}

	/**
	 * Repeated scans fail a job exactly once, so a late real completion is not
	 * competing with a stream of synthesized ones.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void terminationIsIdempotent() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-08-22T12:00:00Z");
		store.recordJobStarted("job-silent", "ws-1", "gone",
				now.minus(Duration.ofHours(20)));

		StuckJobScanner scanner = newScanner(store, Duration.ofHours(6));
		scanner.scanOnce(now);
		List<String> second = scanner.scanOnce(now.plus(Duration.ofHours(1)));

		assertTrue("a second scan must not re-fail the same job", second.isEmpty());
		assertEquals("exactly one terminal event per stalled job", 1, dispatched.size());
		store.close();
	}

	/**
	 * A completed job is not a candidate however old it is; the scanner reads
	 * only jobs the store still considers running.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void completedJobIsNeverScanned() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-08-22T12:00:00Z");
		store.recordJobStarted("job-done", "ws-1", "finished",
				now.minus(Duration.ofHours(40)));
		store.recordJobCompleted("ws-1",
				JobCompletionEvent.success("job-done", "finished"));

		StuckJobScanner scanner = newScanner(store, Duration.ofHours(6));

		assertTrue("a completed job must never be re-failed",
				scanner.scanOnce(now).isEmpty());
		assertFalse("the store should not report it as active",
				store.getActiveJobs("ws-1").stream()
						.anyMatch(j -> "job-done".equals(j.jobId())));
		store.close();
	}

	/**
	 * Disabling the wall-clock ceiling disables the scanner with it. A
	 * workstream that opted out of time bounds must not have its jobs
	 * terminated by the mechanism derived from that bound.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void disabledCeilingDisablesScanning() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-08-22T12:00:00Z");
		store.recordJobStarted("job-silent", "ws-1", "unbounded",
				now.minus(Duration.ofHours(100)));

		StuckJobScanner scanner = newScanner(store, Duration.ZERO);

		Assert.assertEquals("a disabled ceiling yields no threshold",
				Duration.ZERO, scanner.stalenessThreshold());
		assertTrue("nothing may be terminated when the ceiling is disabled",
				scanner.scanOnce(now).isEmpty());
		store.close();
	}

	/**
	 * The threshold is the ceiling times the multiplier, so a job is given
	 * genuine benefit of the doubt beyond the point where it stops launching
	 * new work.
	 */
	@Test(timeout = 60000)
	public void thresholdIsAMultipleOfTheCeiling() {
		StuckJobScanner scanner = newScanner(null, Duration.ofHours(6));

		Assert.assertEquals("default threshold is twice the ceiling",
				Duration.ofHours(12), scanner.stalenessThreshold());

		scanner.setStalenessMultiplier(3);
		Assert.assertEquals(Duration.ofHours(18), scanner.stalenessThreshold());
	}

	/**
	 * A heartbeat recorded for a running job is read back, and the job is
	 * reported as active until it completes.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void heartbeatRoundTripsThroughTheStore() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-08-22T12:00:00Z");
		store.recordJobStarted("job-live", "ws-1", "running", now.minus(Duration.ofHours(1)));
		store.recordHeartbeat("job-live", now.minus(Duration.ofMinutes(1)));

		List<JobStatsStore.ActiveJob> active = store.getActiveJobs("ws-1");
		assertEquals("the running job should be listed", 1, active.size());

		JobStatsStore.ActiveJob job = active.get(0);
		Assert.assertEquals("job-live", job.jobId());
		assertEquals("age is measured from the start time",
				60, job.age(now).toMinutes());
		assertEquals("staleness is measured from the heartbeat",
				1, job.sinceHeartbeat(now).toMinutes());
		assertTrue("the rendered entry should carry the derived ages",
				job.toJson(now).contains("sinceHeartbeatSeconds"));
		store.close();
	}
}
