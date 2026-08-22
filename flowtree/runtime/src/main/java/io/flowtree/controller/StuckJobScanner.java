/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.io.ConsoleFeatures;

/**
 * Converts jobs that have gone silent into terminal failure events.
 *
 * <p>Every other ceiling in the system bounds how much a job <em>does</em>:
 * sessions launched, turns consumed, dollars spent, and the wall-clock ceiling
 * that refuses further launches. None of them produces a terminal status for a
 * job that simply stops — a worker wedged on a network call, a subprocess
 * killed without posting its completion, or a container restarted with jobs in
 * flight. Anything waiting on that job's completion waits forever, because the
 * event it is waiting for is never posted by anyone.</p>
 *
 * <p>This scanner is what posts it. A job whose last liveness signal is older
 * than {@link #stalenessThreshold()} is failed with a message naming the
 * silence, which fires the ordinary completion path and releases whatever was
 * blocked behind it.</p>
 *
 * <p>Deliberately conservative about what it will terminate:</p>
 *
 * <ul>
 *   <li>The threshold is a multiple of the wall-clock ceiling, not the ceiling
 *       itself. A job that legitimately runs past its ceiling stops launching
 *       new sessions but is left alone; only a job that has also stopped
 *       reporting is failed.</li>
 *   <li>Termination is idempotent. The scanner records what it has already
 *       failed and never posts a second event for the same job, so a late real
 *       completion is not double-counted and a repeated scan is harmless.</li>
 *   <li>A scan failure is logged and swallowed. A scanner that dies takes the
 *       recovery path down with it, which is worse than a scan that skips.</li>
 * </ul>
 */
public class StuckJobScanner implements ConsoleFeatures {

	/**
	 * How often the scanner looks for silent jobs. Frequent enough that a
	 * stalled chain recovers in minutes rather than hours, rare enough that the
	 * query cost is irrelevant.
	 */
	public static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofMinutes(5);

	/**
	 * Multiplier applied to the wall-clock ceiling to get the silence a job may
	 * accumulate before it is treated as stuck.
	 *
	 * <p>Two rather than one because the two conditions mean different things.
	 * Reaching the ceiling means "this job may not start more work"; being
	 * silent for twice the ceiling means "this job is not running at all". The
	 * gap between them is the benefit of the doubt, and it is deliberately
	 * wide: failing a job that is actually alive destroys real work, while
	 * failing one late only delays a recovery that was not happening on its
	 * own.</p>
	 */
	public static final int DEFAULT_STALENESS_MULTIPLIER = 2;

	/** The store queried for active jobs and their heartbeats. */
	private final JobStatsStore statsStore;

	/** Receives the synthesized terminal event, keyed by workstream. */
	private final BiConsumer<String, JobCompletionEvent> terminalEventSink;

	/** The wall-clock ceiling the staleness threshold is derived from. */
	private Duration wallClockCeiling;

	/** Multiplier applied to {@link #wallClockCeiling}. */
	private int stalenessMultiplier = DEFAULT_STALENESS_MULTIPLIER;

	/** Job IDs already failed by this scanner; makes termination idempotent. */
	private final List<String> terminated = new ArrayList<>();

	/** The timer driving {@link #scanOnce}; {@code null} until started. */
	private ScheduledExecutorService executor;

	/**
	 * Creates a scanner.
	 *
	 * @param statsStore        the store to query; {@code null} disables scanning
	 * @param wallClockCeiling  the ceiling the staleness threshold derives from
	 * @param terminalEventSink receives each synthesized failure event, keyed by
	 *                          workstream ID; must not be {@code null}
	 */
	public StuckJobScanner(JobStatsStore statsStore, Duration wallClockCeiling,
			BiConsumer<String, JobCompletionEvent> terminalEventSink) {
		this.statsStore = statsStore;
		this.wallClockCeiling = wallClockCeiling;
		this.terminalEventSink = terminalEventSink;
	}

