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

package org.almostrealism.audio.pattern;

import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A wrapper that batches tick operations to execute once per N frames.
 *
 * <p>{@code BatchCell} solves a key timing mismatch in the AudioScene architecture.
 * The standard tick() method is called once per sample frame, but pattern rendering
 * needs to happen once per buffer (typically 256-4096 frames).</p>
 *
 * <h2>Problem</h2>
 * <p>Without batching:</p>
 * <ul>
 *   <li>tick() is called 44100 times per second (at 44.1kHz)</li>
 *   <li>PatternRenderCell would render each buffer 44100 times</li>
 *   <li>Massive performance waste and incorrect audio output</li>
 * </ul>
 *
 * <h2>Solution</h2>
 * <p>{@code BatchCell} wraps a {@link Temporal} and:</p>
 * <ul>
 *   <li>Counts tick invocations</li>
 *   <li>Only executes the wrapped operation every N ticks</li>
 *   <li>Optionally reports the current frame position to a callback</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * int bufferSize = 1024;
 * PatternRenderCell renderCell = new PatternRenderCell(...);
 *
 * // Wrap the render cell to execute once per buffer
 * BatchCell batched = new BatchCell(renderCell, bufferSize, frame -> {
 *     // Frame position callback - used for tracking
 *     currentFrame = frame;
 * });
 *
 * // Now batched.tick() can be called per-sample, but rendering
 * // only happens every 1024 calls
 * }</pre>
 *
 * <h2>Frame Tracking</h2>
 *
 * <p>The {@code frameCallback} is called just before the wrapped operation
 * executes, providing the current absolute frame position. This can be used
 * to update frame tracking state used by {@link PatternRenderCell}.</p>
 *
 * @deprecated Use {@link org.almostrealism.graph.BatchedCell} instead, which provides
 *             a unified Cell-based batching pattern with output buffer management.
 *
 * @see PatternRenderCell
 * @see org.almostrealism.graph.BatchedCell
 * @see Temporal
 *
 * @author Michael Murray
 */
@Deprecated
public class BatchCell implements Temporal, Lifecycle {
	private final Temporal delegate;
	private final int batchSize;
	private final IntConsumer frameCallback;

	private int tickCount;
	private int currentBatch;

	/**
	 * Creates a BatchCell with no frame callback.
	 *
	 * @param delegate The temporal operation to batch
	 * @param batchSize Number of ticks per batch execution
	 */
	public BatchCell(Temporal delegate, int batchSize) {
		this(delegate, batchSize, null);
	}

	/**
	 * Creates a BatchCell with a frame callback.
	 *
	 * @param delegate The temporal operation to batch
	 * @param batchSize Number of ticks per batch execution
	 * @param frameCallback Called with current frame position before each batch
	 */
	public BatchCell(Temporal delegate, int batchSize, IntConsumer frameCallback) {
		this.delegate = delegate;
		this.batchSize = batchSize;
		this.frameCallback = frameCallback;
		this.tickCount = 0;
		this.currentBatch = 0;
	}

	/**
	 * Returns the batch size (frames per batch).
	 */
	public int getBatchSize() {
		return batchSize;
	}

	/**
	 * Returns the current tick count within the current batch.
	 */
	public int getTickCount() {
		return tickCount;
	}

	/**
	 * Returns the current batch number.
	 */
	public int getCurrentBatch() {
		return currentBatch;
	}

	/**
	 * Returns the absolute frame position.
	 */
	public int getCurrentFrame() {
		return currentBatch * batchSize + tickCount;
	}

	/**
	 * Performs one tick, executing the wrapped operation only when
	 * a full batch of ticks has accumulated.
	 *
	 * @return Operation that may execute the wrapped temporal
	 */
	@Override
	public Supplier<Runnable> tick() {
		return () -> () -> {
			tickCount++;

			if (tickCount >= batchSize) {
				// Batch complete - execute the wrapped operation
				int framePosition = currentBatch * batchSize;

				// Notify callback of frame position
				if (frameCallback != null) {
					frameCallback.accept(framePosition);
				}

				// Execute the wrapped operation
				delegate.tick().get().run();

				// Move to next batch
				currentBatch++;
				tickCount = 0;
			}
		};
	}

	/**
	 * Resets the batch counter.
	 */
	@Override
	public void reset() {
		tickCount = 0;
		currentBatch = 0;

		if (delegate instanceof Lifecycle) {
			((Lifecycle) delegate).reset();
		}
	}
}
