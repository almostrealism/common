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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.HashMap;
import java.util.Map;

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
 *       ({@link #addResamplingMapping}). The decoder's {@code 3} -kernel mapping is split into a
 *       zero-padding cell and a projection cell so the tap matmuls contract over a materialized leaf
 *       rather than re-embedding the pad {@code concat}.</li>
 *   <li><b>Segmentation + learned tokens</b> &mdash; the sequence is grouped into segments of
 *       {@code inputSegSize} real positions, and {@code outputSegSize} learned token positions are
 *       appended to each segment ({@link #resamplingSegment}).</li>
 *   <li><b>Windowed transformer stack</b> &mdash; the segmented sequence is folded into contiguous
 *       chunks (with an optional midpoint shift on the second half of the layer stack) and a stack of
 *       differential, DyT-normalized, RoPE self-attention layers is applied
 *       ({@link #addResamplingTransformerStack}); each layer is the {@value #RESAMPLING_TRANSFORMER_LAYER}
 *       of {@link #RESAMPLING_TRANSFORMER_ASSET} ({@link #resamplingLayerBlock}).</li>
 *   <li><b>Extraction</b> &mdash; the learned-token positions are read back out as the resampled
 *       output ({@link #resamplingExtract}).</li>
 * </ol>
 * <p>A decoder runs the same pipeline in the order segment &rarr; transformer &rarr; extract &rarr;
 * mapping, with {@code inputSegSize} and {@code outputSegSize} swapped so the sequence is up-sampled.</p>
 *
 * <h2>AR idiom</h2>
 * <p>The resampling stages are {@link CollectionProducer}-valued methods ({@link #resamplingMapping},
 * {@link #resamplingSegment}, {@link #resamplingExtract}); none calls {@code evaluate()} or
 * {@code get()}. The transformer layers of the windowed stack are not assembled here: one layer's
 * structure — the DynamicTanh pre-norm, the fused {@code to_qkv} projection laid out as the sections
 * {@code Q1, K1, V, Q2, K2}, the two attention maps and their difference, the head merge and the output
 * projection, then the DynamicTanh-normalized gated feed-forward, each inside a residual connection — is
 * the {@value #RESAMPLING_TRANSFORMER_LAYER} of {@link #RESAMPLING_TRANSFORMER_ASSET}, and
 * {@link #resamplingLayerBlock} binds one layer's weights and builds it. The keys and values of each
 * attention map are branches that feed the attention products, so a backward pass reaches every section
 * of the fused projection, and the projections are registered weights of their layers.</p>
 *
 * <p>{@link #transformerResamplingBlock} assembles the stages into a {@link Block} — a
 * {@link SequentialBlock} of materializing {@link org.almostrealism.layers.CellularLayer layers}: one per
 * resampling stage, and <em>several</em> per transformer layer, because every stage of the asset's layer
 * (each normalization, projection, attention product, softmax and gate) is its own cell. This
 * per-stage granularity is deliberate, not merely conventional. A single producer expression spanning a
 * whole transformer layer — let alone the depth-{@code N} stack — blows up twice over: at construction,
 * because each residual ({@code x = x + f(x)}) and each reused sub-projection (the fused {@code to_qkv}
 * sliced five ways, the shared {@code V}, the reused softmax input) is a diamond whose super-linear
 * signature recomputation compounds; and again at compile time, because the same diamonds make a single
 * kernel's embedded expression grow exponentially during {@code Expression.simplify}, independent of
 * tensor size (so even tiny dimensions time out). Materializing each stage's output into a buffer means
 * the next cell reads a leaf rather than re-embedding the entire upstream expression, which bounds both
 * the construction traversal and the per-kernel compile. The returned {@link Block} still follows the
 * Producer pattern: it builds a computation graph and never evaluates it; compilation happens at the
 * pipeline boundary via {@link org.almostrealism.model.Model#compile()}.</p>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author  Michael Murray
 * @see ResamplingConfig
 * @see StateDictionary
 * @see Block
 */
public interface TransformerResamplingFeatures extends SequenceAttentionFeatures {

	/**
	 * Classpath location of the asset defining one transformer layer of the windowed stack: its
	 * {@value #RESAMPLING_TRANSFORMER_LAYER} is the single definition of how a chunked sequence flows
	 * through the DynamicTanh pre-norm, the fused five-section projection, the two attention maps and
	 * their difference, the output projection and the gated feed-forward.
	 */
	String RESAMPLING_TRANSFORMER_ASSET = "/pdsl/resampling_transformer.pdsl";

	/** Name of the {@link #RESAMPLING_TRANSFORMER_ASSET} layer that {@link #resamplingLayerBlock} builds. */
	String RESAMPLING_TRANSFORMER_LAYER = "resampling_transformer_layer";

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
			addResamplingMapping(block, config, weights, keyPrefix,
					shape(batchSize, inChannels, seqLen), segmentShape);
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
		addResamplingMapping(block, config, weights, keyPrefix,
				extractedShape, shape(batchSize, outChannels, extractLen));
		return block;
	}

	/**
	 * Appends the channel-mapping convolution to {@code block} as one or more materializing
	 * {@link org.almostrealism.layers.CellularLayer layers}. The encoder's {@code 1x1} mapping is a
	 * single cell (a per-position linear projection with no padding). The decoder's {@code k}-kernel
	 * mapping ({@code k > 1}) is split into a zero-padding cell that materializes the padded,
	 * channels-last input into a leaf and a projection cell that sums the {@code k} tap matmuls over
	 * that leaf. The split keeps every compiled kernel bounded: a single fused cell would embed the
	 * padding {@code concat}'s {@code Conditional} in each tap's matmul reduction and, summed over the
	 * taps, blows up kernel-series simplification at full SAME dimensions &mdash; the same property that
	 * gives every stage of the transformer layers ({@link #resamplingLayerBlock}) a cell of its own.
	 *
	 * @param block     the sequential block to append to
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param keyPrefix the weight key prefix
	 * @param inShape   the mapping input shape {@code [batch, inChannels, L]}
	 * @param outShape  the mapping output shape {@code [batch, outChannels, L]}
	 */
	default void addResamplingMapping(SequentialBlock block, ResamplingConfig config,
									  StateDictionary weights, String keyPrefix,
									  TraversalPolicy inShape, TraversalPolicy outShape) {
		if (config.getMappingKernel() == 1) {
			block.add(layer(keyPrefix + ".mapping", inShape, outShape,
					in -> resamplingMapping(c(in), config, weights, keyPrefix)));
			return;
		}

		int batchSize = inShape.length(0);
		int inChannels = inShape.length(1);
		int length = inShape.length(2);
		int pad = config.getMappingKernel() / 2;
		TraversalPolicy paddedShape = shape(batchSize, length + 2 * pad, inChannels);

		block.add(layer(keyPrefix + ".mapping_pad", inShape, paddedShape,
				in -> resamplingMappingPadded(c(in), config)));
		block.add(layer(keyPrefix + ".mapping_proj", paddedShape, outShape,
				in -> resamplingMappingProject(c(in), config, weights, keyPrefix)));
	}

	/**
	 * Applies the weight-normalized channel-mapping convolution as a single producer (the encoder's
	 * {@code 1x1} path; the decoder splits the same computation across two cells via
	 * {@link #addResamplingMapping}). The encoder uses a {@code 1x1} convolution (a per-position linear
	 * projection); the decoder uses a {@code 3} -kernel convolution with {@code same} (zero) padding.
	 * The PyTorch weight-norm parameterization is expected to be pre-folded into a single
	 * {@code [outChannels, inChannels, kernel]} weight by the extractor.
	 *
	 * @param x         the input; {@code [batch, inChannels, L]} (channels-first)
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param keyPrefix the weight key prefix
	 * @return the mapped output; {@code [batch, outChannels, L]}
	 */
	default CollectionProducer resamplingMapping(CollectionProducer x, ResamplingConfig config,
												 StateDictionary weights, String keyPrefix) {
		return resamplingMappingProject(resamplingMappingPadded(x, config), config, weights, keyPrefix);
	}

	/**
	 * Transposes the channels-first input to channels-last and applies {@code same} (zero) padding of
	 * {@code mappingKernel / 2} positions on each end of the sequence axis. For a {@code 1x1} mapping
	 * (no padding) this is just the channels-last view. When this is materialized into a leaf by a
	 * {@link org.almostrealism.layers.CellularLayer}, the subsequent tap matmuls read a clean buffer
	 * rather than re-embedding the padding {@code concat}.
	 *
	 * @param x      the channels-first input; {@code [batch, inChannels, L]}
	 * @param config the resampling configuration
	 * @return the padded channels-last sequence; {@code [batch, L + 2 * (mappingKernel / 2), inChannels]}
	 */
	default CollectionProducer resamplingMappingPadded(CollectionProducer x, ResamplingConfig config) {
		TraversalPolicy xs = x.getShape();
		int batchSize = xs.length(0);
		int inChannels = xs.length(1);
		int pad = config.getMappingKernel() / 2;

		// Work in channels-last space so each kernel tap is a plain linear over the channel axis.
		CollectionProducer channelsLast = permute(x, 0, 2, 1);
		if (pad == 0) {
			return channelsLast;
		}

		// The padding must be a producer that generates zeros, not a freshly-allocated
		// PackedCollection: an allocation is only zero-filled on backends that happen to clear new
		// memory (the native/CPU backend), while the Metal backend leaves managed-buffer contents
		// undefined, so reading an unwritten allocation feeds garbage (often non-finite) through the
		// mapping taps. zeros(...) is the backend-independent zero constant.
		CollectionProducer padding = zeros(shape(batchSize, pad, inChannels));
		return concat(1, padding, channelsLast, padding);
	}

	/**
	 * Sums the {@code mappingKernel} tap projections of the padded, channels-last sequence and adds the
	 * bias, returning the channels-first mapped output. Each tap is a {@link #resamplingLinear} matmul
	 * over a contiguous window of {@code paddedChannelsLast}; when that input is a materialized leaf the
	 * matmul reductions never embed the padding {@code concat}, which is what keeps the compiled kernel
	 * bounded at full SAME dimensions.
	 *
	 * @param paddedChannelsLast the padded channels-last sequence;
	 *                           {@code [batch, L + 2 * (mappingKernel / 2), inChannels]}
	 * @param config             the resampling configuration
	 * @param weights            the loaded weights
	 * @param keyPrefix          the weight key prefix
	 * @return the mapped output; {@code [batch, outChannels, L]}
	 */
	default CollectionProducer resamplingMappingProject(CollectionProducer paddedChannelsLast,
														ResamplingConfig config,
														StateDictionary weights, String keyPrefix) {
		TraversalPolicy ps = paddedChannelsLast.getShape();
		int batchSize = ps.length(0);
		int inChannels = ps.length(2);
		int kernel = config.getMappingKernel();
		int pad = kernel / 2;
		int length = ps.length(1) - 2 * pad;
		int outChannels = config.getOutChannels();

		PackedCollection weight = weights.get(keyPrefix + ".mapping.weight");
		PackedCollection bias = weights.get(keyPrefix + ".mapping.bias");
		CollectionProducer w = cp(weight);

		CollectionProducer accumulated = null;
		for (int k = 0; k < kernel; k++) {
			CollectionProducer tap = (kernel == 1)
					? paddedChannelsLast
					: paddedChannelsLast.subset(shape(batchSize, length, inChannels), 0, k, 0);
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
	 * Builds one transformer layer of the resampling stack from the {@value #RESAMPLING_TRANSFORMER_LAYER}
	 * of {@link #RESAMPLING_TRANSFORMER_ASSET}: {@code x = x + selfAttn(DyT(x))} followed by
	 * {@code x = x + ff(DyT(x))}, where the self-attention is differential attention over the sections
	 * {@code Q1, K1, V, Q2, K2} of the fused projection with a lambda of one, and the feed-forward is a
	 * SwiGLU gated linear unit.
	 *
	 * <p>The structure is not assembled here: the asset composes the stages. This method binds the
	 * chunk geometry, the layer's group of {@code weights} (from which every stage reads its tensors by
	 * their path), the groups of the two feed-forward projections, which sit at numeric module indices a
	 * PDSL field path cannot name, and an all-ones mask for the attention maps, since the chunks carry no
	 * padding.</p>
	 *
	 * @param numChunks the attention chunk count (the batch dimension of the per-chunk attention)
	 * @param config    the resampling configuration
	 * @param weights   the loaded weights
	 * @param key       the weight key prefix for this layer (e.g. {@code "encoder.layers.0.transformers.0"})
	 * @return the transformer-layer block; input and output shape {@code [numChunks, effChunk, dim]}
	 */
	default Block resamplingLayerBlock(int numChunks, ResamplingConfig config,
									   StateDictionary weights, String key) {
		int effChunk = config.getEffectiveChunkSize();
		Map<String, Object> args = new HashMap<>();
		args.put("chunks", numChunks);
		args.put("chunk_len", effChunk);
		args.put("heads", config.getHeads());
		args.put("head_dim", config.getDimHead());
		args.put("inner_dim", config.getInnerFfDim());
		args.put("weights", weights.group(key));
		args.put("ff_in", weights.group(key + ".ff.ff.0.proj"));
		args.put("ff_out", weights.group(key + ".ff.ff.2"));
		args.put("every_position", everyPositionValid(numChunks, effChunk));

		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(RESAMPLING_TRANSFORMER_ASSET), RESAMPLING_TRANSFORMER_LAYER,
				shape(numChunks, effChunk, config.getDim()), args);
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
	 * Numerically-stable softmax over the trailing axis, computed via the log-sum-exp identity (the
	 * producer-level equivalent of the framework's stable {@code softmax2d} layer). No zero-masking is
	 * applied. The resampling block's attention maps no longer use it: their softmax is the
	 * {@code softmax()} stage of the attention asset {@link #resamplingLayerBlock} builds.
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
