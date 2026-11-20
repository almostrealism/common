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

import java.util.ArrayList;
import java.util.List;

/**
 * The dispatches a {@link MetalCommandRunner} encodes into one {@link MTLCommandBuffer}, from the
 * moment the runner opens the buffer until the runner has drained it: the runner's open batch (see
 * {@link MetalCommandRunner#closeOpenBatch()}), and afterwards a closed one.
 *
 * <p>A batch is <em>open</em> while the runner encodes dispatches into it, and is
 * {@link #close closed} by a signal of the runner's timeline encoded as its last command. Once
 * closed, it is {@link #commit() committed} to the GPU as soon as every host-signaled bridge
 * encoded into it has been signaled ({@link #isReady()}), and once it has finished it is
 * {@link #drain() drained}: its released-memory callbacks run and its command buffer is released,
 * unless a thread waiting for it off the runner's executor thread still needs the buffer (see
 * {@link #addWaiter()}).</p>
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

	/** Commits the batch to the GPU and releases any thread awaiting that. Executor-thread only. */
	void commit() {
		buffer.commit();
		committed.countDown();
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
	 * waiting for it (see {@link MTLCommandBuffer#isFinished()}).
	 *
	 * @return true once the batch has finished
	 */
	boolean isFinished() { return buffer.isFinished(); }

	/**
	 * Blocks until the batch has been committed, which is what makes waiting for its completion
	 * meaningful, and has then finished on the GPU, by executing or by failing. The wait for the
	 * commit ends early if the calling thread is interrupted, restoring the interrupt status (see
	 * {@link LatchSemaphore#awaitCountDown()}). Only the executor thread commits a batch, so it
	 * must not call this for a batch that may not have been committed yet.
	 *
	 * @return true once the batch has finished, false if the wait was interrupted
	 */
	boolean awaitCompletion() {
		if (!committed.awaitCountDown()) return false;

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
	 * Runs the released-memory callbacks of the finished batch, then records it as drained,
	 * releasing its command buffer unless a thread still waits on it. Every callback runs and the
	 * buffer is released even if a callback fails; the first failure is rethrown afterwards (see
	 * {@link Destroyable#releaseAll}). The callbacks are dropped once they have run, so that
	 * whatever they refer to is not kept reachable by something that outlives the drain, such as
	 * the callback of a bridge whose foreign work settles only after the batch failed.
	 * Executor-thread only.
	 */
	void drain() {
		try {
			Destroyable.releaseAll(onComplete, this::drained);
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
