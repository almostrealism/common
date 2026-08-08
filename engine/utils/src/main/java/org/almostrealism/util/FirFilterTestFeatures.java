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

import java.util.function.IntFunction;


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
 * <p>This mixin is for tests only. It extends {@link TestFeatures}, so implementing it
 * pulls the whole test surface — assertions, depth and profile settings — onto the
 * implementing type. It lives in main sources solely so that tests in other modules can
 * reach it; that is not licence for production code to implement it. A production type
 * that needs one of these operations is evidence the operation belongs on a production
 * {@code Features} mixin instead, and it should be moved there rather than reached for
 * here.</p>
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
	 * @param signal the signal
	 * @param skip   number of samples to skip at each end
	 * @return the sum of squared sample values in the interior region
	 */
	default double energy(PackedCollection signal, int skip) {
		int interior = signal.getMemLength() - 2 * skip;
		if (interior <= 0) return 0.0;

		return sum(cp(signal.range(shape(interior), skip)).sq()).evaluate().toDouble(0);
	}

	/**
	 * Returns the peak absolute value of a signal.
	 *
	 * @param samples the signal
	 * @return the maximum absolute sample value
	 */
	default double peakOf(PackedCollection samples) {
		return max(cp(samples).abs()).evaluate().toDouble(0);
	}

	/**
	 * Assembles a signal from a sequence of fixed-length passes, each pass writing into
	 * the span it occupies.
	 *
	 * <p>The passes run in order and each is given the absolute sample offset it starts
	 * at, so a stateful model — one carrying a delay ring or a filter history between
	 * invocations — sees them in the order it would in a real render. Each pass's output
	 * is copied into the assembled signal where it belongs, so nothing leaves the device
	 * on the way and the whole signal is available for measurement afterwards.</p>
	 *
	 * @param numPasses  the number of passes to run
	 * @param signalSize the frames each pass produces
	 * @param pass       supplies one pass's output, given the offset it begins at
	 * @return the assembled signal, {@code numPasses * signalSize} frames
	 */
	default PackedCollection render(int numPasses, int signalSize,
									IntFunction<PackedCollection> pass) {
		PackedCollection signal = new PackedCollection(numPasses * signalSize);

		for (int p = 0; p < numPasses; p++) {
			int sampleOffset = p * signalSize;
			signal.setFrom(sampleOffset, pass.apply(sampleOffset).range(shape(signalSize)));
		}

		return signal;
	}

	/**
	 * Computes the sum-of-squares energy of the difference between two signals of
	 * equal length. Used to establish that one rendering differs from another.
	 *
	 * @param signal    the signal under test
	 * @param reference the signal to compare against
	 * @return the sum of squared differences
	 */
	default double differenceEnergy(PackedCollection signal, PackedCollection reference) {
		return sum(cp(signal).subtract(cp(reference)).sq()).evaluate().toDouble(0);
	}

	/**
	 * Sums a multi-channel signal down to mono, the channels being laid out as
	 * contiguous runs of equal length.
	 *
	 * @param signal   the multi-channel signal
	 * @param channels the number of channels
	 * @return the summed signal, one sample per channel position
	 */
	default PackedCollection sumChannels(PackedCollection signal, int channels) {
		int length = signal.getMemLength() / channels;
		return matmul(constant(shape(1, channels), 1.0),
				cp(signal.reshape(shape(channels, length)))).evaluate().reshape(shape(length));
	}

	/**
	 * Computes the sum-of-squares energy of each channel of a multi-channel signal,
	 * which is laid out as {@code channels} contiguous runs of equal length.
	 *
	 * @param signal   the multi-channel signal
	 * @param channels the number of channels
	 * @return the per-channel energies, of shape ({@code channels})
	 */
	default PackedCollection channelEnergy(PackedCollection signal, int channels) {
		int length = signal.getMemLength() / channels;
		return sum(cp(signal.reshape(shape(channels, length)).traverse(1)).sq())
				.evaluate().reshape(shape(channels));
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
