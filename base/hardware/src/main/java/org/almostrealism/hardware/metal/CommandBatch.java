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

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;

import java.util.ArrayList;
import java.util.List;

/**
 * The dispatches a {@link MetalCommandRunner} encodes into one {@link MTLCommandBuffer}, from the
 * moment the runner opens the buffer until the runner has drained it: the runner's open batch (see
 * {@link MetalCommandRunner#closeOpenBatch()}), and afterwards a closed one.
 *
 * <p>A batch is <em>open</em> while the runner encodes dispatches into it, and is
 * {@link #close closed} by a signal of the runner's timeline encoded as its last command. Once
 * closed, it is {@link #commit(Runnable) committed} to the GPU as soon as every host-signaled bridge
 * encoded into it has been signaled ({@link #isReady()}), and once it has finished it is
 * {@link #drain() drained}: its released-memory callbacks run and its command buffer is released,
 * unless a thread waiting for it off the runner's executor thread still needs the buffer (see
 * {@link #addWaiter()}).</p>
 *
 * <p>A batch is drained as soon as it has finished, whether or not any thread waits for it. Its
 * {@link #commit(Runnable) commit} registers a Metal completed handler, which hands a drain of the
 * finished batches to the runner's executor thread. A batch with a registered waiter is left to
 * that waiter, whose wait drains it as it always has, so a released-memory callback's failure
 * still reaches the wait.</p>
 *
 * <p>Encoding into the buffer and committing it, the dispatch and bridge counts and the callbacks
 * are confined to the runner's executor thread, like every other command-buffer operation. Any
 * thread may await the commit and completion, or ask whether the batch has been committed or has
 * finished. The waiter count and drained flag are guarded by this batch's monitor, because a
 * waiter outliving a destroyed executor withdraws on its own thread.</p>
 */
final class CommandBatch {
	/** The command buffer the batch's dispatches are encoded into. */
	private final MTLCommandBuffer buffer;
	/** The runner's timeline, which orders its dispatches and whose values identify them. */
	private final MTLEvent timeline;
	/** The timeline value of the first dispatch encoded into the batch. */
	private final long firstValue;
	/** Released-memory callbacks to run once the batch has finished. Executor-thread only. */
	private final List<Runnable> onComplete = new ArrayList<>();
	/**
	 * Listeners to notify once the batch has finished, as soon as that is seen and before it is
	 * drained (see {@link #whenFinished(Runnable)}). Executor-thread only.
	 */
	private final List<Runnable> onFinishedListeners = new ArrayList<>();
	/**
	 * Records the batch as finished and hands the runner's drain off, as the completed handler
	 * registered when the batch was committed does (see {@link #commit(Runnable)}). Set on commit.
	 */
	private Runnable completionReport;
	/** Whether a completed handler was registered when the batch was committed. Executor-thread only. */
	private boolean handlerRegistered;
	/**
	 * Whether a callback-pool thread has been started to report the batch's completion in place
	 * of a completed handler that could not be registered (see {@link #observeIfUnreported()}).
	 * Executor-thread only.
	 */
	private boolean substituteStarted;
	/** Settles once the batch has been committed to the GPU, which only the executor thread does. */
	private final LatchSemaphore committed = new LatchSemaphore(1);
	/** The number of dispatches encoded into the batch. Executor-thread only. */
	private int dispatches;
	/** The host-signaled bridges encoded into the batch and not yet signaled. Executor-thread only. */
	private int pendingBridges;
	/** Threads currently waiting for the batch outside the executor thread. */
	private int waiters;
	/** Whether the batch has been drained (its callbacks have run). */
	private boolean drained;
	/**
	 * Whether Metal has reported, through the completed handler registered when the batch was
	 * committed, that the batch has finished. Written on a Metal thread.
	 */
	private volatile boolean completionReported;

	/**
	 * Opens a batch in a fresh command buffer.
	 *
	 * @param buffer     the command buffer to encode the batch's dispatches into, retained when
	 *                   it was created (see {@link MTL#commandBuffer(long)}) and released once
	 *                   the batch has been drained
	 * @param timeline   the runner's timeline, which the batch signals when it is closed and on
	 *                   which it waits for earlier dispatches
	 * @param firstValue the timeline value the first dispatch encoded into the batch takes
	 */
	CommandBatch(MTLCommandBuffer buffer, MTLEvent timeline, long firstValue) {
		this.buffer = buffer;
		this.timeline = timeline;
		this.firstValue = firstValue;
	}

