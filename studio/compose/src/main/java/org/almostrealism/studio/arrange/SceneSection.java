/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.studio.arrange;
import org.almostrealism.music.arrange.ChannelSection;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.music.data.ChannelInfo;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Represents a section of an audio scene at a specific measure position, containing
 * per-channel sections that govern pattern playback within that time range.
 */
public class SceneSection implements Destroyable {
	/** Measure position where this section begins. */
	private final int position;

	/** Length of this section in measures. */
	private final int length;

	/** Per-channel sections indexed by pattern channel number. */
	private List<ChannelSection> channels;

	/**
	 * Creates a scene section.
	 *
	 * @param position measure position where the section starts
	 * @param length   length of the section in measures
	 * @param channels per-channel section list
	 */
	protected SceneSection(int position, int length,
						   List<ChannelSection> channels) {
		this.position = position;
		this.length = length;
		this.channels = channels;
	}

	/** Returns the measure position where this section starts. */
	public int getPosition() { return position; }

	/** Returns the length of this section in measures. */
	public int getLength() { return length; }

	/**
	 * Returns the channel section for the given channel.
	 *
	 * @param channel the channel whose section to retrieve
	 * @return the corresponding {@link ChannelSection}
	 */
	public ChannelSection getChannelSection(ChannelInfo channel) { return channels.get(channel.getPatternChannel()); }

	/**
	 * Creates a scene section by supplying channel sections via the given factory.
	 *
	 * @param position the starting measure position
	 * @param length   the section length in measures
	 * @param channels the number of channels
	 * @param supply   factory producing channel sections
	 * @return a new scene section
	 */
	public static SceneSection createSection(int position, int length, int channels, Supplier<ChannelSection> supply) {
		return new SceneSection(position, length, IntStream.range(0, channels)
				.mapToObj(i -> supply.get()).collect(Collectors.toList()));
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		Destroyable.destroy(channels);
		channels = null;
	}
}
