/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.audio.test.support;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;
import java.util.stream.IntStream;

/**
 * Utility class for generating synthetic audio test data.
 * Provides methods to create various audio signals for testing without
 * requiring external audio files.
 */
public class TestAudioData {

	/** Default sample rate matching OutputLine.sampleRate (44100 Hz) */
	public static final int DEFAULT_SAMPLE_RATE = OutputLine.sampleRate;

	/** Standard test frequency for A4 (440 Hz) */
	public static final double A4_FREQUENCY = 440.0;

	/** Standard test amplitude (0.5 to avoid clipping) */
	public static final double DEFAULT_AMPLITUDE = 0.5;

	private static final Random random = new Random(42);

	/**
	 * Generates a sine wave at the specified frequency.
	 *
	 * @param frequency   Frequency in Hz
	 * @param duration    Duration in seconds
	 * @param sampleRate  Sample rate in Hz
	 * @return PackedCollection containing the sine wave samples
	 */
	public static PackedCollection sineWave(double frequency, double duration, int sampleRate) {
		return sineWave(frequency, duration, sampleRate, DEFAULT_AMPLITUDE);
	}

	/**
	 * Generates a sine wave at the specified frequency and amplitude.
	 *
	 * @param frequency   Frequency in Hz
	 * @param duration    Duration in seconds
	 * @param sampleRate  Sample rate in Hz
	 * @param amplitude   Amplitude (0.0 to 1.0)
	 * @return PackedCollection containing the sine wave samples
	 */
	public static PackedCollection sineWave(double frequency, double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);
		PackedCollection data = new PackedCollection(samples);
		double angularFrequency = 2 * Math.PI * frequency / sampleRate;

		for (int i = 0; i < samples; i++) {
			data.setMem(i, amplitude * Math.sin(angularFrequency * i));
		}

