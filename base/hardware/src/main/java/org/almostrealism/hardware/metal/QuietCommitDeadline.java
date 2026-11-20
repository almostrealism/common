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

package org.almostrealism.hardware.metal;

import io.almostrealism.concurrent.ConfinedExecutor;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The deadline by which a {@link MetalCommandRunner}'s open batch is committed once it has gone
 * quiet: {@link MetalCommandRunner#quietCommitMicros} after the last dispatch was encoded into it.
 *
 * <p>A batch is otherwise committed only when it is full, when the GPU is idle as a dispatch is
 * encoded, or when a host wait needs it, so dispatches nobody waits for afterwards could stay in the
 * open buffer, never run, and keep the memory their arguments reserve for as long as the runner
 * lives. The deadline is measured from the last dispatch, so a batch that is still being filled
 * back to back is never committed by it.</p>
 *
 * <p>Only one expiry is pending at a time. Each dispatch records when it was encoded and schedules
 * an expiry only if none is pending, so encoding a dispatch costs a clock read, not a timer. When
 * the expiry fires, the runner checks on its executor thread how long the open batch has been quiet
 * (see {@link #expire()}) and either commits it or {@link #schedule(long) schedules} the
 * expiry again for the time that remains.</p>
 *
 * <p>The expiry fires on a timer thread shared by every runner, which does nothing but queue the
 * check on the runner's executor ({@link ConfinedExecutor#runLater}), so all command-buffer state stays
 * confined to that executor. Everything else here is confined to the runner's executor thread
 * as well. A pending expiry holds the runner's check, and so the runner, until it fires, so the
 * runner {@link #cancel() cancels} it when it is destroyed, and the timer drops a cancelled expiry
 * at once rather than when it would have fired.</p>
 */
final class QuietCommitDeadline {
	/** The timer thread every runner's deadlines fire on, which drops a cancelled expiry at once. */
	private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
		Thread t = new Thread(r, "Metal quiet commit");
		t.setDaemon(true);
		return t;
	});

	static {
		timer.setRemoveOnCancelPolicy(true);
	}

	/** The shortest time {@link #postpone()} waits before checking a quiet batch again. */
	private static final long MIN_POSTPONE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

	/** The runner's executor, which every check of the open batch runs on. */
	private final ConfinedExecutor executor;

	/** The check of the open batch, run on {@link #executor} each time an expiry fires. */
	private final Runnable check;

	/** When the last dispatch was encoded, from {@link System#nanoTime()}. Executor-thread only. */
	private long lastDispatch;

	/** Whether an expiry is scheduled and has not been checked yet. Executor-thread only. */
	private boolean pending;

	/** The expiry scheduled most recently, or {@code null} before the first. Executor-thread only. */
	private ScheduledFuture<?> expiry;

	/**
	 * Creates the deadline of one runner's open batch.
	 *
	 * @param executor the runner's executor, which the check runs on; once it has been destroyed
	 *                 an expiry does nothing
	 * @param check    the check of the open batch, run on {@code executor} when an expiry fires
	 */
	QuietCommitDeadline(ConfinedExecutor executor, Runnable check) {
		this.executor = executor;
		this.check = check;
	}

	/**
	 * Records that a dispatch was just encoded into the open batch, and schedules an expiry for
	 * the quiet delay unless one is already pending. Executor-thread only.
	 */
	void dispatched() {
		lastDispatch = System.nanoTime();
		if (!pending) schedule(delayNanos());
	}

	/**
	 * Records that the pending expiry has fired and returns how long the open batch must still
	 * stay quiet before it is committed: the quiet delay less the time since the last dispatch.
	 * Executor-thread only.
	 *
	 * @return the remaining quiet time in nanoseconds, zero or less once the deadline has passed
	 */
	long expire() {
		pending = false;
		return delayNanos() - (System.nanoTime() - lastDispatch);
	}

	/**
	 * Schedules an expiry after the given time. Executor-thread only.
	 *
	 * @param nanos how long from now the expiry fires, in nanoseconds
	 */
	void schedule(long nanos) {
		pending = true;
		expiry = timer.schedule(() -> executor.runLater(check), Math.max(nanos, 0), TimeUnit.NANOSECONDS);
	}

	/**
	 * Cancels the pending expiry, if any, so the timer no longer holds the runner's check. Called
	 * by the runner's final task as it is destroyed, after which nothing schedules an expiry again.
	 * Executor-thread only.
	 */
	void cancel() {
		if (expiry != null) expiry.cancel(false);
		expiry = null;
		pending = false;
	}

	/**
	 * Schedules another expiry after the quiet delay, for an open batch that has gone quiet but
	 * cannot be committed yet, such as one waiting for a host-signaled bridge. The delay is at least
	 * {@link #MIN_POSTPONE_NANOS}, so a zero quiet delay does not turn the wait for the bridge into
	 * a busy loop between the timer and the runner. Executor-thread only.
	 */
	void postpone() {
		schedule(Math.max(delayNanos(), MIN_POSTPONE_NANOS));
	}

	/**
	 * Returns the quiet delay, {@link MetalCommandRunner#quietCommitMicros}, in nanoseconds.
	 *
	 * @return the quiet delay in nanoseconds, never negative
	 */
	private long delayNanos() {
		return TimeUnit.MICROSECONDS.toNanos(Math.max(MetalCommandRunner.quietCommitMicros, 0));
	}
}
