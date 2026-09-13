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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.ResamplingConfig;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.TransformerResamplingFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

/**
 * A transformer-resampling audio autoencoder: a {@link PatchedPretransform} folds samples into
 * channels, a single learned-resampling transformer block ({@link TransformerResamplingFeatures})
 * changes the frame rate, a per-frame linear projection maps to and from the latent width, and a
 * {@link Bottleneck} produces the latent. The decoder is the mirror image.
 *
 * <p>Encoding maps {@code [batch, channels, samples]} to {@code [batch, latentDim, samples / ratio]}
 * where the downsampling {@code ratio} is the pretransform patch size times the resampling stride;
 * decoding maps back. Sample counts must be a whole number of aligned frames (see
 * {@link #alignedAudioLength(int)}); callers zero-pad audio to that length.</p>
 *
 * <p>Weights are read from a {@link StateDictionary} under the reference key layout:
 * {@code encoder.layers.0.*} and {@code decoder.layers.3.*} for the resampling blocks,
 * {@code encoder.layers.2.*} / {@code decoder.layers.1.*} for the latent projections (the
 * released encoder has a parameterless transpose at index 1, the decoder at index 2) and
 * {@code bottleneck.*} for the bottleneck. The blocks returned by {@link #encoder} and
 * {@link #decoder} are compiled by the caller, typically into a compiled-model autoencoder adapter.</p>
 */
public class SAMEAutoEncoder implements TransformerResamplingFeatures, LayerFeatures {

	/** Loaded weights. */
	private final StateDictionary weights;

	/** Sample folding in front of the encoder and behind the decoder. */
	private final PatchedPretransform pretransform;

	/** The downsampling resampling block configuration. */
	private final ResamplingConfig encoderConfig;

	/** The upsampling resampling block configuration. */
	private final ResamplingConfig decoderConfig;

	/** Latent channel count. */
	private final int latentDim;

	/** The latent-boundary transform. */
	private final Bottleneck bottleneck;

	/**
	 * Creates an autoencoder from its parts.
	 *
	 * @param weights       loaded weights under the reference key layout
	 * @param pretransform  sample folding in front of the encoder
	 * @param encoderConfig the downsampling resampling block; its input width must equal the
	 *                      pretransform's encoded channel count
	 * @param decoderConfig the upsampling resampling block; its output width must equal the
	 *                      pretransform's encoded channel count
	 * @param latentDim     latent channel count
	 * @param bottleneck    the latent-boundary transform over {@code latentDim} channels
	 */
	public SAMEAutoEncoder(StateDictionary weights, PatchedPretransform pretransform,
						   ResamplingConfig encoderConfig, ResamplingConfig decoderConfig,
						   int latentDim, Bottleneck bottleneck) {
		if (encoderConfig.getInChannels() != pretransform.getEncodedChannels()) {
			throw new IllegalArgumentException("Encoder input width " + encoderConfig.getInChannels() +
					" does not match the pretransform's " + pretransform.getEncodedChannels() + " channels");
		}

		if (decoderConfig.getOutChannels() != pretransform.getEncodedChannels()) {
			throw new IllegalArgumentException("Decoder output width " + decoderConfig.getOutChannels() +
					" does not match the pretransform's " + pretransform.getEncodedChannels() + " channels");
		}

		if (!encoderConfig.isEncoder() || decoderConfig.isEncoder()) {
			throw new IllegalArgumentException("encoderConfig must downsample and decoderConfig must upsample");
		}

		if (bottleneck.getInputDim() != latentDim || bottleneck.getOutputDim() != latentDim) {
			throw new IllegalArgumentException("Bottleneck must operate on " + latentDim + " channels");
		}

		this.weights = weights;
		this.pretransform = pretransform;
		this.encoderConfig = encoderConfig;
		this.decoderConfig = decoderConfig;
		this.latentDim = latentDim;
		this.bottleneck = bottleneck;
	}

	/**
	 * Creates the small released configuration: stereo audio folded by 256, one 512 to 768 channel
	 * resampling block of stride 16 and depth 6 with 12 heads of 64, chunked attention of chunk size
	 * 32 with midpoint shift, a 3-times GLU feed-forward, a 256-channel latent and a
	 * {@link SoftNormBottleneck} read from the weights.
	 *
	 * @param weights loaded weights under the reference key layout
	 * @return the autoencoder
	 */
	public static SAMEAutoEncoder small(StateDictionary weights) {
		int latentDim = 256;
		ResamplingConfig encoder = new ResamplingConfig(512, 768, 12, 64, 16, 32, 6,
				true, true, true, 3.0, 1, ResamplingConfig.AttentionWindow.CHUNKED);
		ResamplingConfig decoder = new ResamplingConfig(768, 512, 12, 64, 16, 32, 6,
				false, true, true, 3.0, 3, ResamplingConfig.AttentionWindow.CHUNKED);
		return new SAMEAutoEncoder(weights, new PatchedPretransform(2, 256),
				encoder, decoder, latentDim, softNormBottleneck(weights, latentDim));
	}

