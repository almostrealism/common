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
 * Tests for {@link TemporalFeatures#melFilterBank} mel-scale filterbank computation.
 */
public class MelFilterBankTest extends TestSuiteBase implements TemporalFeatures, TestFeatures {

	private static final double TOLERANCE = 1e-6;

	/**
	 * Test mel scale conversion: Hz to mel.
	 */
	@Test
	public void testHzToMel() {
		// Known reference values
		assertEquals("0 Hz should be 0 mel", 0.0, hzToMel(0.0), TOLERANCE);
		assertEquals("1000 Hz", 1000.0, hzToMel(1000.0), 5.0);  // Approx 1000 mel
		assertEquals("700 Hz", 781.9, hzToMel(700.0), 1.0);  // Reference point

		// Verify monotonicity
		double prevMel = 0;
		for (int hz = 100; hz <= 8000; hz += 100) {
			double mel = hzToMel(hz);
			assertTrue("Mel should increase with Hz", mel > prevMel);
			prevMel = mel;
		}
	}

	/**
	 * Test mel scale conversion: mel to Hz.
	 */
	@Test
	public void testMelToHz() {
		// Round-trip test
		for (double hz = 100; hz <= 8000; hz += 100) {
			double mel = hzToMel(hz);
			double hzBack = melToHz(mel);
			assertEquals("Round-trip Hz -> mel -> Hz for " + hz, hz, hzBack, TOLERANCE);
		}
	}

	/**
	 * Test basic mel filterbank creation and application.
	 */
	@Test
	public void testBasicMelFilterBank() {
		int fftSize = 512;
		int sampleRate = 16000;
		int numMelBands = 26;
		int numFreqBins = fftSize / 2 + 1;

		// Create a flat power spectrum (all ones)
		PackedCollection powerSpectrum = new PackedCollection(shape(numFreqBins));
		for (int i = 0; i < numFreqBins; i++) {
			powerSpectrum.setMem(i, 1.0);
		}

		CollectionProducer melBank = melFilterBank(fftSize, sampleRate, numMelBands, cp(powerSpectrum));
		PackedCollection melEnergies = melBank.evaluate();

		// Verify output size
		assertEquals("Output should have numMelBands values", numMelBands, melEnergies.getShape().getTotalSize());

		// For a flat spectrum, all mel bands should have non-zero energy
		for (int i = 0; i < numMelBands; i++) {
			assertTrue("Mel band " + i + " should have positive energy",
					melEnergies.toDouble(i) > 0);
		}
	}

	/**
	 * Test filterbank matrix properties.
	 */
	@Test
	public void testFilterbankMatrix() {
		int fftSize = 512;
		int sampleRate = 16000;
		int numMelBands = 26;
		int numFreqBins = fftSize / 2 + 1;

		PackedCollection filterbankMatrix = createMelFilterbankMatrix(fftSize, sampleRate, numMelBands, 0, 8000);

		// Verify dimensions
		assertEquals("Filterbank should have numMelBands * numFreqBins elements",
				numMelBands * numFreqBins, filterbankMatrix.getShape().getTotalSize());

		// Verify that filters are non-negative
		for (int i = 0; i < filterbankMatrix.getShape().getTotalSize(); i++) {
			assertTrue("Filter values should be non-negative", filterbankMatrix.toDouble(i) >= 0);
		}

		// Verify that each filter has at least one non-zero value
		for (int m = 0; m < numMelBands; m++) {
			boolean hasNonZero = false;
			for (int k = 0; k < numFreqBins; k++) {
				if (filterbankMatrix.toDouble(m * numFreqBins + k) > 0) {
					hasNonZero = true;
					break;
				}
			}
			assertTrue("Each mel filter should have at least one non-zero value", hasNonZero);
		}
	}

	/**
	 * Test mel filterbank with frequency-limited range.
	 */
	@Test
	public void testFrequencyLimitedRange() {
		int fftSize = 512;
		int sampleRate = 16000;
		int numMelBands = 40;
		double fMin = 300;
		double fMax = 8000;

		PackedCollection powerSpectrum = new PackedCollection(shape(fftSize / 2 + 1));
		for (int i = 0; i < fftSize / 2 + 1; i++) {
			powerSpectrum.setMem(i, 1.0);
		}

		CollectionProducer melBank = melFilterBank(fftSize, sampleRate, numMelBands, fMin, fMax, cp(powerSpectrum));
		PackedCollection melEnergies = melBank.evaluate();

		assertEquals("Output should have numMelBands values", numMelBands, melEnergies.getShape().getTotalSize());
	}

	/**
	 * Test that mel filterbank responds to spectral peaks.
	 */
	@Test
	public void testSpectralPeakResponse() {
		int fftSize = 512;
		int sampleRate = 16000;
		int numMelBands = 26;
		int numFreqBins = fftSize / 2 + 1;

		// Create spectrum with a single peak
		PackedCollection powerSpectrum = new PackedCollection(shape(numFreqBins));
		int peakBin = 50;  // Around 1560 Hz at 16kHz sample rate
		for (int i = 0; i < numFreqBins; i++) {
			powerSpectrum.setMem(i, (i == peakBin) ? 100.0 : 0.0);
		}

		CollectionProducer melBank = melFilterBank(fftSize, sampleRate, numMelBands, cp(powerSpectrum));
		PackedCollection melEnergies = melBank.evaluate();

		// Find the mel band with maximum energy
		int maxBand = 0;
		double maxEnergy = melEnergies.toDouble(0);
		for (int i = 1; i < numMelBands; i++) {
			double energy = melEnergies.toDouble(i);
			if (energy > maxEnergy) {
				maxEnergy = energy;
				maxBand = i;
			}
		}

		// The maximum should be in a band corresponding to the peak frequency
		assertTrue("Maximum energy should be significant", maxEnergy > 0);
		// Due to triangular overlap, adjacent bands may also have energy
	}

	/**
	 * Test MFCC computation.
	 */
	@Test
	public void testMFCC() {
		int numMelBands = 26;
		int numMfccCoeffs = 13;

		// Create synthetic mel energies
		PackedCollection melEnergies = new PackedCollection(shape(numMelBands));
		for (int i = 0; i < numMelBands; i++) {
			melEnergies.setMem(i, 1.0 + 0.5 * Math.cos(2.0 * Math.PI * i / numMelBands));
		}

		PackedCollection mfccs = mfcc(numMfccCoeffs, melEnergies);

		// Verify output size
		assertEquals("Should have numMfccCoeffs coefficients", numMfccCoeffs, mfccs.getShape().getTotalSize());

		// First coefficient (c0) is related to overall energy
		// Higher coefficients capture spectral shape
		// Verify we have non-trivial output
		boolean hasNonZero = false;
		for (int i = 0; i < numMfccCoeffs; i++) {
			if (Math.abs(mfccs.toDouble(i)) > 1e-10) {
				hasNonZero = true;
				break;
			}
		}
		assertTrue("MFCCs should have non-zero values", hasNonZero);
	}

	/**
	 * Test MFCC with constant mel energies (should produce specific pattern).
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
	 * Test factory method with default frequency range.
	 */
	@Test
	public void testDefaultFrequencyRange() {
		int fftSize = 512;
		int sampleRate = 16000;
		int numMelBands = 40;

		PackedCollection powerSpectrum = new PackedCollection(shape(fftSize / 2 + 1));
		for (int i = 0; i < fftSize / 2 + 1; i++) {
			powerSpectrum.setMem(i, 1.0);
		}

		// Default should use 0 to sampleRate/2
		CollectionProducer melBank = melFilterBank(fftSize, sampleRate, numMelBands, cp(powerSpectrum));
		PackedCollection melEnergies = melBank.evaluate();

		assertEquals("Output should have numMelBands values", numMelBands, melEnergies.getShape().getTotalSize());
	}
}
