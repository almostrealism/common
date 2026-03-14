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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.GeometryFeatures;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

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
public interface AudioTestFeatures extends GeometryFeatures {

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
		if (!output.hasAudio()) {
			throw new AssertionError("Expected audio output but buffer is silent");
		}
	}

	/**
	 * Asserts that the buffer is silent (all zeros).
	 *
	 * @param output The buffer output line to check
	 */
	default void assertSilent(BufferOutputLine output) {
		if (output.hasAudio()) {
			throw new AssertionError("Expected silence but buffer has audio");
		}
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
		if (Math.abs(estimated - expectedHz) > toleranceHz) {
			throw new AssertionError("Frequency mismatch: expected " + expectedHz + " but was " + estimated);
		}
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
		if (peak < minPeak) {
			throw new AssertionError("Peak amplitude " + peak + " below minimum " + minPeak);
		}
		if (peak > maxPeak) {
			throw new AssertionError("Peak amplitude " + peak + " above maximum " + maxPeak);
		}
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
		if (Math.abs(peak - expected) > tolerance) {
			throw new AssertionError("Peak amplitude mismatch: expected " + expected + " but was " + peak);
		}
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
		if (Math.abs(rms - expected) > tolerance) {
			throw new AssertionError("RMS amplitude mismatch: expected " + expected + " but was " + rms);
		}
	}

	/**
	 * Asserts that the audio has no clipping (peak amplitude <= 1.0).
	 *
	 * @param output The buffer output line to analyze
	 */
	default void assertNoClipping(BufferOutputLine output) {
		double peak = output.getPeakAmplitude();
		if (peak > 1.0) {
			throw new AssertionError("Audio is clipping (peak=" + peak + ")");
		}
	}

	/**
	 * Asserts that at least the specified number of frames were written.
	 *
	 * @param output The buffer output line to check
	 * @param minFrames Minimum number of frames expected
	 */
	default void assertMinFramesWritten(BufferOutputLine output, long minFrames) {
		long written = output.getTotalFramesWritten();
		if (written < minFrames) {
			throw new AssertionError("Expected at least " + minFrames + " frames but only " + written + " written");
		}
	}

	/**
	 * Asserts that at least the specified number of frames were written.
	 *
	 * @param output The mock output line to check
	 * @param minFrames Minimum number of frames expected
	 */
	default void assertMinFramesWritten(MockOutputLine output, long minFrames) {
		long written = output.getFramesWritten();
		if (written < minFrames) {
			throw new AssertionError("Expected at least " + minFrames + " frames but only " + written + " written");
		}
	}

	/**
	 * Asserts that at least the specified duration of audio was written.
	 *
	 * @param output The buffer output line to check
	 * @param minSeconds Minimum duration in seconds
	 */
	default void assertMinDurationWritten(BufferOutputLine output, double minSeconds) {
		double duration = output.getDurationWritten();
		if (duration < minSeconds) {
			throw new AssertionError("Expected at least " + minSeconds + "s but only " + duration + "s written");
		}
	}

	/**
	 * Generates a test sine wave directly into a PackedCollection.
	 *
	 * <p>Uses hardware-accelerated computation via the Producer pattern.</p>
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

		// GPU-accelerated sine generation using Producer pattern
		// time = indices / sampleRate
		// result = amplitude * sin(2 * PI * frequency * time)
		CollectionProducer indices = integers(0, frames);
		CollectionProducer time = indices.divide(c(sampleRate));
		CollectionProducer phase = time.multiply(c(2.0 * Math.PI * frequency));
		CollectionProducer sine = sin(phase).multiply(c(amplitude));

		return sine.evaluate();
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
		if (correlation < minCorrelation) {
			throw new AssertionError("Audio correlation " + correlation + " below threshold " + minCorrelation);
		}
	}

	/**
	 * Returns a temporary WAV file containing synthetic audio data.
	 * The file is created on first call and cached for subsequent calls.
	 * The file is marked for deletion when the JVM exits.
	 *
	 * <p>The generated file contains a 2-second sine wave at 440Hz (A4),
	 * which is suitable for testing audio processing pipelines without
	 * requiring external audio files.</p>
	 *
	 * @return File pointing to the temporary WAV file
	 */
	default File getTestWavFile() {
		return TestWavFileHolder.getTestWavFile();
	}

	/**
	 * Returns a temporary WAV file with custom parameters.
	 * A new file is generated each time this method is called.
	 * The file is marked for deletion when the JVM exits.
	 *
	 * @param frequency Frequency in Hz
	 * @param durationSeconds Duration in seconds (max 5 seconds)
	 * @return File pointing to the temporary WAV file
	 */
	default File getTestWavFile(double frequency, double durationSeconds) {
		return TestWavFileHolder.createTestWavFile(frequency, durationSeconds);
	}

	/**
	 * Returns the path to a temporary WAV file as a String.
	 * Convenience method for APIs that expect a path string.
	 *
	 * @return Path to the temporary WAV file
	 */
	default String getTestWavPath() {
		return getTestWavFile().getAbsolutePath();
	}

	/**
	 * Returns a temporary WAV file identified by a logical name. Files are
	 * cached by name so that repeated calls with the same name return the
	 * same file. This is useful when a test needs multiple distinct samples
	 * (e.g., different drum hits or melodic instruments) and wants to refer
	 * to them by name.
	 *
	 * @param name logical name used as a cache key and file-name prefix
	 * @param frequency frequency in Hz for the primary tone
	 * @param durationSeconds duration in seconds (max 5 seconds)
	 * @param percussive if true, generates a noise burst with exponential
	 *                   decay (suitable for drums/hits); if false, generates
	 *                   a sustained sine wave (suitable for melodic sounds)
	 * @return File pointing to the temporary WAV file
	 */
	default File getNamedTestWavFile(String name, double frequency,
									 double durationSeconds, boolean percussive) {
		return TestWavFileHolder.getOrCreate(name, frequency, durationSeconds, percussive);
	}

	/**
	 * Convenience overload that returns the absolute path to a named test WAV.
	 *
	 * @param name logical name used as a cache key and file-name prefix
	 * @param frequency frequency in Hz for the primary tone
	 * @param durationSeconds duration in seconds (max 5 seconds)
	 * @param percussive if true, generates a percussive sound; if false, melodic
	 * @return absolute path to the temporary WAV file
	 */
	default String getNamedTestWavPath(String name, double frequency,
									   double durationSeconds, boolean percussive) {
		return getNamedTestWavFile(name, frequency, durationSeconds, percussive).getAbsolutePath();
	}

	/**
	 * Internal holder class for lazy initialization of test WAV files.
	 * Uses a static holder to ensure each named file is created only once
	 * across all tests in the same JVM.
	 */
	final class TestWavFileHolder {
		private static volatile File cachedFile;
		private static final java.util.Map<String, File> namedFiles =
				new java.util.concurrent.ConcurrentHashMap<>();

		private TestWavFileHolder() {}

		static File getTestWavFile() {
			if (cachedFile == null) {
				synchronized (TestWavFileHolder.class) {
					if (cachedFile == null) {
						cachedFile = createMelodicWavFile(440.0, 2.0);
					}
				}
			}
			return cachedFile;
		}

		/**
		 * Returns a cached file for the given name, creating it on first access.
		 */
		static File getOrCreate(String name, double frequency,
								double durationSeconds, boolean percussive) {
			return namedFiles.computeIfAbsent(name, k ->
					percussive
							? createPercussiveWavFile(frequency, durationSeconds)
							: createMelodicWavFile(frequency, durationSeconds));
		}

		static File createTestWavFile(double frequency, double durationSeconds) {
			return createMelodicWavFile(frequency, durationSeconds);
		}

		/**
		 * Creates a WAV file containing a sustained sine wave at the given
		 * frequency. Suitable for melodic instruments (bass, lead, harmony).
		 */
		static File createMelodicWavFile(double frequency, double durationSeconds) {
			if (durationSeconds > 5.0) {
				throw new IllegalArgumentException("Test WAV duration must be <= 5 seconds");
			}

			try {
				File tempFile = Files.createTempFile("test_melodic_", ".wav").toFile();
				tempFile.deleteOnExit();

				int sampleRate = OutputLine.sampleRate;
				int numFrames = (int) (durationSeconds * sampleRate);
				int numChannels = 1;
				int validBits = 16;

				double[][] buffer = new double[numChannels][numFrames];
				double phaseIncrement = 2.0 * Math.PI * frequency / sampleRate;
				double phase = 0.0;

				for (int i = 0; i < numFrames; i++) {
					buffer[0][i] = 0.8 * Math.sin(phase);
					phase += phaseIncrement;
				}

				try (WavFile wav = WavFile.newWavFile(tempFile, numChannels, numFrames, validBits, sampleRate)) {
					wav.writeFrames(buffer, numFrames);
				}

				return tempFile;
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to create melodic test WAV file", e);
			}
		}

		/**
		 * Creates a WAV file containing a percussive sound: a burst of
		 * filtered noise with exponential amplitude decay. The {@code frequency}
		 * parameter controls a simple resonant emphasis that colours the noise,
		 * giving each hit a slightly different character.
		 */
		static File createPercussiveWavFile(double frequency, double durationSeconds) {
			if (durationSeconds > 5.0) {
				throw new IllegalArgumentException("Test WAV duration must be <= 5 seconds");
			}

			try {
				File tempFile = Files.createTempFile("test_percussive_", ".wav").toFile();
				tempFile.deleteOnExit();

				int sampleRate = OutputLine.sampleRate;
				int numFrames = (int) (durationSeconds * sampleRate);
				int numChannels = 1;
				int validBits = 16;

				double[][] buffer = new double[numChannels][numFrames];
				java.util.Random rng = new java.util.Random(Double.doubleToLongBits(frequency));

				double decayRate = 5.0 / durationSeconds;
				double phaseIncrement = 2.0 * Math.PI * frequency / sampleRate;
				double phase = 0.0;

				for (int i = 0; i < numFrames; i++) {
					double t = (double) i / sampleRate;
					double envelope = Math.exp(-decayRate * t);
					double noise = rng.nextDouble() * 2.0 - 1.0;
					double tone = Math.sin(phase);
					buffer[0][i] = 0.8 * envelope * (0.7 * noise + 0.3 * tone);
					phase += phaseIncrement;
				}

				try (WavFile wav = WavFile.newWavFile(tempFile, numChannels, numFrames, validBits, sampleRate)) {
					wav.writeFrames(buffer, numFrames);
				}

				return tempFile;
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to create percussive test WAV file", e);
			}
		}
	}
}
