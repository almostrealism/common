/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.concurrent.Semaphore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Owns the Metal command-buffer lifecycle for a {@link MetalComputeContext}.
 *
 * <p>Dispatches are <em>encoded</em> into an open command buffer (not committed per dispatch) on a
 * single-threaded executor, so independent dispatches accumulate into one command buffer. Each
 * dispatch returns a {@link MetalSemaphore} — the operation's single completion handle — and
 * signals a {@link MTLEvent} timeline value. Ordering between dependent dispatches is expressed at
 * the GPU level: when a dispatch is submitted with a {@link MetalSemaphore} dependency, the open
 * buffer is committed and a fresh buffer encodes a wait for the dependency's event value, so the
 * GPU serializes the dependent after the dependency across buffers without a host stall.</p>
 *
 * <p>{@link MetalSemaphore#waitFor()} is the only thing that blocks the host: it commits the
 * dispatch's buffer if still open and waits for it (and every buffer committed before it, since the
 * queue is serial) to complete.</p>
 *
 * <h2>Memory lifetime</h2>
 *
 * <p>Memory referenced by an encoded kernel must stay alive until its command buffer has completed.
 * Callers pass an {@code onCommit} callback to {@link #submit} that releases that memory; the runner
 * runs it only after the buffer the dispatch was encoded into has completed. The Objective-C
 * autorelease pool spans an open buffer's encoding and is drained once that buffer is committed (the
 * queue then retains the committed buffer until it completes).</p>
 */
public class MetalCommandRunner {
	/** Maximum number of kernel arguments a single command may bind. */
	public static final int MAX_ARGS = 512;

	/**
	 * Maximum dispatches encoded into one open command buffer before it is committed. This is a
	 * memory bound (it caps how much encoded-but-uncompleted work and how large an autorelease pool
	 * accumulate), not a behavioural switch — correctness does not depend on its value.
	 */
	private static final int MAX_OPEN = 256;

	/** Single-threaded executor that serializes all command-buffer operations. */
	private ExecutorService executor;

	/** The command queue used to submit encoded Metal compute commands. */
	private final MTLCommandQueue queue;

	/** Timeline event used to order dependent dispatches across command buffers on the GPU. */
	private final MTLEvent event;

	/** Monotonic value last signaled on {@link #event}. Executor-thread only. */
	private long signaled;

	/** The open (encoded, not yet committed) command buffer, or {@code null}. Executor-thread only. */
	private MTLCommandBuffer openBuffer;
	/** Autorelease pool spanning the open buffer's encoding. Executor-thread only. */
	private long openPool;
	/** Number of dispatches encoded into the open buffer. Executor-thread only. */
	private int openCount;
	/** Released-memory callbacks for dispatches in the open buffer. Executor-thread only. */
	private List<Runnable> openOnComplete = new ArrayList<>();

	/** Committed-but-not-yet-completed buffers, in commit (queue) order. Executor-thread only. */
	private final List<CommittedBuffer> committed = new ArrayList<>();

	/**
	 * Creates a Metal command runner.
	 *
	 * @param queue The {@link MTLCommandQueue} for submitting commands
	 */
	public MetalCommandRunner(MTLCommandQueue queue) {
		this.executor = Executors.newSingleThreadExecutor();
		this.queue = queue;
		this.event = queue.getDevice().newSharedEvent();
	}

	/**
	 * Encodes a dispatch into the open command buffer and returns its completion semaphore.
	 *
	 * <p>When {@code dependsOn} is a {@link MetalSemaphore} from this runner, the open buffer is
	 * committed first and the dispatch encodes a GPU wait for that dependency's event value, so the
	 * dependent runs after the dependency without the host blocking. (A foreign dependency must be
	 * waited by the caller before calling this method.)</p>
	 *
	 * @param command   encodes the kernel into the supplied command buffer
	 * @param dependsOn  a prior {@link MetalSemaphore} this dispatch depends on, or {@code null}
	 * @param onComplete released-memory callback to run after this dispatch's buffer completes, or null
	 * @return this dispatch's completion semaphore
	 */
	public MetalSemaphore submit(MetalCommand command, Semaphore dependsOn, Runnable onComplete) {
		List<MetalSemaphore> result = new ArrayList<>(1);

		await(executor.submit(() -> {
			MetalSemaphore dependency =
					dependsOn instanceof MetalSemaphore && ((MetalSemaphore) dependsOn).getRunner() == this
							? (MetalSemaphore) dependsOn : null;

			// Order a dependent dispatch after its dependency across buffers: commit the open buffer
			// (so the dependency's signal lands in an earlier, committed buffer) then wait its value.
			if (dependency != null && dependency.getCommandBuffer() == openBuffer) {
				commitOpenOnExecutor();
			}

			ensureOpenBuffer();

			if (dependency != null) {
				openBuffer.encodeWaitForEvent(event, dependency.getValue());
			}

			command.encode(openBuffer);

			signaled++;
			openBuffer.encodeSignalEvent(event, signaled);
			openCount++;
			if (onComplete != null) openOnComplete.add(onComplete);

			result.add(new MetalSemaphore(null, this, openBuffer, event, signaled));

			if (openCount >= MAX_OPEN) {
				commitOpenOnExecutor();
			}
		}));

		return result.get(0);
	}

