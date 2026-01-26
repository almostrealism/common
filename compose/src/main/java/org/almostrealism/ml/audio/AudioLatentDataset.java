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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.ml.StateDictionary;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.almostrealism.ml.DiffusionTrainingDataset;

/**
 * Dataset that encodes audio files to latent representations for diffusion training.
 *
 * <p>This class handles the complete pipeline of loading audio files, encoding them
 * using the OobleckEncoder + VAEBottleneck, and preparing training samples for
 * diffusion model fine-tuning.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Load autoencoder weights
 * StateDictionary autoencoderWeights = new StateDictionary("/path/to/autoencoder");
 *
 * // Create dataset from audio directory
 * AudioLatentDataset dataset = AudioLatentDataset.fromDirectory(
 *     Path.of("/path/to/audio"),
 *     autoencoderWeights,
 *     5.0,  // 5 second segments
 *     44100 // sample rate
 * );
 *
 * // Use for training
 * for (ValueTarget<PackedCollection> sample : dataset) {
 *     PackedCollection latent = sample.getInput();
 *     // ... training loop
 * }
 * }</pre>
 *
 * @see OobleckEncoder
 * @see VAEBottleneck
 * @author Michael Murray
 */
public class AudioLatentDataset implements Dataset<PackedCollection>, CollectionFeatures, ConsoleFeatures {

	private final List<PackedCollection> latents;
	private final int latentChannels;
	private final int latentLength;
	private final Random random;

	/**
	 * Creates a dataset from pre-computed latents.
	 *
	 * @param latents List of latent representations
	 * @param latentChannels Number of channels in latents (typically 64)
	 * @param latentLength Sequence length of each latent
	 */
	public AudioLatentDataset(List<PackedCollection> latents, int latentChannels, int latentLength) {
		this.latents = new ArrayList<>(latents);
		this.latentChannels = latentChannels;
		this.latentLength = latentLength;
		this.random = new Random(42);
	}

	/**
	 * Creates a dataset by encoding audio files from a directory.
	 *
	 * @param audioDirectory Directory containing WAV files
	 * @param autoencoderWeights Weights for encoder (must contain encoder.* keys)
	 * @param segmentSeconds Length of audio segments in seconds
	 * @param sampleRate Audio sample rate (typically 44100)
	 * @return Dataset of encoded latents
	 * @throws IOException If audio files cannot be read
	 */
	public static AudioLatentDataset fromDirectory(Path audioDirectory,
												   StateDictionary autoencoderWeights,
												   double segmentSeconds,
												   int sampleRate) throws IOException {
		return fromDirectory(audioDirectory, autoencoderWeights, segmentSeconds, sampleRate, -1);
	}

	/**
	 * Creates a dataset by encoding audio files from a directory with a limit.
	 *
	 * @param audioDirectory Directory containing WAV files
	 * @param autoencoderWeights Weights for encoder
	 * @param segmentSeconds Length of audio segments in seconds
	 * @param sampleRate Audio sample rate
	 * @param maxFiles Maximum number of files to process (-1 for all)
	 * @return Dataset of encoded latents
	 * @throws IOException If audio files cannot be read
	 */
	public static AudioLatentDataset fromDirectory(Path audioDirectory,
												   StateDictionary autoencoderWeights,
												   double segmentSeconds,
												   int sampleRate,
												   int maxFiles) throws IOException {
		List<Path> audioFiles;
		try (Stream<Path> paths = Files.walk(audioDirectory)) {
			audioFiles = paths
					.filter(Files::isRegularFile)
					.filter(p -> p.toString().toLowerCase().endsWith(".wav"))
					.limit(maxFiles > 0 ? maxFiles : Long.MAX_VALUE)
					.collect(Collectors.toList());
		}

		return fromFiles(audioFiles, autoencoderWeights, segmentSeconds, sampleRate);
	}

	/**
	 * Creates a dataset by encoding specific audio files.
	 *
	 * @param audioFiles List of WAV file paths
	 * @param autoencoderWeights Weights for encoder
	 * @param segmentSeconds Length of audio segments in seconds
	 * @param sampleRate Audio sample rate
	 * @return Dataset of encoded latents
	 * @throws IOException If audio files cannot be read
	 */
	public static AudioLatentDataset fromFiles(List<Path> audioFiles,
											   StateDictionary autoencoderWeights,
											   double segmentSeconds,
											   int sampleRate) throws IOException {
		// Calculate dimensions
		int segmentSamples = (int) (segmentSeconds * sampleRate);

		// Build encoder model
		System.out.println("Building encoder model...");
		int batchSize = 1;
		OobleckEncoder encoder = new OobleckEncoder(autoencoderWeights, batchSize, segmentSamples);
		int encoderOutputLength = encoder.getOutputLength();

		VAEBottleneck bottleneck = new VAEBottleneck(batchSize, encoderOutputLength);

		// Compile encoder + bottleneck (inference only, no backprop needed)
		Model encoderModel = new Model(new TraversalPolicy(batchSize, 2, segmentSamples));
		encoderModel.add(encoder);
		encoderModel.add(bottleneck.getBottleneck());
		CompiledModel compiledEncoder = encoderModel.compile(false);

		System.out.println("Encoder built. Output shape: (1, 64, " + encoderOutputLength + ")");

		// Process audio files
		List<PackedCollection> latents = new ArrayList<>();

		for (Path audioFile : audioFiles) {
			System.out.println("Processing: " + audioFile.getFileName());
			try {
				List<PackedCollection> fileLatents = encodeAudioFile(
						audioFile, compiledEncoder, segmentSamples, sampleRate);
				latents.addAll(fileLatents);
				System.out.println("  Encoded " + fileLatents.size() + " segments");
			} catch (Exception e) {
				System.err.println("  Failed to process: " + e.getMessage());
			}
		}

		System.out.println("Total latents: " + latents.size());

		return new AudioLatentDataset(latents, 64, encoderOutputLength);
	}

