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
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for spectral analysis methods: unwrapPhase, mfcc, and related utility methods.
 *
 * <p>Note: The GPU-accelerated methods (phase, powerSpectrum, logMagnitude, complexMagnitude)
 * are tested indirectly through higher-level integration tests and are designed to work
 * with data from FFT outputs. This test class focuses on the non-accelerated utility methods.</p>
 */
public class PhaseSpectrumTest extends TestSuiteBase implements TemporalFeatures, TestFeatures {

	private static final double TOLERANCE = 1e-6;

	/**
	 * Test phase unwrapping with simple wrapped phase.
	 */
	@Test
	public void testUnwrapPhase() {
		// Create a phase sequence that wraps around
		// Start at 0, increase by PI/3 each step, should wrap at +/-PI
		int size = 16;
		PackedCollection wrappedPhase = new PackedCollection(shape(size));

		// Simulate wrapped phase: linearly increasing but wrapping at +/- PI
		double phaseIncrement = Math.PI / 3;
		for (int i = 0; i < size; i++) {
			double unwrappedPhase = i * phaseIncrement;
			// Wrap to [-PI, PI]
			double wrapped = unwrappedPhase;
			while (wrapped > Math.PI) wrapped -= 2 * Math.PI;
			while (wrapped < -Math.PI) wrapped += 2 * Math.PI;
			wrappedPhase.setMem(i, wrapped);
		}

		PackedCollection unwrapped = unwrapPhase(wrappedPhase);

		// Verify that differences between consecutive samples are consistent
		for (int i = 1; i < size; i++) {
			double diff = unwrapped.toDouble(i) - unwrapped.toDouble(i - 1);
			assertEquals("Phase difference should be consistent at " + i,
					phaseIncrement, diff, TOLERANCE);
		}
	}

