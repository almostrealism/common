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

package org.almostrealism.util;

import org.almostrealism.collect.PackedCollection;


/**
 * Shared test utilities for FIR filter and convolution tests.
 *
 * <p>Provides reference implementations of signal generation, low-pass FIR
 * coefficient computation, and centered convolution for verifying hardware-
 * accelerated filter implementations.</p>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @see org.almostrealism.time.computations.test.MultiOrderFilterConvolutionTest
 * @see io.almostrealism.compute.test.ReplicationMismatchOptimizationTest
 */
public interface FirFilterTestFeatures extends TestFeatures {

	/**
	 * Reference implementation of low-pass FIR coefficient computation
	 * using sinc-windowed Hamming window.
	 *
	 * <p>The arithmetic is deliberately performed on the host: this is the oracle the
	 * framework's own coefficient computation is checked against, and expressing it
	 * with the same producers it is meant to test would let a fault agree with
	 * itself. Only the result is a collection, which is the form every caller needs.</p>
	 *
	 * @param cutoff the cutoff frequency in Hz
	 * @param sampleRate the sample rate in Hz
	 * @param filterOrder the filter order (number of taps minus one)
	 * @return the computed FIR coefficients
	 */
	default PackedCollection referenceLowPassCoefficients(double cutoff, int sampleRate, int filterOrder) {
		double[] coefficients = new double[filterOrder + 1];
		double normalizedCutoff = 2.0 * cutoff / sampleRate;

		for (int i = 0; i <= filterOrder; i++) {
			if (i == filterOrder / 2) {
				coefficients[i] = normalizedCutoff;
			} else {
				int k = i - filterOrder / 2;
				coefficients[i] = Math.sin(Math.PI * k * normalizedCutoff) / (Math.PI * k);
			}
			coefficients[i] *= 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / filterOrder);
		}

		return PackedCollection.of(coefficients);
	}

	/**
	 * Reference implementation of centered FIR convolution for test verification.
	 *
	 * <p>As with {@link #referenceLowPassCoefficients}, the convolution itself is
	 * host arithmetic on purpose — it exists to disagree with the framework when
	 * the framework is wrong.</p>
	 *
	 * @param signal the input signal
	 * @param coefficients the FIR filter coefficients
	 * @return the convolved output signal
	 */
	default PackedCollection referenceConvolve(PackedCollection signal, PackedCollection coefficients) {
		int length = signal.getMemLength();
		int order = coefficients.getMemLength() - 1;

		double[] in = signal.toArray(0, length);
		double[] taps = coefficients.toArray(0, order + 1);
		double[] output = new double[length];

		for (int n = 0; n < length; n++) {
			double sum = 0.0;
			for (int k = 0; k <= order; k++) {
				int idx = n + k - order / 2;
				if (idx >= 0 && idx < length) {
					sum += in[idx] * taps[k];
				}
			}
			output[n] = sum;
		}

		return PackedCollection.of(output);
	}

	/**
	 * Computes the sum-of-squares energy of a signal, skipping the first and last
	 * {@code skip} samples to avoid FIR filter edge effects.
	 *
	 * @param signal the signal samples
	 * @param skip   number of samples to skip at each end
	 * @return the sum of squared sample values in the interior region
	 */
	default double energy(double[] signal, int skip) {
		double sum = 0.0;
		for (int i = skip; i < signal.length - skip; i++) {
			sum += signal[i] * signal[i];
		}
		return sum;
	}

	/**
	 * Returns the peak absolute value of a signal.
	 *
	 * @param samples the signal samples
	 * @return the maximum absolute sample value
	 */
	default double peakOf(double[] samples) {
		double peak = 0.0;
		for (double v : samples) {
			double a = Math.abs(v);
			if (a > peak) peak = a;
		}
		return peak;
	}

	/**
	 * Converts a {@code float[]} array to a {@code double[]} array.
	 *
	 * @param input the float array to convert
	 * @return a new double array with the same values
	 */
	default double[] floatToDouble(float[] input) {
		double[] output = new double[input.length];
		for (int i = 0; i < input.length; i++) {
			output[i] = input[i];
		}
		return output;
	}

}
