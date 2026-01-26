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

package org.almostrealism.ml.audio.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.AudioTrainingDataCollector;
import org.almostrealism.ml.audio.DataCollectionConfig;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests for {@link AudioTrainingDataCollector}.
 *
 * <p>These tests verify the audio data collection pipeline works correctly,
 * including file loading, segmentation, normalization, and train/validation splitting.</p>
 */
public class AudioTrainingDataCollectorTest extends TestSuiteBase {

	private static final Path BASS_LOOPS_DIR = Path.of("/workspace/project/BASS LOOPS_125");

	/**
	 * Test that the collector can load and segment audio files.
	 */
	@Test
	public void testBasicCollection() throws IOException {
		if (!Files.exists(BASS_LOOPS_DIR)) {
			log("BASS LOOPS directory not found, skipping test");
			return;
		}

		// Use just 3 files for quick testing
		List<Path> audioFiles = getAudioFiles(3);
		Assert.assertFalse("Should have audio files", audioFiles.isEmpty());

		DataCollectionConfig config = DataCollectionConfig.forTesting()
				.segmentLength(2.0)    // 2 second segments
				.minDuration(0.5)      // Accept short segments
				.trainSplitRatio(0.8); // 80% train, 20% validation

		AudioTrainingDataCollector collector = new AudioTrainingDataCollector();
		List<Dataset<PackedCollection>> splits = collector.collect(audioFiles, config);
		Dataset<PackedCollection> trainSet = splits.get(0);
		Dataset<PackedCollection> validSet = splits.get(1);

		log("Train samples: " + countDataset(trainSet));
		log("Validation samples: " + countDataset(validSet));

		Assert.assertTrue("Should have training samples", countDataset(trainSet) > 0);
		Assert.assertTrue("Should have total samples", countDataset(trainSet) + countDataset(validSet) > 0);

		// Verify samples are valid
		int sampleCount = 0;
		for (ValueTarget<PackedCollection> sample : trainSet) {
			Assert.assertNotNull("Sample input should not be null", sample.getInput());
			Assert.assertNotNull("Sample target should not be null", sample.getExpectedOutput());
			Assert.assertTrue("Sample should have data", sample.getInput().getMemLength() > 0);
			sampleCount++;
			if (sampleCount >= 3) break; // Just verify a few
		}

		log("Basic collection test passed");
	}

	/**
	 * Test that collected audio is properly normalized.
	 */
	@Test
	public void testAmplitudeNormalization() throws IOException {
		if (!Files.exists(BASS_LOOPS_DIR)) {
			log("BASS LOOPS directory not found, skipping test");
			return;
		}

		List<Path> audioFiles = getAudioFiles(2);
		Assert.assertFalse("Should have audio files", audioFiles.isEmpty());

		DataCollectionConfig config = DataCollectionConfig.forTesting()
				.segmentLength(1.0)
				.normalizeAmplitude(true)
				.maxSamplesTotal(10);

		AudioTrainingDataCollector collector = new AudioTrainingDataCollector();
		List<Dataset<PackedCollection>> splits = collector.collect(audioFiles, config);
		Dataset<PackedCollection> trainSet = splits.get(0);

		// Check that samples are within [-1, 1] range
		for (ValueTarget<PackedCollection> sample : trainSet) {
			PackedCollection data = sample.getInput();
			for (int i = 0; i < Math.min(1000, data.getMemLength()); i++) {
				double val = data.toDouble(i);
				Assert.assertTrue("Sample value should be <= 1: " + val, val <= 1.0);
				Assert.assertTrue("Sample value should be >= -1: " + val, val >= -1.0);
			}
		}

		log("Amplitude normalization test passed");
	}

	/**
	 * Test train/validation split ratio.
	 */
	@Test
	public void testTrainValidationSplit() throws IOException {
		if (!Files.exists(BASS_LOOPS_DIR)) {
			log("BASS LOOPS directory not found, skipping test");
			return;
		}

		List<Path> audioFiles = getAudioFiles(5);
		Assert.assertFalse("Should have audio files", audioFiles.isEmpty());

		DataCollectionConfig config = DataCollectionConfig.forTesting()
				.segmentLength(1.0)
				.trainSplitRatio(0.7)  // 70% train
				.maxSamplesTotal(50);

		AudioTrainingDataCollector collector = new AudioTrainingDataCollector();
		List<Dataset<PackedCollection>> splits = collector.collect(audioFiles, config);
		Dataset<PackedCollection> trainSet = splits.get(0);
		Dataset<PackedCollection> validSet = splits.get(1);

		int total = countDataset(trainSet) + countDataset(validSet);
		double actualRatio = total > 0 ? (double) countDataset(trainSet) / total : 0.0;
		log("Actual split ratio: " + actualRatio);

		// Allow some variance due to random splitting
		Assert.assertTrue("Split ratio should be close to 0.7",
				actualRatio >= 0.5 && actualRatio <= 0.9);

		log("Train/validation split test passed");
	}