	/**
	 * Reads a {@link SoftNormBottleneck} from the {@code bottleneck.*} weights.
	 *
	 * @param weights   loaded weights
	 * @param latentDim latent channel count
	 * @return the bottleneck
	 */
	public static SoftNormBottleneck softNormBottleneck(StateDictionary weights, int latentDim) {
		PackedCollection runningStd = weights.containsKey("bottleneck.running_std") ?
				weights.get("bottleneck.running_std") : null;
		return new SoftNormBottleneck(latentDim,
				weights.get("bottleneck.scaling_factor"), weights.get("bottleneck.bias"), runningStd);
	}

	/**
	 * Returns the latent channel count.
	 *
	 * @return the latent dimension
	 */
	public int getLatentDim() { return latentDim; }

	/**
	 * Returns the number of audio samples per latent frame.
	 *
	 * @return the pretransform patch size times the resampling stride
	 */
	public int getDownsamplingRatio() {
		return pretransform.getDownsamplingRatio() * encoderConfig.getStride();
	}

	/**
	 * Returns the number of audio channels.
	 *
	 * @return the channel count
	 */
	public int getChannels() { return pretransform.getChannels(); }

	/**
	 * The smallest audio length at or above {@code samples} that encodes as whole, chunk-aligned
	 * frames: a multiple of the patch size whose frame count satisfies the encoder's alignment.
	 *
	 * @param samples an audio length in samples
	 * @return the aligned length
	 */
	public int alignedAudioLength(int samples) {
		int frames = pretransform.paddedLength(samples) / pretransform.getPatchSize();
		return encoderConfig.getPaddedInputLength(frames) * pretransform.getPatchSize();
	}

	/**
	 * The latent length produced for an aligned audio length.
	 *
	 * @param samples an aligned audio length (see {@link #alignedAudioLength(int)})
	 * @return the number of latent frames
	 */
	public int latentLength(int samples) {
		return samples / getDownsamplingRatio();
	}

	/**
	 * Builds the encoder, mapping {@code [batch, channels, samples]} to
	 * {@code [batch, latentDim, samples / ratio]}.
	 *
	 * @param batchSize batch size
	 * @param samples   aligned audio length (see {@link #alignedAudioLength(int)})
	 * @return the encoder block
	 */
	public Block encoder(int batchSize, int samples) {
		if (alignedAudioLength(samples) != samples) {
			throw new IllegalArgumentException("Audio length " + samples +
					" is not aligned; use " + alignedAudioLength(samples));
		}

		int frames = samples / pretransform.getPatchSize();
		int latentLen = latentLength(samples);

		SequentialBlock encoder = new SequentialBlock(shape(batchSize, getChannels(), samples));
		encoder.add(pretransform.encode(batchSize, samples));
		encoder.add(transformerResamplingBlock(batchSize, frames, encoderConfig, weights, "encoder.layers.0"));
		encoder.add(channelProjection("encoder.layers.2", batchSize, encoderConfig.getOutChannels(), latentDim, latentLen));
		encoder.add(bottleneck.bottleneck(batchSize, latentLen));
		return encoder;
	}

	/**
	 * Builds the decoder, mapping {@code [batch, latentDim, latentLen]} to
	 * {@code [batch, channels, latentLen * ratio]}.
	 *
	 * @param batchSize batch size
	 * @param latentLen latent length, a multiple of the decoder's alignment
	 * @return the decoder block
	 */
	public Block decoder(int batchSize, int latentLen) {
		if (decoderConfig.getPaddedInputLength(latentLen) != latentLen) {
			throw new IllegalArgumentException("Latent length " + latentLen +
					" is not aligned; use " + decoderConfig.getPaddedInputLength(latentLen));
		}

		int frames = latentLen * decoderConfig.getStride();

		SequentialBlock decoder = new SequentialBlock(shape(batchSize, latentDim, latentLen));
		decoder.add(bottleneck.decode(batchSize, latentLen));
		decoder.add(channelProjection("decoder.layers.1", batchSize, latentDim, decoderConfig.getInChannels(), latentLen));
		decoder.add(transformerResamplingBlock(batchSize, latentLen, decoderConfig, weights, "decoder.layers.3"));
		decoder.add(pretransform.decode(batchSize, frames));
		return decoder;
	}

	/**
	 * A per-frame linear projection of a channels-first sequence, {@code [batch, in, len]} to
	 * {@code [batch, out, len]}, reading {@code key.weight} ({@code [out, in]}) and {@code key.bias}.
	 *
	 * @param key       weight key prefix
	 * @param batchSize batch size
	 * @param in        input channels
	 * @param out       output channels
	 * @param len       sequence length
	 * @return the projection block
	 */
	protected Block channelProjection(String key, int batchSize, int in, int out, int len) {
		PackedCollection weight = weights.get(key + ".weight");
		PackedCollection bias = weights.containsKey(key + ".bias") ? weights.get(key + ".bias") : null;

		if (weight == null || weight.getShape().getTotalSize() != out * in) {
			throw new IllegalArgumentException(key + ".weight must have shape [" + out + ", " + in + "]");
		}

		TraversalPolicy inShape = shape(batchSize, in, len);
		TraversalPolicy outShape = shape(batchSize, out, len);
		return layer(key, inShape, outShape,
				x -> permute(linear(permute(c(x), 0, 2, 1), weight.reshape(out, in), bias), 0, 2, 1));
	}
}