	private static List<PackedCollection> encodeAudioFile(Path audioFile,
														  CompiledModel encoder,
														  int segmentSamples,
														  int sampleRate) throws IOException {
		List<PackedCollection> latents = new ArrayList<>();

		// Load audio
		WaveData audio = WaveData.load(audioFile.toFile());
		int frameCount = audio.getFrameCount();

		// Convert to stereo if mono using bulk memory operations
		PackedCollection audioData = new PackedCollection(new TraversalPolicy(1, 2, frameCount));
		if (audio.getChannelCount() == 1) {
			// Duplicate mono to stereo using bulk copy
			PackedCollection mono = audio.getChannelData(0);
			audioData.setMem(0, mono);                // Left channel (bulk copy)
			audioData.setMem(frameCount, mono);       // Right channel (bulk copy of same data)
		} else {
			// Already stereo - bulk copy both channels
			PackedCollection left = audio.getChannelData(0);
			PackedCollection right = audio.getChannelData(1);
			audioData.setMem(0, left);                // Left channel (bulk copy)
			audioData.setMem(frameCount, right);      // Right channel (bulk copy)
		}

		// Normalize amplitude using hardware acceleration
		audioData = normalizeAmplitude(audioData);

		// Split into segments and encode
		int offset = 0;

		while (offset + segmentSamples <= frameCount) {
			// Extract segment using bulk copy operations
			PackedCollection segment = new PackedCollection(new TraversalPolicy(1, 2, segmentSamples));
			// Copy left channel segment
			segment.setMem(0, audioData, offset, segmentSamples);
			// Copy right channel segment
			segment.setMem(segmentSamples, audioData, frameCount + offset, segmentSamples);

			// Encode segment
			PackedCollection latent = encoder.forward(segment);

			// Clone the latent using bulk copy (encoder may reuse buffers)
			PackedCollection latentCopy = new PackedCollection(latent.getShape());
			latentCopy.setMem(0, latent);

			latents.add(latentCopy);
			offset += segmentSamples;
		}

		audio.destroy();
		return latents;
	}

	/**
	 * Normalizes audio amplitude to the range [-1, 1] using hardware acceleration.
	 */
	private static PackedCollection normalizeAmplitude(PackedCollection data) {
		CollectionFeatures cf = CollectionFeatures.getInstance();

		// Use hardware-accelerated abs().max() to find maximum absolute value
		double maxAbs = cf.c(cf.p(data)).abs().max().evaluate().toDouble(0);

		if (maxAbs > 0 && maxAbs != 1.0) {
			double scale = 1.0 / maxAbs;
			// Use hardware-accelerated multiply for scaling
			return cf.c(cf.p(data)).multiply(cf.c(scale)).evaluate();
		}

		return data;
	}

	/**
	 * Gets the number of latent samples in this dataset.
	 *
	 * @return Number of samples
	 */
	public int size() {
		return latents.size();
	}

	/**
	 * Gets the number of channels in each latent (typically 64).
	 *
	 * @return Latent channels
	 */
	public int getLatentChannels() {
		return latentChannels;
	}

	/**
	 * Gets the sequence length of each latent.
	 *
	 * @return Latent sequence length
	 */
	public int getLatentLength() {
		return latentLength;
	}

	/**
	 * Gets a random latent from the dataset.
	 *
	 * @return Random latent
	 */
	public PackedCollection getRandomLatent() {
		return latents.get(random.nextInt(latents.size()));
	}

	/**
	 * Gets a specific latent by index.
	 *
	 * @param index Index of the latent
	 * @return Latent at the given index
	 */
	public PackedCollection getLatent(int index) {
		return latents.get(index);
	}

	/**
	 * Shuffles the dataset in place.
	 */
	public void shuffle() {
		Collections.shuffle(latents, random);
	}

	/**
	 * Converts this dataset to a {@link DiffusionTrainingDataset} for use with
	 * {@link org.almostrealism.optimize.ModelOptimizer}.
	 *
	 * @param scheduler    Noise scheduler for timestep sampling and noise addition
	 * @param repeatFactor Number of times to repeat each sample per epoch
	 * @return A DiffusionTrainingDataset wrapping these latents
	 */
	public DiffusionTrainingDataset toDiffusionDataset(DiffusionNoiseScheduler scheduler, int repeatFactor) {
		return new DiffusionTrainingDataset(latents, scheduler, repeatFactor);
	}

	/**
	 * Converts this dataset to a {@link DiffusionTrainingDataset} with no repetition.
	 *
	 * @param scheduler Noise scheduler
	 * @return A DiffusionTrainingDataset wrapping these latents
	 */
	public DiffusionTrainingDataset toDiffusionDataset(DiffusionNoiseScheduler scheduler) {
		return new DiffusionTrainingDataset(latents, scheduler);
	}

	@Override
	public Iterator<ValueTarget<PackedCollection>> iterator() {
		return new Iterator<>() {
			private int index = 0;

			@Override
			public boolean hasNext() {
				return index < latents.size();
			}

			@Override
			public ValueTarget<PackedCollection> next() {
				PackedCollection latent = latents.get(index++);
				// For diffusion training, input and target are the same (clean latent)
				// The actual noisy input is created during training
				return ValueTarget.of(latent, latent);
			}
		};
	}
}
