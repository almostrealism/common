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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.DatasetSplit;
import org.almostrealism.optimize.ValueTarget;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Collects and preprocesses audio files for model fine-tuning.
 *
 * <p>This class provides a complete pipeline from raw audio files to
 * training-ready datasets. It handles loading, normalization, segmentation,
 * optional augmentation, and train/validation splitting.</p>
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * // Create collector
 * AudioTrainingDataCollector collector = new AudioTrainingDataCollector();
 *
 * // Collect training data
 * List<Path> audioFiles = List.of(
 *     Path.of("audio1.wav"),
 *     Path.of("audio2.wav")
 * );
 * DataCollectionConfig config = new DataCollectionConfig()
 *     .segmentLength(5.0)
 *     .trainSplitRatio(0.9);
 *
 * DatasetSplit<PackedCollection> dataset = collector.collect(audioFiles, config);
 *
 * // Use with FineTuner
 * FineTuner fineTuner = new FineTuner(model, fineTuneConfig, outputShape);
 * FineTuningResult result = fineTuner.fineTune(
 *     dataset.getTrainSet(),
 *     dataset.getValidationSet()
 * );
 * }</pre>
 *
 * <h2>Collecting from a Directory</h2>
 * <pre>{@code
 * // Collect all WAV files from a directory
 * DatasetSplit<PackedCollection> dataset = collector.collectFromDirectory(
 *     Path.of("/path/to/audio"),
 *     config
 * );
 * }</pre>
 *
 * <h2>Data Processing Pipeline</h2>
 * <ol>
 *   <li>Load WAV file</li>
 *   <li>Skip if too short (based on minDuration)</li>
 *   <li>Normalize amplitude (optional)</li>
 *   <li>Convert to mono (optional)</li>
 *   <li>Split into segments of target length</li>
 *   <li>Create input/target pairs</li>
 *   <li>Apply augmentation (optional)</li>
 *   <li>Shuffle and split into train/validation</li>
 * </ol>
 *
 * @see DataCollectionConfig
 * @see DatasetSplit<PackedCollection>
 * @author Michael Murray
 */
public class AudioTrainingDataCollector implements LayerFeatures, ConsoleFeatures {

	private final Random random;

	/**
	 * Creates a new audio training data collector with default random seed.
	 */
	public AudioTrainingDataCollector() {
		this(new Random(42));
	}

	/**
	 * Creates a new audio training data collector with specified random generator.
	 *
	 * @param random the random number generator for shuffling and augmentation
	 */
	public AudioTrainingDataCollector(Random random) {
		this.random = random;
	}

	/**
	 * Collects training data from a list of audio files.
	 *
	 * @param audioFiles list of paths to audio files
	 * @param config configuration for data collection
	 * @return the collected training dataset
	 */
	public DatasetSplit<PackedCollection> collect(List<Path> audioFiles, DataCollectionConfig config) {
		List<ValueTarget<PackedCollection>> samples = new ArrayList<>();
		int filesProcessed = 0;
		int filesSkipped = 0;

		for (Path audioFile : audioFiles) {
			if (config.getMaxSamplesTotal() > 0 && samples.size() >= config.getMaxSamplesTotal()) {
				log("Reached max samples limit: " + config.getMaxSamplesTotal());
				break;
			}

			try {
				List<ValueTarget<PackedCollection>> fileSamples = processAudioFile(audioFile, config);
				samples.addAll(fileSamples);
				filesProcessed++;

				if (fileSamples.isEmpty()) {
					filesSkipped++;
				}
			} catch (IOException e) {
				warn("Failed to load audio file: " + audioFile + " - " + e.getMessage());
				filesSkipped++;
			}
		}

		log(String.format("Processed %d files, skipped %d, collected %d samples",
				filesProcessed, filesSkipped, samples.size()));

		// Shuffle samples
		Collections.shuffle(samples, random);

		// Apply max samples limit
		if (config.getMaxSamplesTotal() > 0 && samples.size() > config.getMaxSamplesTotal()) {
			samples = samples.subList(0, config.getMaxSamplesTotal());
		}

		// Split into train/validation
		int splitIdx = (int) (samples.size() * config.getTrainSplitRatio());
		List<ValueTarget<PackedCollection>> trainSamples = samples.subList(0, splitIdx);
		List<ValueTarget<PackedCollection>> validSamples = samples.subList(splitIdx, samples.size());

		return new DatasetSplit<PackedCollection>(
				Dataset.of(trainSamples),
				Dataset.of(validSamples),
				trainSamples.size(),
				validSamples.size()
		);
	}

	/**
	 * Collects training data from all WAV files in a directory.
	 *
	 * @param directory path to directory containing audio files
	 * @param config configuration for data collection
	 * @return the collected training dataset
	 * @throws IOException if directory cannot be read
	 */
	public DatasetSplit<PackedCollection> collectFromDirectory(Path directory, DataCollectionConfig config) throws IOException {
		List<Path> audioFiles;

		try (Stream<Path> paths = Files.walk(directory)) {
			audioFiles = paths
					.filter(Files::isRegularFile)
					.filter(p -> p.toString().toLowerCase().endsWith(".wav"))
					.collect(Collectors.toList());
		}

		log("Found " + audioFiles.size() + " WAV files in " + directory);
		return collect(audioFiles, config);
	}

