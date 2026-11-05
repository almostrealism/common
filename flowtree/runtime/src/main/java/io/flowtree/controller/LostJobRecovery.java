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
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.almostrealism.io.ConsoleFeatures;

/**
 * Surfaces jobs the controller accepted but no worker ever started, so such a
 * loss can no longer sit silent for hours.
 *
 * <p>{@link StuckJobScanner} is this scanner's counterpart for jobs that <em>had</em>
 * started and then went silent. This one watches the other end of a job's life:
 * a {@code SUBMITTED} row that never became {@code STARTED}. Once it has waited
 * past {@link #lostThreshold()} — the same point at which the pending-job listing
 * labels it {@link PendingJob#LOST} — it is a job that was accepted and then
 * dropped on the floor, and nothing else in the system will ever say so.</p>
 *
 * <h2>Why it escalates rather than re-dispatches</h2>
 *
 * <p>The tempting fix is to re-run a lost job automatically. That is only safe
 * when the controller can prove the job never left it, because starting a second
 * coding-agent session on the branch of a job a peer is already running is worse
 * than a job that merely runs late. The controller can make that proof at exactly
 * one moment: a restart, when nothing is in flight — and
 * {@code FlowTreeApiEndpoint.recoverPendingJobs()} already re-dispatches every
 * still-{@code SUBMITTED} job then. In steady state (no restart) a lost job is one
 * of two things, and neither is safe to blindly re-run:</p>
 *
 * <ul>
 *   <li>still queued on the controller, waiting for a worker whose labels match —
 *       re-dispatching would duplicate the queued copy; it does not need
 *       re-dispatch, it needs a worker; and</li>
 *   <li>already handed to a peer that accepted it over the wire and then went
 *       away — the controller cannot tell, without a receipt acknowledgement,
 *       whether that peer ran it, so re-dispatching risks the double run.</li>
 * </ul>
 *
 * <p>So in steady state this scanner does not re-dispatch. It escalates each lost
 * job exactly once — a workstream message and an out-of-band alert, both naming
 * the job and which of the two situations it is in — and leaves the decision to a
 * human. The {@link #queuedLocally} probe is what distinguishes the two cases in
 * the message.</p>
 */
public class LostJobRecovery implements ConsoleFeatures {

	/**
	 * How often the scanner looks for lost jobs. Matches
	 * {@link StuckJobScanner#DEFAULT_SCAN_INTERVAL}: frequent enough that a lost
	 * job is surfaced minutes rather than hours after the threshold, rare enough
	 * that the query cost is irrelevant.
	 */
	public static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofMinutes(5);

	/**
	 * Default wait a {@code SUBMITTED} job may accumulate before it is treated as
	 * lost and escalated. Mirrors the pending-view {@code LOST} label threshold
	 * (the operator's {@code T_lost}); a healthy job is picked up in seconds, so
	 * thirty minutes of silence is unambiguously a loss.
	 */
	public static final Duration DEFAULT_LOST_THRESHOLD = Duration.ofMinutes(30);

	/** The store queried for pending jobs; {@code null} disables scanning. */
	private final JobStatsStore statsStore;

	/** The wait a submitted job may accumulate before it counts as lost. */
	private Duration lostThreshold = DEFAULT_LOST_THRESHOLD;

	/**
	 * Receives the workstream notice (workstream id, message) for each lost job.
	 * {@code null} leaves the escalation as a log line only.
	 */
	private final BiConsumer<String, String> noticeSink;

	/**
	 * Receives the out-of-band alert text for each lost job. {@code null} leaves
	 * the escalation to the workstream notice and the log.
	 */
	private final Consumer<String> alertSink;

	/**
	 * Tells whether a job is still queued somewhere on the controller (never
	 * handed to a peer). {@code null} is treated as "unknown", and the message
	 * omits the distinction rather than guessing.
	 */
	private final Predicate<String> queuedLocally;

	/**
	 * Job IDs already escalated, so each lost job raises exactly one notice and
	 * one alert rather than one per scan. Grows with the number of distinct lost
	 * jobs over the controller's life, which is small by construction.
	 */
	private final Set<String> escalated = new HashSet<>();

	/** The timer driving {@link #scanOnce}; {@code null} until started. */
	private ScheduledExecutorService executor;

	/**
	 * Creates a scanner.
	 *
	 * @param statsStore    the store to query; {@code null} disables scanning
	 * @param noticeSink    receives the per-job workstream notice, or {@code null}
	 * @param alertSink     receives the per-job out-of-band alert, or {@code null}
	 * @param queuedLocally tells whether a job is still queued on the controller,
	 *                      or {@code null} when that cannot be determined
	 */
	public LostJobRecovery(JobStatsStore statsStore,
			BiConsumer<String, String> noticeSink,
			Consumer<String> alertSink,
			Predicate<String> queuedLocally) {
		this.statsStore = statsStore;
		this.noticeSink = noticeSink;
		this.alertSink = alertSink;
		this.queuedLocally = queuedLocally;
	}

