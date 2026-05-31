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
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Owns the Metal command-buffer lifecycle for a {@link MetalComputeContext}.
 *
 * <p>{@link MetalCommand}s are <em>encoded</em> (not committed) on a single-threaded
 * executor. When {@link #enableBatching} is off, each command is committed and waited
 * immediately — one command buffer per dispatch (legacy behaviour). When on, commands are
 * encoded into a single open command buffer (one encoder each, so Metal's cross-encoder
 * hazard tracking orders dependent kernels) and committed once, either at a
 * {@linkplain #completionSemaphore() completion wait} or when {@link #maxBatchSize} commands
 * have accumulated. Batching collapses the command-buffer count — which is what both bounds
 * per-dispatch overhead and avoids the driver stall under sustained dispatch — but is only
 * sound when the per-operation host waits are deferred (see
 * {@code OperationList.enableSemaphoreChaining}); otherwise each commit forces a wait.</p>
 *
 * <h2>Memory lifetime</h2>
 *
 * <p>Memory referenced by an encoded-but-uncommitted kernel must stay alive until the batch
 * commits and completes. Callers pass an {@code onCommit} callback to {@link #submit} that
 * releases that memory; the runner runs it only after the buffer it was encoded into has
 * committed and {@code waitUntilCompleted}. The Objective-C autorelease pool likewise spans
 * the whole batch (the command buffer and its encoders are autoreleased and must survive
 * until commit).</p>
 */
public class MetalCommandRunner {
	/** Maximum number of kernel arguments a single command may bind. */
	public static final int MAX_ARGS = 512;

	/**
	 * When {@code true}, commands are encoded into a single command buffer and committed once
	 * (at a completion wait or the {@link #maxBatchSize} cap); when {@code false}, each command
	 * is committed and waited immediately (one buffer per dispatch). Enabling this also makes
	 * {@link MetalComputeContext#isCompletionDeferred()} report {@code true}, so an
	 * {@code OperationList} chains batched Metal dispatches. Controlled by {@code AR_METAL_BATCH};
	 * defaults on. Set {@code AR_METAL_BATCH=disabled} to restore one-buffer-per-dispatch.
	 */
	public static boolean enableBatching = SystemUtils.isEnabled("AR_METAL_BATCH").orElse(true);

	/** Maximum commands encoded into one batched command buffer before a forced commit. */
	public static int maxBatchSize = Integer.getInteger("AR_METAL_MAX_BATCH", 256);

	/** Single-threaded executor that serializes Metal command submission to avoid concurrency issues. */
	private ExecutorService executor;

	/** The command queue used to submit encoded Metal compute commands. */
	private final MTLCommandQueue queue;

	/** The open (encoded, not yet committed) command buffer, or {@code null}. Executor-thread only. */
	private MTLCommandBuffer openBuffer;
	/** Autorelease pool spanning the open buffer's lifetime. Executor-thread only. */
	private long openPool;
	/** Number of commands encoded into the open buffer. Executor-thread only. */
	private int encoded;
	/** Callbacks to run after the open buffer commits and completes (releases kernel memory). */
	private final List<Runnable> onCommit = new ArrayList<>();

	/** Shared completion semaphore — its {@link Semaphore#waitFor()} commits the open batch. */
	private final Semaphore completion = new FlushSemaphore();

	/**
	 * Creates a Metal command runner.
	 *
	 * @param queue The {@link MTLCommandQueue} for submitting commands
	 */
	public MetalCommandRunner(MTLCommandQueue queue) {
		this.executor = Executors.newSingleThreadExecutor();
		this.queue = queue;
	}

	/**
	 * Encodes a command into the current command buffer.
	 *
	 * <p>Returns after the command has been encoded. The buffer is committed either at a
	 * {@linkplain #completionSemaphore() completion wait}, at the {@link #maxBatchSize} cap,
	 * or immediately when {@link #enableBatching} is off. {@code onCommit} (if non-null) runs
	 * after the buffer this command was encoded into has committed and completed.</p>
	 *
	 * @param command  the command to encode
	 * @param onCommit released-memory callback to run after this command's buffer completes, or null
	 */
	public void submit(MetalCommand command, Runnable onCommit) {
		await(executor.submit(() -> {
			ensureOpenBuffer();
			command.encode(openBuffer);
			encoded++;
			if (onCommit != null) this.onCommit.add(onCommit);

			if (!enableBatching || encoded >= maxBatchSize) {
				commitOnExecutor();
			}
		}));
	}

	/**
	 * Commits and waits for the open batched command buffer, if any, running pending
	 * {@code onCommit} callbacks afterward. Safe to call when no buffer is open.
	 */
	public void flush() {
		await(executor.submit(this::commitOnExecutor));
	}

	/**
	 * Returns the shared completion semaphore whose {@link Semaphore#waitFor()} commits the
	 * open batch. Used as an operation's completion handle so the trailing wait of a chained
	 * group triggers the single commit.
	 *
	 * @return the completion semaphore
	 */
	public Semaphore completionSemaphore() {
		return completion;
	}

	/** Opens a fresh command buffer (and its autorelease pool) if none is currently open. */
	private void ensureOpenBuffer() {
		if (openBuffer == null) {
			openPool = MTL.autoreleasePoolPush();
			openBuffer = queue.commandBuffer();
			encoded = 0;
		}
	}

	/**
	 * Commits and waits for the open buffer, drains its autorelease pool, and runs the
	 * accumulated {@code onCommit} callbacks. Must run on the executor thread.
	 */
	private void commitOnExecutor() {
		if (openBuffer == null) return;

		openBuffer.commit();
		openBuffer.waitUntilCompleted();
		openBuffer = null;
		MTL.autoreleasePoolPop(openPool);
		openPool = 0;
		encoded = 0;

		List<Runnable> callbacks = new ArrayList<>(onCommit);
		onCommit.clear();
		callbacks.forEach(Runnable::run);
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
	 * <p>Commits any open batch and shuts down the executor service.</p>
	 */
	public void destroy() {
		if (executor != null) {
			flush();
			executor.shutdown();
		}
		executor = null;
	}

	/** A {@link Semaphore} whose {@link #waitFor()} commits the runner's open batch. */
	private final class FlushSemaphore implements Semaphore {
		@Override
		public void waitFor() { flush(); }

		@Override
		public OperationMetadata getRequester() { return null; }

		@Override
		public Semaphore withRequester(OperationMetadata requester) { return this; }
	}
}
