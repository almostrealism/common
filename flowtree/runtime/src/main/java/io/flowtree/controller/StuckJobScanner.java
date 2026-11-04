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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

	/**
	 * The silence a started job may accumulate before the controller reclaims
	 * it, applied at wiring time in place of the wall-clock-derived default.
	 *
	 * <p>The {@link #DEFAULT_STALENESS_MULTIPLIER} × wall-clock-ceiling
	 * derivation (12h) predates the harness heartbeat. A healthy job now posts a
	 * heartbeat every {@code HarnessHeartbeatSender.DEFAULT_INTERVAL} (3
	 * minutes) on a timer that is independent of agent activity, so a long but
	 * busy session stays visibly alive and only a job whose harness has actually
	 * died goes silent. Three hours of silence is sixty consecutive missed
	 * heartbeats — conclusive death, while still tolerating a worker network
	 * partition far longer than any observed — so the twelve-hour benefit of the
	 * doubt is no longer warranted and merely holds a scarce worker four times
	 * longer than necessary. The warning (see {@link #warningThreshold()}) makes
	 * the stall visible at half this, long before the kill.</p>
	 */
	public static final Duration DEFAULT_DEAD_THRESHOLD = Duration.ofHours(3);

	/**
	 * Default fraction of the staleness threshold at which a job's silence is
	 * first warned about, when no explicit warning point is configured.
	 *
	 * <p>Half, so the warning lands squarely in the middle of the silence
	 * window — late enough that a brief hiccup has had time to recover on its
	 * own and not raise noise, early enough that a genuine stall is visible
	 * long before the kill rather than only at it.</p>
	 */
	public static final int DEFAULT_WARNING_DIVISOR = 2;

	/** The store queried for active jobs and their heartbeats. */
	private final JobStatsStore statsStore;

	/** Receives the synthesized terminal event, keyed by workstream. */
	private final BiConsumer<String, JobCompletionEvent> terminalEventSink;

	/**
	 * Receives an early stall warning (workstream id, message) partway through
	 * the silence window, before the kill. {@code null} until configured, in
	 * which case the warning is only logged.
	 */
	private BiConsumer<String, String> warningSink;

	/**
	 * Explicit silence at which a job is first warned about. When set it
	 * overrides the {@link #DEFAULT_WARNING_DIVISOR} fraction of the staleness
	 * threshold; a non-positive value restores the default. Clamped below the
	 * staleness threshold at read time so the warning can never coincide with
	 * or follow the kill.
	 */
	private Duration warningThreshold;

	/**
	 * Job IDs already warned about; makes the warning fire exactly once per
	 * job as its silence crosses the warning point, rather than on every scan
	 * until the kill.
	 */
	private final Set<String> warned = new HashSet<>();

	/** The wall-clock ceiling the staleness threshold is derived from. */
	private Duration wallClockCeiling;

	/** Multiplier applied to {@link #wallClockCeiling}. */
	private int stalenessMultiplier = DEFAULT_STALENESS_MULTIPLIER;

	/**
	 * Explicit "dead" threshold (the operator's {@code T_dead}): the silence a
	 * started job may accumulate before it is failed. When set it overrides the
	 * {@link #wallClockCeiling} × {@link #stalenessMultiplier} derivation, so an
	 * operator can tune "silent means dead" without a rebuild. {@code null}
	 * leaves the derived default in place. Never below a sensible floor — a
	 * misconfigured near-zero value would fail live jobs, so a non-positive set
	 * is treated as "no override".
	 */
	private Duration deadThreshold;

	/**
	 * Job IDs already failed by this scanner; makes termination idempotent.
	 *
	 * <p>A set rather than a list because the only operations are membership
	 * and insert, and it grows for the lifetime of the controller — every
	 * stalled job ever terminated stays in it. A linear membership check
	 * would make each scan cost more as that history accumulates.</p>
	 */
	private final Set<String> terminated = new HashSet<>();

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
		if (deadThreshold != null && !deadThreshold.isNegative() && !deadThreshold.isZero()) {
			return deadThreshold;
		}
		if (wallClockCeiling == null || wallClockCeiling.isNegative() || wallClockCeiling.isZero()) {
			return Duration.ZERO;
		}
		return wallClockCeiling.multipliedBy(stalenessMultiplier);
	}

	/**
	 * Sets the explicit "dead" threshold — the silence a started job may
	 * accumulate before the scanner fails it — overriding the wall-clock
	 * derivation.
	 *
	 * <p>This is the operator's {@code T_dead}. The DEAD transition is
	 * destructive (it fails the job through the ordinary completion path), so
	 * the scanner stays conservative: a non-positive or {@code null} value is
	 * treated as "no override" and the derived {@link #wallClockCeiling} ×
	 * {@link #stalenessMultiplier} default is restored rather than disabling
	 * the floor and risking a live job being failed.</p>
	 *
	 * @param deadThreshold the silence a job may accumulate before it is failed;
	 *                      {@code null} or non-positive restores the default
	 */
	public void setDeadThreshold(Duration deadThreshold) {
		this.deadThreshold = (deadThreshold != null && !deadThreshold.isNegative()
				&& !deadThreshold.isZero()) ? deadThreshold : null;
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
	 * Returns the silence at which a job is first warned about, before the
	 * kill.
	 *
	 * <p>The explicit {@link #setWarningThreshold(Duration) warning threshold}
	 * when it is set and strictly below the staleness threshold; otherwise the
	 * {@link #DEFAULT_WARNING_DIVISOR} fraction of the staleness threshold. An
	 * explicit value at or above the staleness threshold would let the warning
	 * coincide with or follow the kill, which defeats its purpose, so it is
	 * clamped back to the default.</p>
	 *
	 * @return the warning threshold, or {@link Duration#ZERO} when scanning is
	 *         disabled (the staleness threshold is itself zero)
	 */
	public Duration warningThreshold() {
		Duration staleness = stalenessThreshold();
		if (staleness.isZero()) return Duration.ZERO;
		if (warningThreshold != null && !warningThreshold.isNegative()
				&& !warningThreshold.isZero() && warningThreshold.compareTo(staleness) < 0) {
			return warningThreshold;
		}
		return staleness.dividedBy(DEFAULT_WARNING_DIVISOR);
	}

	/**
	 * Sets the explicit silence at which a job is first warned about, overriding
	 * the {@link #DEFAULT_WARNING_DIVISOR} fraction of the staleness threshold.
	 *
	 * @param warningThreshold the silence a job may accumulate before it is
	 *                         warned about; {@code null} or non-positive
	 *                         restores the default
	 */
	public void setWarningThreshold(Duration warningThreshold) {
		this.warningThreshold = (warningThreshold != null && !warningThreshold.isNegative()
				&& !warningThreshold.isZero()) ? warningThreshold : null;
	}

	/**
	 * Sets the sink that receives an early stall warning — the workstream id and
	 * a human-readable message — partway through the silence window.
	 *
	 * @param warningSink the sink, or {@code null} to only log the warning
	 */
	public void setWarningSink(BiConsumer<String, String> warningSink) {
		this.warningSink = warningSink;
	}

	/**
	 * Runs one scan: warns about a job partway through its silence window and
	 * fails every job that has been silent too long.
	 *
	 * <p>Separated from the timer so the decisions to warn and to terminate are
	 * testable without waiting on a schedule. A job healthy enough to keep
	 * heartbeating never crosses the warning point and so is never warned about
	 * or failed, because both decisions read the silence since its last
	 * heartbeat, not how long it has been running.</p>
	 *
	 * @param now the reference instant
	 * @return the job IDs failed by this scan, in the order they were failed
	 */
	public synchronized List<String> scanOnce(Instant now) {
		List<String> failed = new ArrayList<>();
		Duration threshold = stalenessThreshold();
		if (statsStore == null || threshold.isZero()) return failed;
		Duration warnAt = warningThreshold();

		for (ActiveJob job : statsStore.getActiveJobs(null)) {
			if (job.jobId() == null || terminated.contains(job.jobId())) continue;

			if (job.isStale(threshold, now)) {
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
				continue;
			}

			// Not yet dead: warn once as the silence crosses the (earlier)
			// warning point, so a stall is visible well before the kill.
			if (!warnAt.isZero() && job.isStale(warnAt, now) && warned.add(job.jobId())) {
				long silentMinutes = job.sinceHeartbeat(now).toMinutes();
				String message = "Job silent: no heartbeat for " + silentMinutes
						+ " minutes (jobId=" + job.jobId() + " workstreamId=" + job.workstreamId()
						+ "); it will be failed if it stays silent past " + threshold.toMinutes()
						+ " minutes";
				warn(message);
				if (warningSink != null) {
					warningSink.accept(job.workstreamId(), message);
				}
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
				+ "s, warning about jobs silent for more than " + warningThreshold().toMinutes()
				+ "m and failing those silent for more than " + stalenessThreshold().toMinutes() + "m");
	}

	/** Stops the periodic scan. Safe to call when never started. */
	public synchronized void stop() {
		if (executor == null) return;
		executor.shutdownNow();
		executor = null;
	}
}
