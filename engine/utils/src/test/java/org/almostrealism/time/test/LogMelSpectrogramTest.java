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

package org.almostrealism.time.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.WindowComputation;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for the centered log-mel front end of {@link TemporalFeatures}:
 * {@link TemporalFeatures#reflectPad}, the {@link WindowComputation.Type#HANN_PERIODIC periodic Hann}
 * window, {@link TemporalFeatures#htkMelFilterbank} and
 * {@link TemporalFeatures#logMelSpectrogram}. Each is compared against a direct double-precision
 * evaluation of its definition (the definition {@code torchaudio} implements), computed here in the
 * test as the reference.
 */
public class LogMelSpectrogramTest extends TestSuiteBase implements TemporalFeatures, TestFeatures {

	/** Reflect padding mirrors the signal about its end samples without repeating them. */
	@Test(timeout = 60000)
	public void reflectPadMirrorsAboutTheEndSamples() {
		PackedCollection signal = pack(1, 2, 3, 4, 5);
		PackedCollection padded = reflectPad(cp(signal), 3).evaluate();
		double[] expected = {4, 3, 2, 1, 2, 3, 4, 5, 4, 3, 2};
		assertEquals(expected.length, padded.getShape().getTotalSize());
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], padded.toDouble(i), 1e-9);
		}
	}

	/** The periodic Hann window divides by the window length, not by the length minus one. */
	@Test(timeout = 60000)
	public void periodicHannDividesByTheLength() {
		int size = 16;
		PackedCollection window = window(WindowComputation.Type.HANN_PERIODIC, size).evaluate();
		for (int n = 0; n < size; n++) {
			assertEquals(0.5 * (1 - Math.cos(2 * Math.PI * n / size)), window.toDouble(n), 1e-6);
		}
	}

	/** The HTK filterbank matches its continuous triangle definition at every bin. */
	@Test(timeout = 60000)
	public void htkFilterbankMatchesItsDefinition() {
		int fftSize = 64;
		int sampleRate = 8000;
		int bands = 10;
		PackedCollection bank = htkMelFilterbank(fftSize, sampleRate, bands, 50, 3500).evaluate();
		double[][] expected = referenceFilterbank(fftSize, sampleRate, bands, 50, 3500);
		assertEquals((fftSize / 2 + 1) * bands, bank.getShape().getTotalSize());
		for (int k = 0; k < expected.length; k++) {
			for (int m = 0; m < bands; m++) {
				assertEquals("bin " + k + " band " + m, expected[k][m], bank.toDouble(k * bands + m), 1e-5);
			}
		}
	}

	/** The log-mel spectrogram of a two-tone signal matches a direct DFT evaluation of every frame. */
	@Test(timeout = 120000)
	public void logMelMatchesDirectEvaluation() {
		int fftSize = 64;
		int hop = 16;
		int sampleRate = 8000;
		int bands = 12;
		int length = 300;

		PackedCollection signal = sin(integers(0, length).multiply(2 * Math.PI * 440 / sampleRate)).multiply(0.6)
				.add(sin(integers(0, length).multiply(2 * Math.PI * 1900 / sampleRate).add(0.4)).multiply(0.3))
				.evaluate();
		double[] samples = new double[length];
		for (int i = 0; i < length; i++) {
			samples[i] = signal.toDouble(i);
		}

		PackedCollection mel = logMelSpectrogram(fftSize, hop, sampleRate, bands, 50, 4000, cp(signal)).evaluate();

		double[][] expected = referenceLogMel(samples, fftSize, hop, sampleRate, bands, 50, 4000);
		assertEquals(expected.length * bands, mel.getShape().getTotalSize());
		for (int t = 0; t < expected.length; t++) {
			for (int m = 0; m < bands; m++) {
				assertEquals("frame " + t + " band " + m, expected[t][m], mel.toDouble(t * bands + m), 2e-3);
			}
		}
	}

	/**
	 * The reference filterbank, {@code [bins][bands]}, evaluated in double precision.
	 *
	 * @param fftSize    the FFT size
	 * @param sampleRate the sample rate
	 * @param bands      the number of bands
	 * @param fMin       the lowest mel point
	 * @param fMax       the highest mel point
	 * @return the weights
	 */
	private double[][] referenceFilterbank(int fftSize, int sampleRate, int bands, double fMin, double fMax) {
		int bins = fftSize / 2 + 1;
		double melMin = hzToMel(fMin);
		double step = (hzToMel(fMax) - melMin) / (bands + 1);
		double[] points = new double[bands + 2];
		for (int p = 0; p < points.length; p++) {
			points[p] = melToHz(melMin + p * step);
		}

		double[][] weights = new double[bins][bands];
		for (int k = 0; k < bins; k++) {
			double f = k * (double) (sampleRate / 2) / (bins - 1);
			for (int m = 0; m < bands; m++) {
				double rising = (f - points[m]) / (points[m + 1] - points[m]);
				double falling = (points[m + 2] - f) / (points[m + 2] - points[m + 1]);
				weights[k][m] = Math.max(0, Math.min(rising, falling));
			}
		}
		return weights;
	}

	/**
	 * The reference log-mel spectrogram, {@code [frames][bands]}: reflect padding, a periodic Hann
	 * window, a direct DFT of each frame, the HTK filterbank and the decibel conversion.
	 *
	 * @param samples    the signal
	 * @param fftSize    the FFT size
	 * @param hop        the hop size
	 * @param sampleRate the sample rate
	 * @param bands      the number of bands
	 * @param fMin       the lowest mel point
	 * @param fMax       the highest mel point
	 * @return the spectrogram in decibels
	 */
	private double[][] referenceLogMel(double[] samples, int fftSize, int hop, int sampleRate,
									   int bands, double fMin, double fMax) {
		int n = samples.length;
		int pad = fftSize / 2;
		double[] padded = new double[n + 2 * pad];
		for (int j = 0; j < padded.length; j++) {
			int offset = Math.abs(j - pad);
			padded[j] = samples[(n - 1) - Math.abs((n - 1) - offset)];
		}

		double[][] bank = referenceFilterbank(fftSize, sampleRate, bands, fMin, fMax);
		int frames = 1 + n / hop;
		int bins = fftSize / 2 + 1;
		double[][] result = new double[frames][bands];
		for (int t = 0; t < frames; t++) {
			double[] power = new double[bins];
			for (int k = 0; k < bins; k++) {
				double re = 0;
				double im = 0;
				for (int j = 0; j < fftSize; j++) {
					double w = 0.5 * (1 - Math.cos(2 * Math.PI * j / fftSize));
					double x = padded[t * hop + j] * w;
					re += x * Math.cos(2 * Math.PI * k * j / fftSize);
					im -= x * Math.sin(2 * Math.PI * k * j / fftSize);
				}
				power[k] = re * re + im * im;
			}

			for (int m = 0; m < bands; m++) {
				double energy = 0;
				for (int k = 0; k < bins; k++) {
					energy += power[k] * bank[k][m];
				}
				result[t][m] = 10 * Math.log10(Math.max(energy, 1e-10));
			}
		}
		return result;
	}
}
