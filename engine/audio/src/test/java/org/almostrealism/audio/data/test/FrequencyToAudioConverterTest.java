/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.FrequencyToAudioConverter;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies that {@link FrequencyToAudioConverter} reconstructs audio with the
 * properties its stages are responsible for: the requested number of frames, a
 * signal that is actually present, and amplitude held inside the headroom the
 * normalization stage is supposed to leave.
 */
public class FrequencyToAudioConverterTest implements TestFeatures {
	/** Frequency bins per analysis frame. */
	private static final int BINS = 16;

	/**
	 * Number of analysis frames in the synthetic input.
	 *
	 * <p>Chosen so the reconstruction is long enough to exercise normalization at a
	 * realistic length. A short buffer will compile a peak reduction that has been
	 * inlined per element, and so would not detect that regression.</p>
	 */
	private static final int FRAMES = 256;

	/** Sample rate of the reconstructed audio. */
	private static final int SAMPLE_RATE = 8000;

	/** Rate at which analysis frames occur. */
	private static final double FREQ_SAMPLE_RATE = 500.0;

	/**
	 * Builds frequency data with energy concentrated in one bin.
	 *
	 * @return the details to convert
	 */
	private WaveDetails tonalDetails() {
		PackedCollection freqData = new PackedCollection(shape(FRAMES, BINS));

		for (int f = 0; f < FRAMES; f++) {
			freqData.setMem(f * BINS + 3, 1.0);
		}

		WaveDetails details = new WaveDetails("tone", SAMPLE_RATE);
		details.setFreqData(freqData);
		details.setFreqBinCount(BINS);
		details.setFreqFrameCount(FRAMES);
		details.setFreqSampleRate(FREQ_SAMPLE_RATE);
		details.setSampleRate(SAMPLE_RATE);
		return details;
	}

	/**
	 * The converted audio should fill the expected number of frames, carry
	 * signal, and stay within the normalization headroom.
	 */
	@Test(timeout = 120000)
	public void convertProducesNormalizedAudio() {
		WaveDetails details = tonalDetails();
		new FrequencyToAudioConverter(new Random(1234)).convert(details);

		PackedCollection audio = details.getData();
		Assert.assertNotNull("Converter produced no audio", audio);

		int expected = (int) (FRAMES * SAMPLE_RATE / FREQ_SAMPLE_RATE);
		Assert.assertEquals(expected, details.getFrameCount());
		Assert.assertEquals(expected, audio.getMemLength());

		assertAllFinite("Audio contains a non-finite sample", audio);

		double peak = max(cp(audio).abs()).evaluate().toDouble(0);

		assertTrue("Converted audio is silent", peak > 1e-6);
		assertTrue("Normalization left no headroom: peak was " + peak, peak <= 0.9 + 1e-4);
		Assert.assertEquals("Normalization should scale the peak to the headroom",
				0.9, peak, 1e-3);
	}

	/**
	 * Frequency data that is entirely zero should stay silent rather than being
	 * scaled by a division that has no meaningful peak to divide by.
	 */
	@Test(timeout = 120000)
	public void silentInputStaysSilent() {
		WaveDetails details = tonalDetails();
		details.getFreqData().clear();

		new FrequencyToAudioConverter(new Random(1234)).convert(details);

		assertAllFinite("Silent input produced a non-finite sample", details.getData());

		Assert.assertEquals("Silent input must stay silent",
				0.0, largestDeviation(0.0, details.getData()), 1e-9);
	}
}
