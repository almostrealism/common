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

package org.almostrealism.ml.audio;

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
	public static final int DIM = 8;

	private final AudioComposer composer;
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

	public double getAudioDuration() { return audioDuration; }
	public void setAudioDuration(double seconds) {
		this.audioDuration = Math.min(composer.getMaximumAudioDuration(), seconds);
	}

	public void addAudio(PackedCollection audio) {
		composer.addAudio(cp(audio));
	}

	public void addFeatures(PackedCollection features) {
		composer.addSource(cp(features));
	}

	public PackedCollection project(PackedCollection position) {
		try (PackedCollection result = composer.getResultant(cp(position)).evaluate()) {
			double[] data = result.toArray();
			int totalSamples = data.length;
			int channelSamples = totalSamples / 2; // Stereo audio, 2 channels
			int finalSamples = (int) (getAudioDuration() * composer.getSampleRate());

			double[] stereoAudio = new double[2 * finalSamples];
			for (int i = 0; i < finalSamples; i++) {
				stereoAudio[i] = data[i];
				stereoAudio[finalSamples + i] = data[channelSamples + i];
			}

			return pack(stereoAudio).reshape(2, finalSamples);
		}
	}

	public void generateAudio(PackedCollection position, String destination) {
		generateAudio(position, new File(destination));
	}

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
