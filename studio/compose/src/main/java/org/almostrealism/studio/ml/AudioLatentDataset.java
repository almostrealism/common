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

package org.almostrealism.studio.ml;
import org.almostrealism.ml.audio.VAEBottleneck;
import org.almostrealism.ml.audio.OobleckEncoder;
import org.almostrealism.ml.audio.DiffusionNoiseScheduler;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.io.Console;
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

	/** Pre-encoded latent representations, one per audio segment. */
	private final List<PackedCollection> latents;

	/** Number of latent channels produced by the encoder (typically 64). */
	private final int latentChannels;

	/** Sequence length of each latent tensor. */
	private final int latentLength;

	/** Random number generator used for dataset shuffling. */
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
		Console.root().println("Building encoder model...");
		int batchSize = 1;
		OobleckEncoder encoder = new OobleckEncoder(autoencoderWeights, batchSize, segmentSamples);
		int encoderOutputLength = encoder.getOutputLength();

		VAEBottleneck bottleneck = new VAEBottleneck(batchSize, encoderOutputLength);

		// Compile encoder + bottleneck (inference only, no backprop needed)
		Model encoderModel = new Model(new TraversalPolicy(batchSize, 2, segmentSamples));
		encoderModel.add(encoder);
		encoderModel.add(bottleneck.getBottleneck());
		CompiledModel compiledEncoder = encoderModel.compile(false);

		Console.root().println("Encoder built. Output shape: (1, 64, " + encoderOutputLength + ")");

		// Process audio files
		List<PackedCollection> latents = new ArrayList<>();

		try {
			for (Path audioFile : audioFiles) {
				Console.root().println("Processing: " + audioFile.getFileName());
				try {
					List<PackedCollection> fileLatents = encodeAudioFile(
							audioFile, compiledEncoder, segmentSamples);
					latents.addAll(fileLatents);
					Console.root().println("Encoded " + fileLatents.size() + " segments");
				} catch (Exception e) {
					Console.root().warn("Failed to process: " + e.getMessage(), e);
				}
			}
		} finally {
			// Release the compiled encoder and its intermediate buffers
			compiledEncoder.destroy();
			encoderModel.destroy();
		}

		Console.root().println("Total latents: " + latents.size());

		return new AudioLatentDataset(latents, 64, encoderOutputLength);
	}

	/**
	 * Encodes a single audio file into a list of fixed-length latent segments.
	 *
	 * @param audioFile      path to the WAV file to encode
	 * @param encoder        the compiled encoder model
	 * @param segmentSamples number of audio samples per segment
	 * @return the list of encoded latent tensors, one per segment
	 * @throws IOException if the audio file cannot be read
	 */
	private static List<PackedCollection> encodeAudioFile(Path audioFile,
														  CompiledModel encoder,
														  int segmentSamples) throws IOException {
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

		// Release the raw WaveData now that we have copied its samples
		audio.destroy();

		// Normalize amplitude using hardware acceleration
		PackedCollection normalizedData = normalizeAmplitude(audioData);
		if (normalizedData != audioData) {
			audioData.destroy();
			audioData = normalizedData;
		}

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

			// Release the segment now that encoding is done
			segment.destroy();

			// Clone the latent using bulk copy (encoder may reuse buffers)
			PackedCollection latentCopy = new PackedCollection(latent.getShape());
			latentCopy.setMem(0, latent);

			latents.add(latentCopy);
			offset += segmentSamples;
		}

		// Release the normalized audio data
		audioData.destroy();
		return latents;
	}

	/**
	 * Normalizes audio amplitude to the range [-1, 1] using hardware acceleration.
	 *
	 * <p>Always returns a freshly allocated {@link PackedCollection}. The caller is
	 * responsible for destroying the original {@code data}. Callers should not rely
	 * on reference identity with the input.</p>
	 */
	private static PackedCollection normalizeAmplitude(PackedCollection data) {
		CollectionFeatures cf = CollectionFeatures.getInstance();

		// Compute the normalization scale as a single producer graph so the scalar
		// decision stays on the device: scale = (max > 0) ? 1/max : 1.
		CollectionProducer maxAbs = cf.c(cf.p(data)).abs().max();
		CollectionProducer scale = maxAbs.greaterThan(cf.c(0.0), cf.c(1.0).divide(maxAbs), cf.c(1.0));
		return cf.c(cf.p(data)).multiply(scale).evaluate();
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
