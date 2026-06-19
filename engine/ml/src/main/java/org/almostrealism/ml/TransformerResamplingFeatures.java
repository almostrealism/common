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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationLayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

/**
 * A general, reusable <em>learned-resampling transformer block</em>: a transformer that changes the
 * length of a sequence by an integer {@code stride} using a learned {@code new_tokens} mechanism rather
 * than a strided convolution. It is the novel core of the SAME audio autoencoder, but the primitive is
 * model-agnostic: any transformer-based encoder/decoder that needs to up- or down-sample a sequence can
 * reuse it by supplying a different {@link ResamplingConfig} and weight set.
 *
 * <h2>What the block does</h2>
 * <p>For an encoder ({@link ResamplingConfig#isEncoder()}), given an input of shape
 * {@code [batch, inChannels, L]}:</p>
 * <ol>
 *   <li><b>Channel mapping</b> &mdash; a {@code 1x1} (encoder) or {@code 3} -kernel (decoder)
 *       weight-normalized convolution maps {@code inChannels -> outChannels}
 *       ({@link #resamplingMapping}).</li>
 *   <li><b>Segmentation + learned tokens</b> &mdash; the sequence is grouped into segments of
 *       {@code inputSegSize} real positions, and {@code outputSegSize} learned token positions are
 *       appended to each segment ({@link #resamplingSegment}).</li>
 *   <li><b>Windowed transformer stack</b> &mdash; the segmented sequence is folded into contiguous
 *       chunks (with an optional midpoint shift on the second half of the layer stack) and a stack of
 *       differential, DyT-normalized, RoPE self-attention layers is applied
 *       ({@link #addResamplingTransformerStack}).</li>
 *   <li><b>Extraction</b> &mdash; the learned-token positions are read back out as the resampled
 *       output ({@link #resamplingExtract}).</li>
 * </ol>
 * <p>A decoder runs the same pipeline in the order segment &rarr; transformer &rarr; extract &rarr;
 * mapping, with {@code inputSegSize} and {@code outputSegSize} swapped so the sequence is up-sampled.</p>
 *
 * <h2>AR idiom</h2>
 * <p>The individual stages are {@link CollectionProducer}-valued methods ({@link #resamplingMapping},
 * {@link #resamplingSegment}, {@link #resamplingAttentionContext}, {@link #resamplingExtract}); none
 * calls {@code evaluate()} or {@code get()}. The differential attention is built directly from
 * {@link #scaledDotProduct} (which, unlike the block-level
 * {@link AttentionFeatures#scaledDotProductAttention}, places no batch-size-1 restriction, so chunked
 * attention keeps its chunk count as the batch dimension), the producer-level
 * {@link NormalizationLayerFeatures#dynamicTanh(CollectionProducer, PackedCollection, PackedCollection,
 * PackedCollection) dynamicTanh}, and the producer-level
 * {@link RotationFeatures#applyRotaryPositionEmbedding(CollectionProducer, PackedCollection)
 * applyRotaryPositionEmbedding}. Each stage maps one-to-one onto a captured SAME reference activation,
 * so a numerical-parity test can localize any discrepancy to a single stage.</p>
 *
 * <p>{@link #transformerResamplingBlock} assembles those stages into a {@link Block} — a
 * {@link SequentialBlock} of materializing {@link org.almostrealism.layers.CellularLayer layers}: one per
 * resampling stage, and <em>several</em> per transformer layer (the fused {@code DyT}+{@code to_qkv}
 * projection, the differential attention, the output projection, and the two GLU feed-forward steps are
 * each their own cell, wrapped in {@code residual} skip-connections). This per-matmul granularity is
 * deliberate, not merely conventional. A single producer expression spanning a whole transformer layer —
 * let alone the depth-{@code N} stack — blows up twice over: at construction, because each residual
 * ({@code x = x + f(x)}) and each reused sub-projection (the fused {@code to_qkv} sliced five ways, the
 * shared {@code V}, the reused softmax input) is a diamond whose super-linear signature recomputation
 * compounds; and again at compile time, because the same diamonds make a single kernel's embedded
 * expression grow exponentially during {@code Expression.simplify}, independent of tensor size (so even
 * tiny dimensions time out). Routing each matmul through a {@link org.almostrealism.layers.CellularLayer}
 * materializes its output into a buffer, so the next cell reads a leaf rather than re-embedding the
 * entire upstream expression — the same idiom {@link AttentionFeatures#attention} and the
 * {@link org.almostrealism.ml.audio.OobleckEncoder} use for their deep stacks, and it bounds both the
 * construction traversal and the per-kernel compile. The returned {@link Block} still follows the
 * Producer pattern: it builds a computation graph and never evaluates it; compilation happens at the
 * pipeline boundary via {@link org.almostrealism.model.Model#compile()}.</p>
 *
 * @author  Michael Murray
 * @see ResamplingConfig
 * @see StateDictionary
 * @see Block
 */
