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
 * Computes a log-spaced magnitude spectrum over fixed-size windows, for tests that
 * need deterministic multi-dimensional features from audio.
 *
 * <p>For window {@code f} and bin {@code b}, with {@code W} samples per window and a
 * bin frequency spaced logarithmically between 20Hz and Nyquist:</p>
 *
 * <pre>
 * real[f][b] = sum over i of s[f*W + i] * cos(2*PI*freq[b]*i / rate)
 * imag[f][b] = sum over i of s[f*W + i] * sin(2*PI*freq[b]*i / rate)
 * out[f][b]  = sqrt(real^2 + imag^2) / W
 * </pre>
 *
 * <p>{@link #computeFeatures(WaveData)} expresses this as a single computation.
 * {@link #referenceFeatures(WaveData)} is the equivalent host-side evaluation, kept
 * as the oracle the conversion is verified against rather than as a second
 * implementation for callers to choose between.</p>
 */
public class LogSpectrumFeatureProvider implements WaveDataFeatureProvider, CodeFeatures {
	/** Lowest bin frequency, in Hz. */
	public static final double MIN_FREQUENCY = 20.0;

	/** Number of windows the signal is divided into. */
	private final int frames;

	/** Number of frequency bins per window. */
	private final int bins;

	/** Sample rate of the audio this provider is applied to. */
	private final int sampleRate;

	/** Duration in seconds the {@code frames} windows are expected to span. */
	private final double duration;

	/**
	 * Creates a provider producing {@code frames x bins} features.
	 *
	 * @param frames      number of windows
	 * @param bins        frequency bins per window
	 * @param sampleRate  sample rate of the audio
	 * @param duration    seconds the windows are expected to span
	 */
	public LogSpectrumFeatureProvider(int frames, int bins, int sampleRate, double duration) {
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

	/**
	 * The frequency of each bin, spaced logarithmically from {@value #MIN_FREQUENCY}
	 * to Nyquist.
	 *
	 * @return a producer of shape {@code [bins]}
	 */
	public CollectionProducer binFrequencies() {
		double logMin = Math.log(MIN_FREQUENCY);
		double logMax = Math.log(sampleRate / 2.0);

		return exp(integers(0, bins)
				.divide(bins - 1.0)
				.multiply(logMax - logMin)
				.add(logMin));
	}

	@Override
	public PackedCollection computeFeatures(WaveData waveData) {
		int totalFrames = waveData.getFrameCount();
		int window = windowSize(totalFrames);

		PackedCollection windows = waveData.getChannelData(0)
				.range(shape(frames * window), 0)
				.reshape(frames, 1, window);

		// angle[b][i] = 2*PI*freq[b]*i / rate, as the outer product of the bin
		// frequencies with the sample offsets within a window
		CollectionProducer angle = repeat(1, window, binFrequencies().reshape(bins, 1))
				.multiply(repeat(0, bins, integers(0, window).reshape(1, window)))
				.multiply(2.0 * Math.PI / sampleRate)
				.reshape(1, bins, window);

		CollectionProducer samples = repeat(1, bins, cp(windows));
		CollectionProducer real = samples.multiply(repeat(0, frames, cos(angle))).sum(2);
		CollectionProducer imaginary = samples.multiply(repeat(0, frames, sin(angle))).sum(2);

		return real.pow(2.0).add(imaginary.pow(2.0)).sqrt()
				.divide(window)
				.evaluate().reshape(frames, bins, 1);
	}

	/**
	 * Evaluates the same spectrum on the host, one bin at a time.
	 *
	 * <p>Retained so {@link #computeFeatures(WaveData)} can be checked against it.
	 * It is not an alternative for callers: it reads every sample back individually.</p>
	 *
	 * @param waveData  the audio to analyse
	 * @return the features in row-major {@code frames x bins} order
	 */
	public double[] referenceFeatures(WaveData waveData) {
		int totalFrames = waveData.getFrameCount();
		int window = windowSize(totalFrames);

		PackedCollection data = waveData.getChannelData(0);
		double[] samples = data.toArray(0, Math.min(totalFrames, data.getMemLength()));

		double logMin = Math.log(MIN_FREQUENCY);
		double logMax = Math.log(sampleRate / 2.0);
		double[] values = new double[frames * bins];

		for (int f = 0; f < frames; f++) {
			int start = f * window;
			int len = Math.min(start + window, samples.length) - start;

			for (int b = 0; b < bins; b++) {
				double freq = Math.exp(logMin + ((double) b / (bins - 1)) * (logMax - logMin));
				double real = 0;
				double imaginary = 0;

				for (int i = 0; i < len; i++) {
					double a = 2.0 * Math.PI * freq * i / sampleRate;
					real += samples[start + i] * Math.cos(a);
					imaginary += samples[start + i] * Math.sin(a);
				}

				values[f * bins + b] = Math.sqrt(real * real + imaginary * imaginary) / len;
			}
		}

		return values;
	}
}
