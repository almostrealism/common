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

package org.almostrealism.studio;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.CellFeatures;

import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.graph.temporal.WaveCell;

import java.util.function.IntFunction;

/**
 * Multi-channel sample mixer that combines an array of {@link WaveCell} audio sources
 * through a shared {@link Mixer}. Each channel is initialized with a factory function
 * that constructs the wave cell and wires it to the corresponding mixer input.
 *
 * <p>Audio delivery is performed via the underlying {@link Mixer}, which accumulates
 * all channel contributions and delivers them to a {@link BufferedOutputScheduler}.</p>
 */
public class SampleMixer implements CellFeatures {
	/** The underlying multi-channel mixer that accumulates all sample contributions. */
	private final Mixer mixer;

	/** Per-channel wave cells providing the audio data for each channel. */
	private WaveCell[] samples;

	/**
	 * Creates a {@code SampleMixer} with the specified number of channels.
	 *
	 * @param channels the number of sample channels
	 */
	public SampleMixer(int channels) {
		this.mixer = new Mixer(channels);
	}

	/**
	 * Initializes all sample channels using the provided factory function. The factory
	 * is called once per channel index and the resulting {@link WaveCell} is connected
	 * to the corresponding mixer channel receptor.
	 *
	 * @param factory a function that constructs a {@link WaveCell} for each channel index
	 */
	public void init(IntFunction<WaveCell> factory) {
		this.samples = new WaveCell[mixer.getChannelCount()];

		for (int i = 0; i < samples.length; i++) {
			samples[i] = factory.apply(i);
			samples[i].setReceptor(mixer.getChannel(i));
		}
	}

	/**
	 * Seeks all sample channels to the specified frame position.
	 *
	 * @param frame the target sample frame position
	 */
	public void setFrame(double frame) {
		for (WaveCell sample : samples) {
			sample.getClock().setFrame(frame);
		}
	}

	/**
	 * Returns the {@link WaveCell} for the specified channel index.
	 *
	 * @param index the zero-based channel index
	 * @return the wave cell for that channel
	 */
	public WaveCell getSample(int index) { return samples[index]; }

	/**
	 * Returns the underlying {@link Mixer} that accumulates all channel outputs.
	 *
	 * @return the channel mixer
	 */
	public Mixer getChannelMixer() { return mixer; }

	/**
	 * Returns the number of channels in this sample mixer.
	 *
	 * @return the channel count
	 */
	public int getChannelCount() { return getChannelMixer().getChannelCount(); }

	/**
	 * Builds and returns a {@link CellList} that combines the sample cells with the
	 * mixer's cell list via dependency requirements.
	 *
	 * @return the combined cell list for this sample mixer
	 */
	public CellList toCellList() {
		CellList sampler = cells(samples.length, i -> samples[i]);
		return mixer.getCells().addRequirements(sampler);
	}

	/**
	 * Delivers the mixed audio output to the specified output line via buffered scheduling.
	 * Throws {@link UnsupportedOperationException} if {@link #init} has not been called.
	 *
	 * @param out the audio output line to deliver audio to
	 * @return a {@link BufferedOutputScheduler} that drives the output
	 */
	public BufferedOutputScheduler buffer(OutputLine out) {
		if (samples == null) {
			throw new UnsupportedOperationException("No samples have been initialized");
		}

		return mixer.buffer(out);
	}
}
