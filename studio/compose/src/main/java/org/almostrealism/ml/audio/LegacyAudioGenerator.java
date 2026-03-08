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

package org.almostrealism.ml.audio;

import org.almostrealism.audio.WavFile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;

import java.io.File;
import java.io.IOException;
import java.util.function.DoubleConsumer;

/**
 * Legacy implementation of AudioGenerator preserved for validation testing.
 * This class contains the original implementation before refactoring to use
 * AudioDiffusionGenerator internally.
 *
 * @deprecated Use {@link AudioGenerator} instead. This class exists only for
 *             validation testing and will be removed after verification.
 */
@Deprecated
public class LegacyAudioGenerator extends ConditionalAudioSystem {

	private double audioDurationSeconds;
	private DoubleConsumer progressMonitor;

	private final AudioComposer composer;
	private final DiffusionSampler sampler;
	private double strength;

	/**
	 * Creates a LegacyAudioGenerator with the provided components.
	 */
	public LegacyAudioGenerator(Tokenizer tokenizer,
						  AudioAttentionConditioner conditioner,
						  AutoEncoder autoencoder,
						  StateDictionary ditStates) {
		this(tokenizer, conditioner, autoencoder, ditStates, 8, System.currentTimeMillis());
	}

	/**
	 * Creates a LegacyAudioGenerator with the provided components and composer configuration.
	 */
	public LegacyAudioGenerator(Tokenizer tokenizer,
						  AudioAttentionConditioner conditioner,
						  AutoEncoder autoencoder,
						  StateDictionary ditStates,
						  int composerDim,
						  long composerSeed) {
		super(tokenizer, conditioner, autoencoder, ditStates);
		composer = new AudioComposer(getAutoencoder(), composerDim, composerSeed);
		audioDurationSeconds = 10.0;
		strength = 0.5;

		// Create sampler with ping-pong strategy - IT OWNS THE LOOP
		this.sampler = new DiffusionSampler(
				getDiffusionModel(),
				new PingPongSamplingStrategy(),
				NUM_STEPS,
				DIT_X_SHAPE
		);
	}

	public double getAudioDuration() { return audioDurationSeconds; }
	public void setAudioDurationSeconds(double seconds) {
		this.audioDurationSeconds = seconds;
	}

	public DoubleConsumer getProgressMonitor() { return progressMonitor; }
	public void setProgressMonitor(DoubleConsumer monitor) {
		this.progressMonitor = monitor;
		sampler.setProgressCallback(monitor);
	}

	public void setStrength(double strength) {
		if (strength < 0.0 || strength > 1.0) {
			throw new IllegalArgumentException("Strength must be between 0.0 and 1.0");
		}
		this.strength = strength;
	}

	public double getStrength() { return strength; }

	public AudioComposer getComposer() { return composer; }

	public int getComposerDimension() { return composer.getEmbeddingDimension(); }

	public void addAudio(PackedCollection audio) {
		composer.addAudio(cp(audio));
	}

	public void addFeatures(PackedCollection features) {
		composer.addSource(cp(features));
	}

	public void generateAudio(String prompt, long seed, String outputPath) throws IOException {
		generateAudio(null, prompt, seed, outputPath);
	}

	public void generateAudio(PackedCollection position, String prompt,
							  long seed, String outputPath) throws IOException {
		double[][] audio = generateAudio(position, prompt, seed);
		try (WavFile f = WavFile.newWavFile(new File(outputPath), 2, audio[0].length, 32, SAMPLE_RATE)) {
			f.writeFrames(audio);
		}
	}

	public double[][] generateAudio(PackedCollection position, String prompt, long seed) {
		try {
			return generateAudio(position, getTokenizer().encodeAsLong(prompt), seed);
		} finally {
			if (progressMonitor != null) {
				progressMonitor.accept(1.0);
			}
		}
	}

	public double[][] generateAudio(PackedCollection position, long[] tokenIds, long seed) {
		try {
			if (position == null) {
				log("Generating audio with seed " + seed +
						" (duration = " + getAudioDuration() + ")");
			} else {
				log("Generating audio from samples with seed " + seed +
						" (duration = " + getAudioDuration() + ", strength = " + getStrength() + ")");
			}

			// 1. Process tokens through conditioners
			AudioAttentionConditioner.ConditionerOutput conditionerOutputs =
					getConditioner().runConditioners(tokenIds, getAudioDuration());

			PackedCollection crossAttnCond = conditionerOutputs.getCrossAttentionInput();
			PackedCollection globalCond = conditionerOutputs.getGlobalCond();

			// 2. Generate interpolated latent from samples (if position provided)
			PackedCollection interpolatedLatent = null;
			if (position != null) {
				interpolatedLatent = composer.getInterpolatedLatent(cp(position)).evaluate();
			}

			// 3. Run diffusion - DELEGATE TO DiffusionSampler - NO LOOP HERE
			PackedCollection finalLatent;
			if (interpolatedLatent == null) {
				finalLatent = sampler.sample(seed, crossAttnCond, globalCond);
			} else {
				finalLatent = sampler.sampleFrom(interpolatedLatent, strength, seed,
						crossAttnCond, globalCond);
			}

			// 4. Decode audio
			long start = System.currentTimeMillis();
			double[][] audio = decodeAudio(finalLatent.reshape(LATENT_DIMENSIONS, -1));
			log((System.currentTimeMillis() - start) + "ms for autoencoder");
			return audio;
		} finally {
			if (progressMonitor != null) {
				progressMonitor.accept(1.0);
			}
		}
	}

	private double[][] decodeAudio(PackedCollection latent) {
		PackedCollection result = getAutoencoder().decode(cp(latent)).evaluate();

		double[] data = result.toArray();
		int totalSamples = data.length;
		int channelSamples = totalSamples / 2; // Stereo audio, 2 channels
		int finalSamples = (int) (getAudioDuration() * SAMPLE_RATE);

		double[][] stereoAudio = new double[2][finalSamples];
		for (int i = 0; i < finalSamples; i++) {
			stereoAudio[0][i] = data[i];
			stereoAudio[1][i] = data[i + channelSamples];
		}

		return stereoAudio;
	}

	public DiffusionSampler getSampler() {
		return sampler;
	}
}
