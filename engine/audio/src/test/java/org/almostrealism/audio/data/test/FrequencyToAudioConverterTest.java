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
	 * Energy placed in a single frequency bin must come out at that bin's frequency.
	 * Each transform frame spans {@code 2 * BINS} samples, so bin 3 completes three
	 * cycles per frame. Measuring the energy at that frequency and at an unrelated
	 * bin over every frame-length block of the output distinguishes the real part of
	 * the inverse transform from any other column of its output, which would carry
	 * the same normalized peak but not the requested tone.
	 */
	@Test(timeout = 120000)
	public void convertedAudioCarriesTheRequestedTone() {
		WaveDetails details = tonalDetails();
		new FrequencyToAudioConverter(new Random(1234)).convert(details);

		double[] audio = details.getData().toArray(0, details.getData().getMemLength());
		double toneEnergy = blockEnergy(audio, 3);
		double offToneEnergy = blockEnergy(audio, 7);

		assertTrue("No energy at the requested tone", toneEnergy > 1e-3);
		assertTrue("Tone energy " + toneEnergy + " does not dominate off-tone energy " + offToneEnergy,
				toneEnergy > 20 * offToneEnergy);
	}

	/**
	 * Sums, over every transform-frame-length block of the signal, the squared
	 * magnitude of the block's discrete Fourier coefficient at the given bin.
	 *
	 * @param signal the samples to measure
	 * @param bin    the bin, in cycles per block
	 * @return the total energy at that bin
	 */
	private static double blockEnergy(double[] signal, int bin) {
		int block = 2 * BINS;
		double energy = 0.0;

		for (int start = 0; start + block <= signal.length; start += block) {
			double re = 0.0;
			double im = 0.0;

			for (int n = 0; n < block; n++) {
				double angle = 2 * Math.PI * bin * n / block;
				re += signal[start + n] * Math.cos(angle);
				im -= signal[start + n] * Math.sin(angle);
			}

			energy += re * re + im * im;
		}

		return energy;
	}

	/**
	 * Conversion must reject details that carry no frequency data rather than
	 * dereferencing a missing spectrum.
	 */
	@Test(timeout = 120000)
	public void missingFrequencyDataIsRejected() {
		WaveDetails details = new WaveDetails("empty", SAMPLE_RATE);
		details.setFreqBinCount(BINS);
		details.setFreqFrameCount(FRAMES);
		details.setFreqSampleRate(FREQ_SAMPLE_RATE);
		details.setSampleRate(SAMPLE_RATE);

		assertConvertRejects(details);
		Assert.assertNull("No audio should be produced for missing frequency data", details.getData());
	}

	/**
	 * Non-positive frequency dimensions describe no spectrum and must be rejected
	 * before any synthesis buffer is allocated.
	 */
	@Test(timeout = 120000)
	public void invalidFrequencyDimensionsAreRejected() {
		WaveDetails details = tonalDetails();
		details.setFreqFrameCount(0);

		assertConvertRejects(details);
		Assert.assertNull("No audio should be produced for invalid dimensions", details.getData());
	}

	/**
	 * Asserts that converting the given details raises {@link IllegalArgumentException}.
	 *
	 * @param details the details expected to be rejected
	 */
	private void assertConvertRejects(WaveDetails details) {
		try {
			new FrequencyToAudioConverter(new Random(1234)).convert(details);
			Assert.fail("Conversion should have been rejected");
		} catch (IllegalArgumentException expected) {
			// expected
		}
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
