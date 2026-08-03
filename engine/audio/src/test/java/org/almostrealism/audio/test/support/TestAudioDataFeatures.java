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

import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;
import java.util.stream.IntStream;

/**
 * Generates synthetic audio test data, so tests do not require external audio files.
 *
 * <p>Implemented as a features mixin rather than a holder of static methods: a test
 * implements this interface and calls the generators directly, which also gives the
 * generators access to the {@link CodeFeatures} operations they are built from.</p>
 */
public interface TestAudioDataFeatures extends CodeFeatures {

	/** Default sample rate matching OutputLine.sampleRate (44100 Hz) */
	int DEFAULT_SAMPLE_RATE = OutputLine.sampleRate;

	/** Standard test frequency for A4 (440 Hz) */
	double A4_FREQUENCY = 440.0;

	/** Standard test amplitude (0.5 to avoid clipping) */
	double DEFAULT_AMPLITUDE = 0.5;

	/** Random number generator with fixed seed for reproducible test data. */
	Random NOISE_SOURCE = new Random(42);

	/**
	 * Generates a sine wave at the specified frequency.
	 *
	 * @param frequency   Frequency in Hz
	 * @param duration    Duration in seconds
	 * @param sampleRate  Sample rate in Hz
	 * @return PackedCollection containing the sine wave samples
	 */
	default PackedCollection sineWave(double frequency, double duration, int sampleRate) {
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
	default PackedCollection sineWave(double frequency, double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);
		double angularFrequency = 2 * Math.PI * frequency / sampleRate;

		return sin(integers(0, samples).multiply(angularFrequency))
				.multiply(amplitude).evaluate();
	}

	/**
	 * Generates a sine wave with default sample rate (44100 Hz).
	 *
	 * @param frequency Frequency in Hz
	 * @param duration  Duration in seconds
	 * @return PackedCollection containing the sine wave samples
	 */
	default PackedCollection sineWave(double frequency, double duration) {
		return sineWave(frequency, duration, DEFAULT_SAMPLE_RATE);
	}

	/**
	 * Generates a 440 Hz (A4) sine wave with default settings.
	 *
	 * @param duration Duration in seconds
	 * @return PackedCollection containing the sine wave samples
	 */
	default PackedCollection a440(double duration) {
		return sineWave(A4_FREQUENCY, duration);
	}

	/**
	 * Generates white noise with uniform distribution.
	 *
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @return PackedCollection containing white noise samples
	 */
	default PackedCollection whiteNoise(double duration, int sampleRate) {
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
	default PackedCollection whiteNoise(double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);

		return rand(shape(samples), NOISE_SOURCE)
				.multiply(2.0).add(-1.0).multiply(amplitude).evaluate();
	}

	/**
	 * Generates white noise with default sample rate.
	 *
	 * @param duration Duration in seconds
	 * @return PackedCollection containing white noise samples
	 */
	default PackedCollection whiteNoise(double duration) {
		return whiteNoise(duration, DEFAULT_SAMPLE_RATE);
	}

	/**
	 * Generates an impulse signal (single sample = 1.0, rest = 0.0).
	 *
	 * @param length Total length in samples
	 * @return PackedCollection containing the impulse
	 */
	default PackedCollection impulse(int length) {
		return impulse(length, 0);
	}

