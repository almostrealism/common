/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.pattern.PatternAudioBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the consolidated pattern-render storage for one {@link AudioScene} runner build:
 * a single contiguous {@link PackedCollection} that backs every
 * {@link PatternAudioBuffer} output region, plus the render cells themselves.
 *
 * <p>Consolidating all render-cell outputs into one root collection lets the compiled
 * per-frame loop's argument deduplication resolve every region to the same kernel
 * argument, and gives the PDSL runner a single zero-copy view over the
 * {@code [MAIN, WET]} voicing regions. Regions are handed out sequentially by
 * {@link #nextRegion(int)} in creation order, which is what defines the
 * {@code [LEFT-MAIN(N), LEFT-WET(N), RIGHT-MAIN(N), RIGHT-WET(N)]} layout consumers
 * rely on.</p>
 *
 * <p>The buffer is zero-filled at allocation: freshly allocated device memory is not
 * guaranteed to be zeroed, and the buffer is read before every region has been rendered
 * (the PDSL runner performs a forward pass at build time to obtain its output handle);
 * any garbage read there would be written into stateful DSP rings where feedback can
 * recirculate it indefinitely.</p>
 */
class PatternRenderBuffers implements Destroyable {

	/** Pattern audio buffers created for each channel during a runner build. */
	private List<PatternAudioBuffer> cells = new ArrayList<>();

	/** Consolidated backing buffer for all render cell outputs. */
	private PackedCollection buffer;

	/** Index of the next region to hand out. */
	private int regionIndex;

	/**
	 * Allocates (or replaces) the consolidated buffer and clears the render-cell list,
	 * preparing for a fresh runner build. The total region count is
	 * {@code channelCount * 4} (MAIN + WET voicing, LEFT + RIGHT stereo).
	 *
	 * @param channelCount number of audio channels
	 * @param bufferSize   frames per render region
	 */
	void consolidate(int channelCount, int bufferSize) {
		cells = new ArrayList<>();
		buffer = new PackedCollection(bufferSize * channelCount * 4);
		buffer.fill(0.0);
		regionIndex = 0;
	}

	/**
	 * Returns the next {@code bufferSize}-frame region of the consolidated buffer,
	 * advancing the region index, or {@code null} when no consolidated buffer has
	 * been allocated.
	 *
	 * @param bufferSize frames in the region
	 * @return a zero-copy view of the region, or {@code null}
	 */
	PackedCollection nextRegion(int bufferSize) {
		if (buffer == null) return null;

		PackedCollection region = buffer.range(
				new TraversalPolicy(bufferSize), regionIndex * bufferSize);
		regionIndex++;
		return region;
	}

	/**
	 * Records a render cell created during the current build.
	 *
	 * @param cell the pattern audio buffer to track
	 */
	void add(PatternAudioBuffer cell) { cells.add(cell); }

	/**
	 * Returns the render cells created during the current build.
	 *
	 * @return an unmodifiable view of the render cells
	 */
	List<PatternAudioBuffer> getCells() { return Collections.unmodifiableList(cells); }

	/**
	 * Returns the consolidated backing buffer, or {@code null} before the first
	 * {@link #consolidate(int, int)}.
	 *
	 * @return the consolidated buffer
	 */
	PackedCollection getBuffer() { return buffer; }

	@Override
	public void destroy() {
		if (buffer != null) {
			buffer.destroy();
			buffer = null;
		}
	}
}
