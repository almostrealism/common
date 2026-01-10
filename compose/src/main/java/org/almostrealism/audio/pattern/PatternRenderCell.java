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

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A Cell that performs incremental pattern rendering for real-time audio streaming.
 *
 * <p>{@code PatternRenderCell} is the key component enabling real-time AudioScene
 * operation. It renders pattern audio incrementally as playback progresses, rather
 * than rendering the entire arrangement during setup.</p>
 *
 * <h2>Operation Model</h2>
 *
 * <p>Unlike traditional pattern rendering which happens once during setup, this cell:</p>
 * <ul>
 *   <li>Tracks the current playback position via {@link #currentFrame}</li>
 *   <li>Renders only the frame range needed for each buffer</li>
 *   <li>Clears the destination buffer before rendering each chunk</li>
 *   <li>Advances the frame position after each tick</li>
 * </ul>
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
 * // In tick phase - renders current buffer
 * cell.tick().get().run();
 * }</pre>
 *
 * <h2>Frame Tracking</h2>
 *
 * <p>The cell does not manage frame position internally. Instead, it accepts
 * a {@link IntSupplier} that provides the current frame position. This allows
 * the frame tracking to be managed externally, typically by the {@link GlobalTimeManager}
 * or a {@link BatchCell} wrapper.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This cell is not thread-safe. It should only be accessed from a single
 * audio processing thread.</p>
 *
 * @see PatternSystemManager#sum(Supplier, ChannelInfo, int, int)
 * @see org.almostrealism.audio.arrange.PatternRenderContext
 * @see BatchCell
 *
 * @author Michael Murray
 */
public class PatternRenderCell implements Cell<PackedCollection>, Temporal {
	private final PatternSystemManager patterns;
	private final Supplier<AudioSceneContext> contextSupplier;
	private final ChannelInfo channel;
	private final int bufferSize;
	private final IntSupplier currentFrame;

	private Receptor<PackedCollection> receptor;
	private PackedCollection destination;

	/**
	 * Creates a new pattern render cell.
	 *
	 * @param patterns The pattern system manager containing patterns to render
	 * @param contextSupplier Supplier for the audio scene context
	 * @param channel The channel to render (index, voicing, stereo channel)
	 * @param bufferSize The size of each render buffer in frames
	 * @param currentFrame Supplier providing the current absolute frame position
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
	}

	/**
	 * Returns the buffer size used by this cell.
	 */
	public int getBufferSize() {
		return bufferSize;
	}

	/**
	 * Returns the channel this cell renders.
	 */
	public ChannelInfo getChannel() {
		return channel;
	}

	/**
	 * Performs setup for pattern rendering.
	 *
	 * <p>In real-time mode, setup is lightweight - it only initializes
	 * the destination buffer. Pattern rendering happens during tick phase.</p>
	 *
	 * @return Operation that initializes the cell
	 */
	@Override
	public Supplier<Runnable> setup() {
		return () -> () -> {
			// Allocate destination buffer for this cell
			if (destination == null) {
				destination = new PackedCollection(bufferSize);
			}
		};
	}

	/**
	 * Performs one tick of pattern rendering.
	 *
	 * <p>Each tick:</p>
	 * <ol>
	 *   <li>Clears the destination buffer</li>
	 *   <li>Gets the current frame position from the frame supplier</li>
	 *   <li>Calls {@link PatternSystemManager#sum} with the frame range</li>
	 *   <li>Pushes the result to the receptor if set</li>
	 * </ol>
	 *
	 * @return Operation that renders one buffer of audio
	 */
	@Override
	public Supplier<Runnable> tick() {
		return () -> {
			OperationList op = new OperationList("PatternRenderCell Tick");

			// Get current frame position
			int startFrame = currentFrame.getAsInt();

			// Create context with destination buffer set
			Supplier<AudioSceneContext> tickContext = () -> {
				AudioSceneContext ctx = contextSupplier.get();
				ctx.setDestination(destination);
				return ctx;
			};

			// Clear destination buffer
			op.add(() -> () -> {
				if (destination != null) {
					destination.clear();
				}
			});

			// Render patterns for this frame range
			op.add(patterns.sum(tickContext, channel, startFrame, bufferSize));

			return op.get();
		};
	}

	/**
	 * Returns the rendered audio destination.
	 *
	 * <p>This producer can be used to access the rendered audio after
	 * tick() has been called.</p>
	 *
	 * @return Producer for the destination buffer
	 */
	public Producer<PackedCollection> getOutput() {
		return () -> args -> destination;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		// Pattern cells generate their own output, they don't transform input
		// Just execute tick and push result to receptor
		return () -> {
			OperationList op = new OperationList("PatternRenderCell Push");
			op.add(tick());

			if (receptor != null) {
				op.add(receptor.push(getOutput()));
			}

			return op.get();
		};
	}

	@Override
	public void setReceptor(Receptor<PackedCollection> r) {
		this.receptor = r;
	}

	/**
	 * Resets the cell for reuse.
	 *
	 * <p>Note: This does not reset the frame position since that is
	 * managed externally.</p>
	 */
	public void reset() {
		if (destination != null) {
			destination.clear();
		}
	}
}