	/**
	 * Processes a single audio file into training samples.
	 *
	 * @param audioFile path to the audio file
	 * @param config configuration for data collection
	 * @return list of training samples from this file
	 * @throws IOException if file cannot be read
	 */
	protected List<ValueTarget<PackedCollection>> processAudioFile(Path audioFile,
																   DataCollectionConfig config) throws IOException {
		List<ValueTarget<PackedCollection>> samples = new ArrayList<>();

		// Load audio
		WaveData audio = WaveData.load(audioFile.toFile());

		// Skip if too short
		if (audio.getDuration() < config.getMinDuration()) {
			audio.destroy();
			return samples;
		}

		// Normalize amplitude if requested
		if (config.isNormalizeAmplitude()) {
			audio = normalizeAmplitude(audio);
		}

		// Convert to mono if requested
		if (config.isConvertToMono() && audio.getChannelCount() > 1) {
			audio = convertToMono(audio);
		}

		// Split into segments
		List<PackedCollection> segments = splitIntoSegments(audio, config);

		for (PackedCollection segment : segments) {
			// For autoencoder training: input and target are the same
			// The model learns to reconstruct the input
			samples.add(ValueTarget.of(segment, segment));

			// Apply augmentation if enabled
			if (config.isAugment()) {
				samples.addAll(augmentSample(segment, config));
			}
		}

		// Clean up
		audio.destroy();

		return samples;
	}

	/**
	 * Normalizes audio amplitude to the range [-1, 1].
	 *
	 * @param audio the audio to normalize
	 * @return normalized audio
	 */
	protected WaveData normalizeAmplitude(WaveData audio) {
		PackedCollection data = audio.getData();
		double maxAbs = 0;

		// Find maximum absolute value
		for (int i = 0; i < data.getMemLength(); i++) {
			double val = Math.abs(data.toDouble(i));
			if (val > maxAbs) {
				maxAbs = val;
			}
		}

		// Normalize if max is greater than 0
		if (maxAbs > 0 && maxAbs != 1.0) {
			double scale = 1.0 / maxAbs;
			PackedCollection normalized = new PackedCollection(data.getShape());
			for (int i = 0; i < data.getMemLength(); i++) {
				normalized.setMem(i, data.toDouble(i) * scale);
			}
			return new WaveData(normalized, audio.getSampleRate());
		}

		return audio;
	}

	/**
	 * Converts stereo audio to mono by averaging channels.
	 *
	 * @param audio the stereo audio
	 * @return mono audio
	 */
	protected WaveData convertToMono(WaveData audio) {
		if (audio.getChannelCount() == 1) {
			return audio;
		}

		int frameCount = audio.getFrameCount();
		PackedCollection mono = new PackedCollection(new TraversalPolicy(1, frameCount));

		PackedCollection left = audio.getChannelData(0);
		PackedCollection right = audio.getChannelData(1);

		for (int i = 0; i < frameCount; i++) {
			double avg = (left.toDouble(i) + right.toDouble(i)) / 2.0;
			mono.setMem(i, avg);
		}

		return new WaveData(mono, audio.getSampleRate());
	}

	/**
	 * Splits audio into segments of the target length.
	 *
	 * @param audio the audio to split
	 * @param config configuration with segment length
	 * @return list of audio segments as PackedCollections
	 */
	protected List<PackedCollection> splitIntoSegments(WaveData audio, DataCollectionConfig config) {
		List<PackedCollection> segments = new ArrayList<>();

		int sampleRate = audio.getSampleRate();
		int segmentSamples = (int) (config.getSegmentLength() * sampleRate);
		int totalSamples = audio.getFrameCount();

		// Get mono channel data
		PackedCollection channelData = audio.getChannelData(0);

		int offset = 0;
		while (offset + segmentSamples <= totalSamples) {
			// Extract segment
			PackedCollection segment = new PackedCollection(new TraversalPolicy(segmentSamples));
			for (int i = 0; i < segmentSamples; i++) {
				segment.setMem(i, channelData.toDouble(offset + i));
			}
			segments.add(segment);
			offset += segmentSamples;
		}

		// Handle remaining samples if they meet minimum duration
		int remaining = totalSamples - offset;
		if (remaining >= (int) (config.getMinDuration() * sampleRate)) {
			PackedCollection lastSegment = new PackedCollection(new TraversalPolicy(remaining));
			for (int i = 0; i < remaining; i++) {
				lastSegment.setMem(i, channelData.toDouble(offset + i));
			}
			segments.add(lastSegment);
		}

		return segments;
	}

	/**
	 * Creates augmented versions of a sample.
	 *
	 * @param segment the original audio segment
	 * @param config configuration with augmentation settings
	 * @return list of augmented samples
	 */
	protected List<ValueTarget<PackedCollection>> augmentSample(PackedCollection segment,
															   DataCollectionConfig config) {
		List<ValueTarget<PackedCollection>> augmented = new ArrayList<>();

		if (config.isAddNoise()) {
			// Add Gaussian noise
			PackedCollection noisy = addGaussianNoise(segment, config.getNoiseLevel());
			augmented.add(ValueTarget.of(noisy, segment));  // Target is clean audio
		}

		// Note: Time stretching is not implemented here as it requires
		// signal processing that would add complexity. It can be added
		// as an enhancement later.

		return augmented;
	}

	/**
	 * Adds Gaussian noise to a sample.
	 *
	 * @param segment the original segment
	 * @param noiseLevel standard deviation of the noise
	 * @return noisy segment
	 */
	protected PackedCollection addGaussianNoise(PackedCollection segment, double noiseLevel) {
		PackedCollection noisy = new PackedCollection(segment.getShape());
		for (int i = 0; i < segment.getMemLength(); i++) {
			double val = segment.toDouble(i) + random.nextGaussian() * noiseLevel;
			// Clip to [-1, 1]
			val = Math.max(-1.0, Math.min(1.0, val));
			noisy.setMem(i, val);
		}
		return noisy;
	}
}
