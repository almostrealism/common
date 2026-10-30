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

import org.almostrealism.hardware.HardwareException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The failure state of one submission stream: the earliest submission whose work did not
 * execute, and the failure that every wait at or after it reports.
 *
 * <p>A stream executes its submissions in order, and a submission ordered after another on the
 * same stream may read what that one wrote. When submission {@code K} fails, every submission at
 * an ordinal of {@code K} or later may therefore have run on inputs that were never produced, and
 * the record reports the failure for all of them: {@link #failureAt(long)} returns it for any
 * ordinal {@code >= K}. This is the one failure model of every backend built on
 * {@link SubmissionStream} &mdash; the timeline value of a Metal dispatch and the sequence number
 * of a CUDA submission are both such ordinals.</p>
 *
 * <p>The rule is deliberately coarse. Once a dependency has been folded into the stream's own
 * order, the stream can no longer tell a true dependent from independent work that merely follows
 * the failure, so both are reported as failed. A waiter that only receives a false failure for
 * work that did run is recoverable; one that receives success for work that read garbage is
 * not.</p>
 *
 * <h2>Bound and reset policy</h2>
 *
 * <p>The record is <em>sticky and never cleared</em>. Clearing it once some later submission
 * completes cleanly would only be safe if a device that killed one submission were known to run
 * every later one correctly, which is not established for any backend; a stream that has
 * recorded a failure is therefore poisoned for the rest of its life, and recovering from the
 * failure means destroying the context that owns it and creating another. The record holds a
 * single failure (the one with the lowest ordinal), so it is bounded in size regardless of how
 * many submissions fail after it.</p>
 *
 * <h2>Recording</h2>
 *
 * <p>{@link #record(long, Throwable)} keeps the failure with the lowest ordinal, so concurrent
 * recorders (a device-status check on one thread, a foreign dependency's settlement on another)
 * converge on the earliest failure whatever order they record in. Recording is also the test seam
 * for every backend: a test injects a failure at a chosen ordinal and observes that every later
 * wait on the stream reports it, without needing a device to actually fail.</p>
 */
public final class FailureRecord {
	/** The failure with the lowest ordinal recorded so far, or {@code null} while none has. */
	private final AtomicReference<Recorded> failure = new AtomicReference<>();

	/**
	 * Records that the submission at {@code ordinal} did not execute, unless a failure is already
	 * recorded at or before it.
	 *
	 * @param ordinal the ordinal of the first submission whose work did not execute
	 * @param cause   why it did not execute
	 */
	public void record(long ordinal, Throwable cause) {
		Recorded next = new Recorded(ordinal, cause);
		failure.accumulateAndGet(next, (current, candidate) -> candidate.precedes(current) ? candidate : current);
	}

	/**
	 * Returns whether any failure has been recorded.
	 *
	 * @return true once a failure has been recorded
	 */
	public boolean isFailed() { return failure.get() != null; }

	/**
	 * Returns the ordinal of the recorded failure, or {@link Long#MAX_VALUE} while nothing has
	 * failed, so that {@code ordinal >= getFailedOrdinal()} tests coverage either way.
	 *
	 * @return the lowest ordinal recorded as failed
	 */
	public long getFailedOrdinal() {
		Recorded recorded = failure.get();
		return recorded == null ? Long.MAX_VALUE : recorded.ordinal;
	}

	/**
	 * Returns the failure a wait for the submission at {@code ordinal} must report, or
	 * {@code null} if no failure is recorded at or before it. Each call returns a new exception
	 * carrying the recorded cause, so concurrent waiters never share (and mutate) one throwable.
	 *
	 * @param ordinal the ordinal of the submission being waited for
	 * @return the failure to report, or {@code null}
	 */
	public HardwareException failureAt(long ordinal) {
		Recorded recorded = failure.get();
		return recorded != null && ordinal >= recorded.ordinal ? recorded.report() : null;
	}

	/**
	 * Throws the failure a wait for the submission at {@code ordinal} must report, if any.
	 *
	 * @param ordinal the ordinal of the submission being waited for
	 * @throws HardwareException if a failure is recorded at or before {@code ordinal}
	 */
	public void require(long ordinal) {
		HardwareException e = failureAt(ordinal);
		if (e != null) throw e;
	}

	/**
	 * One recorded failure: the ordinal of the first submission that did not execute, and why.
	 */
	private static final class Recorded {
		/** The ordinal of the first submission that did not execute. */
		private final long ordinal;
		/** Why it did not execute. */
		private final Throwable cause;

		/**
		 * @param ordinal the ordinal of the first submission that did not execute
		 * @param cause   why it did not execute
		 */
		private Recorded(long ordinal, Throwable cause) {
			this.ordinal = ordinal;
			this.cause = cause;
		}

		/**
		 * Returns whether this record is for an earlier ordinal than {@code other}, and so takes
		 * its place.
		 *
		 * @param other the currently recorded failure, or {@code null}
		 * @return true if this record should replace {@code other}
		 */
		private boolean precedes(Recorded other) { return other == null || ordinal < other.ordinal; }

		/**
		 * Returns a new exception for one waiter, carrying the recorded cause.
		 *
		 * @return the exception to throw to a waiter
		 */
		private HardwareException report() {
			return new HardwareException(cause.getMessage(), cause);
		}
	}
}