	/**
	 * Returns the silence a job may accumulate before it counts as stuck.
	 *
	 * @return the threshold, or {@link Duration#ZERO} when scanning is disabled
	 *         because the wall-clock ceiling is itself disabled
	 */
	public Duration stalenessThreshold() {
		if (wallClockCeiling == null || wallClockCeiling.isNegative() || wallClockCeiling.isZero()) {
			return Duration.ZERO;
		}
		return wallClockCeiling.multipliedBy(stalenessMultiplier);
	}

	/**
	 * Sets the wall-clock ceiling the staleness threshold derives from.
	 *
	 * @param wallClockCeiling the ceiling; non-positive disables scanning
	 */
	public void setWallClockCeiling(Duration wallClockCeiling) {
		this.wallClockCeiling = wallClockCeiling;
	}

	/**
	 * Sets the multiplier applied to the wall-clock ceiling.
	 *
	 * @param stalenessMultiplier the multiplier; must be at least {@code 1}
	 * @throws IllegalArgumentException if {@code stalenessMultiplier < 1}
	 */
	public void setStalenessMultiplier(int stalenessMultiplier) {
		if (stalenessMultiplier < 1) {
			throw new IllegalArgumentException(
					"stalenessMultiplier must be at least 1, got: " + stalenessMultiplier);
		}
		this.stalenessMultiplier = stalenessMultiplier;
	}

	/**
	 * Runs one scan and fails every job that has been silent too long.
	 *
	 * <p>Separated from the timer so the decision to terminate is testable
	 * without waiting on a schedule.</p>
	 *
	 * @param now the reference instant
	 * @return the job IDs failed by this scan, in the order they were failed
	 */
	public synchronized List<String> scanOnce(Instant now) {
		List<String> failed = new ArrayList<>();
		Duration threshold = stalenessThreshold();
		if (statsStore == null || threshold.isZero()) return failed;

		for (JobStatsStore.ActiveJob job : statsStore.getActiveJobs(null)) {
			if (job.jobId() == null || terminated.contains(job.jobId())) continue;
			if (!job.isStale(threshold, now)) continue;

			long silentMinutes = job.sinceHeartbeat(now).toMinutes();
			String reason = "Job stalled: no heartbeat for " + silentMinutes + " minutes";
			warn(reason + " (jobId=" + job.jobId()
					+ " workstreamId=" + job.workstreamId() + "); failing it so anything"
					+ " waiting on its completion is released");

			terminated.add(job.jobId());
			failed.add(job.jobId());
			if (terminalEventSink != null) {
				terminalEventSink.accept(job.workstreamId(),
						JobCompletionEvent.failed(job.jobId(), job.description(), reason, null));
			}
		}
		return failed;
	}

	/**
	 * Starts the periodic scan. Does nothing when already started or when no
	 * store was supplied.
	 *
	 * <p>The scheduled task swallows any {@link RuntimeException} a scan
	 * throws. {@link ScheduledExecutorService#scheduleAtFixedRate} stops
	 * repeating after an uncaught exception and reports nothing further, so
	 * letting one propagate would silently disable stall recovery for the rest
	 * of the controller's life — the failure mode this class exists to
	 * prevent, reintroduced one level up.</p>
	 */
	public synchronized void start() {
		if (executor != null || statsStore == null) return;
		executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "stuck-job-scanner");
			t.setDaemon(true);
			return t;
		});
		long seconds = DEFAULT_SCAN_INTERVAL.getSeconds();
		executor.scheduleAtFixedRate(() -> {
			try {
				scanOnce(Instant.now());
			} catch (RuntimeException e) {
				warn("Stuck-job scan failed: " + e.getMessage());
			}
		}, seconds, seconds, TimeUnit.SECONDS);
		log("Stuck-job scanner started: scanning every " + seconds
				+ "s, failing jobs silent for more than " + stalenessThreshold().toHours() + "h");
	}

	/** Stops the periodic scan. Safe to call when never started. */
	public synchronized void stop() {
		if (executor == null) return;
		executor.shutdownNow();
		executor = null;
	}
}