	/**
	 * Returns the wait a submitted job may accumulate before it counts as lost.
	 *
	 * @return the lost threshold
	 */
	public Duration lostThreshold() {
		return lostThreshold;
	}

	/**
	 * Sets the wait a submitted job may accumulate before it counts as lost. A
	 * {@code null} or non-positive value restores {@link #DEFAULT_LOST_THRESHOLD}.
	 *
	 * @param lostThreshold the operator's {@code T_lost}
	 */
	public void setLostThreshold(Duration lostThreshold) {
		this.lostThreshold = (lostThreshold != null && !lostThreshold.isNegative()
				&& !lostThreshold.isZero()) ? lostThreshold : DEFAULT_LOST_THRESHOLD;
	}

	/**
	 * Runs one scan: escalates every job that has been pending past the lost
	 * threshold and has not already been escalated.
	 *
	 * <p>Separated from the timer so the escalation decision is testable without
	 * waiting on a schedule.</p>
	 *
	 * @param now the reference instant
	 * @return the job IDs escalated by this scan, in the order they were escalated
	 */
	public synchronized List<String> scanOnce(Instant now) {
		List<String> raised = new ArrayList<>();
		if (statsStore == null) return raised;

		for (PendingJob job : statsStore.getPendingJobs(null)) {
			String jobId = job.jobId();
			if (jobId == null || escalated.contains(jobId)) continue;
			if (!job.isLost(lostThreshold, now)) continue;

			// TODO(review): job is marked escalated before describe()/sinks run; if any throws the escalation is lost and never retried
			escalated.add(jobId);
			raised.add(jobId);

			String message = describe(job, now);
			warn(message);
			if (noticeSink != null && job.workstreamId() != null) {
				noticeSink.accept(job.workstreamId(), message);
			}
			if (alertSink != null) {
				alertSink.accept(message);
			}
		}
		return raised;
	}

	/**
	 * Builds the human-readable escalation naming the job and which lost situation
	 * it is in.
	 *
	 * @param job the lost job
	 * @param now the reference instant for its age
	 * @return the escalation message
	 */
	private String describe(PendingJob job, Instant now) {
		StringBuilder message = new StringBuilder("Lost job: accepted by the controller"
				+ " but no worker started it in " + job.age(now).toMinutes()
				+ " minutes (jobId=" + job.jobId() + " workstreamId=" + job.workstreamId());
		if (job.description() != null && !job.description().isEmpty()) {
			message.append(" description=").append(job.description());
		}
		message.append("). ");

		if (queuedLocally == null) {
			message.append("It will not be re-dispatched automatically; a human should decide"
					+ " whether to resubmit it.");
		} else if (queuedLocally.test(job.jobId())) {
			message.append("It is still queued on the controller — no worker with its required"
					+ " labels has connected. It will run when one does; it is not being"
					+ " re-dispatched (that would duplicate the queued copy).");
		} else {
			message.append("It was handed to a peer that never reported starting it, so a peer"
					+ " may still have it. It is not being re-dispatched (a second run on the"
					+ " same branch is worse than a late one); resubmit it manually if the peer"
					+ " is confirmed gone.");
		}
		return message.toString();
	}

	/**
	 * Starts the periodic scan. Does nothing when already started or when no
	 * store was supplied.
	 *
	 * <p>The scheduled task swallows any {@link RuntimeException} a scan throws,
	 * for the same reason {@link StuckJobScanner#start()} does:
	 * {@link ScheduledExecutorService#scheduleAtFixedRate} stops repeating after
	 * an uncaught exception, which would silently disable loss detection for the
	 * rest of the controller's life.</p>
	 */
	public synchronized void start() {
		if (executor != null || statsStore == null) return;
		executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "lost-job-recovery");
			t.setDaemon(true);
			return t;
		});
		long seconds = DEFAULT_SCAN_INTERVAL.getSeconds();
		executor.scheduleAtFixedRate(() -> {
			try {
				scanOnce(Instant.now());
			} catch (RuntimeException e) {
				warn("Lost-job scan failed: " + e.getMessage());
			}
		}, seconds, seconds, TimeUnit.SECONDS);
		log("Lost-job recovery started: scanning every " + seconds
				+ "s, escalating jobs pending for more than " + lostThreshold.toMinutes() + "m");
	}

	/** Stops the periodic scan. Safe to call when never started. */
	public synchronized void stop() {
		if (executor == null) return;
		executor.shutdownNow();
		executor = null;
	}
}
