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

package org.almostrealism.studio.persistence.test.support;

import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataFeatureProvider;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Correlates windows of a signal against integer-Hz cosines, for tests that need
 * deterministic two-dimensional features from audio.
 *
 * <p>For window {@code f} and bin {@code b}, over {@code W} samples per window:</p>
 *
 * <pre>
 * energy[f][b] = sum over i of s[f*W + i] * cos(2*PI*(b+1)*(f*W + i) / rate)
 * out[f][b]    = abs(energy[f][b]) / W
 * </pre>
 *
 * <p>The phase is taken from the position of the sample in the whole signal rather
 * than its position within the window, so a window's result depends on where the
 * window begins. This differs from {@link LogSpectrumFeatureProvider}, whose bins
 * are spaced logarithmically and whose phase restarts at each window.</p>
 *
 * <p>{@link #referenceFeatures(WaveData)} is the host evaluation of the same
 * definition, kept as the oracle {@link #computeFeatures(WaveData)} is checked
 * against rather than as a second implementation for callers to choose between.</p>
 */
public class HarmonicEnergyFeatureProvider implements WaveDataFeatureProvider, CodeFeatures {
	/** Number of windows the signal is divided into. */
	private final int frames;

	/** Number of cosine bins per window. */
	private final int bins;

	/** Sample rate of the audio this provider is applied to. */
	private final int sampleRate;

	/** Duration in seconds the {@code frames} windows are expected to span. */
	private final double duration;

	/**
	 * Creates a provider producing {@code frames x bins} features.
	 *
	 * @param frames      number of windows
	 * @param bins        cosine bins per window
	 * @param sampleRate  sample rate of the audio
	 * @param duration    seconds the windows are expected to span
	 */
	public HarmonicEnergyFeatureProvider(int frames, int bins, int sampleRate, double duration) {
		this.frames = frames;
		this.bins = bins;
		this.sampleRate = sampleRate;
		this.duration = duration;
	}

	@Override
	public int getAudioSampleRate() { return sampleRate; }

	@Override
	public double getFeatureSampleRate() { return frames / duration; }

	/**
	 * The number of samples in each window, given the total length of the signal.
	 *
	 * @param totalFrames  length of the signal in samples
	 * @return the window size
	 */
	public int windowSize(int totalFrames) {
		return Math.max(1, totalFrames / frames);
	}

	@Override
	public PackedCollection computeFeatures(WaveData waveData) {
		PackedCollection data = waveData.getChannelData(0);
		int window = windowSize(waveData.getFrameCount());
		int analysed = frames * window;

		PackedCollection windows = data.range(shape(analysed), 0).reshape(frames, 1, window);

		// The sample's position in the whole signal, and the bin's harmonic, each
		// spread across the axes the other varies over
		CollectionProducer position = repeat(1, bins, integers(0, analysed).reshape(frames, 1, window));
		CollectionProducer harmonic = repeat(0, frames,
				repeat(2, window, integers(1, bins + 1).reshape(1, bins, 1)));

		CollectionProducer angle = position.multiply(harmonic).multiply(2.0 * Math.PI / sampleRate);
		CollectionProducer energy = repeat(1, bins, cp(windows)).multiply(cos(angle)).sum(2);

		return energy.abs().divide(window).evaluate().reshape(frames, bins);
	}

	/**
	 * Evaluates the same correlation on the host, one bin at a time.
	 *
	 * @param waveData  the audio to analyse
	 * @return the features in row-major {@code frames x bins} order
	 */
	public double[] referenceFeatures(WaveData waveData) {
		int totalFrames = waveData.getFrameCount();
		int window = windowSize(totalFrames);

		PackedCollection data = waveData.getChannelData(0);
		double[] samples = data.toArray(0, Math.min(totalFrames, data.getMemLength()));
		double[] values = new double[frames * bins];

		for (int f = 0; f < frames; f++) {
			int start = f * window;

			for (int b = 0; b < bins; b++) {
				double energy = 0;

				for (int i = start; i < Math.min(start + window, samples.length); i++) {
					energy += samples[i] * Math.cos(2.0 * Math.PI * (b + 1) * i / sampleRate);
				}

				values[f * bins + b] = Math.abs(energy) / window;
			}
		}

		return values;
	}
}
