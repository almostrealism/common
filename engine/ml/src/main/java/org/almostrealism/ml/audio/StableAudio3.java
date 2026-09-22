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

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.t5gemma.T5GemmaConfig;
import org.almostrealism.ml.t5gemma.T5GemmaEncoder;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

/**
 * Text-to-audio generation with Stable Audio 3: a prompt and a duration are conditioned by an
 * {@link AudioAttentionConditioner}, a latent is sampled by a {@link DiffusionSampler} driving a
 * {@link DiffusionTransformer} with the ping-pong schedule of the released models, and the latent
 * is decoded by a {@link SAMEAutoEncoder}.
 *
 * <p>The transformer and the decoder are compiled once, for the longest clip the instance
 * generates: the requested duration plus the headroom the released pipeline adds, aligned to
 * whole latent frames and capped at the model's maximum clip. A shorter request is generated at
 * that length with the transformer's padding mask covering the requested duration plus headroom,
 * which is the convention the models were trained under, and the decoded audio is clamped to
 * {@code [-1, 1]} and truncated to the requested duration.</p>
 *
 * <p>Classifier-free guidance is off by default, as in the released pipeline; enabling it runs the
 * conditioner a second time on the negative prompt and the transformer twice per step.</p>
 */
public class StableAudio3 implements CodeFeatures, ConsoleFeatures, Destroyable {

	/** Sample rate of the released models. */
	public static final int SAMPLE_RATE = 44100;

	/** Longest clip of the released models, in samples. */
	public static final int MAX_SAMPLES = 5292032;

	/** Seconds of latent generated beyond the requested duration by the released pipeline. */
	public static final double HEADROOM_SECONDS = 6.0;

	/** Sampling steps of the released pipeline. */
	public static final int DEFAULT_STEPS = 8;

	/** High-noise log-SNR bound of the released inference schedule. */
	public static final double LOG_SNR_START = -6.2;

	/** Low-noise log-SNR bound of the released inference schedule. */
	public static final double LOG_SNR_END = 2.0;

	/** Every instance generates one clip at a time. */
	private static final int BATCH = 1;

	/** Largest duration the released number conditioner encodes, in seconds. */
	private static final double MAX_CONDITIONED_SECONDS = 384.0;

	/** Fourier feature count of the released duration embedder. */
	private static final int DURATION_FOURIER_DIM = 256;

	/** Lowest frequency of the released duration embedder's ladder. */
	private static final double DURATION_MIN_FREQ = 0.5;

	/** Highest frequency of the released duration embedder's ladder. */
	private static final double DURATION_MAX_FREQ = 10000.0;

	/** The prompt and duration conditioner. */
	private final AudioAttentionConditioner conditioner;

	/** The denoiser, compiled for {@link #latentLen} latent frames. */
	private final DiffusionTransformer transformer;

	/** The latent decoder, compiled for {@link #latentLen} latent frames. */
	private final CompiledModel decoder;

	/** Audio channels produced by the decoder. */
	private final int channels;

	/** Latent channels. */
	private final int latentDim;

	/** Samples per latent frame. */
	private final int downsamplingRatio;

	/** Audio sample rate in Hz. */
	private final double sampleRate;

	/** Longest duration this instance generates, in seconds. */
	private final double maxSeconds;

	/** Seconds of latent generated beyond the requested duration. */
	private final double headroomSeconds;

	/** Audio length the models are compiled for, in samples. */
	private final int samples;

	/** Latent length the models are compiled for, in frames. */
	private final int latentLen;

	/** Sampling steps. */
	private int steps = DEFAULT_STEPS;

	/** Guidance scale; one disables guidance. */
	private double guidanceScale = 1.0;

	/** Token ids of the negative prompt used by guidance; empty by default. */
	private long[] negativePrompt = new long[0];

	/** Whether generation progress is logged. */
	private boolean verbose = true;

