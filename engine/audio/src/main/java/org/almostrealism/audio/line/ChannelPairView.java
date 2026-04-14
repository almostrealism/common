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

package org.almostrealism.audio.line;

import org.almostrealism.collect.PackedCollection;

/**
 * An {@link OutputLine} view that represents a specific stereo output pair
 * of a {@link MultiChannelOutputLine}. Used to identify a pair and expose
 * its labels/indices; actual audio data is filled into the parent's per-pair
 * source buffer via {@link MultiChannelOutputLine#setPairSource(int, PackedCollection)}.
 *
 * @see MultiChannelOutputLine#getView(int)
 */
public class ChannelPairView implements OutputLine {

	/** The parent multi-channel output line. */
	private final MultiChannelOutputLine parent;

	/** The 0-based stereo pair index this view represents. */
	private final int pairIndex;

	/** Package-private constructor — views are obtained via
	 * {@link MultiChannelOutputLine#getView(int)}. */
	ChannelPairView(MultiChannelOutputLine parent, int pairIndex) {
		this.parent = parent;
		this.pairIndex = pairIndex;
	}

	/** Returns the 0-based stereo pair index this view represents. */
	public int getPairIndex() {
		return pairIndex;
	}

	/** Returns a human-readable pair label like "1-2", "3-4", etc. */
	public String getPairLabel() {
		int left = pairIndex * 2 + 1;
		int right = pairIndex * 2 + 2;
		return left + "-" + right;
	}

	/** Returns the parent {@link MultiChannelOutputLine}. */
	public MultiChannelOutputLine getParent() {
		return parent;
	}

	@Override
	public void write(PackedCollection sample) {
		// Views don't write directly; the parent handles writing via
		// interleaved multi-channel frames from pair source buffers.
	}

	@Override
	public int getReadPosition() {
		return parent.getReadPosition();
	}

	@Override
	public int getBufferSize() {
		return parent.getBufferSize();
	}

	@Override
	public void start() {
		parent.start();
	}

	@Override
	public void stop() {
		parent.stop();
	}

	@Override
	public boolean isActive() {
		return parent.isActive();
	}

	@Override
	public void reset() {
		parent.reset();
	}

	@Override
	public void destroy() {
		// Views don't own the parent
	}
}
