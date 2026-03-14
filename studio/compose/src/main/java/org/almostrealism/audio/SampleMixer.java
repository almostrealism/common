/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio;

import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.graph.temporal.WaveCell;

import java.util.function.IntFunction;

public class SampleMixer implements CellFeatures {
	private final Mixer mixer;
	private WaveCell[] samples;

	public SampleMixer(int channels) {
		this.mixer = new Mixer(channels);
	}

	public void init(IntFunction<WaveCell> factory) {
		this.samples = new WaveCell[mixer.getChannelCount()];

		for (int i = 0; i < samples.length; i++) {
			samples[i] = factory.apply(i);
			samples[i].setReceptor(mixer.getChannel(i));
		}
	}

	public void setFrame(double frame) {
		for (WaveCell sample : samples) {
			sample.getClock().setFrame(frame);
		}
	}

	public WaveCell getSample(int index) { return samples[index]; }

	public Mixer getChannelMixer() { return mixer; }
	public int getChannelCount() { return getChannelMixer().getChannelCount(); }

	public CellList toCellList() {
		CellList sampler = cells(samples.length, i -> samples[i]);
		return mixer.getCells().addRequirements(sampler);
	}

	public BufferedOutputScheduler buffer(OutputLine out) {
		if (samples == null) {
			throw new UnsupportedOperationException("No samples have been initialized");
		}

		return mixer.buffer(out);
	}
}
