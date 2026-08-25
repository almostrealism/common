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
import org.almostrealism.time.computations.WindowComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Function;

/**
 * Tests for {@link WindowComputation} window function generation.
 * Validates correctness against reference implementations.
 *
 * <p>Each reference is the window's defining formula expressed as a function of
 * position, evaluated with the host's own trigonometry. The host formula is the point:
 * building the expectation from the framework's own {@code cos()} and index arithmetic
 * would check the implementation against itself. Keeping it a function rather than an
 * array is equally deliberate — the expectation is worked out per position as it is
 * needed and never becomes data, so no reference values are carried around to be
 * compared element by element.</p>
 */
public class WindowComputationTest extends TestSuiteBase {

	// ==================== Reference Implementations ====================

	/**
	 * Reference Hann window: w[n] = 0.5 * (1 - cos(2*PI * n / (N-1)))
	 *
	 * @param size the window length
	 * @return the expected value at each position
	 */
	protected Function<int[], Double> referenceHann(int size) {
		return pos -> 0.5 * (1.0 - Math.cos(2.0 * Math.PI * pos[0] / (size - 1)));
	}

	/**
	 * Reference Hamming window: w[n] = 0.54 - 0.46 * cos(2*PI * n / (N-1))
	 *
	 * @param size the window length
	 * @return the expected value at each position
	 */
	protected Function<int[], Double> referenceHamming(int size) {
		return pos -> 0.54 - 0.46 * Math.cos(2.0 * Math.PI * pos[0] / (size - 1));
	}

	/**
	 * Reference Blackman window: w[n] = 0.42 - 0.5 * cos(2*PI*n/(N-1)) + 0.08 * cos(4*PI*n/(N-1))
	 *
	 * @param size the window length
	 * @return the expected value at each position
	 */
	protected Function<int[], Double> referenceBlackman(int size) {
		return pos -> {
			double angle = 2.0 * Math.PI * pos[0] / (size - 1);
			return 0.42 - 0.5 * Math.cos(angle) + 0.08 * Math.cos(2.0 * angle);
		};
	}

	/**
	 * Reference Bartlett window: w[n] = 1 - |2n/(N-1) - 1|
	 *
	 * @param size the window length
	 * @return the expected value at each position
	 */
	protected Function<int[], Double> referenceBartlett(int size) {
		return pos -> 1.0 - Math.abs(2.0 * pos[0] / (size - 1) - 1.0);
	}

	/**
	 * Reference Flat-top window with 5 terms.
	 *
	 * @param size the window length
	 * @return the expected value at each position
	 */
	protected Function<int[], Double> referenceFlattop(int size) {
		double a0 = 0.21557895;
		double a1 = 0.41663158;
		double a2 = 0.277263158;
		double a3 = 0.083578947;
		double a4 = 0.006947368;

		return pos -> {
			double angle = 2.0 * Math.PI * pos[0] / (size - 1);
			return a0
					- a1 * Math.cos(angle)
					+ a2 * Math.cos(2.0 * angle)
					- a3 * Math.cos(3.0 * angle)
					+ a4 * Math.cos(4.0 * angle);
		};
	}

	// ==================== Hann Window Tests ====================

