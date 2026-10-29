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

import io.almostrealism.streams.Semaphore;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The backend-independent coordination of one ordered submission stream: the part of a
 * {@code ComputeContext}'s runner that is the same whether the stream is a Metal command queue,
 * a CUDA stream or anything else that executes submitted work in order.
 *
 * <p>A runner <em>is</em> a submission stream: it extends this class, supplies only what is native
 * to its backend (encoding or launching the work, ordering a same-stream dependency on the device,
 * observing completion), and implements {@link #isStreamThread()} to identify the thread that
 * services its submissions. The stream owns:</p>
 *
 * <ul>
 *   <li><b>The submission ordinal</b> &mdash; a monotonic position for every submission
 *       ({@link #nextOrdinal()}), which is what completions are ordered, merged and failed by.</li>
 *   <li><b>Same-stream ordering</b> &mdash; {@link #ordersAfter(Semaphore)} recognises a
 *       dependency that the stream's own order already satisfies, and
 *       {@link #later(StreamCompletion, Semaphore)} merges two of the stream's completions into
 *       the later of them.</li>
 *   <li><b>The failure record</b> &mdash; one {@link FailureRecord} per stream, so a failure at
 *       ordinal {@code K} is reported by every wait at or after {@code K}.</li>
 *   <li><b>The off-stream-thread guard</b> &mdash; a wait that would have to be serviced by the
 *       stream's own thread is rejected there instead of deadlocking
 *       ({@link #requireOffStreamThread()}).</li>
 *   <li><b>Admission versus destruction</b> &mdash; submission runs under the stream's admission
 *       lock ({@link #admit(Supplier, Function)}), and the stream is closed under the same lock
 *       ({@link #close()}, {@link #closeIf(BooleanSupplier)}), so no submission can be admitted
 *       between a runner deciding it is idle and refusing further work.</li>
 *   <li><b>The hold bound</b> &mdash; how many units of work (command buffers, submissions) may
 *       wait for a foreign dependency before a further submission that would add one waits, on
 *       its submitting thread, for room ({@link #awaitHoldCapacityUninterruptibly()}).</li>
 * </ul>
 *
 * <p>Foreign dependencies are bridged by the runner through {@link Semaphore#onSettled(Runnable)}
 * rather than by parking a thread in {@link Semaphore#waitFor()}: a semaphore that is told when
 * its work settles registers the callback and holds no thread while the work runs.</p>
 *
 * @param <C> the type of completion the stream's submissions return
 */
public abstract class SubmissionStream<C extends StreamCompletion> {
	/** A human-readable name for the stream, used in refusal messages. */
	private final String name;

	/** The type of completion this stream's submissions return. */
	private final Class<C> completionType;

	/** The message of the exception {@link #requireOffStreamThread()} throws. */
	private final String streamThreadMessage;

	/** The maximum number of units of work that may be held at once. */
	private final int maxHeld;

	/** The ordinal of the last submission. */
	private final AtomicLong ordinal = new AtomicLong();

	/** The stream's failure record. */
	private final FailureRecord failures = new FailureRecord();

	/** Held while a submission is admitted, and while the stream is closed. */
	private final Object admission = new Object();

	/** Whether the stream refuses further submissions. Guarded by {@link #admission}. */
	private boolean closed;

	/**
	 * The number of units of work currently held. Read and updated without a lock, because it is
	 * checked on every submission and released on every commit; only a submitter that has to wait
	 * for room takes {@link #holdLock}.
	 */
	private final AtomicInteger held = new AtomicInteger();

	/**
	 * Monitor a submitter waits on for room in the hold bound, notified by {@link #release()} only
	 * while {@link #waiting} reports such a submitter.
	 */
	private final Object holdLock = new Object();

	/**
	 * The number of submitters waiting on {@link #holdLock}. Incremented before a waiter checks
	 * {@link #held}, and read by {@link #release()} after it decrements {@link #held}, so a release
	 * that finds no waiter is one every later waiter's check observes.
	 */
	private final AtomicInteger waiting = new AtomicInteger();

	/**
	 * Creates a stream.
	 *
	 * @param name                a human-readable name for the stream, used in refusal messages
	 * @param completionType      the type of completion the stream's submissions return
	 * @param maxHeld             the maximum number of units of work that may be held at once
	 * @param streamThreadMessage the message of the exception thrown when a wait is attempted on
	 *                            the stream's own thread
	 */
	protected SubmissionStream(String name, Class<C> completionType, int maxHeld,
							   String streamThreadMessage) {
		if (maxHeld < 1) throw new IllegalArgumentException("maxHeld must be positive");

		this.name = name;
		this.completionType = completionType;
		this.maxHeld = maxHeld;
		this.streamThreadMessage = streamThreadMessage;
	}

	/**
	 * Takes the next submission ordinal. Ordinals start at {@code 1} and increase by one per call.
	 *
	 * @return the new submission's ordinal
	 */
	protected long nextOrdinal() { return ordinal.incrementAndGet(); }

	/**
	 * Returns the ordinal of the last submission, or {@code 0} before the first.
	 *
	 * @return the last ordinal taken
	 */
	public long lastOrdinal() { return ordinal.get(); }

	/**
	 * Returns this stream's failure record.
	 *
	 * @return the failure record every completion of the stream consults
	 */
	public FailureRecord getFailures() { return failures; }

	/**
	 * Returns true if {@code dependsOn} is the completion of an earlier submission to this
	 * stream, which the stream's own order satisfies without any host-side bridging.
	 *
	 * @param dependsOn a completion, or {@code null}
	 * @return true if {@code dependsOn} belongs to this stream
	 */
	public boolean ordersAfter(Semaphore dependsOn) {
		return completionType.isInstance(dependsOn) && completionType.cast(dependsOn).getStream() == this;
	}

	/**
	 * Returns whichever of two completions of this stream was submitted later, or {@code null}
	 * if {@code other} does not belong to this stream. The later completion cannot settle before
	 * the earlier one, so it stands for both. A backend whose completions carry more than their
	 * ordinal (Metal's prior-buffer value) builds on this rather than replacing it.
	 *
	 * @param completion a completion of this stream
	 * @param other      another completion
	 * @return the later of the two, or {@code null} if they cannot merge
	 */
	public C later(C completion, Semaphore other) {
		C theirs = own(other);
		if (theirs == null) return null;

		return theirs.getOrdinal() > completion.getOrdinal() ? theirs : completion;
	}

	/**
	 * Returns {@code completion} as a completion of this stream, or {@code null} if it belongs to
	 * another stream or another provider.
	 *
	 * @param completion a completion, or {@code null}
	 * @return the completion, typed as this stream's, or {@code null}
	 */
	public C own(Semaphore completion) {
		return ordersAfter(completion) ? completionType.cast(completion) : null;
	}

	/**
	 * Returns whether the calling thread is the stream's own thread: the one that encodes,
	 * commits or observes its submissions, and so the one a wait for a submission would need.
	 *
	 * @return true when called from the stream's own thread
	 */
	protected abstract boolean isStreamThread();

	/**
	 * Throws if the calling thread is the stream's own thread, where waiting for a submission
	 * would wait for the thread itself.
	 *
	 * @throws IllegalStateException if called from the stream's own thread
	 */
	public void requireOffStreamThread() {
		if (isStreamThread()) {
			throw new IllegalStateException(streamThreadMessage);
		}
	}

	/**
	 * Runs {@code work} under the admission lock if the stream still accepts submissions, and
	 * otherwise hands {@code refused} the refusal (outside the lock) and returns its result.
	 *
	 * @param work    admits one submission
	 * @param refused handles a refusal: rethrows it, or settles the refused submission first
	 * @param <T>     the result of admission
	 * @return the result of {@code work}, or of {@code refused}
	 */
	protected <T> T admit(Supplier<T> work, Function<IllegalStateException, T> refused) {
		synchronized (admission) {
			if (!closed) return work.get();
		}

		return refused.apply(new IllegalStateException("The " + name + " has been destroyed"));
	}

	/**
	 * Closes the stream, refusing every later submission.
	 *
	 * @return true if this call closed it, false if it was already closed
	 */
	protected boolean close() {
		synchronized (admission) {
			if (closed) return false;
			closed = true;
			return true;
		}
	}

	/**
	 * Closes the stream if {@code idle} reports, under the admission lock, that nothing remains
	 * to be done. No submission can be admitted between that check and the close.
	 *
	 * @param idle checks, and may act on, the runner's idleness while submission is excluded
	 * @return true if the stream is closed
	 */
	protected boolean closeIf(BooleanSupplier idle) {
		synchronized (admission) {
			if (!closed && idle.getAsBoolean()) closed = true;
			return closed;
		}
	}

	/**
	 * Returns whether the stream refuses submissions.
	 *
	 * @return true once the stream has been closed
	 */
	public boolean isClosed() {
		synchronized (admission) {
			return closed;
		}
	}

	/**
	 * Returns the maximum number of units of work that may be held at once.
	 *
	 * @return the hold bound
	 */
	public int getMaxHeld() { return maxHeld; }

	/**
	 * Returns whether another unit of work may be held without exceeding the bound.
	 *
	 * @return true while fewer than {@link #getMaxHeld()} units are held
	 */
	protected boolean hasHoldCapacity() {
		return held.get() < maxHeld;
	}

	/**
	 * Returns the number of units of work currently held.
	 *
	 * @return the held count
	 */
	public int getHeldCount() {
		return held.get();
	}

	/** Counts one unit of work as held. */
	protected void hold() {
		held.incrementAndGet();
	}

	/**
	 * Counts one held unit of work as released, waking any submitter waiting for room. The monitor
	 * is taken only when a submitter is waiting, so a commit with no waiter costs one atomic update.
	 */
	protected void release() {
		int previous = held.getAndUpdate(h -> h > 0 ? h - 1 : h);
		if (previous <= 0) throw new IllegalStateException("No held work to release");

		if (waiting.get() > 0) {
			synchronized (holdLock) {
				holdLock.notifyAll();
			}
		}
	}

	/**
	 * Waits, on the calling thread, until another unit of work may be held, without being ended
	 * by an interrupt; an interrupt received meanwhile is restored afterwards. Must not be called
	 * from the stream's own thread or while holding the admission lock, since releasing held work
	 * needs both.
	 */
	protected void awaitHoldCapacityUninterruptibly() {
		boolean interrupted = false;

		synchronized (holdLock) {
			waiting.incrementAndGet();

			try {
				while (held.get() >= maxHeld) {
					try {
						holdLock.wait();
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
			} finally {
				waiting.decrementAndGet();
			}
		}

		if (interrupted) Thread.currentThread().interrupt();
	}
}
