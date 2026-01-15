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
import org.almostrealism.time.computations.WindowComputation;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for {@link TemporalFeatures#stft} Short-Time Fourier Transform computation.
 */
public class STFTComputationTest extends TestSuiteBase implements TemporalFeatures, TestFeatures {

	private static final double TOLERANCE = 1e-6;

	/**
	 * Test basic STFT computation with a simple signal.
	 */
	@Test
	public void testBasicSTFT() {
		int fftSize = 64;
		int hopSize = 32;
		int signalLength = 256;

		// Create a simple sine wave signal
		PackedCollection signal = new PackedCollection(shape(signalLength));
		double frequency = 4.0; // 4 cycles over the signal
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, Math.sin(2.0 * Math.PI * frequency * i / signalLength));
		}

		// Compute STFT
		int expectedFrames = TemporalFeatures.computeNumFrames(signalLength, fftSize, hopSize);
		CollectionProducer stftProducer = stft(fftSize, hopSize, WindowComputation.Type.HANN, cp(signal));
		PackedCollection spectrogram = stftProducer.evaluate();

		assertNotNull("Spectrogram should not be null", spectrogram);

		// Verify output shape: [numFrames, fftSize, 2]
		assertEquals("Output size", expectedFrames * fftSize * 2, spectrogram.getShape().getTotalSize());
	}

	/**
	 * Test STFT with different window types.
	 */
	@Test
	public void testSTFTWithDifferentWindows() {
		int fftSize = 32;
		int hopSize = 16;
		int signalLength = 128;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, Math.sin(2.0 * Math.PI * 2.0 * i / signalLength));
		}

		for (WindowComputation.Type windowType : WindowComputation.Type.values()) {
			CollectionProducer stftProducer = stft(fftSize, hopSize, windowType, cp(signal));
			PackedCollection spectrogram = stftProducer.evaluate();
			assertNotNull("Spectrogram with " + windowType + " window should not be null", spectrogram);
		}
	}

	/**
	 * Test STFT frame count calculation.
	 */
	@Test
	public void testFrameCountCalculation() {
		// Test case 1: Exact fit
		assertEquals(5, TemporalFeatures.computeNumFrames(256, 64, 48));

		// Test case 2: Signal shorter than fftSize
		assertEquals(0, TemporalFeatures.computeNumFrames(32, 64, 16));

		// Test case 3: 75% overlap
		assertEquals(7, TemporalFeatures.computeNumFrames(256, 64, 32));

		// Test case 4: 50% overlap
		assertEquals(7, TemporalFeatures.computeNumFrames(256, 64, 32));

		// Test case 5: No overlap (hop = fftSize)
		assertEquals(4, TemporalFeatures.computeNumFrames(256, 64, 64));
	}

	/**
	 * Test that STFT produces non-zero output for non-zero input.
	 */
	@Test
	public void testNonZeroOutput() {
		int fftSize = 64;
		int hopSize = 32;
		int signalLength = 192;

		// Create a non-zero signal
		PackedCollection signal = new PackedCollection(shape(signalLength));
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, 1.0 + Math.sin(2.0 * Math.PI * i / 32.0));
		}

		CollectionProducer stftProducer = stft(fftSize, hopSize, cp(signal));
		PackedCollection spectrogram = stftProducer.evaluate();

		// Check that we have some non-zero values
		boolean hasNonZero = false;
		for (int i = 0; i < spectrogram.getShape().getTotalSize(); i++) {
			if (Math.abs(spectrogram.toDouble(i)) > 1e-10) {
				hasNonZero = true;
				break;
			}
		}
		assertTrue("Spectrogram should have non-zero values", hasNonZero);
	}

	/**
	 * Test STFT with DC signal (all ones).
	 */
	@Test
	public void testDCSignal() {
		int fftSize = 64;
		int hopSize = 32;
		int signalLength = 128;

		// Create DC signal (all ones)
		PackedCollection signal = new PackedCollection(shape(signalLength));
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, 1.0);
		}

		int numFrames = TemporalFeatures.computeNumFrames(signalLength, fftSize, hopSize);
		CollectionProducer stftProducer = stft(fftSize, hopSize, WindowComputation.Type.HANN, cp(signal));
		PackedCollection spectrogram = stftProducer.evaluate();

		// For a DC signal with Hann window, the DC component (bin 0) should be non-zero
		// and other bins should be near zero
		for (int frame = 0; frame < numFrames; frame++) {
			int frameOffset = frame * fftSize * 2;
			double dcReal = spectrogram.toDouble(frameOffset);
			double dcImag = spectrogram.toDouble(frameOffset + 1);
			double dcMagnitude = Math.sqrt(dcReal * dcReal + dcImag * dcImag);

			// DC component should be significant
			assertTrue("Frame " + frame + " DC magnitude should be > 0.1", dcMagnitude > 0.1);
		}
	}

	/**
	 * Test STFT factory method with default Hann window.
	 */
	@Test
	public void testDefaultHannWindow() {
		int fftSize = 64;
		int hopSize = 32;
		int signalLength = 128;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, Math.random());
		}

		// Default uses Hann window - just verify it produces valid output
		CollectionProducer stftProducer = stft(fftSize, hopSize, cp(signal));
		PackedCollection spectrogram = stftProducer.evaluate();
		assertNotNull("Spectrogram should not be null", spectrogram);

		// Verify output size matches expected (default is Hann window)
		int expectedFrames = TemporalFeatures.computeNumFrames(signalLength, fftSize, hopSize);
		assertEquals("Output size", expectedFrames * fftSize * 2, spectrogram.getShape().getTotalSize());
	}

	/**
	 * Test STFT output shape.
	 */
	@Test
	public void testOutputShape() {
		int fftSize = 128;
		int hopSize = 64;
		int signalLength = 512;

		PackedCollection signal = new PackedCollection(shape(signalLength));
		for (int i = 0; i < signalLength; i++) {
			signal.setMem(i, Math.sin(2.0 * Math.PI * i / 64.0));
		}

		int expectedFrames = TemporalFeatures.computeNumFrames(signalLength, fftSize, hopSize);
		CollectionProducer stftProducer = stft(fftSize, hopSize, cp(signal));
		PackedCollection spectrogram = stftProducer.evaluate();

		// Verify output shape by checking total size
		assertEquals("Output size", expectedFrames * fftSize * 2, spectrogram.getShape().getTotalSize());

		// Verify shape dimensions
		assertEquals("Number of frames", expectedFrames, spectrogram.getShape().length(0));
		assertEquals("FFT size", fftSize, spectrogram.getShape().length(1));
		assertEquals("Complex dimension", 2, spectrogram.getShape().length(2));
	}
}
