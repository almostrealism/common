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

package org.almostrealism.studio;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.CellFeatures;

import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.graph.CollectionCachedStateCell;
import org.almostrealism.graph.SummationCell;

/**
 * Multi-channel audio mixer that routes a fixed number of input channels into a single
 * summed output. Each channel is backed by a {@link SummationCell}, allowing independent
 * audio streams to be combined into a unified output {@link CellList}.
 */
public class Mixer implements CellFeatures {
	/** Per-channel summation cells that accumulate audio data from each input channel. */
	private final SummationCell[] channels;

	/** The output cell list that sums all channel contributions into a single stream. */
	private final CellList cells;

	/** Creates a {@code Mixer} with the default channel count (24). */
	public Mixer() { this(24); }

	/**
	 * Creates a {@code Mixer} with the specified number of channels.
	 *
	 * @param channelCount the number of independent mix channels
	 */
	public Mixer(int channelCount) {
		this.channels = new SummationCell[channelCount];
		this.cells = cells(channelCount, i -> {
			channels[i] = new SummationCell();
			return channels[i];
		}).sum();
	}

	/**
	 * Returns the number of input channels in this mixer.
	 *
	 * @return the channel count
	 */
	public int getChannelCount() { return channels.length; }

	/**
	 * Returns the {@link CollectionCachedStateCell} for the specified channel index.
	 *
	 * @param i the zero-based channel index
	 * @return the channel's summation cell
	 */
	public CollectionCachedStateCell getChannel(int i) {
		return channels[i];
	}

	/**
	 * Returns the summed output {@link CellList}.
	 *
	 * @return the cell list containing the mixed output
	 */
	public CellList getCells() { return cells; }

	/**
	 * Returns the final summed output cell.
	 *
	 * @return the output {@link SummationCell}
	 */
	public SummationCell getOutput() { return (SummationCell) cells.get(0); }

	/**
	 * Delivers the mixed audio output to the specified output line via buffered scheduling.
	 *
	 * @param out the audio output line
	 * @return a {@link BufferedOutputScheduler} that drives the output
	 */
	public BufferedOutputScheduler buffer(OutputLine out) {
		return getCells().buffer(out);
	}
}
