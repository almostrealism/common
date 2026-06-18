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

package org.almostrealism.ml;

// TODO(review): TransformerResamplingFeatures does not exist yet — update @link below when Block C2 creates it
/**
 * Configuration for a learned-resampling transformer block (see
 * {@link TransformerResamplingFeatures#transformerResamplingBlock}).
 *
 * <p>A learned-resampling block changes the length of a sequence by an integer {@code stride}
 * using a learned {@code new_tokens} mechanism instead of strided convolution. The block groups
 * the input sequence into segments, appends learned token(s) to each segment, runs a small stack
 * of self-attention transformer layers over windowed chunks of the segmented sequence, and then
 * extracts the learned-token positions as the resampled output. An encoder block downsamples
 * ({@code L -> L/stride}); a decoder block upsamples ({@code L -> L*stride}).</p>
 *
 * <p>This configuration captures the architecture of the SAME audio autoencoder's resampling
 * blocks, but the primitive is general: any transformer-based encoder/decoder that up/down-samples
 * a sequence can reuse it by supplying a different configuration. The Phase-1 implementation builds
 * the SAME family of blocks (differential attention with {@link org.almostrealism.layers.NormalizationLayerFeatures#dynamicTanh
 * DynamicTanh} normalization and a GLU feed-forward) over {@link AttentionWindow#CHUNKED chunked}
 * attention; {@link AttentionWindow#SLIDING sliding-window} attention is a forward-looking seam.</p>
 *
 * <p>The derived segment/chunk sizes follow the resampling block definition exactly:</p>
 * <ul>
 *   <li>{@code inputSegSize}  = {@code stride} (encoder) or {@code 1} (decoder)</li>
 *   <li>{@code outputSegSize} = {@code 1} (encoder) or {@code stride} (decoder)</li>
 *   <li>{@code subChunkSize}  = {@code stride + 1} (one segment: real tokens followed by learned tokens)</li>
 *   <li>{@code effectiveChunkSize} = {@code chunkSize + chunkSize / stride} (chunk length in segmented space)</li>
 * </ul>
 *
 * @author  Michael Murray
 */
public class ResamplingConfig {

	/**
	 * Strategy for restricting self-attention to local windows of the segmented sequence.
	 */
	public enum AttentionWindow {
		/**
		 * Attention is computed over contiguous, equally-sized chunks of the segmented sequence
		 * (optionally with a midpoint shift on the second half of the layer stack). This is the
		 * strategy used by SAME-S and the only one implemented in Phase 1.
		 */
		CHUNKED,

		/**
		 * Sliding-window attention (used by SAME-L). Reserved as a forward-looking configuration
		 * value; not yet implemented.
		 */
		SLIDING
	}

	private final int inChannels;
	private final int outChannels;
	private final int heads;
	private final int dimHead;
	private final int stride;
	private final int chunkSize;
	private final int depth;
	private final boolean encoder;
	private final boolean variableStride;
	private final boolean chunkMidpointShift;
	private final double ffMult;
	private final int mappingKernel;
	private final AttentionWindow window;

	/**
	 * Creates a resampling block configuration.
	 *
	 * @param inChannels         number of channels of the block input ({@code [B, inChannels, L]})
	 * @param outChannels        number of channels of the block output
	 * @param heads              number of attention heads
	 * @param dimHead            per-head dimension; the transformer width is {@code heads * dimHead}
	 * @param stride             sequence-length change factor (downsample in encoder, upsample in decoder)
	 * @param chunkSize          attention chunk size, measured in input positions; must be a multiple of {@code stride}
	 * @param depth              number of transformer layers in the block
	 * @param encoder            {@code true} for a downsampling encoder block, {@code false} for an upsampling decoder block
	 * @param variableStride     whether the learned token count is fixed at the output segment size (SAME uses {@code true})
	 * @param chunkMidpointShift whether the second half of the layer stack runs on midpoint-shifted chunks
	 * @param ffMult             GLU feed-forward expansion factor; the inner width is {@code round(width * ffMult)}
	 * @param mappingKernel      kernel size of the channel-mapping convolution ({@code 1} for encoder, {@code 3} for decoder)
	 * @param window             attention windowing strategy
	 */
	public ResamplingConfig(int inChannels, int outChannels, int heads, int dimHead,
							int stride, int chunkSize, int depth,
							boolean encoder, boolean variableStride, boolean chunkMidpointShift,
							double ffMult, int mappingKernel, AttentionWindow window) {
		if (stride <= 0 || chunkSize <= 0 || depth <= 0) {
			throw new IllegalArgumentException("stride, chunkSize and depth must be positive");
		}

		if (chunkSize % stride != 0) {
			throw new IllegalArgumentException("chunkSize must be a multiple of stride");
		}

		this.inChannels = inChannels;
		this.outChannels = outChannels;
		this.heads = heads;
		this.dimHead = dimHead;
		this.stride = stride;
		this.chunkSize = chunkSize;
		this.depth = depth;
		this.encoder = encoder;
		this.variableStride = variableStride;
		this.chunkMidpointShift = chunkMidpointShift;
		this.ffMult = ffMult;
		this.mappingKernel = mappingKernel;
		this.window = window;
	}

