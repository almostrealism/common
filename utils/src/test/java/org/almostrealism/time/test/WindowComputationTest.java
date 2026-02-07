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

/**
 * Tests for {@link WindowComputation} window function generation.
 * Validates correctness against reference implementations.
 */
public class WindowComputationTest extends TestSuiteBase {

	private static final double TOLERANCE = 1e-10;

	// ==================== Reference Implementations ====================

	/**
	 * Reference Hann window: w[n] = 0.5 * (1 - cos(2*PI * n / (N-1)))
	 */
	protected double[] referenceHann(int size) {
		double[] window = new double[size];
		for (int n = 0; n < size; n++) {
			window[n] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (size - 1)));
		}
		return window;
	}

	/**
	 * Reference Hamming window: w[n] = 0.54 - 0.46 * cos(2*PI * n / (N-1))
	 */
	protected double[] referenceHamming(int size) {
		double[] window = new double[size];
		for (int n = 0; n < size; n++) {
			window[n] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * n / (size - 1));
		}
		return window;
	}

	/**
	 * Reference Blackman window: w[n] = 0.42 - 0.5 * cos(2*PI*n/(N-1)) + 0.08 * cos(4*PI*n/(N-1))
	 */
	protected double[] referenceBlackman(int size) {
		double[] window = new double[size];
		for (int n = 0; n < size; n++) {
			double angle = 2.0 * Math.PI * n / (size - 1);
			window[n] = 0.42 - 0.5 * Math.cos(angle) + 0.08 * Math.cos(2.0 * angle);
		}
		return window;
	}

	/**
	 * Reference Bartlett window: w[n] = 1 - |2n/(N-1) - 1|
	 */
	protected double[] referenceBartlett(int size) {
		double[] window = new double[size];
		for (int n = 0; n < size; n++) {
			window[n] = 1.0 - Math.abs(2.0 * n / (size - 1) - 1.0);
		}
		return window;
	}

	/**
	 * Reference Flat-top window with 5 terms.
	 */
	protected double[] referenceFlattop(int size) {
		double a0 = 0.21557895;
		double a1 = 0.41663158;
		double a2 = 0.277263158;
		double a3 = 0.083578947;
		double a4 = 0.006947368;

		double[] window = new double[size];
		for (int n = 0; n < size; n++) {
			double angle = 2.0 * Math.PI * n / (size - 1);
			window[n] = a0
					- a1 * Math.cos(angle)
					+ a2 * Math.cos(2.0 * angle)
					- a3 * Math.cos(3.0 * angle)
					+ a4 * Math.cos(4.0 * angle);
		}
		return window;
	}

	// ==================== Hann Window Tests ====================

	@Test
	public void testHannWindowSmall() {
		int size = 64;
		double[] expected = referenceHann(size);
		PackedCollection result = WindowComputation.hann(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testHannWindowMedium() {
		int size = 512;
		double[] expected = referenceHann(size);
		PackedCollection result = hannWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testHannWindowLarge() {
		int size = 2048;
		double[] expected = referenceHann(size);
		PackedCollection result = hannWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testHannWindowProperties() {
		int size = 256;
		PackedCollection window = hannWindow(size).get().evaluate();

		// Hann window should be zero at boundaries
		assertEquals(0.0, window.toDouble(0), TOLERANCE);
		assertEquals(0.0, window.toDouble(size - 1), TOLERANCE);

		// Hann window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		// For even size N, the center is at (N-1)/2 which is not an integer
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		// Hann window should be symmetric
		for (int i = 0; i < size / 2; i++) {
			assertEquals(window.toDouble(i), window.toDouble(size - 1 - i), TOLERANCE);
		}
	}

	// ==================== Hamming Window Tests ====================

	@Test
	public void testHammingWindowSmall() {
		int size = 64;
		double[] expected = referenceHamming(size);
		PackedCollection result = hammingWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testHammingWindowMedium() {
		int size = 512;
		double[] expected = referenceHamming(size);
		PackedCollection result = hammingWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testHammingWindowProperties() {
		int size = 256;
		PackedCollection window = hammingWindow(size).get().evaluate();

		// Hamming window should NOT be zero at boundaries (0.08 at edges)
		assertEquals(0.08, window.toDouble(0), TOLERANCE);
		assertEquals(0.08, window.toDouble(size - 1), TOLERANCE);

		// Hamming window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		// Hamming window should be symmetric
		for (int i = 0; i < size / 2; i++) {
			assertEquals(window.toDouble(i), window.toDouble(size - 1 - i), TOLERANCE);
		}
	}

	// ==================== Blackman Window Tests ====================

	@Test
	public void testBlackmanWindowSmall() {
		int size = 64;
		double[] expected = referenceBlackman(size);
		PackedCollection result = blackmanWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testBlackmanWindowMedium() {
		int size = 512;
		double[] expected = referenceBlackman(size);
		PackedCollection result = blackmanWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testBlackmanWindowProperties() {
		int size = 256;
		PackedCollection window = blackmanWindow(size).get().evaluate();

		// Blackman window should be near zero at boundaries
		assertEquals(0.0, window.toDouble(0), TOLERANCE);
		assertEquals(0.0, window.toDouble(size - 1), TOLERANCE);

		// Blackman window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		// Blackman window should be symmetric
		for (int i = 0; i < size / 2; i++) {
			assertEquals(window.toDouble(i), window.toDouble(size - 1 - i), TOLERANCE);
		}
	}

	// ==================== Bartlett Window Tests ====================

	@Test
	public void testBartlettWindowSmall() {
		int size = 64;
		double[] expected = referenceBartlett(size);
		PackedCollection result = bartlettWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testBartlettWindowMedium() {
		int size = 512;
		double[] expected = referenceBartlett(size);
		PackedCollection result = bartlettWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testBartlettWindowProperties() {
		int size = 256;
		PackedCollection window = bartlettWindow(size).get().evaluate();

		// Bartlett window should be zero at boundaries
		assertEquals(0.0, window.toDouble(0), TOLERANCE);
		assertEquals(0.0, window.toDouble(size - 1), TOLERANCE);

		// Bartlett window should be close to 1.0 at center (exact 1.0 only for odd sizes)
		double centerValue = window.toDouble(size / 2);
		assertTrue("Center value should be close to 1.0 but was " + centerValue, centerValue > 0.99);

		// Bartlett window should be symmetric
		for (int i = 0; i < size / 2; i++) {
			assertEquals(window.toDouble(i), window.toDouble(size - 1 - i), TOLERANCE);
		}

		// Bartlett window should be linear (triangular)
		// Check that first half is monotonically increasing
		for (int i = 1; i <= size / 2; i++) {
			assertTrue(window.toDouble(i) >= window.toDouble(i - 1));
		}
	}

	// ==================== Flat-top Window Tests ====================

	@Test
	public void testFlattopWindowSmall() {
		int size = 64;
		double[] expected = referenceFlattop(size);
		PackedCollection result = flattopWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testFlattopWindowMedium() {
		int size = 512;
		double[] expected = referenceFlattop(size);
		PackedCollection result = flattopWindow(size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], result.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testFlattopWindowProperties() {
		int size = 256;
		PackedCollection window = flattopWindow(size).get().evaluate();

		// Flat-top window should be symmetric
		for (int i = 0; i < size / 2; i++) {
			assertEquals(window.toDouble(i), window.toDouble(size - 1 - i), TOLERANCE);
		}

		// Flat-top window can have negative values at edges (unique property)
		// Just check it's computed without error
		assertNotNull(window);
		assertEquals(size, window.getShape().getTotalSize());
	}

	// ==================== Generic Window Type Tests ====================

	@Test
	public void testWindowByType() {
		int size = 128;

		// Test that window(Type, size) matches specific factory methods
		PackedCollection hannDirect = hannWindow(size).get().evaluate();
		PackedCollection hannByType = window(WindowComputation.Type.HANN, size).get().evaluate();

		for (int i = 0; i < size; i++) {
			assertEquals(hannDirect.toDouble(i), hannByType.toDouble(i), TOLERANCE);
		}
	}

	// ==================== Apply Window Tests ====================

	@Test
	public void testApplyWindow() {
		int size = 64;

		// Create a simple signal of all ones
		PackedCollection signal = new PackedCollection(size);
		for (int i = 0; i < size; i++) {
			signal.setMem(i, 1.0);
		}

		// Apply Hann window
		PackedCollection windowed = applyWindow(cp(signal), WindowComputation.Type.HANN).get().evaluate();

		// Result should equal the window coefficients since signal was all ones
		double[] expected = referenceHann(size);
		for (int i = 0; i < size; i++) {
			assertEquals(expected[i], windowed.toDouble(i), TOLERANCE);
		}
	}

	@Test
	public void testApplyWindowToSineWave() {
		int size = 256;
		double frequency = 4.0; // 4 cycles in window

		// Create a sine wave signal
		PackedCollection signal = new PackedCollection(size);
		for (int i = 0; i < size; i++) {
			signal.setMem(i, Math.sin(2.0 * Math.PI * frequency * i / size));
		}

		// Apply Hann window
		PackedCollection windowed = applyWindow(cp(signal), WindowComputation.Type.HANN).get().evaluate();

		// Verify that windowing reduces edge values
		// At edges, window is ~0, so windowed signal should be ~0
		assertEquals(0.0, windowed.toDouble(0), 0.01);
		assertEquals(0.0, windowed.toDouble(size - 1), 0.01);

		// At center, window is 1, so windowed signal should equal original
		int center = size / 2;
		double expectedCenter = Math.sin(2.0 * Math.PI * frequency * center / size);
		assertEquals(expectedCenter, windowed.toDouble(center), TOLERANCE);
	}
}