	/**
	 * Creates a generator over the given components, compiled for clips of up to
	 * {@code maxSeconds}.
	 *
	 * @param config             the transformer architecture; its latent length is replaced by the
	 *                           length derived from {@code maxSeconds}
	 * @param transformerWeights the transformer weights, released by {@link #destroy()}
	 * @param conditioner        the prompt and duration conditioner, released by {@link #destroy()}
	 * @param autoencoder        the latent autoencoder whose decoder is compiled here
	 * @param sampleRate         the audio sample rate in Hz
	 * @param maxSeconds         the longest duration to generate, in seconds; rejected when it
	 *                           needs more than {@link #MAX_SAMPLES} samples
	 * @param headroomSeconds    seconds of latent generated beyond the requested duration
	 */
	public StableAudio3(DiffusionTransformerConfig config, StateDictionary transformerWeights,
						AudioAttentionConditioner conditioner, SAMEAutoEncoder autoencoder,
						double sampleRate, double maxSeconds, double headroomSeconds) {
		if (maxSeconds <= 0.0 || headroomSeconds < 0.0) {
			throw new IllegalArgumentException("maxSeconds must be positive and headroomSeconds non-negative");
		}

		this.conditioner = conditioner;
		this.channels = autoencoder.getChannels();
		this.latentDim = autoencoder.getLatentDim();
		this.downsamplingRatio = autoencoder.getDownsamplingRatio();
		this.sampleRate = sampleRate;
		this.maxSeconds = maxSeconds;
		this.headroomSeconds = headroomSeconds;
		this.samples = Math.min(MAX_SAMPLES,
				autoencoder.alignedAudioLength(seconds(maxSeconds + headroomSeconds)));
		this.latentLen = autoencoder.latentLength(samples);

		if (seconds(maxSeconds) > samples) {
			throw new IllegalArgumentException("maxSeconds " + maxSeconds + " needs " + seconds(maxSeconds) +
					" samples, beyond the " + samples + " the decoder can produce");
		}

		if (config.getIoChannels() != autoencoder.getLatentDim()) {
			throw new IllegalArgumentException("The transformer's " + config.getIoChannels() +
					" channels do not match the autoencoder's " + autoencoder.getLatentDim() + " latent channels");
		}

		this.transformer = new DiffusionTransformer(
				config.withSequenceLengths(latentLen, config.getCondSeqLen()), transformerWeights);

		Model decoderModel = new Model(shape(BATCH, latentDim, latentLen));
		decoderModel.add(autoencoder.decoder(BATCH, latentLen));
		this.decoder = decoderModel.compile(false);
	}

	/**
	 * Creates the released small model: the T5Gemma prompt encoder with the learned padding
	 * embedding and duration projection of the checkpoint, the small transformer and SAME-S.
	 *
	 * @param transformerWeights   the transformer weights, as extracted with the {@code dit} target
	 * @param conditionerWeights   the conditioner weights, as extracted with the {@code conditioner} target
	 * @param promptEncoderWeights the T5Gemma encoder weights
	 * @param autoencoderWeights   the SAME-S weights, as extracted with the {@code ae} target
	 * @param maxSeconds           the longest duration to generate, in seconds
	 * @return the generator
	 */
	public static StableAudio3 small(StateDictionary transformerWeights, StateDictionary conditionerWeights,
									 StateDictionary promptEncoderWeights, StateDictionary autoencoderWeights,
									 double maxSeconds) {
		T5GemmaEncoder encoder = new T5GemmaEncoder(T5GemmaConfig.baseUl2(), promptEncoderWeights);
		int hidden = encoder.getConfig().getHiddenSize();
		NumberConditioner duration = NumberConditioner.expo(0.0, MAX_CONDITIONED_SECONDS, hidden,
				DURATION_FOURIER_DIM, DURATION_MIN_FREQ, DURATION_MAX_FREQ,
				conditionerWeights.get("conditioner.conditioners.seconds_total.embedder.embedding.1.weight"),
				conditionerWeights.get("conditioner.conditioners.seconds_total.embedder.embedding.1.bias"));
		StableAudio3Conditioner conditioner = new StableAudio3Conditioner(encoder,
				conditionerWeights.get("conditioner.conditioners.prompt.padding_embedding"), duration);

		return new StableAudio3(smallTransformer(hidden, encoder.getConfig().getMaxLength() + 1),
				transformerWeights, conditioner, SAMEAutoEncoder.small(autoencoderWeights),
				SAMPLE_RATE, maxSeconds, HEADROOM_SECONDS);
	}

	/**
	 * The released small transformer: 256 latent channels, width 1024, 20 blocks of 16 heads with
	 * RMS normalization, adaptive layer-norm conditioning on the duration, 64 memory tokens, a
	 * 257-channel local additive conditioning input, deterministic timestep features and a padding
	 * mask over the latent. The latent length is a placeholder replaced at construction.
	 *
	 * @param condDim    width of the conditioner's context tokens and global conditioning
	 * @param condSeqLen number of context tokens
	 * @return the configuration
	 */
	public static DiffusionTransformerConfig smallTransformer(int condDim, int condSeqLen) {
		return new DiffusionTransformerConfig(256, 1024, 20, 16, 1, condDim, condDim,
				"rf_denoiser", 1, condSeqLen)
				.withConditioningMode(ConditioningMode.ADALN)
				.withMemoryTokens(64)
				.withLocalAddCondDim(257)
				.withTimestepEncoding(TimestepEncoding.EXPO)
				.withNormalization(NormalizationType.RMS)
				.withPaddingMask(true);
	}

	/**
	 * Sets the number of sampling steps.
	 *
	 * @param steps sampling steps
	 * @return this generator
	 */
	public StableAudio3 setSteps(int steps) {
		if (steps <= 0) {
			throw new IllegalArgumentException("steps must be positive");
		}

		this.steps = steps;
		return this;
	}

