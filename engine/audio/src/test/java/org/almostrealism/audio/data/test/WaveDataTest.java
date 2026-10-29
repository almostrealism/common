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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.test.support.TestAudioDataFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Tests for {@link WaveData} covering construction, data access,
 * duration calculations, channel handling, and save/load operations.
 */
public class WaveDataTest extends TestSuiteBase implements TestAudioDataFeatures {

	/** Test sample rate derived from {@link OutputLine}. */
	private static final int TEST_SAMPLE_RATE = OutputLine.sampleRate;

	/** Tolerance for floating-point comparisons in duration and sample rate tests. */
	private static final double EPSILON = 0.0001;

	/**
	 * Tests basic construction with explicit channel and frame counts.
	 */
	@Test(timeout = 30000)
	public void constructWithDimensions() {
		WaveData data = new WaveData(2, 44100, TEST_SAMPLE_RATE);

		Assert.assertEquals("Should have 2 channels", 2, data.getChannelCount());
		Assert.assertEquals("Should have 44100 frames", 44100, data.getFrameCount());
		Assert.assertEquals("Sample rate should match", TEST_SAMPLE_RATE, data.getSampleRate());

		data.destroy();
	}

	/**
	 * Tests construction from a PackedCollection.
	 */
	@Test(timeout = 30000)
	public void constructFromPackedCollection() {
		PackedCollection samples = sineWave(440.0, 1.0);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		Assert.assertEquals("Should have 1 channel", 1, data.getChannelCount());
		Assert.assertEquals("Frame count should match sample count",
				samples.getMemLength(), data.getFrameCount());
	}

	/**
	 * Tests duration calculation.
	 */
	@Test(timeout = 30000)
	public void durationCalculation() {
		// 1 second at 44100 Hz
		PackedCollection samples = silence(TEST_SAMPLE_RATE);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		Assert.assertEquals("Duration should be 1.0 second", 1.0, data.getDuration(), EPSILON);

		// 0.5 seconds
		PackedCollection halfSecond = silence(TEST_SAMPLE_RATE / 2);
		WaveData halfData = new WaveData(halfSecond, TEST_SAMPLE_RATE);

		Assert.assertEquals("Duration should be 0.5 seconds", 0.5, halfData.getDuration(), EPSILON);
	}

	/**
	 * Tests channel data retrieval for mono audio.
	 */
	@Test(timeout = 30000)
	public void getChannelDataMono() {
		PackedCollection samples = sineWave(440.0, 0.1);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		PackedCollection channel0 = data.getChannelData(0);
		Assert.assertNotNull("Channel 0 should not be null", channel0);
		Assert.assertEquals("Channel data length should match frame count",
				data.getFrameCount(), channel0.getMemLength());

		// Verify data is accessible
		double firstSample = channel0.toDouble(0);
		Assert.assertTrue("First sample should be finite", Double.isFinite(firstSample));
	}

