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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for a learned-resampling transformer block (see
 * {@link TransformerResamplingFeatures#transformerResamplingBlock(int, int, ResamplingConfig,
 * StateDictionary, String)}).
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

	/** Number of channels of the block input. */
	private final int inChannels;
	/** Number of channels of the block output. */
	private final int outChannels;
	/** Number of attention heads. */
	private final int heads;
	/** Per-head dimension; the transformer width is {@code heads * dimHead}. */
	private final int dimHead;
	/** Sequence-length change factor (downsample in encoder, upsample in decoder). */
	private final int stride;
	/** Attention chunk size in input positions; a multiple of {@code stride}. */
	private final int chunkSize;
	/** Number of transformer layers in the block. */
	private final int depth;
	/** {@code true} for a downsampling encoder block, {@code false} for an upsampling decoder block. */
	private final boolean encoder;
	/** Whether the learned token count tracks the output segment size. */
	private final boolean variableStride;
	/** Whether the second half of the layer stack runs on midpoint-shifted chunks. */
	private final boolean chunkMidpointShift;
	/** GLU feed-forward expansion factor. */
	private final double ffMult;
	/** Kernel size of the channel-mapping convolution ({@code 1} encoder, {@code 3} decoder). */
	private final int mappingKernel;
	/** Attention windowing strategy. */
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

		if (mappingKernel <= 0) {
			throw new IllegalArgumentException("mappingKernel must be positive");
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

	/** Returns the number of channels of the block input. */
	public int getInChannels() { return inChannels; }

	/** Returns the number of channels of the block output. */
	public int getOutChannels() { return outChannels; }

	/** Returns the number of attention heads. */
	public int getHeads() { return heads; }

	/** Returns the per-head dimension. */
	public int getDimHead() { return dimHead; }

	/** Returns the sequence-length change factor. */
	public int getStride() { return stride; }

	/** Returns the attention chunk size in input positions. */
	public int getChunkSize() { return chunkSize; }

	/** Returns the number of transformer layers. */
	public int getDepth() { return depth; }

	/** Returns whether this is a downsampling encoder block. */
	public boolean isEncoder() { return encoder; }

	/** Returns whether the learned token count tracks the output segment size. */
	public boolean isVariableStride() { return variableStride; }

	/** Returns whether the second half of the layer stack uses midpoint-shifted chunks. */
	public boolean isChunkMidpointShift() { return chunkMidpointShift; }

	/** Returns the GLU feed-forward expansion factor. */
	public double getFfMult() { return ffMult; }

	/** Returns the channel-mapping convolution kernel size. */
	public int getMappingKernel() { return mappingKernel; }

	/** Returns the attention windowing strategy. */
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
	 * The number of learned-token positions appended to each segment. This equals the output segment
	 * size in both {@code variableStride} modes &mdash; the flag changes only how the positions are
	 * sourced, not how many there are. With {@code variableStride == true} a single learned token
	 * (the {@code new_tokens} parameter, which carries one token) is broadcast across all
	 * {@code outputSegSize} positions; with {@code variableStride == false} the {@code new_tokens}
	 * parameter itself already carries {@code outputSegSize} distinct tokens. Either way the segment
	 * grows by {@code outputSegSize} positions, so the count returned here is the same.
	 *
	 * @return the learned-token count per segment ({@code outputSegSize})
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
	 * The parameters a resampling block with this configuration reads from a checkpoint, mapped
	 * from weight key to tensor shape: the channel-mapping convolution, the learned resampling token,
	 * and per transformer layer the {@code DynamicTanh} norms (block, query and key), the fused
	 * differential-attention projection ({@code 5 * dim}), the output projection, the rotary
	 * frequencies and the gated feed-forward. The map is insertion-ordered as listed here.
	 *
	 * @param prefix the weight key prefix of the block (for example {@code "encoder.layers.0"})
	 * @return weight key to shape, for every parameter the block consumes
	 */
	public Map<String, int[]> weightShapes(String prefix) {
		int dim = getDim();
		int inner = getInnerFfDim();
		int invFreqLen = Math.max(1, dimHead / 4);

		Map<String, int[]> shapes = new LinkedHashMap<>();
		shapes.put(prefix + ".mapping.weight", new int[]{outChannels, inChannels, mappingKernel});
		shapes.put(prefix + ".mapping.bias", new int[]{outChannels});
		shapes.put(prefix + ".new_tokens", new int[]{1, 1, dim});

		for (int i = 0; i < depth; i++) {
			String lk = prefix + ".transformers." + i;
			shapes.put(lk + ".pre_norm.alpha", new int[]{1});
			shapes.put(lk + ".pre_norm.gamma", new int[]{dim});
			shapes.put(lk + ".pre_norm.beta", new int[]{dim});
			shapes.put(lk + ".ff_norm.alpha", new int[]{1});
			shapes.put(lk + ".ff_norm.gamma", new int[]{dim});
			shapes.put(lk + ".ff_norm.beta", new int[]{dim});
			shapes.put(lk + ".self_attn.to_qkv.weight", new int[]{5 * dim, dim});
			shapes.put(lk + ".self_attn.to_out.weight", new int[]{dim, dim});
			shapes.put(lk + ".self_attn.q_norm.alpha", new int[]{1});
			shapes.put(lk + ".self_attn.q_norm.gamma", new int[]{dimHead});
			shapes.put(lk + ".self_attn.q_norm.beta", new int[]{dimHead});
			shapes.put(lk + ".self_attn.k_norm.alpha", new int[]{1});
			shapes.put(lk + ".self_attn.k_norm.gamma", new int[]{dimHead});
			shapes.put(lk + ".self_attn.k_norm.beta", new int[]{dimHead});
			shapes.put(lk + ".rope.inv_freq", new int[]{invFreqLen});
			shapes.put(lk + ".ff.ff.0.proj.weight", new int[]{2 * inner, dim});
			shapes.put(lk + ".ff.ff.0.proj.bias", new int[]{2 * inner});
			shapes.put(lk + ".ff.ff.2.weight", new int[]{dim, inner});
			shapes.put(lk + ".ff.ff.2.bias", new int[]{dim});
		}

		return shapes;
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