	/**
	 * Enables classifier-free guidance against a negative prompt. A scale of one disables it.
	 *
	 * @param scale          the guidance scale
	 * @param negativePrompt token ids of the negative prompt; empty for the unconditional prompt
	 * @return this generator
	 */
	public StableAudio3 setGuidance(double scale, long[] negativePrompt) {
		this.guidanceScale = scale;
		this.negativePrompt = negativePrompt == null ? new long[0] : negativePrompt;
		return this;
	}

	/**
	 * Sets whether generation progress is logged.
	 *
	 * @param verbose whether to log
	 * @return this generator
	 */
	public StableAudio3 setVerbose(boolean verbose) {
		this.verbose = verbose;
		return this;
	}

	/**
	 * Returns the audio sample rate.
	 *
	 * @return the sample rate in Hz
	 */
	public double getSampleRate() { return sampleRate; }

	/**
	 * Returns the number of latent frames the models are compiled for.
	 *
	 * @return the latent length
	 */
	public int getLatentLength() { return latentLen; }

	/**
	 * Returns the transformer's padding mask, shape {@code [1, latentLength]}; after a generation it
	 * holds the mask that generation used.
	 *
	 * @return the padding mask
	 */
	public PackedCollection getPaddingMask() { return transformer.getPaddingMask(); }

	/**
	 * The number of latent frames treated as valid for a duration: the frames covering the
	 * duration plus the headroom, at most the compiled latent length.
	 *
	 * @param seconds the requested duration
	 * @return the valid frame count
	 */
	public int validFrames(double seconds) {
		int covered = seconds(seconds) + seconds(headroomSeconds);
		return Math.min(latentLen, (int) Math.ceil(covered / (double) downsamplingRatio));
	}

	/**
	 * Generates a clip: the sampling loop and the decoder run here, and the clamp and truncation
	 * of the decoded audio are returned as a producer for the caller to evaluate.
	 *
	 * <p>The producer reads the decoder's output buffer, which the next call overwrites, so it
	 * must be evaluated before the next generation.</p>
	 *
	 * @param seed    seed of the initial noise and the ping-pong noise injections
	 * @param prompt  token ids of the prompt
	 * @param seconds the duration in seconds, at most the duration this instance was built for
	 * @return the audio, shape {@code [channels, samples]}, with values in {@code [-1, 1]}
	 */
	public CollectionProducer generate(long seed, long[] prompt, double seconds) {
		if (seconds <= 0.0 || seconds > maxSeconds) {
			throw new IllegalArgumentException("Duration " + seconds + " is outside (0, " + maxSeconds + "]");
		}

		if (verbose) {
			log("Generating " + seconds + "s with seed " + seed + " (" + latentLen + " latent frames, " +
					validFrames(seconds) + " valid)");
		}

		transformer.setValidLength(validFrames(seconds));

		DiffusionSampler sampler = new DiffusionSampler(transformer,
				new PingPongSamplingStrategy(LogSNRShift.fixed(LOG_SNR_START, LOG_SNR_END)),
				steps, shape(BATCH, latentDim, latentLen))
				.setVerbose(verbose);

		AudioAttentionConditioner.ConditionerOutput positive = conditioner.runConditioners(prompt, seconds);
		PackedCollection context = positive.getCrossAttentionInput().clone();
		PackedCollection global = positive.getGlobalCond().clone();
		PackedCollection negativeContext = null;
		PackedCollection negativeGlobal = null;

		if (guidanceScale != 1.0) {
			AudioAttentionConditioner.ConditionerOutput negative = conditioner.runConditioners(negativePrompt, seconds);
			negativeContext = negative.getCrossAttentionInput().clone();
			negativeGlobal = negative.getGlobalCond().clone();
			sampler.setGuidance(new ClassifierFreeGuidance(guidanceScale), negativeContext, negativeGlobal);
		}

		PackedCollection audio;
		PackedCollection latent = sampler.sample(seed, context, global);
		try {
			audio = decoder.forward(latent);
		} finally {
			latent.destroy();
			context.destroy();
			global.destroy();
			if (negativeContext != null) negativeContext.destroy();
			if (negativeGlobal != null) negativeGlobal.destroy();
		}

		int outputSamples = seconds(seconds);
		return bound(cp(audio).reshape(channels, samples).subset(shape(channels, outputSamples), 0, 0), -1.0, 1.0);
	}

	/**
	 * The number of samples spanning a duration.
	 *
	 * @param seconds the duration
	 * @return the sample count
	 */
	private int seconds(double seconds) {
		return (int) (seconds * sampleRate);
	}

	@Override
	public void destroy() {
		decoder.destroy();
		transformer.destroy();
		conditioner.destroy();
	}
}