	/**
	 * Generates an impulse signal at a specific position.
	 *
	 * @param length   Total length in samples
	 * @param position Position of the impulse (0-indexed)
	 * @return PackedCollection containing the impulse
	 */
	default PackedCollection impulse(int length, int position) {
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
	default PackedCollection ramp(int length) {
		return integers(0, length).divide(length - 1.0).evaluate();
	}

	/**
	 * Generates a linear ramp from start to end values.
	 *
	 * @param length Length in samples
	 * @param start  Starting value
	 * @param end    Ending value
	 * @return PackedCollection containing the ramp
	 */
	default PackedCollection ramp(int length, double start, double end) {
		double step = (end - start) / (length - 1);
		return integers(0, length).multiply(step).add(start).evaluate();
	}

	/**
	 * Generates silence (all zeros).
	 *
	 * @param length Length in samples
	 * @return PackedCollection containing silence
	 */
	default PackedCollection silence(int length) {
		return new PackedCollection(length);
	}

	/**
	 * Generates silence for a specified duration.
	 *
	 * @param duration   Duration in seconds
	 * @param sampleRate Sample rate in Hz
	 * @return PackedCollection containing silence
	 */
	default PackedCollection silence(double duration, int sampleRate) {
		return silence((int) (duration * sampleRate));
	}

	/**
	 * Generates a DC (constant value) signal.
	 *
	 * @param length Length in samples
	 * @param value  The constant value
	 * @return PackedCollection containing the DC signal
	 */
	default PackedCollection dc(int length, double value) {
		return new PackedCollection(length).fill(value);
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
	default PackedCollection squareWave(double frequency, double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);
		double period = sampleRate / frequency;

		CollectionProducer phase = integers(0, samples).mod(period).divide(period);
		return greaterThan(c(0.5), phase,
					c(amplitude), c(-amplitude))
				.evaluate();
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
	default PackedCollection sawtoothWave(double frequency, double duration, int sampleRate, double amplitude) {
		int samples = (int) (duration * sampleRate);
		double period = sampleRate / frequency;

		return integers(0, samples).mod(period).divide(period)
				.multiply(2.0).add(-1.0).multiply(amplitude).evaluate();
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
	default PackedCollection chirp(double startFreq, double endFreq, double duration, int sampleRate) {
		int samples = (int) (duration * sampleRate);
		double freqSlope = (endFreq - startFreq) / duration;

		// Accumulated phase in closed form: summing the per-sample increment
		// 2*pi*(startFreq + freqSlope*i/sampleRate)/sampleRate over 0..i gives
		// a term linear in (i + 1) and a term in i*(i + 1).
		double linear = 2 * Math.PI * startFreq / sampleRate;
		double quadratic = Math.PI * freqSlope / (sampleRate * (double) sampleRate);

		CollectionProducer index = integers(0, samples);
		CollectionProducer next = index.add(1.0);
		CollectionProducer phase = next.multiply(linear)
				.add(index.multiply(next).multiply(quadratic));

		PackedCollection data = sin(phase).multiply(DEFAULT_AMPLITUDE).evaluate();

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
	default PackedCollection harmonics(double fundamental, int harmonicCount, double duration, int sampleRate) {
		int samples = (int) (duration * sampleRate);

		CollectionProducer index = integers(0, samples);
		CollectionProducer sum = null;

		for (int h = 1; h <= harmonicCount; h++) {
			double angularFrequency = 2 * Math.PI * fundamental * h / sampleRate;
			// Natural harmonic decay
			CollectionProducer harmonic =
					sin(index.multiply(angularFrequency)).multiply(1.0 / h);
			sum = sum == null ? harmonic : sum.add(harmonic);
		}

		PackedCollection data = sum.multiply(DEFAULT_AMPLITUDE / harmonicCount).evaluate();

		return data;
	}

	/**
	 * Creates a WaveData object from a PackedCollection.
	 *
	 * @param samples    The audio samples
	 * @param sampleRate The sample rate
	 * @return WaveData wrapping the samples
	 */
	default WaveData toWaveData(PackedCollection samples, int sampleRate) {
		return new WaveData(samples, sampleRate);
	}

	/**
	 * Creates a WaveData object from a PackedCollection with default sample rate.
	 *
	 * @param samples The audio samples
	 * @return WaveData wrapping the samples
	 */
	default WaveData toWaveData(PackedCollection samples) {
		return toWaveData(samples, DEFAULT_SAMPLE_RATE);
	}

	/**
	 * Calculates the RMS (Root Mean Square) of a signal.
	 *
	 * @param data The audio data
	 * @return RMS value
	 */
	default double rms(PackedCollection data) {
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
	default double peak(PackedCollection data) {
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
	default double estimateFrequency(PackedCollection data, int sampleRate) {
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
	default boolean isWithinBounds(PackedCollection data, double maxAmplitude) {
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
	default boolean isSilent(PackedCollection data, double threshold) {
		return peak(data) < threshold;
	}

	/**
	 * Checks if signal is essentially silence with default threshold (0.0001).
	 *
	 * @param data The audio data
	 * @return true if the signal is essentially silent
	 */
	default boolean isSilent(PackedCollection data) {
		return isSilent(data, 0.0001);
	}
}
