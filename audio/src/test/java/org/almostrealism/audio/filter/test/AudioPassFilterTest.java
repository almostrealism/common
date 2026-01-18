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

package org.almostrealism.audio.filter.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.test.support.TestAudioData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link AudioPassFilter} covering high-pass and low-pass
 * filtering with synthetic test signals.
 */
public class AudioPassFilterTest implements CellFeatures, TestFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;
	private static final double DURATION = 0.5; // seconds

	/**
	 * Applies a filter to input data and returns the filtered output.
	 */
	private PackedCollection applyFilter(AudioPassFilter filter, PackedCollection input) {
		PackedCollection output = new PackedCollection(input.getMemLength());
		PackedCollection current = new PackedCollection(1);

		Evaluable<PackedCollection> ev = filter.getResultant(p(current)).get();
		Runnable tick = filter.tick().get();

		for (int i = 0; i < input.getMemLength(); i++) {
			current.setMem(input.toDouble(i));
			output.setMem(i, ev.evaluate().toDouble(0));
			tick.run();
		}

		return output;
	}

	/**
	 * Tests that a high-pass filter attenuates low frequencies.
	 */
	@Test
	public void highPassAttenuatesLowFrequencies() {
		// Create a low-frequency signal (100 Hz) below the cutoff
		PackedCollection lowFreqSignal = TestAudioData.sineWave(100.0, DURATION, SAMPLE_RATE, 1.0);

		// High-pass filter with 500 Hz cutoff
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(500), scalar(0.1), true);

		PackedCollection filtered = applyFilter(filter, lowFreqSignal);

		double inputRms = TestAudioData.rms(lowFreqSignal);
		double outputRms = TestAudioData.rms(filtered);

		// Output should be significantly attenuated (at least 50% reduction)
		Assert.assertTrue("High-pass should attenuate 100 Hz signal with 500 Hz cutoff",
				outputRms < inputRms * 0.5);
	}

	/**
	 * Tests that a high-pass filter passes high frequencies.
	 */
	@Test
	public void highPassPassesHighFrequencies() {
		// Create a high-frequency signal (2000 Hz) above the cutoff
		PackedCollection highFreqSignal = TestAudioData.sineWave(2000.0, DURATION, SAMPLE_RATE, 1.0);

		// High-pass filter with 500 Hz cutoff
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(500), scalar(0.1), true);

		PackedCollection filtered = applyFilter(filter, highFreqSignal);

		double inputRms = TestAudioData.rms(highFreqSignal);
		double outputRms = TestAudioData.rms(filtered);

		// Output should retain most of the signal (at least 50%)
		Assert.assertTrue("High-pass should pass 2000 Hz signal with 500 Hz cutoff",
				outputRms > inputRms * 0.5);
	}

	/**
	 * Tests that a low-pass filter attenuates high frequencies.
	 */
	@Test
	public void lowPassAttenuatesHighFrequencies() {
		// Create a high-frequency signal (5000 Hz) above the cutoff
		PackedCollection highFreqSignal = TestAudioData.sineWave(5000.0, DURATION, SAMPLE_RATE, 1.0);

		// Low-pass filter with 1000 Hz cutoff
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(1000), scalar(0.1), false);

		PackedCollection filtered = applyFilter(filter, highFreqSignal);

		double inputRms = TestAudioData.rms(highFreqSignal);
		double outputRms = TestAudioData.rms(filtered);

		// Output should be significantly attenuated (at least 50% reduction)
		Assert.assertTrue("Low-pass should attenuate 5000 Hz signal with 1000 Hz cutoff",
				outputRms < inputRms * 0.5);
	}

	/**
	 * Tests that a low-pass filter passes low frequencies.
	 */
	@Test
	public void lowPassPassesLowFrequencies() {
		// Create a low-frequency signal (100 Hz) below the cutoff
		PackedCollection lowFreqSignal = TestAudioData.sineWave(100.0, DURATION, SAMPLE_RATE, 1.0);

		// Low-pass filter with 1000 Hz cutoff
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(1000), scalar(0.1), false);

		PackedCollection filtered = applyFilter(filter, lowFreqSignal);

		double inputRms = TestAudioData.rms(lowFreqSignal);
		double outputRms = TestAudioData.rms(filtered);

		// Output should retain most of the signal (at least 50%)
		Assert.assertTrue("Low-pass should pass 100 Hz signal with 1000 Hz cutoff",
				outputRms > inputRms * 0.5);
	}

	/**
	 * Tests that filter can process data without throwing exceptions.
	 */
	@Test
	public void filterProcessesData() {
		PackedCollection signal = TestAudioData.sineWave(440.0, 0.1, SAMPLE_RATE, 0.5);

		AudioPassFilter lowPass = new AudioPassFilter(SAMPLE_RATE, c(1000), scalar(0.1), false);

		// Should not throw
		PackedCollection output = applyFilter(lowPass, signal);
		Assert.assertNotNull("Filter should produce output", output);
		Assert.assertEquals("Output length should match input",
				signal.getMemLength(), output.getMemLength());
	}

	/**
	 * Tests filter sample rate getter/setter.
	 */
	@Test
	public void sampleRateAccessors() {
		AudioPassFilter filter = new AudioPassFilter(44100, c(1000), scalar(0.1), true);

		Assert.assertEquals("Sample rate should be 44100", 44100, filter.getSampleRate());

		filter.setSampleRate(48000);
		Assert.assertEquals("Sample rate should be updated to 48000", 48000, filter.getSampleRate());
	}

	/**
	 * Tests filter isHigh() returns correct value.
	 */
	@Test
	public void isHighAccessor() {
		AudioPassFilter highPass = new AudioPassFilter(SAMPLE_RATE, c(1000), scalar(0.1), true);
		AudioPassFilter lowPass = new AudioPassFilter(SAMPLE_RATE, c(1000), scalar(0.1), false);

		Assert.assertTrue("High-pass filter should return true for isHigh()", highPass.isHigh());
		Assert.assertFalse("Low-pass filter should return false for isHigh()", lowPass.isHigh());
	}

	/**
	 * Tests that filter reset method can be called.
	 * Note: AudioPassFilter cannot be reused for filtering after
	 * getResultant is called, but reset() can still be called.
	 */
	@Test
	public void filterResetCallable() {
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(1000), scalar(0.1), true);

		// Reset should not throw on fresh filter
		filter.reset();

		// Filter should still work after reset on fresh filter
		PackedCollection signal = TestAudioData.sineWave(500.0, 0.1, SAMPLE_RATE);
		PackedCollection output = applyFilter(filter, signal);
		Assert.assertFalse("Filter should produce output",
				TestAudioData.isSilent(output, 0.001));
	}

	/**
	 * Tests that high-pass filter with very low cutoff passes most signals.
	 */
	@Test
	public void highPassVeryLowCutoff() {
		// Any audio signal at 440 Hz
		PackedCollection signal = TestAudioData.sineWave(440.0, DURATION, SAMPLE_RATE, 1.0);

		// High-pass with very low cutoff (20 Hz - subsonic)
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(20), scalar(0.1), true);

		PackedCollection filtered = applyFilter(filter, signal);

		double inputRms = TestAudioData.rms(signal);
		double outputRms = TestAudioData.rms(filtered);

		// Signal should pass through mostly unchanged
		Assert.assertTrue("High-pass with 20 Hz cutoff should pass 440 Hz signal",
				outputRms > inputRms * 0.7);
	}

	/**
	 * Tests that low-pass filter with very high cutoff passes most signals.
	 */
	@Test
	public void lowPassVeryHighCutoff() {
		// Any audio signal at 440 Hz
		PackedCollection signal = TestAudioData.sineWave(440.0, DURATION, SAMPLE_RATE, 1.0);

		// Low-pass with very high cutoff (15000 Hz)
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(15000), scalar(0.1), false);

		PackedCollection filtered = applyFilter(filter, signal);

		double inputRms = TestAudioData.rms(signal);
		double outputRms = TestAudioData.rms(filtered);

		// Signal should pass through mostly unchanged
		Assert.assertTrue("Low-pass with 15000 Hz cutoff should pass 440 Hz signal",
				outputRms > inputRms * 0.7);
	}

	/**
	 * Tests filter output stays within reasonable bounds.
	 */
	@Test
	public void outputWithinBounds() {
		PackedCollection signal = TestAudioData.sineWave(1000.0, DURATION, SAMPLE_RATE, 1.0);

		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(500), scalar(0.5), true);
		PackedCollection filtered = applyFilter(filter, signal);

		// Output should not exceed input amplitude significantly (with some tolerance for resonance)
		double outputPeak = TestAudioData.peak(filtered);
		Assert.assertTrue("Filter output should not exceed 2x input amplitude",
				outputPeak <= 2.0);
	}
}
