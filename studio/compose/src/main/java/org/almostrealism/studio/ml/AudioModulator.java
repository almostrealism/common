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

package org.almostrealism.studio.ml;
import org.almostrealism.ml.audio.AutoEncoder;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;

import java.io.File;
import java.util.Random;

/**
 * Audio modulation utility that uses latent space interpolation to create
 * variations of audio samples.
 * <p>
 * This class wraps an {@link AudioComposer} to provide a simple API for
 * loading audio samples and generating interpolated variations.
 */
public class AudioModulator implements AutoCloseable, CodeFeatures {
	/** Default latent interpolation dimension. */
	public static final int DIM = 8;

	/** The underlying audio composer that performs latent interpolation. */
	private final AudioComposer composer;

	/** Target audio duration in seconds, capped by the autoencoder's maximum. */
	private double audioDuration;

	/**
	 * Creates an AudioModulator with the provided autoencoder.
	 *
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 */
	public AudioModulator(AutoEncoder autoencoder) {
		this(autoencoder, DIM, System.currentTimeMillis());
	}

	/**
	 * Creates an AudioModulator with the provided autoencoder and seed.
	 *
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 * @param seed random seed for the audio composer
	 */
	public AudioModulator(AutoEncoder autoencoder, long seed) {
		this(autoencoder, DIM, seed);
	}

	/**
	 * Creates an AudioModulator with the provided autoencoder, dimension, and seed.
	 *
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 * @param dim dimension of the latent interpolation space
	 * @param seed random seed for the audio composer
	 */
	public AudioModulator(AutoEncoder autoencoder, int dim, long seed) {
		composer = new AudioComposer(autoencoder, dim, seed);
		audioDuration = composer.getMaximumAudioDuration();
	}

	/** Returns the target audio output duration in seconds. */
	public double getAudioDuration() { return audioDuration; }

	/**
	 * Sets the target audio output duration in seconds, capped by the autoencoder maximum.
	 *
	 * @param seconds the desired duration
	 */
	public void setAudioDuration(double seconds) {
		this.audioDuration = Math.min(composer.getMaximumAudioDuration(), seconds);
	}

	/**
	 * Encodes and adds the given raw audio as a composable source.
	 *
	 * @param audio the raw audio samples to encode and add
	 */
	public void addAudio(PackedCollection audio) {
		composer.addAudio(cp(audio));
	}

	/**
	 * Adds pre-encoded latent features directly as a composable source.
	 *
	 * @param features the pre-encoded feature collection to add
	 */
	public void addFeatures(PackedCollection features) {
		composer.addSource(cp(features));
	}

	/**
	 * Projects the given position vector through the latent composition, returning
	 * stereo audio samples shaped {@code [2, finalSamples]}.
	 *
	 * @param position the interpolation position vector
	 * @return stereo audio data as a packed collection
	 */
	public PackedCollection project(PackedCollection position) {
		try (PackedCollection result = composer.getResultant(cp(position)).evaluate()) {
			int channelSamples = result.getShape().getTotalSize() / 2; // Stereo audio, 2 channels
			int finalSamples = (int) (getAudioDuration() * composer.getSampleRate());

			// Each channel is truncated to finalSamples, which is the leading
			// column range of the result viewed as one row per channel.
			PackedCollection stereoAudio = new PackedCollection(2, finalSamples);
			a(cp(stereoAudio), subset(shape(2, finalSamples),
					cp(result).reshape(2, channelSamples), 0, 0)).get().run();
			return stereoAudio;
		}
	}

	/**
	 * Generates audio from the given position vector and saves it to the named file.
	 *
	 * @param position    the interpolation position vector
	 * @param destination the output file path
	 */
	public void generateAudio(PackedCollection position, String destination) {
		generateAudio(position, new File(destination));
	}

	/**
	 * Generates audio from the given position vector and saves it to the given file.
	 *
	 * @param position    the interpolation position vector
	 * @param destination the output file
	 */
	public void generateAudio(PackedCollection position, File destination) {
		PackedCollection result = project(position);
		WaveData out = new WaveData(result, (int) composer.getSampleRate());
		out.save(destination);
	}

	@Override
	public void close() {
		composer.destroy();
	}
}