	/**
	 * Returns the command buffer the batch's dispatches are encoded into: the identity a
	 * dispatch's completion carries (see {@link MetalSemaphore#getCommandBuffer()}), and what the
	 * runner inspects for a failure (see {@link MetalCommandRunner#errorOf}).
	 *
	 * @return the batch's command buffer
	 */
	MTLCommandBuffer getBuffer() { return buffer; }

	/**
	 * Returns whether the given command buffer is this batch's. A dispatch's completion carries
	 * the command buffer it was encoded into, which identifies its batch.
	 *
	 * @param commandBuffer a command buffer, or {@code null}
	 * @return true if the batch's dispatches are encoded into {@code commandBuffer}
	 */
	boolean isFor(MTLCommandBuffer commandBuffer) { return buffer == commandBuffer; }

	/**
	 * Returns the timeline value of the first dispatch encoded into the batch, the lowest
	 * value that does not execute if the batch fails.
	 *
	 * @return the batch's first timeline value
	 */
	long getFirstValue() { return firstValue; }

	/**
	 * Returns the number of dispatches encoded into the batch.
	 *
	 * @return the batch's dispatch count
	 */
	int getDispatchCount() { return dispatches; }

	/**
	 * Encodes a GPU wait, ahead of the batch's next dispatch, for the timeline to reach the
	 * given value: the value of an earlier dispatch that the next one must follow, in a batch
	 * that may still be running. Executor-thread only.
	 *
	 * @param value the timeline value to wait for
	 */
	void encodeWait(long value) {
		buffer.encodeWaitForEvent(timeline, value);
	}

	/**
	 * Encodes a GPU wait, ahead of the batch's next dispatch, on a host-signaled bridge: an event
	 * the host signals to {@code 1} once the foreign work it stands for has settled. The bridge
	 * is pending until {@link #bridgeSignaled()} counts that signal, and the batch is not
	 * {@link #isReady() ready} to be committed while any bridge is pending, so the GPU never waits
	 * for the host. The event is released with the batch's released-memory callbacks.
	 * Executor-thread only.
	 *
	 * @param bridge the bridge's event, used for this bridge alone
	 */
	void encodeBridge(MTLEvent bridge) {
		buffer.encodeWaitForEvent(bridge, 1);
		onComplete.add(bridge::release);
		pendingBridges++;
	}

	/** Counts the signal of one of the batch's bridges (see {@link #encodeBridge}). Executor-thread only. */
	void bridgeSignaled() { pendingBridges--; }

	/**
	 * Returns whether every host-signaled bridge encoded into the batch has been signaled, so
	 * that committing it cannot leave the GPU waiting for the host. Executor-thread only.
	 *
	 * @return true if the batch may be committed
	 */
	boolean isReady() { return pendingBridges == 0; }

	/**
	 * Encodes one dispatch into the batch. Executor-thread only.
	 *
	 * @param command encodes the dispatch's kernel into the batch's command buffer
	 */
	void encode(MetalCommand command) {
		command.encode(buffer);
		dispatches++;
	}

	/**
	 * Closes the batch by encoding, as its last command, a signal of the timeline to the given
	 * value. The GPU executes the signal once all of the batch's work has completed, which
	 * releases every later wait for a dispatch of the batch (see {@link MetalCommandRunner}). No
	 * dispatch is encoded into the batch afterwards. Executor-thread only.
	 *
	 * @param value the timeline value of the last dispatch the runner has encoded: the batch's
	 *              last, or an earlier batch's when this one holds none
	 */
	void close(long value) {
		buffer.encodeSignalEvent(timeline, value);
	}