	/**
	 * Commits (if still open) the buffer the given dispatch was encoded into and blocks until it,
	 * and every buffer committed before it, has completed; then runs their released-memory
	 * callbacks. Invoked by {@link MetalSemaphore#waitFor()}.
	 *
	 * @param commandBuffer the dispatch's command buffer
	 */
	public void complete(MTLCommandBuffer commandBuffer) {
		await(executor.submit(() -> completeOnExecutor(commandBuffer)));
	}

	/** Opens a fresh command buffer (and its autorelease pool) if none is currently open. */
	private void ensureOpenBuffer() {
		if (openBuffer == null) {
			openPool = MTL.autoreleasePoolPush();
			openBuffer = queue.commandBuffer();
			openCount = 0;
		}
	}

	/**
	 * Commits the open buffer (if any) and moves it to the committed list, draining its autorelease
	 * pool. Does not wait for completion. Must run on the executor thread.
	 */
	private void commitOpenOnExecutor() {
		if (openBuffer == null) return;

		openBuffer.commit();
		MTL.autoreleasePoolPop(openPool);
		openPool = 0;

		committed.add(new CommittedBuffer(openBuffer, openOnComplete));
		openBuffer = null;
		openCount = 0;
		openOnComplete = new ArrayList<>();
	}

	/**
	 * Commits the target buffer if it is still open, then waits for it and every earlier committed
	 * buffer to complete, running their callbacks in order. Must run on the executor thread.
	 */
	private void completeOnExecutor(MTLCommandBuffer target) {
		if (target == openBuffer) commitOpenOnExecutor();

		int index = -1;
		for (int i = 0; i < committed.size(); i++) {
			if (committed.get(i).buffer == target) {
				index = i;
				break;
			}
		}

		// Not found means it already completed and was drained by an earlier wait.
		for (int i = 0; i <= index; i++) {
			CommittedBuffer c = committed.remove(0);
			c.buffer.waitUntilCompleted();
			c.onComplete.forEach(Runnable::run);
		}
	}

	/** Waits for the given executor task to finish, rethrowing any execution failure. */
	private static void await(Future<?> f) {
		try {
			f.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Destroys this command runner and releases all resources.
	 *
	 * <p>Commits and waits for any open and committed buffers, runs their callbacks, releases the
	 * timeline event, and shuts down the executor service.</p>
	 */
	public void destroy() {
		if (executor != null) {
			await(executor.submit(() -> {
				commitOpenOnExecutor();
				while (!committed.isEmpty()) {
					CommittedBuffer c = committed.remove(0);
					c.buffer.waitUntilCompleted();
					c.onComplete.forEach(Runnable::run);
				}
				event.release();
			}));
			executor.shutdown();
		}
		executor = null;
	}

	/** A committed command buffer and the released-memory callbacks to run once it completes. */
	private static final class CommittedBuffer {
		/** The committed command buffer. */
		private final MTLCommandBuffer buffer;
		/** Released-memory callbacks to run once the buffer completes. */
		private final List<Runnable> onComplete;

		/**
		 * Creates a committed-buffer record.
		 *
		 * @param buffer     the committed command buffer
		 * @param onComplete callbacks to run once it completes
		 */
		private CommittedBuffer(MTLCommandBuffer buffer, List<Runnable> onComplete) {
			this.buffer = buffer;
			this.onComplete = onComplete;
		}
	}
}