	/** @return number of channels of the block input. */
	public int getInChannels() { return inChannels; }

	/** @return number of channels of the block output. */
	public int getOutChannels() { return outChannels; }

	/** @return number of attention heads. */
	public int getHeads() { return heads; }

	/** @return per-head dimension. */
	public int getDimHead() { return dimHead; }

	/** @return sequence-length change factor. */
	public int getStride() { return stride; }

	/** @return attention chunk size in input positions. */
	public int getChunkSize() { return chunkSize; }

	/** @return number of transformer layers. */
	public int getDepth() { return depth; }

	/** @return whether this is a downsampling encoder block. */
	public boolean isEncoder() { return encoder; }

	/** @return whether the learned token count tracks the output segment size. */
	public boolean isVariableStride() { return variableStride; }

	/** @return whether the second half of the layer stack uses midpoint-shifted chunks. */
	public boolean isChunkMidpointShift() { return chunkMidpointShift; }

	/** @return GLU feed-forward expansion factor. */
	public double getFfMult() { return ffMult; }

	/** @return channel-mapping convolution kernel size. */
	public int getMappingKernel() { return mappingKernel; }

	/** @return attention windowing strategy. */
	public AttentionWindow getWindow() { return window; }

	/**
	 * The transformer width: {@code heads * dimHead}. For an encoder this equals {@code outChannels}
	 * (the mapping runs before the transformer); for a decoder it equals {@code inChannels} (the
	 * mapping runs after).
	 *
	 * @return the transformer width
	 */
	public int getDim() {
		return heads * dimHead;
	}

	/**
	 * Number of real (input) positions grouped into each segment.
	 *
	 * @return {@code stride} for an encoder, {@code 1} for a decoder
	 */
	public int getInputSegSize() {
		return encoder ? stride : 1;
	}

	/**
	 * Number of learned-token positions extracted from each segment as output.
	 *
	 * @return {@code 1} for an encoder, {@code stride} for a decoder
	 */
	public int getOutputSegSize() {
		return encoder ? 1 : stride;
	}

	/**
	 * The number of learned tokens appended to each segment. With {@code variableStride}, this is the
	 * output segment size; otherwise the {@code new_tokens} parameter already carries that many tokens.
	 *
	 * @return the learned-token count per segment
	 */
	public int getNewTokenCount() {
		return getOutputSegSize();
	}

	/**
	 * The length of one segment in segmented space: the real positions plus the learned tokens.
	 *
	 * @return {@code stride + 1}
	 */
	public int getSubChunkSize() {
		return stride + 1;
	}

	/**
	 * The attention chunk size measured in segmented space (one chunk spans
	 * {@code chunkSize / stride} segments).
	 *
	 * @return {@code chunkSize + chunkSize / stride}
	 */
	public int getEffectiveChunkSize() {
		return chunkSize + chunkSize / stride;
	}

	/**
	 * The inner width of the GLU feed-forward (before the gating split).
	 *
	 * @return {@code round(getDim() * ffMult)}
	 */
	public int getInnerFfDim() {
		return (int) Math.round(getDim() * ffMult);
	}

	/**
	 * The input sequence length after zero-padding to the block's alignment requirement. The encoder
	 * pads to a multiple of {@code chunkSize}; the decoder pads (in transposed/segment-input space) to
	 * a multiple of {@code chunkSize / stride}.
	 *
	 * @param seqLen the unpadded input sequence length
	 * @return the padded sequence length
	 */
	public int getPaddedInputLength(int seqLen) {
		int modulo = encoder ? chunkSize : chunkSize / stride;
		return ((seqLen + modulo - 1) / modulo) * modulo;
	}

	/**
	 * The output sequence length produced for the given input length.
	 *
	 * @param seqLen the unpadded input sequence length
	 * @return {@code paddedLength / stride} for an encoder, {@code paddedLength * stride} for a decoder
	 */
	public int getOutputLength(int seqLen) {
		int padded = getPaddedInputLength(seqLen);
		return encoder ? padded / stride : padded * stride;
	}
}