	/**
	 * Commits the batch to the GPU and releases any thread awaiting that. Before committing,
	 * registers a completed handler on the buffer (see {@link MTLCommandBuffer#addCompletedHandler})
	 * that records the batch as finished and then runs {@code onFinished} on the Metal thread that
	 * reports the completion. {@code onFinished} must only hand work off, since the runner's state
	 * is confined to its executor thread. On a host where completed handlers are unavailable
	 * nothing is registered, and the batch is seen to have finished only through the buffer's
	 * status ({@link #isFinished()}). The batch is committed even if registering the handler
	 * fails, so a thread awaiting the commit is never left waiting. A batch committed without a
	 * handler that has, or later gets, a {@link #whenFinished finish listener} has its completion
	 * reported by a waiting thread instead (see {@link #observeIfUnreported()}). Executor-thread
	 * only.
	 *
	 * @param onFinished run on a Metal thread once the batch has finished, by executing or failing
	 */
	void commit(Runnable onFinished) {
		completionReport = () -> {
			completionReported = true;
			onFinished.run();
		};

		try {
			handlerRegistered = buffer.addCompletedHandler(completionReport);
		} finally {
			buffer.commit();
			committed.countDown();
		}

		if (!onFinishedListeners.isEmpty()) observeIfUnreported();
	}

	/**
	 * Substitutes for a completed handler that could not be registered when the batch was
	 * committed, so that a {@link #whenFinished finish listener} is not left waiting for an
	 * unrelated drain. A registration fails for a single buffer when no JNI global reference can be
	 * created, or for every later buffer once the operation has been disabled (see
	 * {@link MTLCompletedHandler#register}); either can happen after a
	 * {@link MetalSemaphore#onSettled(Runnable)} chose to rely on the handler. A
	 * {@link Semaphore#CALLBACK_EXECUTOR} thread then waits for this buffer alone and reports its
	 * completion exactly as the handler would have, keeping the buffer retained meanwhile as a
	 * {@link #addWaiter() waiter} does. This is started at most once per batch, and only when a
	 * listener needs it. Does nothing if the handler was registered or the batch is not committed.
	 * Executor-thread only.
	 */
	private void observeIfUnreported() {
		if (!isCommitted() || handlerRegistered || substituteStarted) return;

		substituteStarted = true;
		addWaiter();
		Semaphore.CALLBACK_EXECUTOR.execute(() -> {
			try {
				buffer.waitUntilCompleted();
			} finally {
				removeWaiter();
				completionReport.run();
			}
		});
	}

	/**
	 * Returns whether the batch has been committed to the GPU, without waiting for it.
	 *
	 * @return true once the batch has been committed
	 */
	boolean isCommitted() {
		return committed.isSettled();
	}

	/**
	 * Returns whether the batch has finished on the GPU, by executing or by failing, without
	 * waiting for it: Metal has reported it through the batch's completed handler (see
	 * {@link #commit(Runnable)}), or the buffer's status shows it (see
	 * {@link MTLCommandBuffer#isFinished()}).
	 *
	 * @return true once the batch has finished
	 */
	boolean isFinished() { return completionReported || buffer.isFinished(); }

	/**
	 * Blocks until the batch has been committed, which is what makes waiting for its completion
	 * meaningful, and has then finished on the GPU, by executing or by failing. The wait for the
	 * commit ends early if the calling thread is interrupted, restoring the interrupt status (see
	 * {@link LatchSemaphore#awaitCountDown()}); the commit is checked again after an interrupted
	 * wait, because the latch reports an interrupt even once it has been counted down, so an
	 * interrupt alone never ends a wait whose commit has happened, including one that happens
	 * while the wait begins. The wait for the
	 * GPU is never ended by an interrupt. Only the executor thread commits a batch, so it must not
	 * call this for a batch that may not have been committed yet.
	 *
	 * @return true once the batch has finished, false if the wait for the commit was interrupted
	 */
	boolean awaitCompletion() {
		if (!committed.awaitCountDown() && !isCommitted()) return false;

		buffer.waitUntilCompleted();
		return true;
	}

	/**
	 * Blocks until the batch has been committed and has then finished on the GPU, as
	 * {@link #awaitCompletion()} does, without being ended by an interrupt; an interrupt received
	 * meanwhile is restored afterwards (see {@link LatchSemaphore#waitForUninterruptibly()}).
	 */
	void awaitCompletionUninterruptibly() {
		committed.waitForUninterruptibly();
		buffer.waitUntilCompleted();
	}

