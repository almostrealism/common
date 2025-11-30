/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.synth;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.notes.NoteAudio;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;

public class InterpolatedAudioSynthesisModel implements AudioSynthesisModel, CellFeatures {
	private final double[] frequencyRatios;
	private final double sampleRate;
	private final PackedCollection levelData;
	private final int samples;

	public InterpolatedAudioSynthesisModel(double[] frequencyRatios,
										   double sampleRate,
										   PackedCollection levelData) {
		this.frequencyRatios = frequencyRatios;
		this.sampleRate = sampleRate;
		this.levelData = levelData;

		if (levelData.getShape().getDimensions() != 2 ||
				levelData.getShape().length(0) != frequencyRatios.length) {
			throw new IllegalArgumentException();
		} else {
			this.samples = levelData.getShape().length(1);
		}
	}

	public double[] getFrequencyRatios() { return frequencyRatios; }

	public double getSampleRate() { return sampleRate; }

	public PackedCollection getLevelData() { return levelData; }

	@Override
	public Producer<PackedCollection> getLevels(double frequencyRatio,
												   Producer<PackedCollection> time) {
		int left = 0;
		int right = 0;

		for (int i = 0; i < frequencyRatios.length; i++) {
			if (frequencyRatio > frequencyRatios[i]) {
				left = i;
				right = i + 1;
			}
		}

		// TODO  Mix this value from left with value from right
		return interpolate(
				traverse(0, cp(levelData.range(shape(samples), left * samples))),
				traverse(1, time), sampleRate);
	}

	public static InterpolatedAudioSynthesisModel create(NoteAudio audio, KeyPosition<?> root, KeyboardTuning tuning) {
		PackedCollection frequencies = new WaveData(audio.getAudio(root, -1).evaluate(), audio.getSampleRate()).fft(-1, true);

		int samples = frequencies.getShape().length(0);
		int frequencyCount = frequencies.getShape().length(1);
		double frequencyMax = audio.getSampleRate() / (double) WaveData.FFT_BINS;
		double fftSampleRate = audio.getSampleRate() / (double) (WaveData.FFT_BINS * WaveData.FFT_POOL);

		// TODO Rescaling by the number of bins should not be necessary, but for the
		// TODO moment FourierTransform does not normalize the output on its own
		double scale = WaveData.FFT_BINS;
		frequencies.setMem(frequencies.doubleStream().map(l -> l / scale).toArray());
		frequencies = frequencies.reshape(new TraversalPolicy(samples, frequencyCount)).transpose();

		double fundamental = tuning.getTone(root).asHertz();
		double[] frequencyRatios = new double[frequencyCount];
		for (int i = 0; i < frequencyCount; i++) {
			frequencyRatios[i] = i * frequencyMax / fundamental;
		}

		return new InterpolatedAudioSynthesisModel(frequencyRatios, fftSampleRate, frequencies);
	}
}