public interface TransformerResamplingFeatures extends RotationFeatures, NormalizationLayerFeatures {

	/**
	 * Builds the complete learned-resampling block as a {@link Block}: a {@link SequentialBlock} of
	 * materializing layers (channel mapping, segmentation, the windowed transformer stack, and
	 * extraction), assembled in encoder or decoder order per {@link ResamplingConfig#isEncoder()}.
	 *
	 * <p>The block's declared input shape is {@code [batch, inChannels, L]} and its output shape is
	 * {@code [batch, outChannels, L/stride]} (encoder) or {@code [batch, outChannels, L*stride]}
	 * (decoder). {@code L} is assumed to already satisfy the block's chunk alignment (the SAME
	 * autoencoder feeds chunk-aligned lengths); see {@link ResamplingConfig#getPaddedInputLength(int)}
	 * for the alignment rule.</p>
	 *
	 * @param batchSize  the batch dimension {@code batch}
	 * @param seqLen     the (chunk-aligned) input sequence length {@code L}
	 * @param config     the resampling configuration
	 * @param weights    the loaded weights; keys are looked up under {@code keyPrefix}
	 * @param keyPrefix  the weight key prefix (e.g. {@code "encoder.layers.0"})
	 * @return the assembled resampling block
	 */
	default Block transformerResamplingBlock(int batchSize, int seqLen,
											 ResamplingConfig config,
											 StateDictionary weights, String keyPrefix) {
		int inChannels = config.getInChannels();
		int outChannels = config.getOutChannels();
		int dim = config.getDim();
		int numSeg = seqLen / config.getInputSegSize();
		int segLen = numSeg * config.getSubChunkSize();
		int extractLen = numSeg * config.getOutputSegSize();

		TraversalPolicy segmentShape = shape(batchSize, dim, seqLen);
		TraversalPolicy segmentedShape = shape(batchSize, segLen, dim);
		TraversalPolicy extractedShape = shape(batchSize, dim, extractLen);

		if (config.isEncoder()) {
			// mapping (inChannels -> dim) runs first, then segment -> transformer stack -> extract.
			SequentialBlock block = new SequentialBlock(shape(batchSize, inChannels, seqLen));
			block.add(layer(keyPrefix + ".mapping", shape(batchSize, inChannels, seqLen), segmentShape,
					in -> resamplingMapping(c(in), config, weights, keyPrefix)));
			block.add(layer(keyPrefix + ".segment", segmentShape, segmentedShape,
					in -> resamplingSegment(c(in), batchSize, config, weights, keyPrefix)));
			addResamplingTransformerStack(block, batchSize, config, weights, keyPrefix);
			block.add(layer(keyPrefix + ".extract", segmentedShape, extractedShape,
					in -> resamplingExtract(c(in), batchSize, config)));
			return block;
		}

		// decoder: segment -> transformer stack -> extract, then mapping (dim -> outChannels) last.
		SequentialBlock block = new SequentialBlock(segmentShape);
		block.add(layer(keyPrefix + ".segment", segmentShape, segmentedShape,
				in -> resamplingSegment(c(in), batchSize, config, weights, keyPrefix)));
		addResamplingTransformerStack(block, batchSize, config, weights, keyPrefix);
		block.add(layer(keyPrefix + ".extract", segmentedShape, extractedShape,
				in -> resamplingExtract(c(in), batchSize, config)));
		block.add(layer(keyPrefix + ".mapping", extractedShape, shape(batchSize, outChannels, extractLen),
				in -> resamplingMapping(c(in), config, weights, keyPrefix)));
		return block;
	}

