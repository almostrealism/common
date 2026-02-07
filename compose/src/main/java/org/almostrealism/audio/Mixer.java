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

package org.almostrealism.audio;

import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.graph.CollectionCachedStateCell;
import org.almostrealism.graph.SummationCell;

public class Mixer implements CellFeatures {
	private final SummationCell[] channels;
	private final CellList cells;

	public Mixer() { this(24); }

	public Mixer(int channelCount) {
		this.channels = new SummationCell[channelCount];
		this.cells = cells(channelCount, i -> {
			channels[i] = new SummationCell();
			return channels[i];
		}).sum();
	}

	public int getChannelCount() { return channels.length; }

	public CollectionCachedStateCell getChannel(int i) {
		return channels[i];
	}

	public CellList getCells() { return cells; }

	public SummationCell getOutput() { return (SummationCell) cells.get(0); }

	public BufferedOutputScheduler buffer(OutputLine out) {
		return getCells().buffer(out);
	}
}