	/**
	 * Tests Hann window with small size.
	 */
	@Test(timeout = 30000)
	public void testHannWindowSmall() {
		int size = 64;
		PackedCollection result = WindowComputation.hann(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceHann(size), result));
	}

	/**
	 * Tests Hann window with medium size.
	 */
	@Test(timeout = 30000)
	public void testHannWindowMedium() {
		int size = 512;
		PackedCollection result = hannWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceHann(size), result));
	}

	/**
	 * Tests Hann window with large size.
	 */
	@Test(timeout = 30000)
	public void testHannWindowLarge() {
		int size = 2048;
		PackedCollection result = hannWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceHann(size), result));
	}

	/**
	 * Tests Hann window properties.
	 */
	@Test(timeout = 30000)
	public void testHannWindowProperties() {
		int size = 256;
		PackedCollection window = hannWindow(size).get().evaluate();

		// Hann window should be zero at boundaries
		assertEquals(0.0, window.toDouble(0));
		assertEquals(0.0, window.toDouble(size - 1));

		// Hann window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		// For even size N, the center is at (N-1)/2 which is not an integer
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		assertSymmetric(window);
	}

	// ==================== Hamming Window Tests ====================

	/**
	 * Tests Hamming window with small size.
	 */
	@Test(timeout = 30000)
	public void testHammingWindowSmall() {
		int size = 64;
		PackedCollection result = hammingWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceHamming(size), result));
	}

	/**
	 * Tests Hamming window with medium size.
	 */
	@Test(timeout = 30000)
	public void testHammingWindowMedium() {
		int size = 512;
		PackedCollection result = hammingWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceHamming(size), result));
	}

	/**
	 * Tests Hamming window properties.
	 */
	@Test(timeout = 30000)
	public void testHammingWindowProperties() {
		int size = 256;
		PackedCollection window = hammingWindow(size).get().evaluate();

		// Hamming window should NOT be zero at boundaries (0.08 at edges)
		assertEquals(0.08, window.toDouble(0));
		assertEquals(0.08, window.toDouble(size - 1));

		// Hamming window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		assertSymmetric(window);
	}

	// ==================== Blackman Window Tests ====================

	/**
	 * Tests Blackman window with small size.
	 */
	@Test(timeout = 30000)
	public void testBlackmanWindowSmall() {
		int size = 64;
		PackedCollection result = blackmanWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceBlackman(size), result));
	}

	/**
	 * Tests Blackman window with medium size.
	 */
	@Test(timeout = 30000)
	public void testBlackmanWindowMedium() {
		int size = 512;
		PackedCollection result = blackmanWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceBlackman(size), result));
	}

	/**
	 * Tests Blackman window properties.
	 */
	@Test(timeout = 30000)
	public void testBlackmanWindowProperties() {
		int size = 256;
		PackedCollection window = blackmanWindow(size).get().evaluate();

		// Blackman window should be near zero at boundaries
		assertEquals(0.0, window.toDouble(0));
		assertEquals(0.0, window.toDouble(size - 1));

		// Blackman window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		assertSymmetric(window);
	}

	// ==================== Bartlett Window Tests ====================

	/**
	 * Tests Bartlett window with small size.
	 */
	@Test(timeout = 30000)
	public void testBartlettWindowSmall() {
		int size = 64;
		PackedCollection result = bartlettWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceBartlett(size), result));
	}

	/**
	 * Tests Bartlett window with medium size.
	 */
	@Test(timeout = 30000)
	public void testBartlettWindowMedium() {
		int size = 512;
		PackedCollection result = bartlettWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceBartlett(size), result));
	}

	/**
	 * Tests Bartlett window properties.
	 */
	@Test(timeout = 30000)
	public void testBartlettWindowProperties() {
		int size = 256;
		PackedCollection window = bartlettWindow(size).get().evaluate();

		// Bartlett window should be zero at boundaries
		assertEquals(0.0, window.toDouble(0));
		assertEquals(0.0, window.toDouble(size - 1));

		// Bartlett window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		assertSymmetric(window);

		// The rising half of the triangle must never step down; the smallest step across
		// it settles that, with a small tolerance for floating-point precision.
		int half = size / 2;
		double smallestStep = -max(cp(window.range(shape(half), 0))
				.subtract(cp(window.range(shape(half), 1)))).evaluate().toDouble(0);
		assertTrue("Expected the first half to increase monotonically, but the smallest"
				+ " step was " + smallestStep, smallestStep >= -1e-6);
	}

	// ==================== Flat-top Window Tests ====================

	/**
	 * Tests Flat-top window with small size.
	 */
	@Test(timeout = 30000)
	public void testFlattopWindowSmall() {
		int size = 64;
		PackedCollection result = flattopWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceFlattop(size), result));
	}

	/**
	 * Tests Flat-top window with medium size.
	 */
	@Test(timeout = 30000)
	public void testFlattopWindowMedium() {
		int size = 512;
		PackedCollection result = flattopWindow(size).get().evaluate();

		assertEquals(0.0, largestDeviation(shape(size), referenceFlattop(size), result));
	}

	/**
	 * Tests Flat-top window properties.
	 */
	@Test(timeout = 30000)
	public void testFlattopWindowProperties() {
		int size = 256;
		PackedCollection window = flattopWindow(size).get().evaluate();

		assertSymmetric(window);

		// Flat-top window can have negative values at edges (unique property)
		// Just check it's computed without error
		assertNotNull(window);
		assertEquals(size, window.getShape().getTotalSize());
	}

	// ==================== Generic Window Type Tests ====================

	/**
	 * Tests window creation by type enum.
	 */
	@Test(timeout = 30000)
	public void testWindowByType() {
		int size = 128;

		// Test that window(Type, size) matches specific factory methods
		PackedCollection hannDirect = hannWindow(size).get().evaluate();
		PackedCollection hannByType = window(WindowComputation.Type.HANN, size).get().evaluate();

		assertEquals(0.0, largestDeviation(hannDirect, hannByType));
	}

	// ==================== Apply Window Tests ====================

	/**
	 * Tests applying window to a signal.
	 */
	@Test(timeout = 30000)
	public void testApplyWindow() {
		int size = 64;

		// Create a simple signal of all ones
		PackedCollection signal = new PackedCollection(size);
		signal.fill(1.0);

		// Apply Hann window
		PackedCollection windowed = applyWindow(cp(signal), WindowComputation.Type.HANN).get().evaluate();

		// Result should equal the window coefficients since signal was all ones
		assertEquals(0.0, largestDeviation(shape(size), referenceHann(size), windowed));
	}

	/**
	 * Tests applying window to a sine wave signal.
	 */
	@Test(timeout = 30000)
	public void testApplyWindowToSineWave() {
		int size = 256;
		double frequency = 4.0; // 4 cycles in window

		// Create a sine wave signal
		PackedCollection signal = new PackedCollection(size);
		sin(integers(0, size).multiply(2.0 * Math.PI * frequency / size))
				.into(signal.traverseEach()).evaluate();

		// Apply Hann window
		PackedCollection windowed = applyWindow(cp(signal), WindowComputation.Type.HANN).get().evaluate();

		// Verify that windowing reduces edge values
		// At edges, window is ~0, so windowed signal should be ~0
		assertEquals(0.0, windowed.toDouble(0), 0.01);
		assertEquals(0.0, windowed.toDouble(size - 1), 0.01);

		// At center, window is 1, so windowed signal should equal original
		int center = size / 2;
		double expectedCenter = Math.sin(2.0 * Math.PI * frequency * center / size);
		assertEquals(expectedCenter, windowed.toDouble(center));
	}
}