	/**
	 * Applies the weight-normalized channel-mapping convolution. The encoder uses a {@code 1x1}
	 * convolution (a per-position linear projection); the decoder uses a {@code 3} -kernel convolution
	 * with {@code same} (zero) padding. The PyTorch weight-norm parameterization is expected to be
	 * pre-folded into a single {@code [outChannels, inChannels, kernel]} weight by the extractor.
	 *
	 * @param x         the input; {@code [batch, inChannels, L]} (channels-first)
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param keyPrefix the weight key prefix
	 * @return the mapped output; {@code [batch, outChannels, L]}
	 */
	default CollectionProducer resamplingMapping(CollectionProducer x, ResamplingConfig config,
												 StateDictionary weights, String keyPrefix) {
		TraversalPolicy xs = x.getShape();
		int batchSize = xs.length(0);
		int inChannels = xs.length(1);
		int length = xs.length(2);
		int outChannels = config.getOutChannels();
		int kernel = config.getMappingKernel();
		int pad = kernel / 2;

		PackedCollection weight = weights.get(keyPrefix + ".mapping.weight");
		PackedCollection bias = weights.get(keyPrefix + ".mapping.bias");
		CollectionProducer w = cp(weight);

		// Work in channels-last space so each kernel tap is a plain linear over the channel axis.
		CollectionProducer channelsLast = permute(x, 0, 2, 1);
		CollectionProducer padded = channelsLast;
		if (pad > 0) {
			CollectionProducer zeros = cp(new PackedCollection(shape(batchSize, pad, inChannels)));
			padded = concat(1, zeros, channelsLast, zeros);
		}

		CollectionProducer accumulated = null;
		for (int k = 0; k < kernel; k++) {
			CollectionProducer tap = (kernel == 1)
					? channelsLast
					: padded.subset(shape(batchSize, length, inChannels), 0, k, 0);
			CollectionProducer tapWeight = w.subset(shape(outChannels, inChannels, 1), 0, 0, k)
					.reshape(outChannels, inChannels);
			CollectionProducer projection = resamplingLinear(tap, tapWeight, null);
			accumulated = (accumulated == null) ? projection : accumulated.add(projection);
		}

		accumulated = accumulated.add(
				cp(bias).reshape(outChannels).repeat(0, length).repeat(0, batchSize));
		return permute(accumulated, 0, 2, 1);
	}

	/**
	 * Groups the (transposed) sequence into segments and appends the learned tokens, producing the
	 * pre-transformer "segment" tensor. Each segment holds {@code inputSegSize} real positions followed
	 * by {@code outputSegSize} learned-token positions sourced from the {@code new_tokens} parameter
	 * (a single learned token broadcast across the output positions for {@code variableStride}).
	 *
	 * @param x         channels-first input; {@code [batch, dim, L]}
	 * @param batchSize the batch dimension
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param keyPrefix the weight key prefix
	 * @return the segmented sequence; {@code [batch, numSeg * subChunkSize, dim]}
	 */
	default CollectionProducer resamplingSegment(CollectionProducer x, int batchSize,
												 ResamplingConfig config,
												 StateDictionary weights, String keyPrefix) {
		int dim = config.getDim();
		int inputSeg = config.getInputSegSize();
		int outputSeg = config.getOutputSegSize();
		int length = x.getShape().length(2);
		int numSeg = length / inputSeg;

		// [batch, dim, L] -> [batch, L, dim] -> [batch * numSeg, inputSeg, dim]
		CollectionProducer segments = permute(x, 0, 2, 1).reshape(batchSize * numSeg, inputSeg, dim);

		// new_tokens [1, 1, dim]: one learned token broadcast to outputSeg positions per segment.
		PackedCollection newTokens = weights.get(keyPrefix + ".new_tokens");
		CollectionProducer learned = cp(newTokens).reshape(dim)
				.repeat(0, outputSeg)
				.repeat(0, batchSize * numSeg);

		CollectionProducer withTokens = concat(1, segments, learned);
		return withTokens.reshape(batchSize, numSeg * config.getSubChunkSize(), dim);
	}

