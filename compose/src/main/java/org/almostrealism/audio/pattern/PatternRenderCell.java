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
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.CellAdapter;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A {@link org.almostrealism.graph.Cell} that performs incremental pattern rendering
 * for real-time audio streaming.
 *
 * <p>{@code PatternRenderCell} is the key component enabling real-time AudioScene
 * operation. It renders pattern audio incrementally as playback progresses, rather
 * than rendering the entire arrangement during setup.</p>
 *
 * <h2>Architecture</h2>
 *
 * <p>This cell separates pattern preparation from per-frame processing:</p>
 * <ul>
 *   <li><strong>{@link #prepareBatch()}</strong> - Renders patterns into the output
 *       buffer. Called <em>outside</em> the per-frame loop, before each buffer.</li>
 *   <li><strong>{@link #tick()}</strong> - A no-op that returns an empty compilable
 *       operation. Called <em>inside</em> the per-frame loop.</li>
 *   <li><strong>{@link #push(Producer)}</strong> - Forwards the output buffer to
 *       the receptor (effects pipeline). Compilable.</li>
 * </ul>
 *
 * <p>This separation allows the per-frame loop to be a compiled hardware-accelerated
 * {@link org.almostrealism.hardware.computations.Loop}, while pattern rendering
 * (which involves Java-based note evaluation) happens outside the loop.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PatternRenderCell cell = new PatternRenderCell(
 *     patterns, contextSupplier, channel, bufferSize, frameSupplier);
 *
 * // In setup phase
 * cell.setup().get().run();
 * cell.prepareBatch().get().run();  // Render first buffer
 *
 * // In tick phase (per buffer)
 * cell.prepareBatch().get().run();  // Render next buffer (outside loop)
 * loop(cells.tick(), bufferSize);   // Per-frame processing (compiled loop)
 * }</pre>
 *
 * <h2>Frame Tracking</h2>
 *
 * <p>The cell accepts an {@link IntSupplier} that provides the current frame
 * position. This allows frame tracking to be managed externally by the runner.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This cell is not thread-safe. It should only be accessed from a single
 * audio processing thread.</p>
 *
 * @see PatternSystemManager#sum(Supplier, ChannelInfo, IntSupplier, int)
 * @see org.almostrealism.audio.AudioScene#runnerRealTime
 *
 * @author Michael Murray
 */
public class PatternRenderCell extends CellAdapter<PackedCollection>
		implements Temporal, Lifecycle, CollectionFeatures {

	private final PatternSystemManager patterns;
	private final Supplier<AudioSceneContext> contextSupplier;
	private final ChannelInfo channel;
	private final IntSupplier currentFrame;
	private final int bufferSize;
	private final PackedCollection outputBuffer;

	/**
	 * Creates a new pattern render cell.
	 *
	 * <p>The output buffer is allocated immediately to ensure that
	 * {@link #getOutputProducer()} can provide a valid producer before
	 * setup() is called. This is required because the cell pipeline
	 * (including effects processing) is built before setup runs.</p>
	 *
	 * @param patterns       the pattern system manager containing patterns to render
	 * @param contextSupplier supplier for the audio scene context
	 * @param channel        the channel to render (index, voicing, stereo channel)
	 * @param bufferSize     the size of each render buffer in frames
	 * @param currentFrame   supplier providing the current absolute frame position
	 */
	public PatternRenderCell(PatternSystemManager patterns,
							 Supplier<AudioSceneContext> contextSupplier,
							 ChannelInfo channel,
							 int bufferSize,
							 IntSupplier currentFrame) {
		this.patterns = patterns;
		this.contextSupplier = contextSupplier;
		this.channel = channel;
		this.bufferSize = bufferSize;
		this.currentFrame = currentFrame;
		this.outputBuffer = new PackedCollection(bufferSize);
	}

	/**
	 * Prepares the next buffer of pattern audio.
	 *
	 * <p>This method renders patterns for the frame range
	 * [{@code currentFrame}, {@code currentFrame + bufferSize}) into the
	 * output buffer. It should be called <em>outside</em> the per-frame
	 * loop, once per buffer.</p>
	 *
	 * <p>The operation:</p>
	 * <ol>
	 *   <li>Clears the output buffer</li>
	 *   <li>Creates a context with the output buffer as destination</li>
	 *   <li>Calls {@link PatternSystemManager#sum} to render overlapping notes</li>
	 * </ol>
	 *
	 * @return an operation that renders one buffer of pattern audio
	 */
	public Supplier<Runnable> prepareBatch() {
		Supplier<AudioSceneContext> batchContext = () -> {
			AudioSceneContext ctx = contextSupplier.get();
			ctx.setDestination(outputBuffer);
			return ctx;
		};

		Supplier<Runnable> sumSupplier = patterns.sum(batchContext, channel, currentFrame, bufferSize);

		return () -> {
			Runnable sumOp = sumSupplier.get();
			return () -> {
				outputBuffer.clear();
				sumOp.run();
			};
		};
	}

	/**
	 * Returns the buffer size in frames.
	 */
	public int getBufferSize() {
		return bufferSize;
	}

	/**
	 * Returns the output buffer where {@link #prepareBatch()} writes its results.
	 */
	public PackedCollection getOutputBuffer() {
		return outputBuffer;
	}

	/**
	 * Returns a {@link Producer} wrapping the output buffer.
	 *
	 * <p>The returned producer provides a direct reference to the underlying
	 * {@link PackedCollection}, which allows expression trees to resolve
	 * the memory address for indexed reads.</p>
	 *
	 * @return a producer for the output buffer
	 */
	public Producer<PackedCollection> getOutputProducer() {
		return cp(outputBuffer);
	}

	/**
	 * Returns the channel this cell renders.
	 */
	public ChannelInfo getChannel() {
		return channel;
	}

	/**
	 * No-op tick operation.
	 *
	 * <p>Pattern data is already in the output buffer (prepared by
	 * {@link #prepareBatch()}). The per-frame loop reads from the buffer
	 * via downstream cells that receive data through {@link #push}.</p>
	 *
	 * <p>This method returns an empty {@link OperationList} which is a
	 * compilable no-op, ensuring the per-frame loop remains fully compilable.</p>
	 *
	 * @return an empty compilable operation
	 */
	@Override
	public Supplier<Runnable> tick() {
		return new OperationList("PatternRenderCell Tick (no-op)");
	}

	/**
	 * Forwards the output buffer to the receptor.
	 *
	 * <p>This cell is a source cell: it generates its own output via
	 * {@link #prepareBatch()}, so the {@code protein} argument is ignored.
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
		return new OperationList("PatternRenderCell Push (no receptor)");
	}

	/**
	 * Initializes the cell.
	 *
	 * @return an empty setup operation
	 */
	@Override
	public Supplier<Runnable> setup() {
		return new OperationList("PatternRenderCell Setup");
	}

	/**
	 * Resets the cell by clearing the output buffer.
	 */
	@Override
	public void reset() {
		outputBuffer.clear();
	}
}
