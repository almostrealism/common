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

package org.almostrealism.audio;

import org.almostrealism.audio.line.BufferOutputLine;
import org.almostrealism.audio.line.MockOutputLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.LineUtilities;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.collect.PackedCollection;

import static org.junit.Assert.*;

/**
 * Test utility interface providing helper methods for audio testing.
 * Implement this interface in test classes to gain access to:
 * <ul>
 *   <li>Mock and buffer output line creation</li>
 *   <li>Audio assertion helpers (frequency, amplitude, etc.)</li>
 *   <li>Waveform analysis utilities</li>
 *   <li>Headless-compatible output line selection</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * public class MySynthTest extends TestSuiteBase implements AudioTestFeatures {
 *     {@literal @}Test
 *     public void testSineWave() {
 *         BufferOutputLine output = bufferOutput(44100);
 *         // ... run synthesis ...
 *         assertFrequencyApprox(output, 440.0, 5.0);
 *         assertHasAudio(output);
 *     }
 * }
 * </pre>
 */
public interface AudioTestFeatures {

	/**
	 * Tolerance for frequency comparisons in Hz.
	 */
	double DEFAULT_FREQUENCY_TOLERANCE = 5.0;

	/**
	 * Tolerance for amplitude comparisons.
	 */
	double DEFAULT_AMPLITUDE_TOLERANCE = 0.01;

	/**
	 * Creates a mock output line for pipeline testing without data capture.
	 *
	 * @return A new MockOutputLine with default settings
	 */
	default MockOutputLine mockOutput() {
		return new MockOutputLine();
	}

	/**
	 * Creates a mock output line with the specified buffer size.
	 *
	 * @param bufferSize The buffer size in frames
	 * @return A new MockOutputLine
	 */
	default MockOutputLine mockOutput(int bufferSize) {
		return new MockOutputLine(bufferSize);
	}

	/**
	 * Creates a buffer output line for capturing audio data.
	 *
	 * @param capacityFrames The capacity in frames
	 * @return A new BufferOutputLine
	 */
	default BufferOutputLine bufferOutput(int capacityFrames) {
		return new BufferOutputLine(capacityFrames);
	}

	/**
	 * Creates a buffer output line with default capacity (1 second at default sample rate).
	 *
	 * @return A new BufferOutputLine
	 */
	default BufferOutputLine bufferOutput() {
		return new BufferOutputLine(OutputLine.sampleRate);
	}

	/**
	 * Attempts to get a hardware output line, falling back to mock if unavailable.
	 * This is useful for tests that can optionally produce audible output.
	 *
	 * @return An OutputLine (either hardware or mock)
	 */
	default OutputLine getOutputLineOrMock() {
		try {
			OutputLine line = LineUtilities.getLine();
			if (line != null) {
				return line;
			}
		} catch (Exception e) {
			// Hardware not available
		}
		return mockOutput();
	}

	/**
	 * Checks if hardware audio output is available.
	 *
	 * @return true if hardware output can be obtained
	 */
	default boolean isHardwareAudioAvailable() {
		try {
			OutputLine line = LineUtilities.getLine();
			if (line != null) {
				line.destroy();
				return true;
			}
		} catch (Exception e) {
			// Hardware not available
		}
		return false;
	}

	/**
	 * Asserts that the buffer contains non-zero audio samples.
	 *
	 * @param output The buffer output line to check
	 */
	default void assertHasAudio(BufferOutputLine output) {
		assertTrue("Expected audio output but buffer is silent", output.hasAudio());
	}

	/**
	 * Asserts that the buffer is silent (all zeros).
	 *
	 * @param output The buffer output line to check
	 */
	default void assertSilent(BufferOutputLine output) {
		assertFalse("Expected silence but buffer has audio", output.hasAudio());
	}

	/**
	 * Asserts that the estimated frequency is approximately equal to expected.
	 *
	 * @param output The buffer output line to analyze
	 * @param expectedHz Expected frequency in Hz
	 * @param toleranceHz Tolerance in Hz
	 */
	default void assertFrequencyApprox(BufferOutputLine output, double expectedHz, double toleranceHz) {
		double estimated = output.estimateFrequency();
		assertEquals("Frequency mismatch", expectedHz, estimated, toleranceHz);
	}

	/**
	 * Asserts that the estimated frequency is approximately equal to expected,
	 * using default tolerance.
	 *
	 * @param output The buffer output line to analyze
	 * @param expectedHz Expected frequency in Hz
	 */
	default void assertFrequencyApprox(BufferOutputLine output, double expectedHz) {
		assertFrequencyApprox(output, expectedHz, DEFAULT_FREQUENCY_TOLERANCE);
	}

	/**
	 * Asserts that the peak amplitude is within the expected range.
	 *
	 * @param output The buffer output line to analyze
	 * @param minPeak Minimum expected peak amplitude
	 * @param maxPeak Maximum expected peak amplitude
	 */
	default void assertPeakAmplitudeInRange(BufferOutputLine output, double minPeak, double maxPeak) {
		double peak = output.getPeakAmplitude();
		assertTrue("Peak amplitude " + peak + " below minimum " + minPeak, peak >= minPeak);
		assertTrue("Peak amplitude " + peak + " above maximum " + maxPeak, peak <= maxPeak);
	}