	/**
	 * Appends the windowed transformer stack to {@code block}, whose current output must be the
	 * segmented sequence {@code [batch, segLen, dim]}. Each transformer layer is added as a
	 * materializing {@link org.almostrealism.layers.CellularLayer} operating on contiguous attention
	 * chunks {@code [numChunks, effChunk, dim]}; the reshapes between segmented and chunked space are
	 * metadata-only {@link #reshape(TraversalPolicy, TraversalPolicy) reshape blocks}.
	 *
	 * <p>When {@link ResamplingConfig#isChunkMidpointShift()} is set, the first half of the layers run
	 * on the standard contiguous chunks and the second half run on chunks shifted by half the effective
	 * chunk size (the sequence is symmetrically padded by a half-chunk of repeated edge content, then
	 * cropped back). Each chunk is an independent attention batch element.</p>
	 *
	 * @param block     the sequential block to append to; its output must be {@code [batch, segLen, dim]}
	 * @param batchSize the batch dimension
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param keyPrefix the weight key prefix
	 */
	default void addResamplingTransformerStack(SequentialBlock block, int batchSize,
											   ResamplingConfig config,
											   StateDictionary weights, String keyPrefix) {
		int dim = config.getDim();
		int depth = config.getDepth();
		int effChunk = config.getEffectiveChunkSize();
		int segLen = block.getOutputShape().length(1);

		TraversalPolicy segmentedShape = shape(batchSize, segLen, dim);
		int numChunks = (batchSize * segLen) / effChunk;
		TraversalPolicy chunkShape = shape(numChunks, effChunk, dim);

		if (!config.isChunkMidpointShift()) {
			block.add(reshape(segmentedShape, chunkShape));
			for (int i = 0; i < depth; i++) {
				block.add(resamplingLayerBlock(numChunks, config, weights, layerKey(keyPrefix, i)));
			}
			block.add(reshape(chunkShape, segmentedShape));
			return;
		}

		int split = depth / 2;
		int shift = effChunk / 2;
		int shiftedLen = segLen + 2 * shift;
		int numChunksShifted = (batchSize * shiftedLen) / effChunk;
		TraversalPolicy shiftedShape = shape(batchSize, shiftedLen, dim);
		TraversalPolicy shiftedChunkShape = shape(numChunksShifted, effChunk, dim);

		// First half: standard chunks.
		block.add(reshape(segmentedShape, chunkShape));
		for (int i = 0; i < split; i++) {
			block.add(resamplingLayerBlock(numChunks, config, weights, layerKey(keyPrefix, i)));
		}
		block.add(reshape(chunkShape, segmentedShape));

		// Midpoint shift: pad with a half-chunk of repeated edge content on each side, then re-chunk.
		block.add(layer(keyPrefix + ".shift", segmentedShape, shiftedShape,
				in -> concat(1,
						c(in).subset(shape(batchSize, shift, dim), 0, 0, 0),
						c(in),
						c(in).subset(shape(batchSize, shift, dim), 0, segLen - shift, 0))));
		block.add(reshape(shiftedShape, shiftedChunkShape));

		// Second half: shifted chunks.
		for (int i = split; i < depth; i++) {
			block.add(resamplingLayerBlock(numChunksShifted, config, weights, layerKey(keyPrefix, i)));
		}
		block.add(reshape(shiftedChunkShape, shiftedShape));

		// Crop back to the original segmented length.
		block.add(layer(keyPrefix + ".crop", shiftedShape, segmentedShape,
				in -> c(in).subset(segmentedShape, 0, shift, 0)));
	}