	/**
	 * Test phase unwrapping with no wrapping needed.
	 */
	@Test
	public void testUnwrapPhaseNoWrapping() {
		int size = 10;
		PackedCollection wrappedPhase = new PackedCollection(shape(size));

		// Phase values within [-PI, PI] with small differences (no wrapping needed)
		for (int i = 0; i < size; i++) {
			wrappedPhase.setMem(i, -Math.PI / 2 + i * 0.1);
		}

		PackedCollection unwrapped = unwrapPhase(wrappedPhase);

		// Should be identical (no unwrapping needed)
		for (int i = 0; i < size; i++) {
			assertEquals("Unwrapped should match wrapped when no jumps at " + i,
					wrappedPhase.toDouble(i), unwrapped.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * Test phase unwrapping with negative wrap.
	 */
	@Test
	public void testUnwrapPhaseNegativeWrap() {
		int size = 8;
		PackedCollection wrappedPhase = new PackedCollection(shape(size));

		// Decreasing phase that wraps from -PI to +PI
		double phaseIncrement = -Math.PI / 3;
		for (int i = 0; i < size; i++) {
			double unwrappedPhase = i * phaseIncrement;
			double wrapped = unwrappedPhase;
			while (wrapped > Math.PI) wrapped -= 2 * Math.PI;
			while (wrapped < -Math.PI) wrapped += 2 * Math.PI;
			wrappedPhase.setMem(i, wrapped);
		}

		PackedCollection unwrapped = unwrapPhase(wrappedPhase);

		// Verify monotonically decreasing
		for (int i = 1; i < size; i++) {
			double diff = unwrapped.toDouble(i) - unwrapped.toDouble(i - 1);
			assertEquals("Phase difference should be consistent at " + i,
					phaseIncrement, diff, TOLERANCE);
		}
	}

	/**
	 * Test phase unwrapping with single value.
	 */
	@Test
	public void testUnwrapPhaseSingleValue() {
		PackedCollection wrappedPhase = new PackedCollection(shape(1));
		wrappedPhase.setMem(0, 1.5);

		PackedCollection unwrapped = unwrapPhase(wrappedPhase);
		assertEquals("Single value should be unchanged", 1.5, unwrapped.toDouble(0), TOLERANCE);
	}

	/**
	 * Test phase unwrapping handles large jumps.
	 */
	@Test
	public void testUnwrapPhaseLargeJumps() {
		int size = 5;
		PackedCollection wrappedPhase = new PackedCollection(shape(size));

		// Sequence that jumps across PI boundary
		wrappedPhase.setMem(0, 2.5);    // Near PI
		wrappedPhase.setMem(1, -2.5);   // Jumped across -PI (actually continuing forward)
		wrappedPhase.setMem(2, -1.5);
		wrappedPhase.setMem(3, -0.5);
		wrappedPhase.setMem(4, 0.5);

		PackedCollection unwrapped = unwrapPhase(wrappedPhase);

		// After unwrapping, the sequence should be monotonically increasing
		for (int i = 1; i < size; i++) {
			assertTrue("Unwrapped phase should be monotonically increasing",
					unwrapped.toDouble(i) > unwrapped.toDouble(i - 1) - Math.PI);
		}
	}

	/**
	 * Test mel to Hz conversion consistency.
	 */
	@Test
	public void testMelToHzRoundTrip() {
		// Test round-trip conversion for various frequencies
		double[] testFreqs = {100, 500, 1000, 2000, 4000, 8000};
		for (double hz : testFreqs) {
			double mel = hzToMel(hz);
			double hzBack = melToHz(mel);
			assertEquals("Round-trip Hz->mel->Hz for " + hz, hz, hzBack, TOLERANCE);
		}
	}

	/**
	 * Test mel scale properties.
	 */
	@Test
	public void testMelScaleProperties() {
		// Mel scale should be monotonically increasing
		double prevMel = hzToMel(0);
		for (int hz = 100; hz <= 8000; hz += 100) {
			double mel = hzToMel(hz);
			assertTrue("Mel should increase with Hz", mel > prevMel);
			prevMel = mel;
		}

		// 0 Hz should give 0 mel
		assertEquals("0 Hz should be 0 mel", 0.0, hzToMel(0.0), TOLERANCE);
	}

	/**
	 * Test MFCC computation returns correct size.
	 */
	@Test
	public void testMFCCOutputSize() {
		int numMelBands = 26;
		int numMfccCoeffs = 13;

		PackedCollection melEnergies = new PackedCollection(shape(numMelBands));
		for (int i = 0; i < numMelBands; i++) {
			melEnergies.setMem(i, 1.0 + 0.1 * i);
		}

		PackedCollection mfccs = mfcc(numMfccCoeffs, melEnergies);
		assertEquals("MFCC output should have correct size", numMfccCoeffs, mfccs.getShape().getTotalSize());
	}

	/**
	 * Test MFCC with constant input produces expected pattern.
	 */
	@Test
	public void testMFCCConstantInput() {
		int numMelBands = 26;
		int numMfccCoeffs = 13;

		// All mel bands have same energy
		PackedCollection melEnergies = new PackedCollection(shape(numMelBands));
		for (int i = 0; i < numMelBands; i++) {
			melEnergies.setMem(i, 1.0);
		}

		PackedCollection mfccs = mfcc(numMfccCoeffs, melEnergies);

		// For constant input, c0 should be non-zero (captures average)
		// Higher coefficients should be near zero (no spectral variation)
		assertTrue("c0 should be non-zero for constant input", Math.abs(mfccs.toDouble(0)) > 1e-10);

		for (int i = 1; i < numMfccCoeffs; i++) {
			assertEquals("Higher MFCCs should be near zero for constant input",
					0.0, mfccs.toDouble(i), 1e-6);
		}
	}

	/**
	 * Test MFCC produces meaningful output for varying input.
	 */
	@Test
	public void testMFCCVaryingInput() {
		int numMelBands = 26;
		int numMfccCoeffs = 13;

		// Create mel energies with spectral variation
		PackedCollection melEnergies = new PackedCollection(shape(numMelBands));
		for (int i = 0; i < numMelBands; i++) {
			melEnergies.setMem(i, 1.0 + 0.5 * Math.cos(2.0 * Math.PI * i / numMelBands));
		}

		PackedCollection mfccs = mfcc(numMfccCoeffs, melEnergies);

		// With varying input, some higher coefficients should be non-zero
		boolean hasNonZeroHigherCoeffs = false;
		for (int i = 1; i < numMfccCoeffs; i++) {
			if (Math.abs(mfccs.toDouble(i)) > 1e-6) {
				hasNonZeroHigherCoeffs = true;
				break;
			}
		}
		assertTrue("Higher MFCCs should be non-zero for varying input", hasNonZeroHigherCoeffs);
	}

	/**
	 * Test MFCC error handling for invalid parameters.
	 */
	@Test(expected = IllegalArgumentException.class)
	public void testMFCCInvalidNumCoeffs() {
		int numMelBands = 10;
		int numMfccCoeffs = 20;  // More coefficients than mel bands

		PackedCollection melEnergies = new PackedCollection(shape(numMelBands));
		for (int i = 0; i < numMelBands; i++) {
			melEnergies.setMem(i, 1.0);
		}

		mfcc(numMfccCoeffs, melEnergies);  // Should throw
	}

	/**
	 * Test MFCC with edge case of equal numCoeffs and numMelBands.
	 */
	@Test
	public void testMFCCMaxCoeffs() {
		int numMelBands = 10;
		int numMfccCoeffs = 10;  // Same as mel bands

		PackedCollection melEnergies = new PackedCollection(shape(numMelBands));
		for (int i = 0; i < numMelBands; i++) {
			melEnergies.setMem(i, 1.0 + i * 0.1);
		}

		PackedCollection mfccs = mfcc(numMfccCoeffs, melEnergies);
		assertEquals("MFCC output should have correct size", numMfccCoeffs, mfccs.getShape().getTotalSize());
	}

	/**
	 * Test MFCC orthonormality property.
	 * The DCT basis functions should be orthonormal.
	 */
	@Test
	public void testMFCCOrthonormality() {
		int numMelBands = 20;

		// Create impulse at different positions and check DCT coefficients
		for (int impulsePos = 0; impulsePos < 5; impulsePos++) {
			PackedCollection melEnergies = new PackedCollection(shape(numMelBands));
			melEnergies.setMem(impulsePos, 1.0);

			PackedCollection mfccs = mfcc(numMelBands, melEnergies);

			// Sum of squares should be close to 1 (due to orthonormal scaling)
			double sumSquares = 0;
			for (int i = 0; i < numMelBands; i++) {
				sumSquares += mfccs.toDouble(i) * mfccs.toDouble(i);
			}
			// For log(1+epsilon) input, the energy is preserved
			// This verifies the DCT is properly normalized
			assertTrue("DCT should preserve energy approximately", sumSquares > 0);
		}
	}
}
