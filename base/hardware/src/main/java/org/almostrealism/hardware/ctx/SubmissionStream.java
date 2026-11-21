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

import io.almostrealism.concurrent.HandOffWindow;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.io.SystemUtils;

import java.util.concurrent.TimeUnit;
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
 *       dependency that the stream's own order already satisfies, and {@link #own(Semaphore)}
 *       types such a dependency as one of the stream's completions, which
 *       {@link StreamCompletion#later(Semaphore)} merges into the later of the two.</li>
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
 *   <li><b>The idle-commit policy</b> &mdash; when a stream that batches submissions into a unit
 *       the device does not see until it is committed (a command buffer) closes that unit early
 *       because the device has run out of work ({@link #closeEarlyIfIdle}, {@link #minIdleCommit}),
 *       and how many units were closed for that reason ({@link #getIdleCommitCount()}).</li>
 *   <li><b>The quiet-commit policy</b> &mdash; when such a stream commits an open unit that no
 *       submission has been added to for a while, so that work nobody waits for still runs and
 *       releases the memory it holds ({@link #closeWhenQuiet()}, {@link #quietCommitMicros}), and
 *       how many units were committed for that reason ({@link #getQuietCommitCount()}).</li>
 *   <li><b>The completion-polling policy</b> &mdash; how long a host wait for a submission polls
 *       the device's own record of its progress before it blocks on the completion notification
 *       ({@link #pollForCompletion}, {@link #completionSpinMicros}).</li>
 * </ul>
 *
 * <p>Foreign dependencies are bridged by the runner through {@link Semaphore#onSettled(Runnable)}
 * rather than by parking a thread in {@link Semaphore#waitFor()}: a semaphore that is told when
 * its work settles registers the callback and holds no thread while the work runs.</p>
 *
 * @param <C> the type of completion the stream's submissions return
 */
public abstract class SubmissionStream<C extends StreamCompletion> {
	/**
	 * Fewest submissions an open batch must hold before it is closed early because the device has
	 * finished everything committed before it (see {@link #closeEarlyIfIdle}). A batch runs only
	 * once it is committed, so while the device is idle every submission held in the open batch is
	 * work it could already be doing: committing then lets the device run that work while the host
	 * prepares what follows, instead of starting only once the batch is full or the host waits.
	 * Each commit also has a fixed cost, so a smaller batch is left open: on Metal, for a forward
	 * pass of several hundred small kernels, closing every buffer at 32 dispatches cost more than it
	 * recovered, while closing at 64 recovered close to a tenth of the pass. While the device is
	 * busy nothing changes. Zero disables early commits. Read on every submission. Controlled by
	 * {@code AR_HARDWARE_IDLE_COMMIT}.
	 *
	 * <p>Only a stream that holds submissions back in a batch has anything to close early. A
	 * stream that launches every submission as it is admitted (such as the CUDA runner) never
	 * leaves the device idle while it holds work it could run, so it has no use for this policy.</p>
	 */
	public static int minIdleCommit = SystemUtils.getInt("AR_HARDWARE_IDLE_COMMIT").orElse(64);

	/**
	 * How long, in microseconds, an open batch holding submissions may go without a new submission
	 * before it is committed, even though nothing waits for it (see {@link #closeWhenQuiet()}).
	 * A batch runs only once it is committed, and is otherwise committed only when it is full, when
	 * the device is idle as a submission is added, or when a host wait needs it, so without this,
	 * submissions nobody waits for could stay in the open batch, never run, and keep the memory
	 * their arguments reserve. The delay is measured from the last submission, so it never commits
	 * a batch that is still being filled back to back. A batch that could not run if it were
	 * committed now (see {@link #canCommitOpenBatch()}) is checked again after another delay
	 * instead. Read each time a deadline is scheduled; a value of zero or less commits as soon as
	 * the stream's thread is free. Controlled by {@code AR_HARDWARE_QUIET_COMMIT}.
	 *
	 * <p>Only a stream that holds submissions back in a batch has anything to commit. A stream
	 * that launches every submission as it is admitted (such as the CUDA runner) never calls
	 * {@link #closeWhenQuiet()}, so this has no effect on it.</p>
	 */
	public static int quietCommitMicros = SystemUtils.getInt("AR_HARDWARE_QUIET_COMMIT").orElse(10000);

	/**
	 * How long, in microseconds, a host wait for a submission polls the device's own record of its
	 * progress before it blocks on the completion notification instead (see
	 * {@link #pollForCompletion}). Every accelerator API reports finished work in two ways: a
	 * value the host can read without waiting (a Metal shared event's signaled value, a CUDA
	 * event's {@code cuEventQuery}, an OpenCL event's {@code CL_EVENT_COMMAND_EXECUTION_STATUS}),
	 * and a blocking wait that parks the host thread until the driver wakes it (Metal's completion
	 * report, {@code cuEventSynchronize}, {@code clWaitForEvents}). The wake-up costs tens of
	 * microseconds on top of the work, which for a wait issued at once, as every evaluation whose
	 * result the host reads is, is a large share of the whole round trip. A wait whose work takes
	 * longer than the window blocks as it would without polling, having occupied its core for the
	 * window. Zero disables polling. Read when a stream is created. Controlled by
	 * {@code AR_HARDWARE_COMPLETION_SPIN}.
	 *
	 * <p>Only a stream that can read its device's progress without waiting polls (see
	 * {@link #canPollCompletion()}); whatever the window, any other stream blocks at once.</p>
	 */
	public static int completionSpinMicros = SystemUtils.getInt("AR_HARDWARE_COMPLETION_SPIN").orElse(1000);

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

	/** The number of batches closed early by {@link #closeEarlyIfIdle}. */
	private final AtomicLong idleCommits = new AtomicLong();

	/** The number of batches committed by {@link #commitIfQuiet()} once they went quiet. */
	private final AtomicLong quietCommits = new AtomicLong();

	/**
	 * The deadline by which the open batch is committed once it goes quiet. Its expiry hands the
	 * check to the stream's own thread ({@link #runOnStreamThread}). Stream-thread only.
	 */
	private final QuietCommitDeadline quiet = new QuietCommitDeadline(() -> runOnStreamThread(this::commitIfQuiet));

	/**
	 * How long a host wait polls for its submission to finish (see {@link #completionSpinMicros}),
	 * read when the stream is created.
	 */
	private final HandOffWindow completionWindow;

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
		this.completionWindow = completionSpinMicros > 0
				? new HandOffWindow(completionSpinMicros, TimeUnit.MICROSECONDS) : HandOffWindow.NONE;
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
	 * Returns whether the device has finished everything committed before the open batch, and
	 * the open batch could be committed to it at once. Consulted by {@link #closeEarlyIfIdle(int)}
	 * only once the open batch has reached {@link #minIdleCommit}, on whatever thread owns the
	 * open batch.
	 *
	 * <p>A stream that launches every submission as it is admitted holds no batch back from its
	 * device, so it is never idle with work it could hand over; that is what this returns unless a
	 * batching stream overrides it.</p>
	 *
	 * @return true if the device is idle and the open batch could be committed to it immediately
	 */
	protected boolean canCommitToIdleDevice() { return false; }

	/**
	 * Closes the open batch, if any, so that the device can run it. Called by
	 * {@link #closeEarlyIfIdle(int)} on whatever thread owns the open batch. A stream that launches
	 * every submission as it is admitted has no open batch, so unless a batching stream overrides
	 * this it closes nothing.
	 *
	 * @return true if a batch was closed, false if no batch was open
	 */
	protected boolean closeOpenBatch() { return false; }

	/**
	 * Closes the open batch early if it holds at least {@link #minIdleCommit} submissions and the
	 * device has run out of work, so the device starts on the batch while the host prepares what
	 * follows. The stream supplies what is native to it by overriding
	 * {@link #canCommitToIdleDevice()} (whether its device is idle) and {@link #closeOpenBatch()}
	 * (how a batch is closed). Every batch closed here is counted by {@link #getIdleCommitCount()}.
	 * Must be called on whatever thread owns the open batch.
	 *
	 * @param pending the number of submissions in the open batch
	 * @return true if the open batch was closed
	 */
	protected boolean closeEarlyIfIdle(int pending) {
		int threshold = minIdleCommit;
		if (threshold <= 0 || pending < threshold || !canCommitToIdleDevice() || !closeOpenBatch()) {
			return false;
		}

		idleCommits.incrementAndGet();
		return true;
	}

	/**
	 * Releases what the stream's work still holds once the device has finished it, without waiting
	 * for the device: memory reservations, references to the work's arguments, and anything else
	 * the stream keeps until it observes that work complete. A memory provider that has reached its
	 * ceiling calls this (see
	 * {@link org.almostrealism.hardware.mem.HardwareMemoryProvider#releaseFinishedWork()}) so that
	 * memory held only by finished work can be freed even though no thread waits for that work.
	 *
	 * <p>A stream that releases each submission's resources as soon as it completes, which is what
	 * this assumes unless a stream overrides it, holds nothing for finished work, so there is
	 * nothing to release. A stream that releases them only when a host wait observes the
	 * completion (a batching stream that drains its command buffers on a wait) overrides this, and
	 * also hands the device any batch it is holding back, so that work no thread will wait for
	 * runs and can then be released by a later call.</p>
	 */
	public void releaseFinishedWork() { }

	/**
	 * Returns the number of batches closed early because the device had run out of work (see
	 * {@link #closeEarlyIfIdle}).
	 *
	 * @return the number of idle commits
	 */
	public long getIdleCommitCount() { return idleCommits.get(); }

	/**
	 * Queues {@code task} on the stream's own thread, the one that owns the open batch, without
	 * waiting for it, unless the stream can no longer run it. May be called from any thread; the
	 * quiet-commit deadline calls it from its timer thread, which must never touch the open batch
	 * itself.
	 *
	 * <p>Only a stream that holds submissions back in a batch arms the deadline (see
	 * {@link #closeWhenQuiet()}), so only such a stream is asked to run anything here; a stream
	 * that launches every submission as it is admitted never is, which is why the task is dropped
	 * unless a batching stream overrides this.</p>
	 *
	 * @param task the work to run on the stream's own thread
	 * @return true if the task was queued, false if it was dropped
	 */
	protected boolean runOnStreamThread(Runnable task) { return false; }

	/**
	 * Returns the number of submissions in the open batch, or zero when no batch is open.
	 * Consulted by the quiet-commit check on the stream's own thread. A stream that launches every
	 * submission as it is admitted holds no open batch, which is what this returns unless a
	 * batching stream overrides it.
	 *
	 * @return the number of submissions held in the open batch
	 */
	protected int getOpenBatchSize() { return 0; }

	/**
	 * Returns whether the open batch could run on the device if it were committed now. A batch that
	 * waits for a host-signaled dependency, or that would be held behind an earlier batch that does,
	 * could not, so the quiet-commit check postpones it instead of committing it. Consulted on the
	 * stream's own thread, only while {@link #getOpenBatchSize()} is positive. A stream whose
	 * batches never wait for the host can always commit, which is what this returns unless a
	 * stream overrides it.
	 *
	 * @return true if committing the open batch now would let the device run it
	 */
	protected boolean canCommitOpenBatch() { return true; }

	/**
	 * Records that a submission was just added to the open batch and left there, so that the batch
	 * is committed once {@link #quietCommitMicros} pass without another (see
	 * {@link #commitIfQuiet()}). A stream that batches submissions calls this for every submission
	 * the open batch keeps, typically when {@link #closeEarlyIfIdle} did not close it. Only one
	 * expiry is pending at a time, so this costs a clock read. Must be called on the stream's own
	 * thread.
	 */
	protected void closeWhenQuiet() {
		quiet.submitted();
	}

	/**
	 * Cancels the pending quiet-commit expiry, if any, so the timer no longer holds this stream.
	 * Called on the stream's own thread as the stream is destroyed, after which it adds no
	 * submission to a batch again.
	 */
	protected void cancelQuietCommit() {
		quiet.cancel();
	}

	/**
	 * Commits the open batch if no submission has been added to it for {@link #quietCommitMicros},
	 * which the quiet-commit deadline hands to the stream's own thread when it expires. A batch
	 * that is still being filled is checked again once the rest of its quiet time has passed. A
	 * batch that could not run if it were committed now (see {@link #canCommitOpenBatch()}) is
	 * checked again after another delay. Every batch closed here is counted by
	 * {@link #getQuietCommitCount()}. Runs on the stream's own thread.
	 */
	private void commitIfQuiet() {
		long remaining = quiet.expire();
		if (getOpenBatchSize() == 0) return;

		if (remaining > 0) {
			quiet.schedule(remaining);
		} else if (!canCommitOpenBatch()) {
			quiet.postpone();
		} else if (closeOpenBatch()) {
			quietCommits.incrementAndGet();
		}
	}

	/**
	 * Returns the number of batches committed because they went quiet (see
	 * {@link #closeWhenQuiet()}).
	 *
	 * @return the number of quiet commits
	 */
	public long getQuietCommitCount() { return quietCommits.get(); }

	/**
	 * Returns whether the stream can read its device's progress without waiting, which
	 * {@link #pollForCompletion} needs. A stream that can only block on completion, which is what
	 * this returns unless a stream overrides it, never polls.
	 *
	 * @return true if the stream can tell, without waiting, whether a submission has finished
	 */
	protected boolean canPollCompletion() { return false; }

	/**
	 * Returns, without waiting, whether the device's own record of its progress has reached the
	 * submission with the given ordinal (see {@link #nextOrdinal()}): a Metal shared event's
	 * signaled value, a CUDA event's {@code cuEventQuery}, and so on. Consulted by
	 * {@link #pollForCompletion} only when {@link #canPollCompletion()} is true, so a stream that
	 * can read its device's progress overrides both; any other stream never reaches this, which
	 * is why it reports false unless overridden. What reaching the ordinal proves (that the
	 * submission executed rather than failed, for instance) is for the stream to decide, since it
	 * knows what its device reports.
	 *
	 * @param ordinal the ordinal of the submission a host wait waits for
	 * @return true if the device's progress has reached {@code ordinal}
	 */
	protected boolean hasReached(long ordinal) { return false; }

	/**
	 * Returns whether a host wait for one of this stream's submissions polls before it blocks:
	 * whether the stream can read its device's progress (see {@link #canPollCompletion()}) and the
	 * polling window it was created with (see {@link #completionSpinMicros}) is not zero.
	 *
	 * @return true if {@link #pollForCompletion} polls
	 */
	public boolean isPollingForCompletion() {
		return completionWindow != HandOffWindow.NONE && canPollCompletion();
	}

	/**
	 * Polls the device's progress ({@link #hasReached(long)}) for the submission with the given
	 * ordinal, for up to {@link #completionSpinMicros}. A stream calls this before it blocks on the
	 * completion notification, and blocks only if it returns false. Polling only decides how soon
	 * the wait notices that the work has finished; deciding whether what the device's progress
	 * shows can be trusted (that it cannot be mistaken for a failure, for instance) stays with the
	 * stream, which knows what its device reports. An interrupt ends polling at once and is left
	 * pending for the blocking wait that follows.
	 *
	 * @param ordinal the ordinal of the submission a host wait waits for
	 * @return true if the device's progress reached {@code ordinal} before polling stopped; always
	 *         false when the stream does not poll (see {@link #isPollingForCompletion()})
	 */
	protected boolean pollForCompletion(long ordinal) {
		return isPollingForCompletion() && completionWindow.pollUntil(() -> hasReached(ordinal));
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