	/**
	 * Builds one transformer layer of the resampling stack as a {@link SequentialBlock} of materializing
	 * {@link org.almostrealism.layers.CellularLayer layers} wrapped in residual skip-connections:
	 * {@code x = x + selfAttn(DyT(x))} (a {@code DyT}+fused-{@code to_qkv} projection cell, a differential
	 * attention cell, and the output-projection cell) followed by {@code x = x + ff(DyT(x))} (a
	 * {@code DyT}+input-projection cell and a GLU output cell). Materializing each matmul's output into a
	 * buffer is what keeps every compiled kernel bounded: the next cell reads a leaf rather than
	 * re-embedding the whole upstream expression, so the differential attention's reused sub-projections
	 * (the fused {@code to_qkv} sliced five ways, the shared {@code V}, the reused softmax input) cannot
	 * compound into an exponentially large single-kernel expression at compile time.
	 *
	 * @param numChunks the attention chunk count (the batch dimension of the per-chunk attention)
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param key       the weight key prefix for this layer (e.g. {@code "encoder.layers.0.transformers.0"})
	 * @return the transformer-layer block; input and output shape {@code [numChunks, effChunk, dim]}
	 */
	default Block resamplingLayerBlock(int numChunks, ResamplingConfig config,
									   StateDictionary weights, String key) {
		int dim = config.getDim();
		int effChunk = config.getEffectiveChunkSize();
		TraversalPolicy chunkShape = shape(numChunks, effChunk, dim);
		TraversalPolicy qkvShape = shape(numChunks, effChunk, 5 * dim);
		TraversalPolicy projShape = shape(numChunks, effChunk, 2 * config.getInnerFfDim());

		SequentialBlock attention = new SequentialBlock(chunkShape);
		attention.add(layer(key + ".attn_proj", chunkShape, qkvShape,
				in -> resamplingAttentionProjection(c(in), weights, key)));
		attention.add(layer(key + ".attn_context", qkvShape, chunkShape,
				in -> resamplingAttentionContext(c(in), config, weights, key)));
		attention.add(layer(key + ".attn_out", chunkShape, chunkShape,
				in -> resamplingLinear(c(in), cp(weights.get(key + ".self_attn.to_out.weight")), null)));

		SequentialBlock feedForward = new SequentialBlock(chunkShape);
		feedForward.add(layer(key + ".ff_proj", chunkShape, projShape,
				in -> resamplingFeedForwardProjection(c(in), weights, key)));
		feedForward.add(layer(key + ".ff_out", projShape, chunkShape,
				in -> resamplingFeedForwardGate(c(in), config, weights, key)));

		SequentialBlock layerBlock = new SequentialBlock(chunkShape);
		layerBlock.add(residual(attention));
		layerBlock.add(residual(feedForward));
		return layerBlock;
	}

	/**
	 * The pre-norm and fused query/key/value projection of the differential attention: applies the
	 * {@code pre_norm} {@link NormalizationLayerFeatures#dynamicTanh DyT} normalization and the bias-free
	 * {@code to_qkv} linear, producing the five concatenated sections in the order
	 * {@code [Q1, K1, V, Q2, K2]}.
	 *
	 * @param x        the layer input; {@code [numChunks, chunkLen, dim]}
	 * @param weights  the loaded weights
	 * @param layerKey the weight key prefix for this layer
	 * @return the fused projection; {@code [numChunks, chunkLen, 5 * dim]}
	 */
	default CollectionProducer resamplingAttentionProjection(CollectionProducer x,
															 StateDictionary weights, String layerKey) {
		CollectionProducer normed = dynamicTanh(x,
				weights.get(layerKey + ".pre_norm.alpha"),
				weights.get(layerKey + ".pre_norm.gamma"),
				weights.get(layerKey + ".pre_norm.beta"));
		return resamplingLinear(normed, cp(weights.get(layerKey + ".self_attn.to_qkv.weight")), null);
	}

