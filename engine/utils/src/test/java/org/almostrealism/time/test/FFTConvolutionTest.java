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

package org.almostrealism.time.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for {@link TemporalFeatures#fftConvolve} FFT-based convolution.
 */
public class FFTConvolutionTest extends TestSuiteBase implements TemporalFeatures, TestFeatures {

	/** Tolerance for floating-point comparisons. */
	private static final double TOLERANCE = 1e-6;

	/**
	 * Reference direct convolution implementation for comparison.
	 */
	protected double[] directConvolve(double[] signal, double[] kernel) {
		int outputLength = signal.length + kernel.length - 1;
		double[] result = new double[outputLength];

		for (int n = 0; n < outputLength; n++) {
			for (int k = 0; k < kernel.length; k++) {
				int signalIdx = n - k;
				if (signalIdx >= 0 && signalIdx < signal.length) {
					result[n] += signal[signalIdx] * kernel[k];
				}
			}
		}
		return result;
	}

	/**
	 * Test basic FFT convolution against direct convolution.
	 */
	@Test(timeout = 120000)
	public void testBasicConvolution() {
		PackedCollection signal = pack(1.0, 2.0, 3.0, 4.0, 5.0);
		PackedCollection kernel = pack(1.0, 0.0, -1.0);

		// Direct convolution for reference
		double[] expected = directConvolve(signal.toArray(), kernel.toArray());

		CollectionProducer fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.evaluate();

		// Verify output length
		assertEquals("Output length should be signal + kernel - 1",
				expected.length, result.getShape().getTotalSize());

		// Compare results
		for (int i = 0; i < expected.length; i++) {
			assertEquals("Convolution result at index " + i, expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution with delta function (identity).
	 */
	@Test(timeout = 120000)
	public void testDeltaKernel() {
		PackedCollection signal = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
		double[] signalArray = signal.toArray();

		// Delta function kernel
		PackedCollection kernel = pack(1.0);

		CollectionProducer fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.evaluate();

		// Convolving with delta should return the original signal
		assertEquals("Output length", signalArray.length, result.getShape().getTotalSize());
		for (int i = 0; i < signalArray.length; i++) {
			assertEquals("Result should match signal at " + i, signalArray[i], result.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution commutativity: signal * kernel = kernel * signal
	 */
	@Test(timeout = 120000)
	public void testCommutativity() {
		PackedCollection a = pack(1.0, 2.0, 3.0, 4.0);
		PackedCollection b = pack(0.5, -0.5, 0.5);

		// a * b
		PackedCollection result1 = fftConvolve(cp(a), cp(b)).evaluate();

		// b * a
		PackedCollection result2 = fftConvolve(cp(b), cp(a)).evaluate();

		assertEquals("Output lengths should match", result1.getShape().getTotalSize(), result2.getShape().getTotalSize());

		for (int i = 0; i < result1.getShape().getTotalSize(); i++) {
			assertEquals("Convolution should be commutative at " + i,
					result1.toDouble(i), result2.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution with all-zeros kernel.
	 */
	@Test(timeout = 120000)
	public void testZeroKernel() {
		int signalLength = 10;
		int kernelLength = 5;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		integers(1, signalLength + 1).into(signal.traverseEach()).evaluate();

		PackedCollection kernel = new PackedCollection(shape(kernelLength));
		// All zeros by default

		CollectionProducer fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.evaluate();

		// Convolving with zeros should give all zeros
		for (int i = 0; i < result.getShape().getTotalSize(); i++) {
			assertEquals("Result should be zero at " + i, 0.0, result.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution with moving average kernel.
	 */
	@Test(timeout = 120000)
	public void testMovingAverageKernel() {
		PackedCollection signal = pack(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);

		// 4-point moving average kernel
		PackedCollection kernel = pack(0.25, 0.25, 0.25, 0.25);

		double[] expected = directConvolve(signal.toArray(), kernel.toArray());

		PackedCollection result = fftConvolve(cp(signal), cp(kernel)).evaluate();

		for (int i = 0; i < expected.length; i++) {
			assertEquals("Moving average result at " + i, expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution output shape calculation.
	 */
	@Test(timeout = 120000)
	public void testOutputShape() {
		int signalLength = 100;
		int kernelLength = 25;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		PackedCollection kernel = new PackedCollection(shape(kernelLength));

		CollectionProducer fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.evaluate();

		assertEquals("Output length should be signal + kernel - 1",
				signalLength + kernelLength - 1, result.getShape().getTotalSize());
	}

	/**
	 * Test convolution output shape for various input sizes.
	 */
	@Test(timeout = 120000)
	public void testOutputShapeVariousSizes() {
		// Test multiple input size combinations
		int[][] testCases = {
			{100, 50},
			{64, 16},
			{256, 32},
			{1, 1}
		};

		for (int[] testCase : testCases) {
			int signalLength = testCase[0];
			int kernelLength = testCase[1];

			PackedCollection signal = new PackedCollection(shape(signalLength));
			PackedCollection kernel = new PackedCollection(shape(kernelLength));

			PackedCollection result = fftConvolve(cp(signal), cp(kernel)).evaluate();

			assertEquals("Output length for " + signalLength + " * " + kernelLength,
					signalLength + kernelLength - 1, result.getShape().getTotalSize());
		}
	}

	/**
	 * Test convolution with impulse response (single delayed impulse).
	 */
	@Test(timeout = 120000)
	public void testDelayedImpulse() {
		PackedCollection signal = pack(1.0, 2.0, 3.0, 4.0, 5.0);
		double[] signalArray = signal.toArray();
		int delay = 3;

		// Create delayed impulse: [0, 0, 0, 1]
		PackedCollection kernel = new PackedCollection(shape(delay + 1));
		kernel.setMem(delay, 1.0);

		PackedCollection result = fftConvolve(cp(signal), cp(kernel)).evaluate();

		// Result should be signal delayed by 'delay' samples
		for (int i = 0; i < delay; i++) {
			assertEquals("Zeros before signal at " + i, 0.0, result.toDouble(i), TOLERANCE);
		}
		for (int i = 0; i < signalArray.length; i++) {
			assertEquals("Delayed signal at " + (i + delay), signalArray[i], result.toDouble(i + delay), TOLERANCE);
		}
	}

	/**
	 * Test larger convolution for performance verification.
	 */
	@Test(timeout = 300000)
	public void testLargerConvolution() {
		int signalLength = 1024;
		int kernelLength = 64;

		// Generate random-ish signal
		PackedCollection signal = new PackedCollection(shape(signalLength));
		sin(integers(0, signalLength).multiply(2.0 * Math.PI / 128.0))
				.into(signal.traverseEach()).evaluate();

		// Generate impulse response kernel
		PackedCollection kernel = new PackedCollection(shape(kernelLength));
		exp(integers(0, kernelLength).multiply(-1.0 / 10.0))
				.multiply(cos(integers(0, kernelLength).multiply(Math.PI / 8.0)))
				.into(kernel.traverseEach()).evaluate();

		CollectionProducer fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.evaluate();

		assertEquals("Output length", signalLength + kernelLength - 1, result.getShape().getTotalSize());

		// Just verify it produces non-trivial output
		boolean hasNonZero = false;
		for (int i = 0; i < result.getShape().getTotalSize(); i++) {
			if (Math.abs(result.toDouble(i)) > 1e-10) {
				hasNonZero = true;
				break;
			}
		}
		assertTrue("Result should have non-zero values", hasNonZero);
	}
}
