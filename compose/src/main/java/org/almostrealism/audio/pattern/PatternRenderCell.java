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

import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.graph.BatchedCell;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A {@link BatchedCell} that performs incremental pattern rendering for real-time
 * audio streaming.
 *
 * <p>{@code PatternRenderCell} is the key component enabling real-time AudioScene
 * operation. It renders pattern audio incrementally as playback progresses, rather
 * than rendering the entire arrangement during setup.</p>
 *
 * <h2>Operation Model</h2>
 *
 * <p>This cell extends {@link BatchedCell} with {@code batchSize == outputSize == bufferSize}.
 * The base class handles:</p>
 * <ul>
 *   <li>Tick counting (fires {@link #renderBatch()} once per buffer)</li>
 *   <li>Output buffer allocation and management</li>
 *   <li>push() forwarding to receptor (without triggering rendering)</li>
 *   <li>Lifecycle (setup/reset)</li>
 * </ul>
 *
 * <p>This subclass provides the actual pattern rendering logic via
 * {@link #renderBatch()}, which delegates to
 * {@link PatternSystemManager#sum(Supplier, ChannelInfo, int, int)}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PatternRenderCell cell = new PatternRenderCell(
 *     patterns, contextSupplier, channel, bufferSize,
 *     () -> currentFrame  // Frame position tracker
 * );
 *
 * // In setup phase - only light initialization
 * cell.setup().get().run();
 *
 * // In tick phase (called per-sample, renders once per buffer)
 * cell.tick().get().run();
 *
 * // In compiled path (renders immediately, no counting)
 * cell.renderNow().get().run();
 * }</pre>
 *
 * <h2>Frame Tracking</h2>
 *
 * <p>The cell accepts a {@link IntSupplier} that provides the current frame
 * position. This allows frame tracking to be managed externally, typically
 * by the {@code GlobalTimeManager} or a {@link BatchedCell} frame callback.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This cell is not thread-safe. It should only be accessed from a single
 * audio processing thread.</p>
 *
 * @see PatternSystemManager#sum(Supplier, ChannelInfo, int, int)
 * @see BatchedCell
 *
 * @author Michael Murray
 */
public class PatternRenderCell extends BatchedCell implements CollectionFeatures {
	private final PatternSystemManager patterns;
	private final Supplier<AudioSceneContext> contextSupplier;
	private final ChannelInfo channel;
	private final IntSupplier currentFrame;

	/**
	 * Creates a new pattern render cell.
	 *
	 * <p>The output buffer is allocated immediately by the {@link BatchedCell}
	 * constructor to ensure that {@link #getOutputProducer()} can provide a valid
	 * producer before setup() is called. This is required because the cell
	 * pipeline (including effects processing) is built before setup runs.</p>
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
		super(bufferSize, bufferSize);
		this.patterns = patterns;
		this.contextSupplier = contextSupplier;
		this.channel = channel;
		this.currentFrame = currentFrame;
	}

	/**
	 * Renders one buffer of pattern audio into the output buffer.
	 *
	 * <p>The returned operation is built once and reused for every render.
	 * Each execution:</p>
	 * <ol>
	 *   <li>Clears the output buffer</li>
	 *   <li>Creates a context with the output buffer as destination</li>
	 *   <li>Calls {@link PatternSystemManager#sum} which reads the current
	 *       frame position from the frame supplier</li>
	 * </ol>
	 *
	 * @return operation that renders one buffer of pattern audio
	 */
	@Override
	protected Supplier<Runnable> renderBatch() {
		Supplier<AudioSceneContext> tickContext = () -> {
			AudioSceneContext ctx = contextSupplier.get();
			ctx.setDestination(getOutputBuffer());
			return ctx;
		};

		Runnable sumOp = patterns.sum(tickContext, channel, currentFrame, getBatchSize()).get();

		return () -> () -> {
			getOutputBuffer().clear();
			sumOp.run();
		};
	}

	/**
	 * Returns the channel this cell renders.
	 */
	public ChannelInfo getChannel() {
		return channel;
	}
}