	/**
	 * The differential-attention context from a fused {@code [Q1, K1, V, Q2, K2]} projection: applies DyT
	 * query/key normalization and partial rotary embeddings, then computes
	 * {@code softmax(Q1 K1^T/sqrt(d)) V - softmax(Q2 K2^T/sqrt(d)) V} (no learned lambda) and merges the
	 * heads. The bias-free {@code to_out} projection is applied separately by the caller.
	 *
	 * @param qkv      the fused projection; {@code [numChunks, chunkLen, 5 * dim]}
	 * @param config   the resampling configuration
	 * @param weights  the loaded weights
	 * @param layerKey the weight key prefix for this layer
	 * @return the merged attention context; {@code [numChunks, chunkLen, dim]}
	 */
	default CollectionProducer resamplingAttentionContext(CollectionProducer qkv, ResamplingConfig config,
														  StateDictionary weights, String layerKey) {
		int dim = config.getDim();
		int heads = config.getHeads();
		int dimHead = config.getDimHead();

		CollectionProducer q1 = attentionHeads(qkvSection(qkv, 0, dim), heads, dimHead);
		CollectionProducer k1 = attentionHeads(qkvSection(qkv, 1, dim), heads, dimHead);
		CollectionProducer v = attentionHeads(qkvSection(qkv, 2, dim), heads, dimHead);
		CollectionProducer q2 = attentionHeads(qkvSection(qkv, 3, dim), heads, dimHead);
		CollectionProducer k2 = attentionHeads(qkvSection(qkv, 4, dim), heads, dimHead);

		PackedCollection qAlpha = weights.get(layerKey + ".self_attn.q_norm.alpha");
		PackedCollection qGamma = weights.get(layerKey + ".self_attn.q_norm.gamma");
		PackedCollection qBeta = weights.get(layerKey + ".self_attn.q_norm.beta");
		PackedCollection kAlpha = weights.get(layerKey + ".self_attn.k_norm.alpha");
		PackedCollection kGamma = weights.get(layerKey + ".self_attn.k_norm.gamma");
		PackedCollection kBeta = weights.get(layerKey + ".self_attn.k_norm.beta");
		q1 = dynamicTanh(q1, qAlpha, qGamma, qBeta);
		q2 = dynamicTanh(q2, qAlpha, qGamma, qBeta);
		k1 = dynamicTanh(k1, kAlpha, kGamma, kBeta);
		k2 = dynamicTanh(k2, kAlpha, kGamma, kBeta);

		PackedCollection invFreq = weights.get(layerKey + ".rope.inv_freq");
		q1 = applyRotaryPositionEmbedding(q1, invFreq);
		q2 = applyRotaryPositionEmbedding(q2, invFreq);
		k1 = applyRotaryPositionEmbedding(k1, invFreq);
		k2 = applyRotaryPositionEmbedding(k2, invFreq);

		CollectionProducer out = scaledDotProductAttention(q1, k1, v, dimHead)
				.subtract(scaledDotProductAttention(q2, k2, v, dimHead));
		return mergeAttentionHeads(out);
	}

	/**
	 * The pre-norm and input projection of the SwiGLU feed-forward: applies the {@code ff_norm}
	 * {@link NormalizationLayerFeatures#dynamicTanh DyT} normalization and the {@code dim -> 2 * innerFfDim}
	 * projection (with bias), producing the concatenated {@code [a, gate]} halves.
	 *
	 * @param x        the layer input; {@code [numChunks, chunkLen, dim]}
	 * @param weights  the loaded weights
	 * @param layerKey the weight key prefix for this layer
	 * @return the projected halves; {@code [numChunks, chunkLen, 2 * innerFfDim]}
	 */
	default CollectionProducer resamplingFeedForwardProjection(CollectionProducer x,
															   StateDictionary weights, String layerKey) {
		CollectionProducer normed = dynamicTanh(x,
				weights.get(layerKey + ".ff_norm.alpha"),
				weights.get(layerKey + ".ff_norm.gamma"),
				weights.get(layerKey + ".ff_norm.beta"));
		return resamplingLinear(normed,
				cp(weights.get(layerKey + ".ff.ff.0.proj.weight")),
				weights.get(layerKey + ".ff.ff.0.proj.bias"));
	}