	/**
	 * Tests invalid channel access throws exception.
	 */
	@Test(timeout = 5000, expected = IndexOutOfBoundsException.class)
	public void getChannelDataInvalidNegative() {
		PackedCollection samples = silence(1000);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);
		data.getChannelData(-1);
	}

	/**
	 * Tests range extraction with time-based parameters.
	 */
	@Test(timeout = 30000)
	public void rangeExtractionTime() {
		// Create 2 seconds of audio
		PackedCollection samples = sineWave(440.0, 2.0);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		// Extract 0.5 seconds starting at 0.5 seconds
		WaveData range = data.range(0, 0.5, 0.5);

		Assert.assertEquals("Range duration should be 0.5 seconds", 0.5, range.getDuration(), EPSILON);
		Assert.assertEquals("Range should have correct frame count",
				(int) (0.5 * TEST_SAMPLE_RATE), range.getFrameCount());
	}

	/**
	 * Tests range extraction with sample-based parameters.
	 */
	@Test(timeout = 30000)
	public void rangeExtractionSamples() {
		PackedCollection samples = ramp(10000);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		// Extract samples 1000-2000
		WaveData range = data.range(0, 1000, 1000);

		Assert.assertEquals("Range should have 1000 frames", 1000, range.getFrameCount());

		// Verify the range starts at the right place (ramp value at position 1000)
		double expectedStart = 1000.0 / 9999.0; // ramp goes from 0 to 1
		double actualStart = range.getChannelData(0).toDouble(0);
		Assert.assertEquals("Range should start at correct position", expectedStart, actualStart, 0.001);
	}

	/**
	 * Builds a WaveData from channel-major samples.
	 *
	 * @param channels the channel count
	 * @param frames   the frames per channel
	 * @param samples  the channel-major sample values, length {@code channels * frames}
	 * @return the WaveData
	 */
	private WaveData waveData(int channels, int frames, double... samples) {
		PackedCollection data = channels == 1 ?
				new PackedCollection(frames) : new PackedCollection(channels, frames);
		data.set(0, samples);
		return new WaveData(data, TEST_SAMPLE_RATE);
	}

	/**
	 * Tests that a mono source is duplicated across every target channel and that
	 * frames beyond the source length are zero-padded.
	 */
	@Test(timeout = 30000)
	public void conformMonoFillsEveryChannelAndZeroPads() {
		WaveData mono = waveData(1, 4, 1.0, 2.0, 3.0, 4.0);

		PackedCollection conformed = mono.conform(2, 8);

		Assert.assertEquals(2, conformed.getShape().getDimensions());
		Assert.assertEquals(2, conformed.getShape().length(0));
		Assert.assertEquals(8, conformed.getShape().length(1));

		for (int channel = 0; channel < 2; channel++) {
			Assert.assertEquals(1.0, conformed.toDouble(channel * 8), 1e-9);
			Assert.assertEquals(4.0, conformed.toDouble(channel * 8 + 3), 1e-9);
			Assert.assertEquals(0.0, conformed.toDouble(channel * 8 + 4), 1e-9);
			Assert.assertEquals(0.0, conformed.toDouble(channel * 8 + 7), 1e-9);
		}
	}

	/**
	 * Tests that a source longer than the target frame count is truncated.
	 */
	@Test(timeout = 30000)
	public void conformTruncatesLongerSource() {
		WaveData mono = waveData(1, 8, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);

		PackedCollection conformed = mono.conform(1, 4);

		Assert.assertEquals(4, conformed.getShape().length(1));
		Assert.assertEquals(1.0, conformed.toDouble(0), 1e-9);
		Assert.assertEquals(4.0, conformed.toDouble(3), 1e-9);
	}

	/**
	 * Tests that each channel of a multi-channel source is preserved independently.
	 */
	@Test(timeout = 30000)
	public void conformPreservesStereoChannels() {
		// Channel-major: channel 0 = [1,2,3,4], channel 1 = [5,6,7,8].
		WaveData stereo = waveData(2, 4, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);

		PackedCollection conformed = stereo.conform(2, 4);

		Assert.assertEquals(1.0, conformed.toDouble(0), 1e-9);
		Assert.assertEquals(4.0, conformed.toDouble(3), 1e-9);
		Assert.assertEquals(5.0, conformed.toDouble(4), 1e-9);
		Assert.assertEquals(8.0, conformed.toDouble(7), 1e-9);
	}

	/**
	 * Tests that a source with more channels than the target is truncated to the
	 * target channel count, keeping the leading channels and dropping the rest.
	 */
	@Test(timeout = 30000)
	public void conformTruncatesExtraChannels() {
		// Channel-major: channel 0 = [1,2,3,4], channel 1 = [5,6,7,8].
		WaveData stereo = waveData(2, 4, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);

		PackedCollection conformed = stereo.conform(1, 4);

		Assert.assertEquals(1, conformed.getShape().length(0));
		Assert.assertEquals(4, conformed.getShape().length(1));
		Assert.assertEquals(1.0, conformed.toDouble(0), 1e-9);
		Assert.assertEquals(2.0, conformed.toDouble(1), 1e-9);
		Assert.assertEquals(3.0, conformed.toDouble(2), 1e-9);
		Assert.assertEquals(4.0, conformed.toDouble(3), 1e-9);
	}

	/**
	 * Tests BufferDetails retrieval.
	 */
	@Test(timeout = 30000)
	public void bufferDetails() {
		PackedCollection samples = silence(TEST_SAMPLE_RATE * 2); // 2 seconds
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		BufferDetails details = data.getBufferDetails();

		Assert.assertNotNull("BufferDetails should not be null", details);
		Assert.assertEquals("Sample rate should match", TEST_SAMPLE_RATE, details.getSampleRate());
		Assert.assertEquals("Duration should match", 2.0, details.getDuration(), EPSILON);
	}

	/**
	 * Tests sample rate modification.
	 */
	@Test(timeout = 30000)
	public void setSampleRate() {
		PackedCollection samples = silence(44100);
		WaveData data = new WaveData(samples, 44100);

		Assert.assertEquals("Initial duration at 44100 Hz", 1.0, data.getDuration(), EPSILON);

		// Change sample rate - same frame count, different duration
		data.setSampleRate(22050);

		Assert.assertEquals("New sample rate should be set", 22050, data.getSampleRate());
		Assert.assertEquals("Duration should double at half sample rate", 2.0, data.getDuration(), EPSILON);
	}

	/**
	 * Tests save and load roundtrip with mono audio.
	 */
	@Test(timeout = 30000)
	public void saveAndLoadRoundtripMono() throws IOException {
		// Create mono test data - 1 second of sine wave
		int frames = TEST_SAMPLE_RATE;
		PackedCollection monoData = sineWave(440.0, 1.0);

		WaveData original = new WaveData(monoData, TEST_SAMPLE_RATE);

		// Create temp file
		File tempFile = Files.createTempFile("wave_test_", ".wav").toFile();
		tempFile.deleteOnExit();

		try {
			// Save
			boolean saved = original.save(tempFile);
			Assert.assertTrue("Save should succeed", saved);
			Assert.assertTrue("File should exist", tempFile.exists());
			Assert.assertTrue("File should have content", tempFile.length() > 0);

			// Load
			WaveData loaded = WaveData.load(tempFile);

			Assert.assertEquals("Loaded sample rate should match",
					original.getSampleRate(), loaded.getSampleRate());
			// Note: WAV saving may convert mono to stereo internally
			Assert.assertTrue("Loaded should have at least 1 channel",
					loaded.getChannelCount() >= 1);
			Assert.assertEquals("Loaded frame count should match",
					original.getFrameCount(), loaded.getFrameCount());

			// Verify some sample values (allowing for WAV quantization)
			PackedCollection originalChannel = original.getChannelData(0);
			PackedCollection loadedChannel = loaded.getChannelData(0);

			// Check a sample in the middle
			int sampleIndex = frames / 2;
			double originalValue = originalChannel.toDouble(sampleIndex);
			double loadedValue = loadedChannel.toDouble(sampleIndex);

			// WAV files have quantization error, so allow tolerance
			Assert.assertEquals("Sample values should be close",
					originalValue, loadedValue, 0.01);

			loaded.destroy();
		} finally {
			original.destroy();
			tempFile.delete();
		}
	}

	/**
	 * Tests destroy properly cleans up resources.
	 */
	@Test(timeout = 30000)
	public void destroyReleasesResources() {
		PackedCollection samples = sineWave(440.0, 1.0);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		Assert.assertNotNull("Data should exist before destroy", data.getData());

		data.destroy();

		Assert.assertNull("Data should be null after destroy", data.getData());
	}

	/**
	 * Tests with different sample rates.
	 */
	@Test(timeout = 30000)
	public void differentSampleRates() {
		int[] sampleRates = {22050, 44100, 48000, 96000};

		for (int rate : sampleRates) {
			PackedCollection samples = new PackedCollection(rate); // 1 second worth
			WaveData data = new WaveData(samples, rate);

			Assert.assertEquals("Sample rate should match for " + rate, rate, data.getSampleRate());
			Assert.assertEquals("Duration should be 1 second for " + rate, 1.0, data.getDuration(), EPSILON);

			data.destroy();
		}
	}

	/**
	 * Tests FFT functionality returns correct shape.
	 */
	@Test(timeout = 30000)
	public void fftReturnsCorrectShape() {
		// Create at least FFT_BINS worth of samples
		int frames = WaveData.FFT_BINS * 4;
		PackedCollection samples = sineWave(440.0,
				(double) frames / TEST_SAMPLE_RATE, TEST_SAMPLE_RATE);
		WaveData data = new WaveData(samples, TEST_SAMPLE_RATE);

		PackedCollection fftResult = data.fft(0);

		Assert.assertNotNull("FFT result should not be null", fftResult);
		Assert.assertTrue("FFT result should have data", fftResult.getMemLength() > 0);

		fftResult.destroy();
		data.destroy();
	}
}
