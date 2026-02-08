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

package org.almostrealism.audio.pattern.test;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;

import static org.junit.Assert.*;

/**
 * Audio statistics for verifying correctness of rendered audio.
 *
 * <p>This record captures key metrics about an audio file that can be used
 * to verify that the audio contains actual content (not silence) and meets
 * expected quality thresholds.</p>
 *
 * <h2>Correctness Verification</h2>
 *
 * <p>Use the assertion methods to verify audio correctness:</p>
 * <ul>
 *   <li>{@link #assertNonSilent(String)} - verifies audio is not silent</li>
 *   <li>{@link #assertValidAudio(String)} - verifies audio meets quality thresholds</li>
 * </ul>
 *
 * <h2>Interpretation Guide</h2>
 *
 * <table border="1">
 *   <tr><th>Metric</th><th>Silent</th><th>Marginal</th><th>Good</th></tr>
 *   <tr><td>maxAmplitude</td><td>&lt; 0.001</td><td>0.001-0.01</td><td>&gt; 0.01</td></tr>
 *   <tr><td>rmsLevel</td><td>&lt; 0.0001</td><td>0.0001-0.001</td><td>&gt; 0.001</td></tr>
 *   <tr><td>nonZeroRatio</td><td>&lt; 1%</td><td>1-10%</td><td>&gt; 10%</td></tr>
 * </table>
 *
 * @param frameCount     total number of audio frames
 * @param durationSeconds duration in seconds
 * @param maxAmplitude   maximum absolute amplitude (0.0 to 1.0)
 * @param rmsLevel       root-mean-square amplitude level
 * @param nonZeroRatio   fraction of samples with amplitude &gt; 0.0001
 *
 * @see RealTimeTestHelper#analyzeAudio(String)
 */
public record AudioStats(
		int frameCount,
		double durationSeconds,
		double maxAmplitude,
		double rmsLevel,
		double nonZeroRatio
) {

	/** Threshold below which audio is considered silent. */
	public static final double SILENCE_AMPLITUDE_THRESHOLD = 0.001;

	/** Threshold below which RMS is considered too low. */
	public static final double SILENCE_RMS_THRESHOLD = 0.0001;

	/** Minimum non-zero ratio for valid audio. */
	public static final double MIN_NONZERO_RATIO = 0.01;

	/**
	 * Creates AudioStats from WaveData.
	 *
	 * @param data       the wave data to analyze
	 * @param sampleRate sample rate for duration calculation
	 * @return computed statistics
	 */
	public static AudioStats fromWaveData(WaveData data, int sampleRate) {
		int frameCount = data.getFrameCount();
		double duration = (double) frameCount / sampleRate;

		PackedCollection channel0 = data.getChannelData(0);
		int length = channel0.getShape().getTotalSize();

		double maxAmplitude = 0;
		double sumSquares = 0;
		int nonZeroCount = 0;

		for (int i = 0; i < length; i++) {
			double val = Math.abs(channel0.valueAt(i));
			if (val > maxAmplitude) maxAmplitude = val;
			sumSquares += val * val;
			if (val > 0.0001) nonZeroCount++;
		}

		double rms = Math.sqrt(sumSquares / length);
		double nonZeroRatio = (double) nonZeroCount / length;

		return new AudioStats(frameCount, duration, maxAmplitude, rms, nonZeroRatio);
	}

	/**
	 * Creates AudioStats from a PackedCollection buffer.
	 *
	 * @param buffer     the audio buffer to analyze
	 * @param sampleRate sample rate for duration calculation
	 * @return computed statistics
	 */
	public static AudioStats fromBuffer(PackedCollection buffer, int sampleRate) {
		int length = buffer.getMemLength();
		double duration = (double) length / sampleRate;

		double maxAmplitude = 0;
		double sumSquares = 0;
		int nonZeroCount = 0;

		for (int i = 0; i < length; i++) {
			double val = Math.abs(buffer.valueAt(i));
			if (val > maxAmplitude) maxAmplitude = val;
			sumSquares += val * val;
			if (val > 0.0001) nonZeroCount++;
		}

		double rms = Math.sqrt(sumSquares / length);
		double nonZeroRatio = (double) nonZeroCount / length;

		return new AudioStats(length, duration, maxAmplitude, rms, nonZeroRatio);
	}

	/**
	 * Returns true if the audio appears to be silent.
	 *
	 * <p>Audio is considered silent if max amplitude is below the
	 * silence threshold ({@value #SILENCE_AMPLITUDE_THRESHOLD}).</p>
	 */
	public boolean isSilent() {
		return maxAmplitude < SILENCE_AMPLITUDE_THRESHOLD;
	}

	/**
	 * Returns true if the audio has marginal (very low) levels.
	 *
	 * <p>Marginal audio has amplitude above silence but below typical
	 * listening levels. This may indicate a problem with rendering.</p>
	 */
	public boolean isMarginal() {
		return !isSilent() && (rmsLevel < 0.001 || nonZeroRatio < 0.1);
	}

	/**
	 * Asserts that the audio is not silent.
	 *
	 * <p><b>Correctness property:</b> This verifies that the rendering
	 * pipeline produced actual audio content, not just zeros.</p>
	 *
	 * @param message failure message prefix
	 * @throws AssertionError if audio is silent
	 */
	public void assertNonSilent(String message) {
		assertTrue(message + " - max amplitude too low (got " +
						String.format("%.6f", maxAmplitude) + ", need > " + SILENCE_AMPLITUDE_THRESHOLD + ")",
				maxAmplitude > SILENCE_AMPLITUDE_THRESHOLD);

		assertTrue(message + " - RMS level too low (got " +
						String.format("%.6f", rmsLevel) + ", need > " + SILENCE_RMS_THRESHOLD + ")",
				rmsLevel > SILENCE_RMS_THRESHOLD);
	}

	/**
	 * Asserts that the audio meets quality thresholds for valid content.
	 *
	 * <p><b>Correctness property:</b> This verifies that the audio has
	 * reasonable amplitude levels and a significant portion of non-zero
	 * samples, indicating proper sample data throughout (not just a
	 * brief blip followed by silence).</p>
	 *
	 * @param message failure message prefix
	 * @throws AssertionError if audio doesn't meet thresholds
	 */
	public void assertValidAudio(String message) {
		assertNonSilent(message);

		assertTrue(message + " - non-zero ratio too low (got " +
						String.format("%.1f%%", nonZeroRatio * 100) + ", need > " +
						String.format("%.0f%%", MIN_NONZERO_RATIO * 100) + ")",
				nonZeroRatio > MIN_NONZERO_RATIO);
	}

	/**
	 * Returns a human-readable summary of the statistics.
	 */
	@Override
	public String toString() {
		return String.format(
				"AudioStats[frames=%d, duration=%.2fs, max=%.6f, rms=%.6f, nonZero=%.1f%%, silent=%s]",
				frameCount, durationSeconds, maxAmplitude, rmsLevel,
				nonZeroRatio * 100, isSilent() ? "YES" : "NO");
	}
}
