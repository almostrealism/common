/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataFeatureProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the frequency analysis {@link WaveDetailsFactory} computes for audio
 * with more than one channel.
 */
public class WaveDetailsFactoryTest extends TestSuiteBase {

	/** Sample rate of the analyzed audio. */
	private static final int SAMPLE_RATE = 44100;

	/** Length of the analyzed audio, in seconds. */
	private static final int SECONDS = 2;

	/** Model identifier reported by {@link FixedFeatureProvider}. */
	private static final String MODEL = "fixed-model";

	/** Frame count given to stale frequency data stored before reanalysis. */
	private static final int STALE_FRAMES = 10;

	/**
	 * Each channel's analysis must occupy its own block of the frequency data, laid
	 * out as (channels, frames, bins, 1). A tone in the left channel and silence in
	 * the right must yield energy in every complete frame of channel 0 and none at
	 * all in channel 1; writing a later channel over an earlier one would erase the
	 * tone from channel 0 after its first frame.
	 */
	@Test(timeout = 120000)
	public void stereoChannelsAreAnalyzedSeparately() {
		WaveDetails details = stereoDetails();
		new WaveDetailsFactory(SAMPLE_RATE).forExisting(details);

		Assert.assertEquals(2, details.getFreqChannelCount());
		Assert.assertTrue("Expected several analysis frames, found " + details.getFreqFrameCount(),
				details.getFreqFrameCount() > 2);
		assertLeftToneRightSilence(details);
	}

	/**
	 * Stereo frequency data stored before the current feature model analyzed the
	 * record cannot be trusted, so it must be recomputed from the audio along with
	 * the features. The stale data here is silent in both channels; after
	 * reanalysis the left channel must carry the tone.
	 */
	@Test(timeout = 120000)
	public void staleStereoFrequencyDataIsReanalyzedForANewModel() {
		WaveDetails details = stereoDetails();
		storeStaleFrequencyData(details);

		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		factory.setFeatureProvider(new FixedFeatureProvider());
		factory.forExisting(details);

		Assert.assertNotNull("Features for the current model were not computed",
				details.getFeatures(MODEL));
		Assert.assertNotEquals("Stale frequency data was kept",
				STALE_FRAMES, details.getFreqFrameCount());
		assertLeftToneRightSilence(details);
	}

	/**
	 * A record that already has features for the current model was analyzed after
	 * the channel layout was corrected, so its frequency data is kept as it is.
	 */
	@Test(timeout = 120000)
	public void frequencyDataIsKeptWhenTheCurrentModelHasFeatures() {
		WaveDetails details = stereoDetails();
		storeStaleFrequencyData(details);
		details.putFeatures(new WaveFeatures(MODEL, 1.0, 1, 8, 4, new PackedCollection(4, 8)));
		PackedCollection stored = details.getFreqData();

		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		factory.setFeatureProvider(new FixedFeatureProvider());
		factory.forExisting(details);

		Assert.assertSame("Frequency data was recomputed", stored, details.getFreqData());
		Assert.assertEquals(STALE_FRAMES, details.getFreqFrameCount());
	}

	/**
	 * Reanalyzing stale multi-channel frequency data replaces the stored buffer,
	 * so the superseded one must be released rather than leaked. Its native memory
	 * is potentially GPU-resident and finalization is disabled by default, so
	 * dropping the Java reference alone would hold it until the collector runs.
	 */
	@Test(timeout = 120000)
	public void staleFrequencyBufferIsReleasedWhenReanalyzed() {
		WaveDetails details = stereoDetails();
		storeStaleFrequencyData(details);
		PackedCollection stale = details.getFreqData();

		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		factory.setFeatureProvider(new FixedFeatureProvider());
		factory.forExisting(details);

		Assert.assertNotSame("Stale frequency buffer was kept", stale, details.getFreqData());
		Assert.assertTrue("Superseded frequency buffer was not released", stale.isDestroyed());
	}

	/**
	 * Creates details for two seconds of stereo audio carrying a 440 Hz tone in
	 * the left channel and silence in the right, with no frequency analysis.
	 *
	 * @return the details
	 */
	private WaveDetails stereoDetails() {
		int frames = SAMPLE_RATE * SECONDS;
		PackedCollection audio = concat(0,
				sin(integers(0, frames).multiply(2 * Math.PI * 440.0 / SAMPLE_RATE)).reshape(shape(1, frames)),
				constant(shape(1, frames), 0.0)).evaluate();

		WaveDetails details = new WaveDetails("stereo", SAMPLE_RATE);
		details.setChannelCount(2);
		details.setFrameCount(frames);
		details.setData(audio);
		return details;
	}

	/**
	 * Gives the details silent stereo frequency data, standing in for an analysis
	 * stored before each channel was given its own block.
	 *
	 * @param details the details to update
	 */
	private void storeStaleFrequencyData(WaveDetails details) {
		int bins = WaveDetailsFactory.defaultBins;
		details.setFreqChannelCount(2);
		details.setFreqFrameCount(STALE_FRAMES);
		details.setFreqBinCount(bins);
		details.setFreqSampleRate(1.0);
		details.setFreqData(new PackedCollection(2, STALE_FRAMES, bins, 1));
	}

	/**
	 * Asserts that every complete frame of channel 0 carries energy and that
	 * channel 1 carries none.
	 *
	 * @param details the analyzed details
	 */
	private void assertLeftToneRightSilence(WaveDetails details) {
		int freqFrames = details.getFreqFrameCount();
		int bins = details.getFreqBinCount();
		double[] left = details.getFreqData(0).toArray(0, freqFrames * bins);
		double[] right = details.getFreqData(1).toArray(0, freqFrames * bins);

		for (int f = 0; f < freqFrames - 1; f++) {
			Assert.assertTrue("Left channel frame " + f + " lost its tone",
					frameEnergy(left, f, bins) > 1e-6);
		}

		for (int f = 0; f < freqFrames; f++) {
			Assert.assertEquals("Silent right channel frame " + f + " carries energy",
					0.0, frameEnergy(right, f, bins), 1e-9);
		}
	}

	/**
	 * Sums the magnitudes of one analysis frame.
	 *
	 * @param data  the analysis of one channel, frame after frame
	 * @param frame the frame to measure
	 * @param bins  the number of bins in each frame
	 * @return the total magnitude in the frame
	 */
	private static double frameEnergy(double[] data, int frame, int bins) {
		double energy = 0.0;
		for (int b = 0; b < bins; b++) {
			energy += Math.abs(data[frame * bins + b]);
		}
		return energy;
	}

	/**
	 * A feature provider that reports a fixed model and returns empty features,
	 * so tests can exercise the factory's handling of feature provenance without
	 * running a real model.
	 */
	private static class FixedFeatureProvider implements WaveDataFeatureProvider {
		@Override
		public PackedCollection computeFeatures(WaveData waveData) {
			return new PackedCollection(4, 8);
		}

		@Override
		public int getAudioSampleRate() { return SAMPLE_RATE; }

		@Override
		public double getFeatureSampleRate() { return 1.0; }

		@Override
		public String getModelIdentifier() { return MODEL; }
	}
}
