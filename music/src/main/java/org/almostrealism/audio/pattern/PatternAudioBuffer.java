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

package org.almostrealism.audio.pattern;

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A buffer that holds rendered pattern audio for real-time streaming.
 *
 * <p>{@code PatternAudioBuffer} extends the pattern system to support incremental
 * rendering. It renders pattern audio into a buffer as playback progresses, rather
 * than rendering the entire arrangement upfront.</p>
 *
 * <h2>Architecture</h2>
 *
 * <p>This class is a buffer holder with batch rendering capability:</p>
 * <ul>
 *   <li><strong>{@link #prepareBatch()}</strong> - Renders patterns into the output
 *       buffer. Called <em>outside</em> the per-frame loop, before each buffer.</li>
 *   <li><strong>{@link #getOutputProducer()}</strong> - Provides a producer for the
 *       output buffer that downstream components can read from.</li>
 * </ul>
 *
 * <p>This separation allows per-frame processing loops to be compiled and
 * hardware-accelerated, while pattern rendering (which involves Java-based
 * note evaluation) happens outside the loop.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PatternAudioBuffer buffer = new PatternAudioBuffer(
 *     patterns, contextSupplier, channel, bufferSize, frameSupplier);
 *
 * // In setup phase
 * buffer.setup().get().run();
 * buffer.prepareBatch().get().run();  // Render first buffer
 *
 * // In tick phase (per buffer)
 * buffer.prepareBatch().get().run();  // Render next buffer (outside loop)
 * loop(cells.tick(), bufferSize);     // Per-frame processing (compiled loop)
 * }</pre>
 *
 * <h2>Frame Tracking</h2>
 *
 * <p>The buffer accepts an {@link IntSupplier} that provides the current frame
 * position. This allows frame tracking to be managed externally by the runner.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is not thread-safe. It should only be accessed from a single
 * audio processing thread.</p>
 *
 * @see PatternSystemManager#sum(Supplier, ChannelInfo, IntSupplier, int)
 * @see org.almostrealism.audio.AudioScene#runnerRealTime
 *
 * @author Michael Murray
 */
public class PatternAudioBuffer implements Setup, CollectionFeatures {

	private final PatternSystemManager patterns;
	private final Supplier<AudioSceneContext> contextSupplier;
	private final ChannelInfo channel;
	private final IntSupplier currentFrame;
	private final int bufferSize;
	private final PackedCollection outputBuffer;

	/**
	 * Creates a new pattern audio buffer.
	 *
	 * <p>The output buffer is allocated immediately to ensure that
	 * {@link #getOutputProducer()} can provide a valid producer before
	 * setup() is called. This is required because downstream components
	 * are built before setup runs.</p>
	 *
	 * @param patterns       the pattern system manager containing patterns to render
	 * @param contextSupplier supplier for the audio scene context
	 * @param channel        the channel to render (index, voicing, stereo channel)
	 * @param bufferSize     the size of each render buffer in frames
	 * @param currentFrame   supplier providing the current absolute frame position
	 */
	public PatternAudioBuffer(PatternSystemManager patterns,
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
	 * Returns the channel this buffer handles.
	 */
	public ChannelInfo getChannel() {
		return channel;
	}

	/**
	 * Initializes the buffer.
	 *
	 * @return an empty setup operation
	 */
	@Override
	public Supplier<Runnable> setup() {
		return new OperationList("PatternAudioBuffer Setup");
	}

	/**
	 * Resets the buffer by clearing its contents.
	 */
	public void reset() {
		outputBuffer.clear();
	}
}
