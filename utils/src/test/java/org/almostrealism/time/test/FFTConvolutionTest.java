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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.FFTConvolution;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for {@link FFTConvolution} FFT-based convolution computation.
 */
public class FFTConvolutionTest extends TestSuiteBase implements TemporalFeatures, TestFeatures {

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
	@Test
	public void testBasicConvolution() {
		double[] signalArray = {1, 2, 3, 4, 5};
		double[] kernelArray = {1, 0, -1};

		// Direct convolution for reference
		double[] expected = directConvolve(signalArray, kernelArray);

		// FFT convolution
		PackedCollection signal = new PackedCollection(shape(signalArray.length));
		for (int i = 0; i < signalArray.length; i++) {
			signal.setMem(i, signalArray[i]);
		}

		PackedCollection kernel = new PackedCollection(shape(kernelArray.length));
		for (int i = 0; i < kernelArray.length; i++) {
			kernel.setMem(i, kernelArray[i]);
		}

		FFTConvolution fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.get().evaluate();

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
	@Test
	public void testDeltaKernel() {
		double[] signalArray = {1, 2, 3, 4, 5, 6, 7, 8};
		double[] kernelArray = {1};  // Delta function

		PackedCollection signal = new PackedCollection(shape(signalArray.length));
		for (int i = 0; i < signalArray.length; i++) {
			signal.setMem(i, signalArray[i]);
		}

		PackedCollection kernel = new PackedCollection(shape(kernelArray.length));
		kernel.setMem(0, 1.0);

		FFTConvolution fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.get().evaluate();

		// Convolving with delta should return the original signal
		assertEquals("Output length", signalArray.length, result.getShape().getTotalSize());
		for (int i = 0; i < signalArray.length; i++) {
			assertEquals("Result should match signal at " + i, signalArray[i], result.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution commutativity: signal * kernel = kernel * signal
	 */
	@Test
	public void testCommutativity() {
		double[] aArray = {1, 2, 3, 4};
		double[] bArray = {0.5, -0.5, 0.5};

		PackedCollection a = new PackedCollection(shape(aArray.length));
		for (int i = 0; i < aArray.length; i++) {
			a.setMem(i, aArray[i]);
		}

		PackedCollection b = new PackedCollection(shape(bArray.length));
		for (int i = 0; i < bArray.length; i++) {
			b.setMem(i, bArray[i]);
		}

		// a * b
		PackedCollection result1 = fftConvolve(cp(a), cp(b)).get().evaluate();

		// b * a
		PackedCollection result2 = fftConvolve(cp(b), cp(a)).get().evaluate();

		assertEquals("Output lengths should match", result1.getShape().getTotalSize(), result2.getShape().getTotalSize());

		for (int i = 0; i < result1.getShape().getTotalSize(); i++) {
			assertEquals("Convolution should be commutative at " + i,
					result1.toDouble(i), result2.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution with all-zeros kernel.
	 */
	@Test
	public void testZeroKernel() {
		int signalLength = 10;
		int kernelLength = 5;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, i + 1.0);
		}

		PackedCollection kernel = new PackedCollection(shape(kernelLength));
		// All zeros by default

		FFTConvolution fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.get().evaluate();

		// Convolving with zeros should give all zeros
		for (int i = 0; i < result.getShape().getTotalSize(); i++) {
			assertEquals("Result should be zero at " + i, 0.0, result.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution with moving average kernel.
	 */
	@Test
	public void testMovingAverageKernel() {
		double[] signalArray = {1, 1, 1, 1, 1, 1, 1, 1};
		double[] kernelArray = {0.25, 0.25, 0.25, 0.25};  // 4-point moving average

		double[] expected = directConvolve(signalArray, kernelArray);

		PackedCollection signal = new PackedCollection(shape(signalArray.length));
		for (int i = 0; i < signalArray.length; i++) {
			signal.setMem(i, signalArray[i]);
		}

		PackedCollection kernel = new PackedCollection(shape(kernelArray.length));
		for (int i = 0; i < kernelArray.length; i++) {
			kernel.setMem(i, kernelArray[i]);
		}

		PackedCollection result = fftConvolve(cp(signal), cp(kernel)).get().evaluate();

		for (int i = 0; i < expected.length; i++) {
			assertEquals("Moving average result at " + i, expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test convolution output shape calculation.
	 */
	@Test
	public void testOutputShape() {
		int signalLength = 100;
		int kernelLength = 25;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		PackedCollection kernel = new PackedCollection(shape(kernelLength));

		FFTConvolution fftConv = fftConvolve(cp(signal), cp(kernel));

		assertEquals("Output length should be signal + kernel - 1",
				signalLength + kernelLength - 1, fftConv.getOutputLength());
	}

	/**
	 * Test FFT size is power of 2.
	 */
	@Test
	public void testFFTSizeIsPowerOfTwo() {
		int signalLength = 100;
		int kernelLength = 50;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		PackedCollection kernel = new PackedCollection(shape(kernelLength));

		FFTConvolution fftConv = fftConvolve(cp(signal), cp(kernel));

		int fftSize = fftConv.getFftSize();
		assertTrue("FFT size should be power of 2", (fftSize & (fftSize - 1)) == 0);
		assertTrue("FFT size should be >= output length", fftSize >= fftConv.getOutputLength());
	}

	/**
	 * Test convolution with impulse response (single delayed impulse).
	 */
	@Test
	public void testDelayedImpulse() {
		double[] signalArray = {1, 2, 3, 4, 5};
		int delay = 3;

		// Create delayed impulse: [0, 0, 0, 1]
		PackedCollection signal = new PackedCollection(shape(signalArray.length));
		for (int i = 0; i < signalArray.length; i++) {
			signal.setMem(i, signalArray[i]);
		}

		PackedCollection kernel = new PackedCollection(shape(delay + 1));
		kernel.setMem(delay, 1.0);

		PackedCollection result = fftConvolve(cp(signal), cp(kernel)).get().evaluate();

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
	@Test
	public void testLargerConvolution() {
		int signalLength = 1024;
		int kernelLength = 64;

		// Generate random-ish signal
		PackedCollection signal = new PackedCollection(shape(signalLength));
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, Math.sin(2.0 * Math.PI * i / 128.0));
		}

		// Generate impulse response kernel
		PackedCollection kernel = new PackedCollection(shape(kernelLength));
		for (int i = 0; i < kernelLength; i++) {
			kernel.setMem(i, Math.exp(-i / 10.0) * Math.cos(Math.PI * i / 8.0));
		}

		FFTConvolution fftConv = fftConvolve(cp(signal), cp(kernel));
		PackedCollection result = fftConv.get().evaluate();

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
