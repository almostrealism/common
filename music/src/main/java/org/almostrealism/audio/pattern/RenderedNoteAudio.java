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

/**
 * Represents a note audio sample ready for rendering at a specific frame offset.
 *
 * <p>{@code RenderedNoteAudio} is the output of {@link ScaleTraversalStrategy#getNoteDestinations}
 * and serves as the bridge between pattern elements and actual audio rendering. Each instance
 * contains:</p>
 * <ul>
 *   <li><strong>producer</strong>: A {@link Producer} that generates the audio samples when evaluated</li>
 *   <li><strong>offset</strong>: The frame position where this note should be rendered in the destination buffer</li>
 * </ul>
 *
 * <h2>Rendering Process</h2>
 *
 * <p>In {@link PatternFeatures#render}, each {@code RenderedNoteAudio} is processed as follows:</p>
 * <ol>
 *   <li>The producer is evaluated within a {@code Heap.stage()} context</li>
 *   <li>The resulting audio is summed to the destination buffer at the specified offset</li>
 *   <li>Audio that extends beyond the buffer is clipped</li>
 * </ol>
 *
 * <h2>Real-Time Considerations</h2>
 *
 * <p><strong>Critical:</strong> The {@code offset} field contains an absolute frame position
 * relative to the start of the arrangement. For real-time rendering:</p>
 * <ul>
 *   <li>Offsets must be converted to buffer-relative positions</li>
 *   <li>Notes may span multiple buffers, requiring partial rendering</li>
 *   <li>Notes that started in previous buffers need source offset calculation</li>
 * </ul>
 *
 * @see PatternElement#getNoteDestinations
 * @see ScaleTraversalStrategy#getNoteDestinations
 * @see PatternFeatures#render
 *
 * @author Michael Murray
 */
public class RenderedNoteAudio {
	private Producer<PackedCollection> producer;
	private int offset;
	private int expectedFrameCount;

	public RenderedNoteAudio() {
		this(null, 0, 0);
	}

	public RenderedNoteAudio(Producer<PackedCollection> producer, int offset) {
		this(producer, offset, 0);
	}

	/**
	 * Creates a RenderedNoteAudio with an expected frame count for pre-filtering.
	 *
	 * <p>The {@code expectedFrameCount} enables overlap checks before the expensive
	 * {@code evaluate()} call. When non-zero, rendering can skip notes whose
	 * {@code [offset, offset + expectedFrameCount)} range does not overlap the
	 * target buffer.</p>
	 *
	 * @param producer the audio producer
	 * @param offset absolute frame offset in the arrangement
	 * @param expectedFrameCount estimated number of frames this note will produce
	 */
	public RenderedNoteAudio(Producer<PackedCollection> producer, int offset, int expectedFrameCount) {
		this.producer = producer;
		this.offset = offset;
		this.expectedFrameCount = expectedFrameCount;
	}

	public Producer<PackedCollection> getProducer() {
		return producer;
	}

	public void setProducer(Producer<PackedCollection> producer) {
		this.producer = producer;
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
}