	/**
	 * The SwiGLU gating and output projection: from the {@code [a, gate]} halves computes
	 * {@code a * SiLU(gate)} (where {@code SiLU(z) = z * sigmoid(z)}) and applies the
	 * {@code innerFfDim -> dim} output projection (with bias).
	 *
	 * @param projected the projected halves; {@code [numChunks, chunkLen, 2 * innerFfDim]}
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param layerKey  the weight key prefix for this layer
	 * @return the feed-forward output; {@code [numChunks, chunkLen, dim]}
	 */
	default CollectionProducer resamplingFeedForwardGate(CollectionProducer projected, ResamplingConfig config,
														StateDictionary weights, String layerKey) {
		int inner = config.getInnerFfDim();
		TraversalPolicy xs = projected.getShape();
		int rows0 = xs.length(0);
		int rows1 = xs.length(1);

		CollectionProducer a = projected.subset(shape(rows0, rows1, inner), 0, 0, 0);
		CollectionProducer gate = projected.subset(shape(rows0, rows1, inner), 0, 0, inner);
		CollectionProducer gated = a.multiply(gate.multiply(sigmoid(gate)));

		return resamplingLinear(gated,
				cp(weights.get(layerKey + ".ff.ff.2.weight")),
				weights.get(layerKey + ".ff.ff.2.bias"));
	}

	/**
	 * Extracts the learned-token positions from each segment as the resampled output.
	 *
	 * @param transformed the transformer-stack output; {@code [batch, segLen, dim]}
	 * @param batchSize   the batch dimension
	 * @param config      the resampling configuration
	 * @return the extracted output; {@code [batch, dim, numSeg * outputSegSize]}
	 */
	default CollectionProducer resamplingExtract(CollectionProducer transformed, int batchSize,
												 ResamplingConfig config) {
		int dim = config.getDim();
		int subChunk = config.getSubChunkSize();
		int outputSeg = config.getOutputSegSize();
		int segLen = transformed.getShape().length(1);
		int numSeg = segLen / subChunk;

		// [batch, segLen, dim] -> [batch * numSeg, subChunk, dim]; take the trailing learned tokens.
		CollectionProducer perSegment = transformed.reshape(batchSize * numSeg, subChunk, dim);
		CollectionProducer learned = perSegment.subset(
				shape(batchSize * numSeg, outputSeg, dim), 0, subChunk - outputSeg, 0);

		// [batch * numSeg, outputSeg, dim] -> [batch, numSeg * outputSeg, dim] -> [batch, dim, ...]
		CollectionProducer merged = learned.reshape(batchSize, numSeg * outputSeg, dim);
		return permute(merged, 0, 2, 1);
	}

	/**
	 * Computes standard scaled dot-product attention {@code softmax(Q K^T / sqrt(d)) V} for inputs laid
	 * out as {@code [batch, heads, seqLen, dimHead]}. Unlike the block-level attention helper, this
	 * places no restriction on the batch dimension, so chunked attention may keep its chunk count there.
	 *
	 * @param q       queries; {@code [batch, heads, seqLen, dimHead]}
	 * @param k       keys; {@code [batch, heads, seqLen, dimHead]}
	 * @param v       values; {@code [batch, heads, seqLen, dimHead]}
	 * @param dimHead the per-head dimension (the softmax temperature is {@code 1/sqrt(dimHead)})
	 * @return the attention output; {@code [batch, heads, seqLen, dimHead]}
	 */
	default CollectionProducer scaledDotProductAttention(CollectionProducer q, CollectionProducer k,
														 CollectionProducer v, int dimHead) {
		CollectionProducer scores = scaledDotProduct(q, k, true).multiply(c(1.0 / Math.sqrt(dimHead)));
		return scaledDotProduct(softmaxLastAxis(scores), v);
	}

	/**
	 * Numerically-stable softmax over the trailing axis, computed via the log-sum-exp identity (the
	 * producer-level equivalent of the framework's stable {@code softmax2d} layer). No zero-masking is
	 * applied, which is correct for the dense (unmasked) attention used by the resampling block.
	 *
	 * @param input the logits; the softmax is taken over the last dimension
	 * @return the softmax probabilities, same shape as {@code input}
	 */
	default CollectionProducer softmaxLastAxis(CollectionProducer input) {
		TraversalPolicy s = input.getShape();
		int axis = s.getDimensions() - 1;
		int seqLen = s.length(axis);

		CollectionProducer max = traverse(axis, input).max();
		CollectionProducer stable = traverse(axis + 1, input).subtract(max.expand(seqLen));
		CollectionProducer logSum = stable.exp().traverse(axis).sum().log().expand(seqLen);
		return stable.subtract(logSum).exp().reshape(s);
	}

