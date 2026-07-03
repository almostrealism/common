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
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.DistributionMetric;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

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
 * runs it only after the buffer the dispatch was encoded into has completed.</p>
 *
 * <p>Objective-C memory: every executor task runs inside its own autorelease pool (see
 * {@link #runInPool}) so transient autoreleased Metal objects (encoders, the command buffer's own
 * autorelease reference) are drained per task and do not accumulate in the driver. The open command
 * buffer outlives its creating task, so it is retained explicitly when created
 * ({@link MTL#commandBuffer(long)}) and released ({@link MTLCommandBuffer#release()}) once it has
 * completed.</p>
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

	/** Total dispatches encoded across all runners since the last reset. */
	public static final AtomicLong totalDispatchCount = new AtomicLong();
	/** Total command-buffer commits across all runners since the last reset. */
	public static final AtomicLong totalCommitCount = new AtomicLong();

	/**
	 * Returns the mean number of dispatches per committed command buffer (the effective GPU batch
	 * size) since the last {@link #resetBatchSizeCounters()}, or {@code 0} when nothing has committed.
	 *
	 * @return mean dispatches per commit
	 */
	public static double meanBatchSize() {
		long commits = totalCommitCount.get();
		return commits == 0 ? 0 : totalDispatchCount.get() / (double) commits;
	}

	/** Resets the batch-size counters; behaviour-neutral. */
	public static void resetBatchSizeCounters() {
		totalDispatchCount.set(0);
		totalCommitCount.set(0);
	}

	/**
	 * Distribution of commit-forcing host waits across the operations that requested them, keyed by
	 * the requester's {@link OperationMetadata#getDisplayName() display name}. Only waits that
	 * actually forced a commit are recorded (a wait for an already-committed buffer costs nothing
	 * attributable), so this distribution identifies which operations are responsible for breaking
	 * command-buffer batching.
	 */
	public static DistributionMetric hostCompleteRequesters =
			Hardware.console.distribution("mtlHostCompleteRequesters");

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
	/** Total command-buffer commits. Written on the executor thread; volatile for diagnostic reads. */
	private volatile long commitCount;
	/** Commits forced by a host-side wait ({@link #complete}). Executor-thread written; volatile reads. */
	private volatile long hostCompleteCommits;
	/** Commits forced by the open buffer reaching {@link #MAX_OPEN} dispatches. Executor-thread written. */
	private volatile long maxOpenCommits;
	/** Commits performed while destroying the runner. Executor-thread written; volatile reads. */
	private volatile long destroyCommits;
	/** Number of dispatches encoded into the open buffer. Executor-thread only. */
	private int openCount;
	/** Released-memory callbacks for dispatches in the open buffer. Executor-thread only. */
	private List<Runnable> openOnComplete = new ArrayList<>();

	/** Committed-but-not-yet-completed buffers, in commit (queue) order. Executor-thread only. */
	private final List<CommittedBuffer> committed = new ArrayList<>();

	/**
	 * True while an open (created but not yet committed) command buffer exists. Enforces the
	 * one-open-buffer-per-{@link MetalComputeContext} invariant: a single context must never have a
	 * second uncommitted command buffer in flight (cross-buffer coordination is only meaningful
	 * <em>between</em> contexts, and a Computation tree compiles against a single Metal context).
	 * Tracked explicitly so an attempt to open a second buffer fails fast instead of silently
	 * producing the kind of wedged-completion state this work has repeatedly hit.
	 */
	private boolean bufferOpen;

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
	 * <p>When {@code dependsOn} is a {@link MetalSemaphore} from this runner, ordering costs
	 * nothing or almost nothing: a dependency still in the open command buffer is already
	 * ordered ahead of this dispatch by Metal's hazard tracking (every buffer is allocated
	 * with default tracking; see {@code MTL.cpp}), so it is simply dropped; a dependency in
	 * an earlier, committed buffer is honored by encoding a GPU wait for its event value.
	 * Neither case blocks the host or forces a commit, so chaining completion semaphores
	 * through a sequence of dispatches preserves batching. (A foreign dependency must be
	 * waited by the caller before calling this method.)</p>
	 *
	 * @param requester  metadata of the operation the dispatch belongs to, or {@code null}; carried by
	 *                   the returned semaphore so a later commit-forcing wait can be attributed to it
	 * @param command   encodes the kernel into the supplied command buffer
	 * @param dependsOn  a prior {@link MetalSemaphore} this dispatch depends on, or {@code null}
	 * @param onComplete released-memory callback to run after this dispatch's buffer completes, or null
	 * @return this dispatch's completion semaphore
	 */
	public MetalSemaphore submit(OperationMetadata requester, MetalCommand command,
								 Semaphore dependsOn, Runnable onComplete) {
		List<MetalSemaphore> result = new ArrayList<>(1);

		await(executor.submit(() -> runInPool(() -> {
			MetalSemaphore dependency =
					dependsOn instanceof MetalSemaphore && ((MetalSemaphore) dependsOn).getRunner() == this
							? (MetalSemaphore) dependsOn : null;

			// A dependency encoded into the still-open buffer is already ordered ahead of this
			// dispatch by in-buffer hazard tracking; no commit and no event wait are needed.
			if (dependency != null && dependency.getCommandBuffer() == openBuffer) {
				dependency = null;
			}

			ensureOpenBuffer();

			if (dependency != null) {
				openBuffer.encodeWaitForEvent(event, dependency.getValue());
			}

			command.encode(openBuffer);

			signaled++;
			openBuffer.encodeSignalEvent(event, signaled);
			openCount++;
			totalDispatchCount.incrementAndGet();
			if (onComplete != null) openOnComplete.add(onComplete);

			result.add(new MetalSemaphore(requester, this, openBuffer, event, signaled));

			if (openCount >= MAX_OPEN && commitOpenOnExecutor()) {
				maxOpenCommits++;
			}
		})));

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
		complete(commandBuffer, null);
	}

	/**
	 * Commits (if still open) the buffer the given dispatch was encoded into and blocks until it,
	 * and every buffer committed before it, has completed; then runs their released-memory
	 * callbacks. When the wait forces a commit, the commit is attributed to {@code requester} in
	 * {@link #hostCompleteRequesters} so batching-breaking waits can be traced to the operations
	 * responsible for them.
	 *
	 * @param commandBuffer the dispatch's command buffer
	 * @param requester     metadata of the operation waiting for completion, or {@code null}
	 */
	public void complete(MTLCommandBuffer commandBuffer, OperationMetadata requester) {
		await(executor.submit(() -> runInPool(() -> completeOnExecutor(commandBuffer, requester))));
	}

	/**
	 * Runs {@code task} on the executor's thread inside a per-task Objective-C autorelease pool, so
	 * transient autoreleased Metal objects (compute command encoders, and the command buffer's own
	 * autorelease reference) are drained when the task ends rather than accumulating in the driver
	 * until it stalls. The open command buffer survives across tasks because it is retained
	 * explicitly when created (see {@link MTL#commandBuffer(long)}) and released by
	 * {@link #completeOnExecutor} once it has completed. A pool scoped to each task (rather than one
	 * spanning a buffer's whole open lifetime across several tasks) is required: spanning a pool
	 * across the executor's task boundaries wedged command-buffer completion.
	 *
	 * @param task the work to run inside a fresh autorelease pool
	 */
	private void runInPool(Runnable task) {
		long pool = MTL.autoreleasePoolPush();

		try {
			task.run();
		} finally {
			MTL.autoreleasePoolPop(pool);
		}
	}

	/** Opens a fresh command buffer if none is currently open. */
	private void ensureOpenBuffer() {
		if (openBuffer == null) {
			if (bufferOpen) {
				throw new IllegalStateException("A second open command buffer was requested while " +
						"one is still open on this ComputeContext — the one-open-buffer-per-context " +
						"invariant is violated");
			}

			openBuffer = queue.commandBuffer();
			openCount = 0;
			bufferOpen = true;
		}
	}

	/**
	 * Returns the number of command buffers this runner has committed. Batching diagnostics:
	 * a group of dispatches that was expected to share one command buffer can be checked by
	 * comparing this count before and after the group is issued.
	 *
	 * @return the total number of command-buffer commits so far
	 */
	public long getCommitCount() { return commitCount; }

	/**
	 * Returns the number of commits forced by a host-side completion wait ({@link #complete}).
	 * Together with {@link #getMaxOpenCommitCount()} and {@link #getDestroyCommitCount()} this
	 * partitions {@link #getCommitCount()} by cause: host waits are the commits that break
	 * batching on demand, while {@link #MAX_OPEN} commits are the expected steady-state cadence.
	 *
	 * @return the number of commits caused by host completion waits
	 */
	public long getHostCompleteCommitCount() { return hostCompleteCommits; }

	/**
	 * Returns the number of commits forced by the open buffer reaching {@link #MAX_OPEN} dispatches.
	 *
	 * @return the number of commits caused by the open-buffer dispatch bound
	 */
	public long getMaxOpenCommitCount() { return maxOpenCommits; }

	/**
	 * Returns the number of commits performed while destroying the runner.
	 *
	 * @return the number of commits caused by {@link #destroy()}
	 */
	public long getDestroyCommitCount() { return destroyCommits; }

	/**
	 * Commits the open buffer (if any) and moves it to the committed list. Does not wait for
	 * completion. Must run on the executor thread.
	 *
	 * @return true if a commit was performed, false if no buffer was open
	 */
	private boolean commitOpenOnExecutor() {
		if (openBuffer == null) return false;

		totalCommitCount.incrementAndGet();
		commitCount++;

		openBuffer.commit();
		bufferOpen = false;

		committed.add(new CommittedBuffer(openBuffer, openOnComplete));
		openBuffer = null;
		openCount = 0;
		openOnComplete = new ArrayList<>();
		return true;
	}

	/**
	 * Commits the target buffer if it is still open, then waits for it and every earlier committed
	 * buffer to complete, running their callbacks in order. Must run on the executor thread.
	 */
	private void completeOnExecutor(MTLCommandBuffer target, OperationMetadata requester) {
		if (target == openBuffer && commitOpenOnExecutor()) {
			hostCompleteCommits++;
			hostCompleteRequesters.addEntry(
					requester == null ? "unknown" : requester.getDisplayName(), 1);
		}

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
			c.buffer.release();
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
			await(executor.submit(() -> runInPool(() -> {
				if (commitOpenOnExecutor()) destroyCommits++;
				while (!committed.isEmpty()) {
					CommittedBuffer c = committed.remove(0);
					c.buffer.waitUntilCompleted();
					c.onComplete.forEach(Runnable::run);
					c.buffer.release();
				}
				event.release();
			})));
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
