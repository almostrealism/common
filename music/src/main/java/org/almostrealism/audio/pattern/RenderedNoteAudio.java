/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.collect.PackedCollection;

import java.util.function.IntFunction;

/**
 * Represents a note audio sample ready for rendering at a specific frame offset.
 *
 * <p>{@code RenderedNoteAudio} is the output of {@link ScaleTraversalStrategy#getNoteDestinations}
 * and serves as the bridge between pattern elements and actual audio rendering. Each instance
 * contains:</p>
 * <ul>
 *   <li><strong>offsetArg</strong>: A caller-owned {@link PackedCollection} for passing the start
 *       frame offset to the producer factory</li>
 *   <li><strong>producerFactory</strong>: A function that creates a {@link Producer} for a given
 *       frame count, using the offset stored in {@code offsetArg}</li>
 *   <li><strong>offset</strong>: The absolute frame position where this note should be rendered
 *       in the destination buffer</li>
 * </ul>
 *
 * <h2>Rendering Process</h2>
 *
 * <p>In {@link PatternFeatures#render}, each {@code RenderedNoteAudio} is processed by setting
 * the start frame in {@link #getOffsetArg()}, then calling {@link #getProducer(int)} with the
 * desired frame count. The resulting audio is cached by note offset for reuse across buffer
 * ticks, and the overlap region is summed to the destination buffer.</p>
 *
 * <h2>Signature Independence</h2>
 *
 * <p>The producer factory creates producers whose computation signature is independent of
 * the start frame value (because the offset is a runtime {@link PackedCollection} argument,
 * not a structural parameter). This enables compiled kernel reuse across different frame
 * positions via the instruction set cache.</p>
 *
 * @see PatternElement#getNoteDestinations
 * @see ScaleTraversalStrategy#getNoteDestinations
 * @see PatternFeatures#render
 *
 * @author Michael Murray
 */
public class RenderedNoteAudio {
	private int offset;
	private int expectedFrameCount;
	private PackedCollection offsetArg;
	private IntFunction<Producer<PackedCollection>> producerFactory;

	public RenderedNoteAudio() {
		this(0, 0);
	}

	/**
	 * Creates a RenderedNoteAudio with an expected frame count for pre-filtering.
	 *
	 * <p>The {@code expectedFrameCount} enables overlap checks before the expensive
	 * {@code evaluate()} call. When non-zero, rendering can skip notes whose
	 * {@code [offset, offset + expectedFrameCount)} range does not overlap the
	 * target buffer.</p>
	 *
	 * @param offset absolute frame offset in the arrangement
	 * @param expectedFrameCount estimated number of frames this note will produce
	 */
	public RenderedNoteAudio(int offset, int expectedFrameCount) {
		this.offset = offset;
		this.expectedFrameCount = expectedFrameCount;
	}

	public int getOffset() {
		return offset;
	}

	public void setOffset(int offset) {
		this.offset = offset;
	}

	/**
	 * Returns the estimated number of frames this note will produce.
	 *
	 * <p>This estimate is computed from the note's duration before the
	 * producer is evaluated. A value of 0 means no estimate is available
	 * and pre-filtering should be skipped for this note.</p>
	 */
	public int getExpectedFrameCount() {
		return expectedFrameCount;
	}

	public void setExpectedFrameCount(int expectedFrameCount) {
		this.expectedFrameCount = expectedFrameCount;
	}

	/**
	 * Returns the caller-owned {@link PackedCollection} used to pass the
	 * start frame offset to producers. The caller sets the value
	 * via {@code getOffsetArg().setMem(0, startFrame)} before calling
	 * {@link #getProducer(int)}.
	 *
	 * <p>Because the same {@link PackedCollection} instance is reused across
	 * calls, the {@link org.almostrealism.collect.computations.CollectionProviderProducer}
	 * signature remains stable (based on memory address, not data value),
	 * enabling compiled kernel reuse via the instruction set cache.</p>
	 */
	public PackedCollection getOffsetArg() {
		return offsetArg;
	}

	public void setOffsetArg(PackedCollection offsetArg) {
		this.offsetArg = offsetArg;
	}

	/**
	 * Sets the factory for creating {@link Producer}s that evaluate this note's audio.
	 *
	 * <p>The factory accepts a frame count and returns a Producer that generates
	 * exactly that many output frames. The start frame offset is communicated
	 * via the {@link #getOffsetArg()} PackedCollection, which the caller sets
	 * before invoking the factory. This design keeps the computation signature
	 * independent of the actual start frame value.</p>
	 *
	 * @param factory function mapping frameCount to a Producer
	 */
	public void setProducerFactory(IntFunction<Producer<PackedCollection>> factory) {
		this.producerFactory = factory;
	}

	/**
	 * Creates a {@link Producer} that evaluates the specified number of frames.
	 *
	 * <p>The caller must set the start frame offset in {@link #getOffsetArg()}
	 * before calling this method.</p>
	 *
	 * @param frameCount number of frames to produce
	 * @return a Producer for the requested frame count
	 * @throws IllegalStateException if no producer factory is set
	 */
	public Producer<PackedCollection> getProducer(int frameCount) {
		if (producerFactory == null) {
			throw new IllegalStateException(
					"No producer factory set on RenderedNoteAudio; " +
					"this indicates a missing setup in ScaleTraversalStrategy");
		}
		return producerFactory.apply(frameCount);
	}
}
