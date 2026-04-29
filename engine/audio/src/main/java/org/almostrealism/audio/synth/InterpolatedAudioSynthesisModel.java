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

/**
 * An {@link AudioSynthesisModel} that provides time-varying amplitude levels for different
 * frequency ratios by interpolating from pre-computed FFT analysis data. Typically created
 * from recorded audio samples to synthesize realistic instrument sounds.
 *
 * @see AudioSynthesisModel
 * @see NoteAudio
 * @see WaveData
 */
public class InterpolatedAudioSynthesisModel implements AudioSynthesisModel, CellFeatures {
	/** Ordered frequency ratio values corresponding to each row of the level data. */
	private final double[] frequencyRatios;

	/** Audio sample rate in Hz used for interpolation timing. */
	private final double sampleRate;

	/** 2D packed collection (frequencyRatios.length x samples) of FFT-derived level values. */
	private final PackedCollection levelData;

	/** Number of time samples in each row of the level data matrix. */
	private final int samples;

	/**
	 * Creates an InterpolatedAudioSynthesisModel from pre-computed frequency data.
	 *
	 * @param frequencyRatios sorted array of frequency ratio values; length determines the first dimension of levelData
	 * @param sampleRate      audio sample rate in Hz
	 * @param levelData       2D packed collection with shape [frequencyRatios.length, samples]
	 * @throws IllegalArgumentException if levelData dimensions do not match frequencyRatios length
	 */
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

		for (int i = 0; i < frequencyRatios.length; i++) {
			if (frequencyRatio > frequencyRatios[i]) {
				left = i;
			}
		}

		// TODO  Mix this value from left with value from right
		return interpolate(
				traverse(0, cp(levelData.range(shape(samples), left * samples))),
				traverse(1, time), sampleRate);
	}

	/**
	 * Creates an InterpolatedAudioSynthesisModel by performing FFT analysis on the given NoteAudio.
	 * The model captures harmonic levels across the frequency range, normalized relative to
	 * the fundamental frequency of the root note.
	 *
	 * @param audio  the source audio used for spectral analysis
	 * @param root   the root key position whose audio is analyzed
	 * @param tuning the keyboard tuning used to determine the fundamental frequency
	 * @return a new model built from the spectral data
	 */
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