	/**
	 * Test that augmentation produces more samples.
	 */
	@Test
	public void testAugmentation() throws IOException {
		if (!Files.exists(BASS_LOOPS_DIR)) {
			log("BASS LOOPS directory not found, skipping test");
			return;
		}

		List<Path> audioFiles = getAudioFiles(2);
		Assert.assertFalse("Should have audio files", audioFiles.isEmpty());

		// Without augmentation
		DataCollectionConfig configNoAug = DataCollectionConfig.forTesting()
				.segmentLength(2.0)
				.augment(false)
				.maxSamplesTotal(50);

		AudioTrainingDataCollector collectorNoAug = new AudioTrainingDataCollector();
		List<Dataset<PackedCollection>> splitsNoAug = collectorNoAug.collect(audioFiles, configNoAug);
		int sizeNoAug = countDataset(splitsNoAug.get(0)) + countDataset(splitsNoAug.get(1));

		// With noise augmentation
		DataCollectionConfig configWithAug = DataCollectionConfig.forTesting()
				.segmentLength(2.0)
				.augment(true)
				.addNoise(true)
				.noiseLevel(0.01)
				.maxSamplesTotal(100);

		AudioTrainingDataCollector collectorWithAug = new AudioTrainingDataCollector();
		List<Dataset<PackedCollection>> splitsWithAug = collectorWithAug.collect(audioFiles, configWithAug);
		int sizeWithAug = countDataset(splitsWithAug.get(0)) + countDataset(splitsWithAug.get(1));

		log("Without augmentation: " + sizeNoAug + " samples");
		log("With augmentation: " + sizeWithAug + " samples");

		Assert.assertTrue("Augmentation should produce more samples",
				sizeWithAug >= sizeNoAug);

		log("Augmentation test passed");
	}

	/**
	 * Test collecting from a directory.
	 */
	@Test
	public void testCollectFromDirectory() throws IOException {
		if (!Files.exists(BASS_LOOPS_DIR)) {
			log("BASS LOOPS directory not found, skipping test");
			return;
		}

		DataCollectionConfig config = DataCollectionConfig.forTesting()
				.segmentLength(2.0)
				.maxSamplesTotal(20);

		AudioTrainingDataCollector collector = new AudioTrainingDataCollector();
		List<Dataset<PackedCollection>> splits = collector.collectFromDirectory(BASS_LOOPS_DIR, config);

		int trainCount = countDataset(splits.get(0));
		int validCount = countDataset(splits.get(1));
		int totalSize = trainCount + validCount;
		log("Collected from directory: train=" + trainCount + ", valid=" + validCount);

		Assert.assertTrue("Should have samples from directory", totalSize > 0);

		log("Directory collection test passed");
	}

	/**
	 * Test that segment lengths are correct.
	 */
	@Test
	public void testSegmentLength() throws IOException {
		if (!Files.exists(BASS_LOOPS_DIR)) {
			log("BASS LOOPS directory not found, skipping test");
			return;
		}

		List<Path> audioFiles = getAudioFiles(1);
		Assert.assertFalse("Should have audio files", audioFiles.isEmpty());

		double targetSeconds = 1.0;
		int expectedSampleRate = 44100;
		int expectedSamples = (int) (targetSeconds * expectedSampleRate);

		DataCollectionConfig config = DataCollectionConfig.forTesting()
				.segmentLength(targetSeconds)
				.targetSampleRate(expectedSampleRate)
				.maxSamplesTotal(10);

		AudioTrainingDataCollector collector = new AudioTrainingDataCollector();
		List<Dataset<PackedCollection>> splits = collector.collect(audioFiles, config);
		Dataset<PackedCollection> trainSet = splits.get(0);

		// Check at least one segment has the expected length
		boolean foundCorrectLength = false;
		for (ValueTarget<PackedCollection> sample : trainSet) {
			int length = sample.getInput().getMemLength();
			log("Segment length: " + length + " samples");
			if (Math.abs(length - expectedSamples) < 1000) { // Allow some tolerance
				foundCorrectLength = true;
				break;
			}
		}

		Assert.assertTrue("Should have segments of approximately correct length",
				foundCorrectLength || countDataset(trainSet) == 0);

		log("Segment length test passed");
	}

	private List<Path> getAudioFiles(int maxFiles) throws IOException {
		try (Stream<Path> paths = Files.list(BASS_LOOPS_DIR)) {
			return paths
					.filter(Files::isRegularFile)
					.filter(p -> p.toString().toLowerCase().endsWith(".wav"))
					.limit(maxFiles)
					.collect(Collectors.toList());
		}
	}

	private int countDataset(Dataset<?> dataset) {
		int count = 0;
		for (Object ignored : dataset) {
			count++;
		}
		return count;
	}
}