	/**
	 * Asserts that the peak amplitude is approximately equal to expected.
	 *
	 * @param output The buffer output line to analyze
	 * @param expected Expected peak amplitude
	 * @param tolerance Tolerance
	 */
	default void assertPeakAmplitudeApprox(BufferOutputLine output, double expected, double tolerance) {
		double peak = output.getPeakAmplitude();
		assertEquals("Peak amplitude mismatch", expected, peak, tolerance);
	}

	/**
	 * Asserts that the RMS amplitude is approximately equal to expected.
	 *
	 * @param output The buffer output line to analyze
	 * @param expected Expected RMS amplitude
	 * @param tolerance Tolerance
	 */
	default void assertRmsAmplitudeApprox(BufferOutputLine output, double expected, double tolerance) {
		double rms = output.getRmsAmplitude();
		assertEquals("RMS amplitude mismatch", expected, rms, tolerance);
	}

	/**
	 * Asserts that the audio has no clipping (peak amplitude <= 1.0).
	 *
	 * @param output The buffer output line to analyze
	 */
	default void assertNoClipping(BufferOutputLine output) {
		double peak = output.getPeakAmplitude();
		assertTrue("Audio is clipping (peak=" + peak + ")", peak <= 1.0);
	}

	/**
	 * Asserts that at least the specified number of frames were written.
	 *
	 * @param output The buffer output line to check
	 * @param minFrames Minimum number of frames expected
	 */
	default void assertMinFramesWritten(BufferOutputLine output, long minFrames) {
		long written = output.getTotalFramesWritten();
		assertTrue("Expected at least " + minFrames + " frames but only " + written + " written",
				written >= minFrames);
	}

	/**
	 * Asserts that at least the specified number of frames were written.
	 *
	 * @param output The mock output line to check
	 * @param minFrames Minimum number of frames expected
	 */
	default void assertMinFramesWritten(MockOutputLine output, long minFrames) {
		long written = output.getFramesWritten();
		assertTrue("Expected at least " + minFrames + " frames but only " + written + " written",
				written >= minFrames);
	}

	/**
	 * Asserts that at least the specified duration of audio was written.
	 *
	 * @param output The buffer output line to check
	 * @param minSeconds Minimum duration in seconds
	 */
	default void assertMinDurationWritten(BufferOutputLine output, double minSeconds) {
		double duration = output.getDurationWritten();
		assertTrue("Expected at least " + minSeconds + "s but only " + duration + "s written",
				duration >= minSeconds);
	}

	/**
	 * Generates a test sine wave directly into a PackedCollection.
	 *
	 * @param frequency Frequency in Hz
	 * @param amplitude Amplitude (0.0 to 1.0)
	 * @param durationSeconds Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @return PackedCollection containing the sine wave
	 */
	default PackedCollection generateTestSine(double frequency, double amplitude,
												  double durationSeconds, int sampleRate) {
		int frames = (int) (durationSeconds * sampleRate);
		PackedCollection result = new PackedCollection(frames);

		double phaseIncrement = 2.0 * Math.PI * frequency / sampleRate;
		double phase = 0.0;

		for (int i = 0; i < frames; i++) {
			result.setMem(i, amplitude * Math.sin(phase));
			phase += phaseIncrement;
		}

		return result;
	}

	/**
	 * Generates a test sine wave with default sample rate.
	 *
	 * @param frequency Frequency in Hz
	 * @param amplitude Amplitude (0.0 to 1.0)
	 * @param durationSeconds Duration in seconds
	 * @return PackedCollection containing the sine wave
	 */
	default PackedCollection generateTestSine(double frequency, double amplitude,
												  double durationSeconds) {
		return generateTestSine(frequency, amplitude, durationSeconds, OutputLine.sampleRate);
	}

	/**
	 * Computes the correlation between two audio buffers.
	 * Returns 1.0 for identical signals, 0.0 for uncorrelated, -1.0 for inverted.
	 *
	 * @param a First audio buffer
	 * @param b Second audio buffer
	 * @return Correlation coefficient (-1.0 to 1.0)
	 */
	default double computeCorrelation(PackedCollection a, PackedCollection b) {
		int length = Math.min(a.getMemLength(), b.getMemLength());
		if (length == 0) return 0.0;

		double sumA = 0, sumB = 0;
		for (int i = 0; i < length; i++) {
			sumA += a.toDouble(i);
			sumB += b.toDouble(i);
		}
		double meanA = sumA / length;
		double meanB = sumB / length;

		double covariance = 0;
		double varA = 0, varB = 0;

		for (int i = 0; i < length; i++) {
			double diffA = a.toDouble(i) - meanA;
			double diffB = b.toDouble(i) - meanB;
			covariance += diffA * diffB;
			varA += diffA * diffA;
			varB += diffB * diffB;
		}

		double stdA = Math.sqrt(varA / length);
		double stdB = Math.sqrt(varB / length);

		if (stdA == 0 || stdB == 0) return 0.0;

		return covariance / (length * stdA * stdB);
	}

	/**
	 * Asserts that two audio buffers are similar (correlation above threshold).
	 *
	 * @param expected Expected audio
	 * @param actual Actual audio
	 * @param minCorrelation Minimum correlation (0.0 to 1.0)
	 */
	default void assertAudioSimilar(PackedCollection expected, PackedCollection actual,
									 double minCorrelation) {
		double correlation = computeCorrelation(expected, actual);
		assertTrue("Audio correlation " + correlation + " below threshold " + minCorrelation,
				correlation >= minCorrelation);
	}
}