	/**
	 * A PyTorch-style linear projection {@code y = x W^T + b}, where {@code W} has logical shape
	 * {@code [out, in]} (any shape whose flat size is {@code out * in} is accepted and reshaped). The
	 * leading dimensions of {@code x} are preserved; only the trailing feature dimension changes from
	 * {@code in} to {@code out}.
	 *
	 * @param x      the input; {@code [..., in]}
	 * @param weight the weight producer; logical {@code [out, in]}
	 * @param bias   the bias ({@code [out]}), or {@code null} for no bias
	 * @return the projected output; {@code [..., out]}
	 */
	default CollectionProducer resamplingLinear(CollectionProducer x, CollectionProducer weight,
												PackedCollection bias) {
		TraversalPolicy xs = x.getShape();
		int rank = xs.getDimensions();
		int in = xs.length(rank - 1);
		int rows = xs.getTotalSize() / in;
		int out = weight.getShape().getTotalSize() / in;

		CollectionProducer a = x.reshape(shape(1, 1, rows, in));
		CollectionProducer w = weight.reshape(shape(1, 1, out, in));
		CollectionProducer y = scaledDotProduct(a, w, true).reshape(rows, out);
		if (bias != null) {
			y = y.add(cp(bias).reshape(out).repeat(0, rows));
		}

		int[] outDims = new int[rank];
		for (int i = 0; i < rank - 1; i++) {
			outDims[i] = xs.length(i);
		}
		outDims[rank - 1] = out;
		return y.reshape(shape(outDims));
	}

	/**
	 * Extracts one of the equal sections of a fused projection along the trailing feature axis.
	 *
	 * @param fused   the fused projection; {@code [a, b, sections * width]}
	 * @param section the section index
	 * @param width   the section width
	 * @return the section; {@code [a, b, width]}
	 */
	default CollectionProducer qkvSection(CollectionProducer fused, int section, int width) {
		TraversalPolicy s = fused.getShape();
		return fused.subset(shape(s.length(0), s.length(1), width), 0, 0, section * width);
	}

	/**
	 * Reshapes a {@code [batch, seqLen, heads * dimHead]} tensor to the multi-head attention layout
	 * {@code [batch, heads, seqLen, dimHead]}.
	 *
	 * @param x       the input; {@code [batch, seqLen, heads * dimHead]}
	 * @param heads   the number of heads
	 * @param dimHead the per-head dimension
	 * @return the multi-head layout; {@code [batch, heads, seqLen, dimHead]}
	 */
	default CollectionProducer attentionHeads(CollectionProducer x, int heads, int dimHead) {
		TraversalPolicy s = x.getShape();
		int batch = s.length(0);
		int seqLen = s.length(1);
		return x.reshape(batch, seqLen, heads, dimHead).permute(0, 2, 1, 3);
	}

	/**
	 * Merges the multi-head attention layout {@code [batch, heads, seqLen, dimHead]} back to
	 * {@code [batch, seqLen, heads * dimHead]}.
	 *
	 * @param x the multi-head layout; {@code [batch, heads, seqLen, dimHead]}
	 * @return the merged layout; {@code [batch, seqLen, heads * dimHead]}
	 */
	default CollectionProducer mergeAttentionHeads(CollectionProducer x) {
		TraversalPolicy s = x.getShape();
		int batch = s.length(0);
		int heads = s.length(1);
		int seqLen = s.length(2);
		int dimHead = s.length(3);
		return x.permute(0, 2, 1, 3).reshape(batch, seqLen, heads * dimHead);
	}

	/**
	 * Builds the weight key for a transformer layer within a resampling block.
	 *
	 * @param keyPrefix the block key prefix
	 * @param layerIndex the layer index
	 * @return the layer key prefix {@code keyPrefix + ".transformers." + layerIndex}
	 */
	default String layerKey(String keyPrefix, int layerIndex) {
		return keyPrefix + ".transformers." + layerIndex;
	}
}
