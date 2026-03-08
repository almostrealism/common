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

import java.util.function.IntToDoubleFunction;

/**
 * Shared test utilities for FIR filter and convolution tests.
 *
 * <p>Provides reference implementations of signal generation, low-pass FIR
 * coefficient computation, and centered convolution for verifying hardware-
 * accelerated filter implementations.</p>
 *
 * @see org.almostrealism.time.computations.test.MultiOrderFilterConvolutionTest
 * @see io.almostrealism.compute.test.ReplicationMismatchOptimizationTest
 */
public interface FirFilterTestFeatures extends TestFeatures {

	/**
	 * Creates a {@link PackedCollection} signal populated by the given generator function.
	 * Uses bulk {@code setMem(double[])} to avoid per-element mutation in a loop.
	 *
	 * @param size number of samples
	 * @param generator function from sample index to sample value
	 * @return a packed collection containing the generated signal
	 */
	default PackedCollection createSignal(int size, IntToDoubleFunction generator) {
		double[] data = new double[size];
		for (int i = 0; i < size; i++) {
			data[i] = generator.applyAsDouble(i);
		}
		PackedCollection signal = new PackedCollection(size);
		signal.setMem(data);
		return signal;
	}

	/**
	 * Reference implementation of low-pass FIR coefficient computation
	 * using sinc-windowed Hamming window.
	 *
	 * @param cutoff the cutoff frequency in Hz
	 * @param sampleRate the sample rate in Hz
	 * @param filterOrder the filter order (number of taps minus one)
	 * @return the computed FIR coefficients
	 */
	default double[] referenceLowPassCoefficients(double cutoff, int sampleRate, int filterOrder) {
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

		return coefficients;
	}

	/**
	 * Asserts that a convolution result matches the expected output element-by-element
	 * using hardware-precision tolerance from {@link TestFeatures#assertEquals(double, double)}.
	 *
	 * @param expected the expected output values
	 * @param result the actual convolution result
	 * @param length number of elements to compare
	 */
	default void assertConvolutionEquals(double[] expected, PackedCollection result, int length) {
		for (int i = 0; i < length; i++) {
			assertEquals(expected[i], result.toDouble(i));
		}
	}

	/**
	 * Reference implementation of centered FIR convolution for test verification.
	 *
	 * @param signal the input signal
	 * @param coefficients the FIR filter coefficients
	 * @return the convolved output signal
	 */
	default double[] referenceConvolve(double[] signal, double[] coefficients) {
		int order = coefficients.length - 1;
		double[] output = new double[signal.length];

		for (int n = 0; n < signal.length; n++) {
			double sum = 0.0;
			for (int k = 0; k <= order; k++) {
				int idx = n + k - order / 2;
				if (idx >= 0 && idx < signal.length) {
					sum += signal[idx] * coefficients[k];
				}
			}
			output[n] = sum;
		}

		return output;
	}
}