		return data;
	}

	/**
	 * Generates a sine wave with default sample rate (44100 Hz).
	 *
	 * @param frequency Frequency in Hz
	 * @param duration  Duration in seconds
	 * @return PackedCollection containing the sine wave samples
	 */
	public static PackedCollection sineWave(double frequency, double duration) {
		return sineWave(frequency, duration, DEFAULT_SAMPLE_RATE);
	}

	/**
	 * Generates a 440 Hz (A4) sine wave with default settings.
	 *
	 * @param duration Duration in seconds
	 * @return PackedCollection containing the sine wave samples
	 */
	public static PackedCollection a440(double duration) {
		return sineWave(A4_FREQUENCY, duration);
	}

	/**
	 * Generates white noise with uniform distribution.
	 *
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @return PackedCollection containing white noise samples
	 */
	public static PackedCollection whiteNoise(double duration, int sampleRate) {
		return whiteNoise(duration, sampleRate, DEFAULT_AMPLITUDE);
	}

	/**
	 * Generates white noise with uniform distribution.
	 *
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @param amplitude  Maximum amplitude
	 * @return PackedCollection containing white noise samples
	 */
	public static PackedCollection whiteNoise(double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);
		PackedCollection data = new PackedCollection(samples);

		for (int i = 0; i < samples; i++) {
			data.setMem(i, amplitude * (2 * random.nextDouble() - 1));
		}

		return data;
	}

	/**
	 * Generates white noise with default sample rate.
	 *
	 * @param duration Duration in seconds
	 * @return PackedCollection containing white noise samples
	 */
	public static PackedCollection whiteNoise(double duration) {
		return whiteNoise(duration, DEFAULT_SAMPLE_RATE);
	}

	/**
	 * Generates an impulse signal (single sample = 1.0, rest = 0.0).
	 *
	 * @param length Total length in samples
	 * @return PackedCollection containing the impulse
	 */
	public static PackedCollection impulse(int length) {
		return impulse(length, 0);
	}

	/**
	 * Generates an impulse signal at a specific position.
	 *
	 * @param length   Total length in samples
	 * @param position Position of the impulse (0-indexed)
	 * @return PackedCollection containing the impulse
	 */
	public static PackedCollection impulse(int length, int position) {
		PackedCollection data = new PackedCollection(length);
		// All zeros by default, set impulse at position
		if (position >= 0 && position < length) {
			data.setMem(position, 1.0);
		}
		return data;
	}

	/**
	 * Generates a linear ramp from 0 to 1.
	 *
	 * @param length Length in samples
	 * @return PackedCollection containing the ramp
	 */
	public static PackedCollection ramp(int length) {
		PackedCollection data = new PackedCollection(length);
		for (int i = 0; i < length; i++) {
			data.setMem(i, (double) i / (length - 1));
		}
		return data;
	}

	/**
	 * Generates a linear ramp from start to end values.
	 *
	 * @param length Length in samples
	 * @param start  Starting value
	 * @param end    Ending value
	 * @return PackedCollection containing the ramp
	 */
	public static PackedCollection ramp(int length, double start, double end) {
		PackedCollection data = new PackedCollection(length);
		double step = (end - start) / (length - 1);
		for (int i = 0; i < length; i++) {
			data.setMem(i, start + step * i);
		}
		return data;
	}

	/**
	 * Generates silence (all zeros).
	 *
	 * @param length Length in samples
	 * @return PackedCollection containing silence
	 */
	public static PackedCollection silence(int length) {
		return new PackedCollection(length);
	}

	/**
	 * Generates silence for a specified duration.
	 *
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @return PackedCollection containing silence
	 */
	public static PackedCollection silence(double duration, int sampleRate) {
		return silence((int) (duration * sampleRate));
	}

	/**
	 * Generates a DC (constant value) signal.
	 *
	 * @param length Length in samples
	 * @param value  The constant value
	 * @return PackedCollection containing the DC signal
	 */
	public static PackedCollection dc(int length, double value) {
		PackedCollection data = new PackedCollection(length);
		for (int i = 0; i < length; i++) {
			data.setMem(i, value);
		}
		return data;
	}

	/**
	 * Generates a square wave.
	 *
	 * @param frequency  Frequency in Hz
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @param amplitude  Amplitude
	 * @return PackedCollection containing the square wave
	 */
	public static PackedCollection squareWave(double frequency, double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);
		PackedCollection data = new PackedCollection(samples);
		double period = sampleRate / frequency;

		for (int i = 0; i < samples; i++) {
			double phase = (i % period) / period;
			data.setMem(i, phase < 0.5 ? amplitude : -amplitude);
		}

		return data;
	}

	/**
	 * Generates a sawtooth wave.
	 *
	 * @param frequency  Frequency in Hz
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @param amplitude  Amplitude
	 * @return PackedCollection containing the sawtooth wave
	 */
	public static PackedCollection sawtoothWave(double frequency, double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);
		PackedCollection data = new PackedCollection(samples);
		double period = sampleRate / frequency;

		for (int i = 0; i < samples; i++) {
			double phase = (i % period) / period;
			data.setMem(i, amplitude * (2 * phase - 1));
		}

		return data;
	}

	/**
	 * Generates a chirp (frequency sweep) signal.
	 *
	 * @param startFreq  Starting frequency in Hz
	 * @param endFreq    Ending frequency in Hz
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @return PackedCollection containing the chirp
	 */
	public static PackedCollection chirp(double startFreq, double endFreq, double duration, int sampleRate) {
		int samples = (int) (duration * sampleRate);
		PackedCollection data = new PackedCollection(samples);
		double freqSlope = (endFreq - startFreq) / duration;

		double phase = 0;
		for (int i = 0; i < samples; i++) {
			double t = (double) i / sampleRate;
			double instantFreq = startFreq + freqSlope * t;
			phase += 2 * Math.PI * instantFreq / sampleRate;
			data.setMem(i, DEFAULT_AMPLITUDE * Math.sin(phase));
		}

		return data;
	}

	/**
	 * Generates a signal with multiple harmonics (fundamental + overtones).
	 *
	 * @param fundamental    Fundamental frequency in Hz
	 * @param harmonicCount  Number of harmonics (including fundamental)
	 * @param duration       Duration in seconds
	 * @param sampleRate     Sample rate in Hz
	 * @return PackedCollection containing the harmonic signal
	 */
	public static PackedCollection harmonics(double fundamental, int harmonicCount, double duration, int sampleRate) {
		int samples = (int) (duration * sampleRate);
		PackedCollection data = new PackedCollection(samples);

		for (int i = 0; i < samples; i++) {
			double value = 0;
			for (int h = 1; h <= harmonicCount; h++) {
				double freq = fundamental * h;
				double amplitude = 1.0 / h; // Natural harmonic decay
				value += amplitude * Math.sin(2 * Math.PI * freq * i / sampleRate);
			}
			// Normalize
			data.setMem(i, value * DEFAULT_AMPLITUDE / harmonicCount);
		}

		return data;
	}

	/**
	 * Creates a WaveData object from a PackedCollection.
	 *
	 * @param samples    The audio samples
	 * @param sampleRate The sample rate
	 * @return WaveData wrapping the samples
	 */
	public static WaveData toWaveData(PackedCollection samples, int sampleRate) {
		return new WaveData(samples, sampleRate);
	}

	/**
	 * Creates a WaveData object from a PackedCollection with default sample rate.
	 *
	 * @param samples The audio samples
	 * @return WaveData wrapping the samples
	 */
	public static WaveData toWaveData(PackedCollection samples) {
		return toWaveData(samples, DEFAULT_SAMPLE_RATE);
	}

	/**
	 * Calculates the RMS (Root Mean Square) of a signal.
	 *
	 * @param data The audio data
	 * @return RMS value
	 */
	public static double rms(PackedCollection data) {
		double sum = 0;
		int length = data.getMemLength();
		for (int i = 0; i < length; i++) {
			double sample = data.toDouble(i);
			sum += sample * sample;
		}
		return Math.sqrt(sum / length);
	}

	/**
	 * Calculates the peak amplitude of a signal.
	 *
	 * @param data The audio data
	 * @return Peak amplitude (absolute value)
	 */
	public static double peak(PackedCollection data) {
		double max = 0;
		int length = data.getMemLength();
		for (int i = 0; i < length; i++) {
			double abs = Math.abs(data.toDouble(i));
			if (abs > max) max = abs;
		}
		return max;
	}

	/**
	 * Finds the dominant frequency in a signal using zero-crossing analysis.
	 * This is a simple approximation suitable for pure tones.
	 *
	 * @param data       The audio data
	 * @param sampleRate The sample rate
	 * @return Estimated frequency in Hz
	 */
	public static double estimateFrequency(PackedCollection data, int sampleRate) {
		int zeroCrossings = 0;
		int length = data.getMemLength();
		double prev = data.toDouble(0);

		for (int i = 1; i < length; i++) {
			double current = data.toDouble(i);
			if ((prev >= 0 && current < 0) || (prev < 0 && current >= 0)) {
				zeroCrossings++;
			}
			prev = current;
		}

		double duration = (double) length / sampleRate;
		return zeroCrossings / (2.0 * duration);
	}

	/**
	 * Verifies that a signal contains samples within expected amplitude bounds.
	 *
	 * @param data         The audio data
	 * @param maxAmplitude Maximum expected amplitude
	 * @return true if all samples are within bounds
	 */
	public static boolean isWithinBounds(PackedCollection data, double maxAmplitude) {
		int length = data.getMemLength();
		for (int i = 0; i < length; i++) {
			if (Math.abs(data.toDouble(i)) > maxAmplitude) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks if signal is essentially silence (all samples near zero).
	 *
	 * @param data      The audio data
	 * @param threshold Threshold for considering a sample as silence
	 * @return true if the signal is essentially silent
	 */
	public static boolean isSilent(PackedCollection data, double threshold) {
		return peak(data) < threshold;
	}

	/**
	 * Checks if signal is essentially silence with default threshold (0.0001).
	 *
	 * @param data The audio data
	 * @return true if the signal is essentially silent
	 */
	public static boolean isSilent(PackedCollection data) {
		return isSilent(data, 0.0001);
	}
}
