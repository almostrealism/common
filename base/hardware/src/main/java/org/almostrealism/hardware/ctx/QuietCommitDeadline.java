/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.ctx;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The deadline by which a {@link SubmissionStream}'s open batch is committed once it has gone
 * quiet: {@link SubmissionStream#quietCommitMicros} after the last submission was added to it
 * (see {@link SubmissionStream#closeWhenQuiet()}).
 *
 * <p>A batch is otherwise committed only when it is full, when the device is idle as a submission
 * is added, or when a host wait needs it, so submissions nobody waits for afterwards could stay in
 * the open batch, never run, and keep the memory their arguments reserve for as long as the stream
 * lives. The deadline is measured from the last submission, so a batch that is still being filled
 * back to back is never committed by it.</p>
 *
 * <p>Only one expiry is pending at a time. Each submission records when it was added and schedules
 * an expiry only if none is pending, so adding a submission costs a clock read, not a timer. When
 * the expiry fires, the stream checks on its own thread how long the open batch has been quiet
 * (see {@link #expire()}) and either commits it or {@link #schedule(long) schedules} the expiry
 * again for the time that remains.</p>
 *
 * <p>The expiry fires on a timer thread shared by every stream, which only runs the expiry it was
 * given, and that must do nothing but queue the check on the stream's own thread, so the state of
 * the open batch stays confined to that thread. Everything else here is confined to the stream's
 * thread as well. A pending expiry holds the stream's check, and so the stream, until it fires, so
 * the stream {@link #cancel() cancels} it when it is destroyed, and the timer drops a cancelled
 * expiry at once rather than when it would have fired.</p>
 */
final class QuietCommitDeadline {
	/** The timer thread every stream's deadlines fire on, which drops a cancelled expiry at once. */
	private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
		Thread t = new Thread(r, "Submission stream quiet commit");
		t.setDaemon(true);
		return t;
	});

	static {
		timer.setRemoveOnCancelPolicy(true);
	}

	/** The shortest time {@link #postpone()} waits before checking a quiet batch again. */
	private static final long MIN_POSTPONE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

	/** Run on the timer thread when an expiry fires; queues the check on the stream's thread. */
	private final Runnable onExpiry;

	/** When the last submission was added, from {@link System#nanoTime()}. Stream-thread only. */
	private long lastSubmission;

	/** Whether an expiry is scheduled and has not been checked yet. Stream-thread only. */
	private boolean pending;

	/** The expiry scheduled most recently, or {@code null} before the first. Stream-thread only. */
	private ScheduledFuture<?> expiry;

	/**
	 * Creates the deadline of one stream's open batch.
	 *
	 * @param onExpiry run on the timer thread each time an expiry fires; must only queue the check
	 *                 of the open batch on the stream's own thread, and do nothing once the stream
	 *                 can no longer run it
	 */
	QuietCommitDeadline(Runnable onExpiry) {
		this.onExpiry = onExpiry;
	}

	/**
	 * Records that a submission was just added to the open batch, and schedules an expiry for the
	 * quiet delay unless one is already pending. Stream-thread only.
	 */
	void submitted() {
		lastSubmission = System.nanoTime();
		if (!pending) schedule(delayNanos());
	}

	/**
	 * Records that the pending expiry has fired and returns how long the open batch must still
	 * stay quiet before it is committed: the quiet delay less the time since the last submission.
	 * Stream-thread only.
	 *
	 * @return the remaining quiet time in nanoseconds, zero or less once the deadline has passed
	 */
	long expire() {
		pending = false;
		return delayNanos() - (System.nanoTime() - lastSubmission);
	}

	/**
	 * Schedules an expiry after the given time. Stream-thread only.
	 *
	 * @param nanos how long from now the expiry fires, in nanoseconds
	 */
	void schedule(long nanos) {
		pending = true;
		expiry = timer.schedule(onExpiry, Math.max(nanos, 0), TimeUnit.NANOSECONDS);
	}

	/**
	 * Cancels the pending expiry, if any, so the timer no longer holds the stream's check. Called
	 * as the stream is destroyed, after which nothing schedules an expiry again. Stream-thread only.
	 */
	void cancel() {
		if (expiry != null) expiry.cancel(false);
		expiry = null;
		pending = false;
	}

	/**
	 * Schedules another expiry after the quiet delay, for an open batch that has gone quiet but
	 * cannot be committed yet, such as one waiting for a host-signaled dependency. The delay is at
	 * least {@link #MIN_POSTPONE_NANOS}, so a zero quiet delay does not turn the wait for the
	 * dependency into a busy loop between the timer and the stream. Stream-thread only.
	 */
	void postpone() {
		schedule(Math.max(delayNanos(), MIN_POSTPONE_NANOS));
	}

	/**
	 * Returns the quiet delay, {@link SubmissionStream#quietCommitMicros}, in nanoseconds.
	 *
	 * @return the quiet delay in nanoseconds, never negative
	 */
	private long delayNanos() {
		return TimeUnit.MICROSECONDS.toNanos(Math.max(SubmissionStream.quietCommitMicros, 0));
	}
}
