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
import org.almostrealism.hardware.OperationList;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.pattern.PatternAudioBuffer;
import org.almostrealism.music.pattern.PatternSystemManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

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
 * <p>The buffer is explicitly zero-filled at allocation. Plain provider allocations are
 * already zero-initialized on every standard backend, but this buffer is read before
 * every region has been rendered (the PDSL runner performs a forward pass at build time
 * to obtain its output handle) and any non-zero content read there would be written into
 * stateful DSP rings where feedback can recirculate it indefinitely — so the zero
 * invariant is stated here directly rather than left to the allocation context (a
 * Heap-carved or shared-file-backed allocation would not be cleared).</p>
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
	 * Creates the render cells for one stereo side, MAIN voicing for every channel
	 * followed by WET voicing for every channel — the same order the CellList path
	 * produces them, so {@link #nextRegion} hands out regions in the
	 * {@code [MAIN(N), WET(N)]} per-side layout PDSL consumers rely on. WET cells
	 * are skipped when {@code includeWet} is {@code false}; the consolidated buffer
	 * is still sized for 4 regions per channel, but the skipped regions are never
	 * rendered (remain zero-filled) and subsequent regions are allocated in
	 * creation order.
	 *
	 * @param patterns       the pattern system the cells render from
	 * @param contextFactory produces the {@link AudioSceneContext} for one channel voicing
	 * @param channels       channel indices to render
	 * @param audioChannel   LEFT or RIGHT stereo channel
	 * @param bufferSize     frames per render buffer
	 * @param frameSupplier  supplies the current frame position for pattern rendering
	 * @param includeWet     whether to create the WET voicing cells (efx enabled)
	 * @param setup          the setup OperationList to accumulate operations in
	 */
	void createRenderCells(PatternSystemManager patterns,
						   Function<ChannelInfo, AudioSceneContext> contextFactory,
						   List<Integer> channels,
						   ChannelInfo.StereoChannel audioChannel,
						   int bufferSize, IntSupplier frameSupplier,
						   boolean includeWet, OperationList setup) {
		int[] idx = channels.stream().mapToInt(i -> i).toArray();

		for (int i = 0; i < idx.length; i++) {
			createRenderCell(patterns, contextFactory,
					new ChannelInfo(idx[i], ChannelInfo.Voicing.MAIN, audioChannel),
					bufferSize, frameSupplier, setup);
		}

		if (includeWet) {
			for (int i = 0; i < idx.length; i++) {
				createRenderCell(patterns, contextFactory,
						new ChannelInfo(idx[i], ChannelInfo.Voicing.WET, audioChannel),
						bufferSize, frameSupplier, setup);
			}
		}
	}

	/**
	 * Creates and registers a {@link PatternAudioBuffer} render cell for one channel
	 * voicing, carving its region from the consolidated render buffer and wiring its
	 * setup and initial-render operations into {@code setup}.
	 *
	 * @param patterns       the pattern system the cell renders from
	 * @param contextFactory produces the {@link AudioSceneContext} for the channel voicing
	 * @param channel        the channel (index, voicing, stereo channel)
	 * @param bufferSize     frames per render buffer
	 * @param frameSupplier  supplies the current frame position for pattern rendering
	 * @param setup          the setup OperationList (render cell setup and initial render are added here)
	 * @return the created and registered render cell
	 */
	PatternAudioBuffer createRenderCell(PatternSystemManager patterns,
										Function<ChannelInfo, AudioSceneContext> contextFactory,
										ChannelInfo channel, int bufferSize,
										IntSupplier frameSupplier, OperationList setup) {
		Supplier<AudioSceneContext> ctx = () -> contextFactory.apply(channel);

		PatternAudioBuffer renderCell = new PatternAudioBuffer(
				patterns, ctx, channel, bufferSize, frameSupplier,
				nextRegion(bufferSize));

		setup.add(renderCell.setup());
		setup.add(renderCell.prepareBatch());
		add(renderCell);

		return renderCell;
	}

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
