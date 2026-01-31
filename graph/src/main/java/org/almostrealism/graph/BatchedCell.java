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

package org.almostrealism.graph;

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.Temporal;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Abstract base class for cells that adapt per-sample tick rates to per-buffer
 * batch operations.
 *
 * <p>{@code BatchedCell} provides a unified pattern for cells that need to
 * accumulate a fixed number of {@link #tick()} calls before performing a
 * batch operation. This solves the fundamental timing mismatch in audio
 * processing where tick() is called per-sample (e.g., 44100 times/sec)
 * but rendering needs to happen once per buffer (e.g., ~43 times/sec
 * at 1024-sample buffers).</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><strong>tick()</strong> counts invocations and fires {@link #renderBatch()}
 *       once every {@code batchSize} ticks</li>
 *   <li><strong>push()</strong> forwards the current output buffer to the receptor
 *       without triggering rendering or affecting timing</li>
 *   <li><strong>renderNow()</strong> triggers rendering immediately, bypassing
 *       the tick counter (for compiled paths)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyRenderCell extends BatchedCell {
 *     protected MyRenderCell(int bufferSize) {
 *         super(bufferSize, bufferSize);
 *     }
 *
 *     @Override
 *     protected Supplier<Runnable> renderBatch() {
 *         return () -> () -> {
 *             // Render one buffer of output into getOutputBuffer()
 *         };
 *     }
 * }
 * }</pre>
 *
 * <h2>Output</h2>
 * <p>The output buffer is allocated in the constructor so that
 * {@link #getOutputProducer()} returns a valid producer before
 * {@link #setup()} is called. This is required because cell
 * pipelines (including effects processing) are built during
 * construction, before setup runs.</p>
 *
 * @see CellAdapter
 * @see Temporal
 * @see org.almostrealism.graph.CachedStateCell
 *
 * @author Michael Murray
 */
public abstract class BatchedCell extends CellAdapter<PackedCollection>
		implements Temporal, Lifecycle, CollectionFeatures {

	private final int batchSize;
	private final int outputSize;
	private final PackedCollection output;
	private IntConsumer frameCallback;
	private Runnable cachedRender;
	private int tickCount;
	private int currentBatch;

	/**
	 * Creates a new batched cell.
	 *
	 * @param batchSize  the number of tick() calls that constitute one batch
	 * @param outputSize the size of the output buffer in frames
	 */
	protected BatchedCell(int batchSize, int outputSize) {
		this.batchSize = batchSize;
		this.outputSize = outputSize;
		this.output = new PackedCollection(outputSize);
		this.tickCount = 0;
		this.currentBatch = 0;
	}

	/**
	 * Creates a new batched cell with a frame callback.
	 *
	 * @param batchSize     the number of tick() calls that constitute one batch
	 * @param outputSize    the size of the output buffer in frames
	 * @param frameCallback called with the current frame position before each batch render
	 */
	protected BatchedCell(int batchSize, int outputSize, IntConsumer frameCallback) {
		this(batchSize, outputSize);
		this.frameCallback = frameCallback;
	}

	/**
	 * Renders one batch of output into {@link #getOutputBuffer()}.
	 *
	 * <p>This method is called once every {@code batchSize} ticks by the
	 * counting mechanism in {@link #tick()}, or directly via
	 * {@link #renderNow()}.</p>
	 *
	 * @return an operation that renders one batch of output
	 */
	protected abstract Supplier<Runnable> renderBatch();

	/**
	 * Returns the number of tick() calls per batch.
	 */
	public int getBatchSize() {
		return batchSize;
	}

	/**
	 * Returns the output buffer size in frames.
	 */
	public int getOutputSize() {
		return outputSize;
	}

	/**
	 * Returns the output buffer where {@link #renderBatch()} writes its results.
	 */
	public PackedCollection getOutputBuffer() {
		return output;
	}

	/**
	 * Returns a {@link Producer} wrapping the output buffer.
	 *
	 * <p>The returned producer provides a direct reference to the underlying
	 * {@link PackedCollection}, which allows expression trees (such as
	 * {@code WaveCellPush}) to resolve the memory address for indexed reads.
	 * Using {@code cp(output)} rather than a functional producer ensures that
	 * the computation framework can build correct {@code reference(index)}
	 * expressions against the backing memory.</p>
	 *
	 * @return a producer for the output buffer
	 */
	public Producer<PackedCollection> getOutputProducer() {
		return cp(output);
	}

	/**
	 * Returns the current batch number (number of completed batches).
	 */
	public int getCurrentBatch() {
		return currentBatch;
	}

	/**
	 * Returns the absolute frame position (start frame of the current batch).
	 */
	public int getCurrentFrame() {
		return currentBatch * batchSize;
	}

	/**
	 * Sets an optional callback that is invoked with the current frame
	 * position just before each batch render.
	 *
	 * @param frameCallback called with the current frame position before each batch
	 */
	public void setFrameCallback(IntConsumer frameCallback) {
		this.frameCallback = frameCallback;
	}

	/**
	 * Advances the batch counter by one.
	 *
	 * <p>Subclasses that override {@link #tick()} should call this after
	 * completing a batch to keep the counter in sync.</p>
	 */
	protected void advanceBatch() {
		currentBatch++;
	}

	/**
	 * Counts tick invocations and fires {@link #renderBatch()} once every
	 * {@code batchSize} ticks.
	 *
	 * <p>Each call increments an internal counter. When the counter reaches
	 * {@code batchSize}, the batch is rendered, the batch counter advances,
	 * and the tick counter resets to zero.</p>
	 *
	 * @return an operation that conditionally renders based on tick count
	 */
	@Override
	public Supplier<Runnable> tick() {
		return () -> () -> {
			tickCount++;
			if (tickCount >= batchSize) {
				if (frameCallback != null) {
					frameCallback.accept(getCurrentFrame());
				}
				if (cachedRender == null) {
					cachedRender = renderBatch().get();
				}
				cachedRender.run();
				currentBatch++;
				tickCount = 0;
			}
		};
	}

	/**
	 * Forwards the current output buffer to the receptor without triggering
	 * rendering or affecting the tick counter.
	 *
	 * <p>This cell is a source cell: it generates its own output via
	 * {@link #renderBatch()}, so the {@code protein} argument is ignored.
	 * The current output buffer is forwarded to the receptor as-is.</p>
	 *
	 * @param protein ignored (this cell generates its own output)
	 * @return an operation that pushes the output buffer to the receptor
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		if (getReceptor() != null) {
			return getReceptor().push(getOutputProducer());
		}
		return new OperationList("BatchedCell Push (empty)");
	}

	/**
	 * Renders immediately without counting ticks.
	 *
	 * <p>This method delegates directly to {@link #renderBatch()} without
	 * affecting the tick or batch counters. Useful when rendering is
	 * managed externally (e.g., in a compiled loop path).</p>
	 *
	 * @return an operation that renders one batch of output
	 */
	public Supplier<Runnable> renderNow() {
		return renderBatch();
	}

	/**
	 * Initializes the cell by resetting counters and cached state.
	 *
	 * <p>The output buffer is intentionally not cleared here because
	 * {@link #renderBatch()} clears it before each render. This allows
	 * pre-rendered buffers (via {@link #renderNow()}) to survive
	 * multiple setup() calls during pipeline initialization.</p>
	 *
	 * @return an operation that performs setup
	 */
	@Override
	public Supplier<Runnable> setup() {
		return () -> () -> {
			cachedRender = null;
			tickCount = 0;
			currentBatch = 0;
		};
	}

	/**
	 * Resets the cell by clearing the output buffer and resetting counters.
	 */
	@Override
	public void reset() {
		output.clear();
		cachedRender = null;
		tickCount = 0;
		currentBatch = 0;
	}
}