	/**
	 * Adds a released-memory callback to run once the batch has finished. Executor-thread only.
	 *
	 * @param callback the callback to run when the batch is drained
	 */
	void whenComplete(Runnable callback) {
		onComplete.add(callback);
	}

	/**
	 * Adds a listener to notify once the batch has finished on the GPU, by executing or failing,
	 * or at once if it already has. Unlike a {@link #whenComplete released-memory callback}, which
	 * runs only when the batch is drained, a listener is notified as soon as the runner sees the
	 * batch finish ({@link #notifyIfFinished()}), even while a thread is registered to wait on it
	 * and so keeps it from being drained without that thread. If the batch was committed without
	 * a completed handler, its completion is observed by a waiting thread so that the listener is
	 * still notified (see {@link #observeIfUnreported()}). That thread is started even when the
	 * batch has already finished and the listener is notified at once, since nothing else then
	 * reports the completion to the runner, which would leave the batch undrained and its buffer
	 * retained until an unrelated wait. That thread is started even if notifying a listener fails.
	 * A listener must only hand work off, since it runs on the executor thread. Executor-thread
	 * only.
	 *
	 * @param listener the listener to notify once the batch has finished
	 */
	void whenFinished(Runnable listener) {
		onFinishedListeners.add(listener);

		try {
			notifyIfFinished();
		} finally {
			observeIfUnreported();
		}
	}

	/**
	 * Notifies, once each, the listeners registered with {@link #whenFinished(Runnable)} if the
	 * batch has finished on the GPU. Executor-thread only.
	 *
	 * @return true if the batch has finished
	 */
	boolean notifyIfFinished() {
		if (!isCommitted() || !isFinished()) return false;

		notifyFinished();
		return true;
	}

	/**
	 * Drops, and then notifies, every listener registered with {@link #whenFinished(Runnable)}.
	 * Every listener is notified even if an earlier one fails, since a dropped listener is never
	 * notified again; the first failure is rethrown afterwards (see {@link Destroyable#releaseAll}).
	 */
	private void notifyFinished() {
		List<Runnable> listeners = new ArrayList<>(onFinishedListeners);
		onFinishedListeners.clear();
		Destroyable.releaseAll(listeners);
	}

	/**
	 * Notifies the listeners registered with {@link #whenFinished(Runnable)}, if any remain, then
	 * runs the released-memory callbacks of the finished batch, then records it as drained,
	 * releasing its command buffer unless a thread still waits on it. Every listener and callback
	 * runs and the buffer is released even if one of them fails; the first failure is rethrown
	 * afterwards (see
	 * {@link Destroyable#releaseAll}). The callbacks are dropped once they have run, so that
	 * whatever they refer to is not kept reachable by something that outlives the drain, such as
	 * the callback of a bridge whose foreign work settles only after the batch failed.
	 * Executor-thread only.
	 */
	void drain() {
		List<Runnable> drains = new ArrayList<>(onComplete.size() + 1);
		drains.add(this::notifyFinished);
		drains.addAll(onComplete);

		try {
			Destroyable.releaseAll(drains, this::drained);
		} finally {
			onComplete.clear();
		}
	}

	/** Records that the batch has been drained, releasing its buffer unless a thread still waits on it. */
	private synchronized void drained() {
		drained = true;
		releaseIfUnused();
	}

	/** Registers one waiter, keeping the batch's buffer retained until it is withdrawn. */
	synchronized void addWaiter() {
		waiters++;
	}

	/**
	 * Returns whether a thread waiting for the batch off the executor thread is registered on it,
	 * in which case that thread's wait drains it (see {@link MetalCommandRunner#complete}).
	 *
	 * @return true while at least one waiter is registered
	 */
	synchronized boolean hasWaiters() {
		return waiters > 0;
	}

	/** Withdraws one waiter, releasing the buffer if it was the last and the batch is drained. */
	synchronized void removeWaiter() {
		waiters--;
		releaseIfUnused();
	}

	/** Releases the command buffer once the batch is drained and no thread waits on it. */
	private void releaseIfUnused() {
		if (drained && waiters == 0) buffer.release();
	}
}
