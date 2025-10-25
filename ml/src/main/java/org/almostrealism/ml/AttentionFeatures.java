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

package org.almostrealism.ml;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.List;
import java.util.function.Function;

public interface AttentionFeatures extends RotationFeatures {

	default Function<TraversalPolicy, CellularLayer> attentionKeys(Producer<PackedCollection<?>> keys,
																   ComputeRequirement... requirements) {
		return inputShape -> attentionKeys(inputShape, keys, requirements);
	}

	default CellularLayer attentionKeys(TraversalPolicy inputShape,
										Producer<PackedCollection<?>> keys,
										ComputeRequirement... requirements) {
		TraversalPolicy keyShape = shape(keys); // (seqLength, heads, headSize)

		if (inputShape.getDimensions() != 2 || keyShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = inputShape.length(1);
		int dim = heads * headSize;

		int seqLength = keyShape.length(0);
		TraversalPolicy outputShape = shape(heads, seqLength).traverseEach();

		if (keyShape.length(1) != heads || keyShape.length(2) != headSize)
			throw new IllegalArgumentException();

		// TODO  divide(c(Math.sqrt(headSize))) is better to include
		// TODO  outside this method rather than within the layer
		return layer("attentionKeys", inputShape, outputShape, input ->
				traverse(1, keys).map(v -> v.multiply(input))
						.traverse(2).sum()
						.divide(c(Math.sqrt(headSize)))
						.reshape(shape(seqLength, heads))
						.enumerate(1, 1)
						.reshape(outputShape), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> attentionValues(Producer<PackedCollection<?>> values,
																     ComputeRequirement... requirements) {
		return inputShape -> attentionValues(inputShape, values, requirements);
	}

	default CellularLayer attentionValues(TraversalPolicy inputShape,
										  Producer<PackedCollection<?>> values,
										  ComputeRequirement... requirements) {
		TraversalPolicy valueShape = shape(values); // (seqLength, heads, headSize)

		if (inputShape.getDimensions() != 2 || valueShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = valueShape.length(2);
		int dim = heads * headSize;

		int seqLength = inputShape.length(1);
		TraversalPolicy outputShape = shape(dim);

		if (valueShape.length(1) != heads || valueShape.length(0) != seqLength)
			throw new IllegalArgumentException();

		return layer("attentionValues", inputShape, outputShape, input -> {
			Producer<PackedCollection<?>> v = reshape(shape(seqLength, dim), values);
			v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

			CollectionProducer<PackedCollection<?>> a = traverse(1, input).expand(headSize, x -> x.repeat(headSize));
			CollectionProducer<PackedCollection<?>> o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();
			return o.reshape(shape(dim).traverseEach());
		}, requirements);
	}

	/**
	 * Standard multi-head attention without QK-Norm or GQA.
	 * Delegates to the full attention method with null optional parameters.
	 */
	default Block attention(int heads,
							PackedCollection<?> rmsAttWeight,
							PackedCollection<?> wk, PackedCollection<?> wv,
							PackedCollection<?> wq, PackedCollection<?> wo,
							PackedCollection<?> freqCis,
							Producer<PackedCollection<?>> position,
							ComputeRequirement... requirements) {
		return attention(heads, heads, rmsAttWeight, wk, wv, wq, wo,
				null, null, freqCis, position, requirements);
	}

	/**
	 * Multi-head attention with optional QK-Norm and Grouped Query Attention (GQA).
	 *
	 * <p>This is the unified attention implementation supporting:</p>
	 * <ul>
	 * <li><b>Standard MHA:</b> Set kvHeads = heads, qkNormQ = null, qkNormK = null</li>
	 * <li><b>GQA:</b> Set kvHeads &lt; heads (typically heads/4 or heads/8)</li>
	 * <li><b>QK-Norm:</b> Provide qkNormQ and qkNormK weights (epsilon = 1e-6)</li>
	 * </ul>
	 *
	 * @param heads Number of query attention heads
	 * @param kvHeads Number of key/value heads (for GQA, use heads for standard MHA)
	 * @param rmsAttWeight Pre-attention RMSNorm weights
	 * @param wk Key projection weights
	 * @param wv Value projection weights
	 * @param wq Query projection weights
	 * @param wo Output projection weights
	 * @param qkNormQ Query normalization weights (null to skip QK-Norm)
	 * @param qkNormK Key normalization weights (null to skip QK-Norm)
	 * @param freqCis RoPE frequency embeddings
	 * @param position Current position in sequence
	 * @param requirements Compute requirements
	 * @return Attention block
	 */
	default Block attention(int heads, int kvHeads,
							PackedCollection<?> rmsAttWeight,
							PackedCollection<?> wk, PackedCollection<?> wv,
							PackedCollection<?> wq, PackedCollection<?> wo,
							PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,
							PackedCollection<?> freqCis,
							Producer<PackedCollection<?>> position,
							ComputeRequirement... requirements) {
		int dim = rmsAttWeight.getShape().length(0);
		int headSize = freqCis.getShape().size(1);
		int seqLen = freqCis.getShape().length(0);
		int kvDim = dim * kvHeads / heads;

		SequentialBlock attention = new SequentialBlock(shape(dim));

		PackedCollection<?> keyCache = new PackedCollection<>(seqLen, kvHeads, headSize);
		PackedCollection<?> valueCache = new PackedCollection<>(seqLen, kvHeads, headSize);

		attention.add(rmsnorm(rmsAttWeight, requirements));

		SequentialBlock keys = attention.branch();
		SequentialBlock values = attention.branch();

		TraversalPolicy kvHeadShapeComplex = shape(kvHeads, headSize / 2, 2);
		TraversalPolicy kvHeadShape = shape(kvHeads, headSize);

		/* KEYS **/
		keys.add(dense(wk));
		if (qkNormK != null) {
			// QK-Norm: normalize keys before RoPE
			keys.add(reshape(shape(kvDim), kvHeadShape));
			keys.add(norm(qkNormK, null, 1e-6, requirements));
			keys.add(reshape(kvHeadShape, kvHeadShapeComplex));
		} else {
			keys.add(reshape(shape(kvDim), kvHeadShapeComplex));
		}
		keys.add(ropeRotation(kvHeadShapeComplex, freqCis, position));
		keys.andThen(into(keyCache.reshape(shape(seqLen, kvDim)), position));
		/* ---- **/

		/* VALUES **/
		values.add(dense(wv));
		values.andThen(into(valueCache.reshape(shape(seqLen, kvDim)), position));
		/* ---- **/

		/* QUERY **/
		TraversalPolicy headShapeComplex = shape(heads, headSize / 2, 2);
		TraversalPolicy headShape = shape(heads, headSize);
		TraversalPolicy attentionShape = shape(heads, seqLen);

		attention.add(dense(wq));
		if (qkNormQ != null) {
			// QK-Norm: normalize queries before RoPE
			attention.add(reshape(shape(dim), headShape));
			attention.add(norm(qkNormQ, null, 1e-6, requirements));
			attention.add(reshape(headShape, headShapeComplex));
		} else {
			attention.add(reshape(shape(dim), headShapeComplex));
		}
		attention.add(ropeRotation(headShapeComplex, freqCis, position));
		attention.add(reshape(headShapeComplex, headShape));
		attention.add(attentionKeys(headShape, p(keyCache)));
		attention.add(softmax(attentionShape, true));
		attention.add(attentionValues(attentionShape, p(valueCache)));
		attention.add(dense(wo));
		/* ---- **/

		return attention;
	}

	default Block sequenceAttention(int batchSize, int seqLen, int dim, int heads,
									PackedCollection<?> toQkvWeight, PackedCollection<?> toOutWeight,
									PackedCollection<?> qNormWeight, PackedCollection<?> qNormBias,
									PackedCollection<?> kNormWeight, PackedCollection<?> kNormBias,
									PackedCollection<?> invFreq) {
		int dimHead = dim / heads;

		SequentialBlock attention = new SequentialBlock(shape(batchSize, seqLen, dim));

		// 1. Fused QKV projection
		attention.add(dense(toQkvWeight)); // Projects to dim*3

		// 2. Split QKV and reshape to multi-head format
		attention.reshape(batchSize, seqLen, 3, dim);
		List<Block> qkv = attention.split(shape(batchSize, seqLen, 1, dim), 0);
		SequentialBlock q = (SequentialBlock) qkv.get(0).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock k = (SequentialBlock) qkv.get(1).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock v = (SequentialBlock) qkv.get(2).reshape(batchSize, seqLen, heads, dimHead);

		// 3. Permute to (batch, heads, seqLen, dimHead)
		// This matches Python's rearrange(t, 'b n (h d) -> b h n d', h = h)
		q.permute(0, 2, 1, 3);
		k.permute(0, 2, 1, 3);
		v.permute(0, 2, 1, 3);

		// 4. Apply QK normalization
		q.add(norm(qNormWeight, qNormBias, 1e-6));
		k.add(norm(kNormWeight, kNormBias, 1e-6));

		// 5. Apply rotary embeddings to Q and K
		q.add(applyRotaryPositionEmbedding(shape(batchSize, heads, seqLen, dimHead), invFreq));
		k.add(applyRotaryPositionEmbedding(shape(batchSize, heads, seqLen, dimHead), invFreq));

		// 6. Store K and V tensors for use in attention computation
		PackedCollection<?> kTensor = new PackedCollection<>(shape(batchSize, heads, seqLen, dimHead));
		PackedCollection<?> vTensor = new PackedCollection<>(shape(batchSize, heads, seqLen, dimHead));

		k.andThen(into(kTensor));
		v.andThen(into(vTensor));

		// 7. Compute scaled dot-product attention using stored tensors
		q.add(scaledDotProductAttention(batchSize, seqLen, heads, dimHead, kTensor, vTensor));

		// Rearrange back to (batch, seqLen, dim)
		q.permute(0, 2, 1, 3)
			.reshape(batchSize, seqLen, dim);

		// 8. Output projection
		q.add(dense(toOutWeight));

		return attention;
	}


	default Block sequenceCrossAttention(int batchSize, int querySeqLen, int contextSeqLen,
										 int dim, int heads,
										 PackedCollection<?> toQWeight, PackedCollection<?> toKvWeight,
										 PackedCollection<?> toOutWeight,
										 PackedCollection<?> qNormWeight, PackedCollection<?> qNormBias,
										 PackedCollection<?> kNormWeight, PackedCollection<?> kNormBias,
										 Block contextInput, Receptor<PackedCollection<?>> attentionScores) {
		int dimHead = dim / heads;

		SequentialBlock crossAttention = new SequentialBlock(shape(batchSize, querySeqLen, dim));

		// 1. Project main input to queries
		crossAttention.add(dense(toQWeight)); // Projects to dim
		crossAttention.reshape(batchSize, querySeqLen, heads, dimHead);
		crossAttention.permute(0, 2, 1, 3); // (batch, heads, querySeqLen, dimHead)

		// 2. Apply Q normalization
		crossAttention.add(norm(qNormWeight, qNormBias, 1e-6));

		// 3. Process context input through separate branch for K and V
		SequentialBlock contextBranch = contextInput.branch();
		contextBranch.add(dense(toKvWeight)); // Projects to dim * 2 (K and V)
		contextBranch.reshape(batchSize, contextSeqLen, 2, dim);

		List<Block> kv = contextBranch.split(shape(batchSize, contextSeqLen, 1, dim), 0);
		SequentialBlock k = (SequentialBlock) kv.get(0).reshape(batchSize, contextSeqLen, heads, dimHead);
		SequentialBlock v = (SequentialBlock) kv.get(1).reshape(batchSize, contextSeqLen, heads, dimHead);

		// 4. Permute K and V to (batch, heads, contextSeqLen, dimHead)
		k.permute(0, 2, 1, 3);
		v.permute(0, 2, 1, 3);

		// 5. Apply K normalization (no rotary for context keys/values)
		k.add(norm(kNormWeight, kNormBias, 1e-6));

		// 6. Store K and V tensors for use in attention computation
		PackedCollection<?> kTensor = new PackedCollection<>(shape(batchSize, heads, contextSeqLen, dimHead));
		PackedCollection<?> vTensor = new PackedCollection<>(shape(batchSize, heads, contextSeqLen, dimHead));

		k.andThen(into(kTensor));
		v.andThen(into(vTensor));

		// 7. Apply attention to values
		crossAttention.add(scaledDotProductAttention(
				batchSize, querySeqLen, contextSeqLen,
				heads, dimHead, kTensor, vTensor, attentionScores));

		// 8. Rearrange back to (batch, querySeqLen, dim)
		crossAttention.permute(0, 2, 1, 3)
				.reshape(batchSize, querySeqLen, dim);

		// 9. Output projection
		crossAttention.add(dense(toOutWeight));

		return crossAttention;
	}

	default Block feedForward(
			PackedCollection<?> rms,
			PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
			ComputeRequirement... requirements) {
		int dim = w2.getShape().length(0);
		return feedForward(shape(dim), rms, null,
				w1, w2, w3, null, null, null,
				requirements);
	}

	default Block feedForward(
			TraversalPolicy shape,
			PackedCollection<?> normWeights, PackedCollection<?> normBiases,
			PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
			PackedCollection<?> w1Bias, PackedCollection<?> w2Bias, PackedCollection<?> w3Bias,
			ComputeRequirement... requirements) {
		SequentialBlock feedForward = new SequentialBlock(shape);
		feedForward.add(rmsnorm(normWeights, normBiases, requirements));

		SequentialBlock hidden = new SequentialBlock(shape);
		hidden.add(dense(w1, w1Bias));
		hidden.add(silu());

		feedForward.product(dense(w3, w3Bias), hidden);
		feedForward.add(dense(w2, w2Bias));
		return feedForward;
	}

	default Function<TraversalPolicy, Block> gatedLinear(PackedCollection<?> weight,
														 PackedCollection<?> bias) {
		return inputShape -> gatedLinear(inputShape, weight, bias);
	}

	default Block gatedLinear(TraversalPolicy inputShape,
							  PackedCollection<?> weight,
							  PackedCollection<?> bias) {
		SequentialBlock glu = new SequentialBlock(inputShape);
		glu.add(dense(weight, bias));

		// Split the output into two parts, one for
		// the linear transform and one for the gate
		List<Block> split = glu.split(2, glu.getOutputShape().getDimensions() - 1, 0);
		Block gate = split.get(1).andThen(silu());

		// Apply activation to the gate and multiply
		// it with the linear output
		glu.add(product(gate));
		return glu;
	}

	default Function<TraversalPolicy, Block> gatedLinearFeedForward(PackedCollection<?> normWeights, PackedCollection<?> normBiases,
																	 PackedCollection<?> weightIn, PackedCollection<?> biasIn,
																	 PackedCollection<?> weightOut, PackedCollection<?> biasOut,
																	ComputeRequirement... requirements) {
		return inputShape ->
				gatedLinearFeedForward(inputShape, normWeights, normBiases,
										weightIn, biasIn, weightOut, biasOut,
										requirements);
	}

	default Block gatedLinearFeedForward(TraversalPolicy inputShape,
										 PackedCollection<?> normWeights, PackedCollection<?> normBiases,
										 PackedCollection<?> weightIn, PackedCollection<?> biasIn,
										 PackedCollection<?> weightOut, PackedCollection<?> biasOut,
										 ComputeRequirement... requirements) {
		SequentialBlock feedForward = new SequentialBlock(inputShape);
		feedForward.add(norm(normWeights, normBiases, requirements));
		feedForward.add(gatedLinear(weightIn, biasIn));
		feedForward.add(dense(weightOut, biasOut));
		return feedForward;
	}

	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   // Self-attention weights
								   PackedCollection<?> preNormWeight, PackedCollection<?> preNormBias,
								   PackedCollection<?> selfQkv, PackedCollection<?> selfWo,
								   PackedCollection<?> selfQNormWeight, PackedCollection<?> selfQNormBias,
								   PackedCollection<?> selfKNormWeight, PackedCollection<?> selfKNormBias,
								   PackedCollection<?> invFreq,
								   // Cross-attention weights
								   PackedCollection<?> crossAttPreNormWeight, PackedCollection<?> crossAttPreNormBias,
								   PackedCollection<?> crossWq, PackedCollection<?> crossKv, PackedCollection<?> crossWo,
								   PackedCollection<?> crossQNormWeight, PackedCollection<?> crossQNormBias,
								   PackedCollection<?> crossKNormWeight, PackedCollection<?> crossKNormBias,
								   // Feed-forward weights
								   PackedCollection<?> ffnNormWeight, PackedCollection<?> ffnNormBias,
								   PackedCollection<?> w1, PackedCollection<?> w2,
								   PackedCollection<?> w1Bias, PackedCollection<?> w2Bias) {
		return transformerBlock(batchSize, dim, seqLen, heads, crossAttend,
				contextSeqLen, context,
				preNormWeight, preNormBias,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq,
				crossAttPreNormWeight, crossAttPreNormBias,
				crossWq, crossKv, crossWo,
				crossQNormWeight, crossQNormBias,
				crossKNormWeight, crossKNormBias,
				ffnNormWeight, ffnNormBias,
				w1, w2, w1Bias, w2Bias,
				null);
	}

	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   // Self-attention weights
								   PackedCollection<?> preNormWeight, PackedCollection<?> preNormBias,
								   PackedCollection<?> selfQkv, PackedCollection<?> selfWo,
								   PackedCollection<?> selfQNormWeight, PackedCollection<?> selfQNormBias,
								   PackedCollection<?> selfKNormWeight, PackedCollection<?> selfKNormBias,
								   PackedCollection<?> invFreq,
								   // Cross-attention weights
								   PackedCollection<?> crossAttPreNormWeight, PackedCollection<?> crossAttPreNormBias,
								   PackedCollection<?> crossWq, PackedCollection<?> crossKv, PackedCollection<?> crossWo,
								   PackedCollection<?> crossQNormWeight, PackedCollection<?> crossQNormBias,
								   PackedCollection<?> crossKNormWeight, PackedCollection<?> crossKNormBias,
								   // Feed-forward weights
								   PackedCollection<?> ffnNormWeight, PackedCollection<?> ffnNormBias,
								   PackedCollection<?> w1, PackedCollection<?> w2,
								   PackedCollection<?> w1Bias, PackedCollection<?> w2Bias,
								   Receptor<PackedCollection<?>> attentionScores) {
		SequentialBlock block = new SequentialBlock(shape(batchSize, seqLen, dim));

		// Self-attention with pre-normalization inside residual branch
		// Python: x = x + self_attn(pre_norm(x))
		SequentialBlock selfAttentionWithNorm = new SequentialBlock(shape(batchSize, seqLen, dim));
		selfAttentionWithNorm.add(norm(preNormWeight, preNormBias));
		selfAttentionWithNorm.add(sequenceAttention(
				batchSize, seqLen, dim, heads,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq));
		block.add(residual(selfAttentionWithNorm));

		// Cross-attention with pre-normalization inside residual branch (if needed)
		// Python: x = x + cross_attn(cross_attend_norm(x))
		if (crossAttend) {
			if (context == null) {
				throw new IllegalArgumentException("Context block cannot be null for cross-attention");
			}

			SequentialBlock crossAttentionWithNorm = new SequentialBlock(shape(batchSize, seqLen, dim));
			crossAttentionWithNorm.add(norm(crossAttPreNormWeight, crossAttPreNormBias));
			crossAttentionWithNorm.add(sequenceCrossAttention(
					batchSize, seqLen, contextSeqLen, dim, heads,
					crossWq, crossKv, crossWo,
					crossQNormWeight, crossQNormBias,
					crossKNormWeight, crossKNormBias,
					context, attentionScores));
			block.add(residual(crossAttentionWithNorm));
		}

		// Feed-forward with normalization inside residual branch
		block.add(residual(gatedLinearFeedForward(block.getOutputShape(),
				ffnNormWeight, ffnNormBias, w1, w1Bias, w2, w2Bias)));

		return block;
	}

	/**
	 * Standard transformer layer without QK-Norm or GQA.
	 * Delegates to the full transformer method with null optional parameters.
	 */
	default Block transformer(int heads,
							  PackedCollection<?> rmsAttWeight,
							  PackedCollection<?> wk, PackedCollection<?> wv,
							  PackedCollection<?> wq, PackedCollection<?> wo,
							  PackedCollection<?> freqCis,
							  PackedCollection<?> rmsFfnWeight,
							  PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
							  Producer<PackedCollection<?>> position,
							  ComputeRequirement... requirements) {
		return transformer(heads, heads, rmsAttWeight, wk, wv, wq, wo,
				null, null, freqCis, rmsFfnWeight, w1, w2, w3, position, requirements);
	}

	/**
	 * Transformer layer with optional QK-Norm and Grouped Query Attention (GQA).
	 *
	 * <p>This is the unified transformer implementation combining:</p>
	 * <ul>
	 * <li><b>Attention block:</b> Multi-head attention with optional QK-Norm and GQA</li>
	 * <li><b>Feed-forward block:</b> SwiGLU gated FFN</li>
	 * </ul>
	 *
	 * @param heads Number of query attention heads
	 * @param kvHeads Number of key/value heads (for GQA, use heads for standard MHA)
	 * @param rmsAttWeight Pre-attention RMSNorm weights
	 * @param wk Key projection weights
	 * @param wv Value projection weights
	 * @param wq Query projection weights
	 * @param wo Output projection weights
	 * @param qkNormQ Query normalization weights (null to skip QK-Norm)
	 * @param qkNormK Key normalization weights (null to skip QK-Norm)
	 * @param freqCis RoPE frequency embeddings
	 * @param rmsFfnWeight Pre-FFN RMSNorm weights
	 * @param w1 FFN gate projection
	 * @param w2 FFN down projection
	 * @param w3 FFN up projection
	 * @param position Current position in sequence
	 * @param requirements Compute requirements
	 * @return Complete transformer layer block
	 */
	default Block transformer(int heads, int kvHeads,
							  PackedCollection<?> rmsAttWeight,
							  PackedCollection<?> wk, PackedCollection<?> wv,
							  PackedCollection<?> wq, PackedCollection<?> wo,
							  PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,
							  PackedCollection<?> freqCis,
							  PackedCollection<?> rmsFfnWeight,
							  PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
							  Producer<PackedCollection<?>> position,
							  ComputeRequirement... requirements) {
		int dim = rmsAttWeight.getShape().length(0);
		SequentialBlock transformer = new SequentialBlock(shape(dim));
		transformer.accum(attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				qkNormQ, qkNormK, freqCis, position, requirements), requirements);
		transformer.accum(feedForward(rmsFfnWeight, w1, w2, w3, requirements), requirements);
		return transformer;
	}

	default Function<TraversalPolicy, CellularLayer> context(Block v, int batchSize, int heads, int dimHead, int size) {
		if (v.getOutputShape().getDimensions() != 4 ||
				v.getOutputShape().length(1) != heads ||
				v.getOutputShape().length(2) != dimHead ||
				v.getOutputShape().length(3) != size) {
			throw new IllegalArgumentException();
		}

		return compose("context", v, shape(batchSize, heads, dimHead, dimHead), (a, b) -> {
			CollectionProducer<PackedCollection<?>> pa = c(a)
					.traverse(3)
					.repeat(dimHead);
			CollectionProducer<PackedCollection<?>> pb = c(b)
					.traverse(2)
					.repeat(dimHead);
			return multiply(pa, pb).sum(4);
		});
	}

	default Function<TraversalPolicy, Block> linearAttention(int dim) {
		return shape -> {
			int batchSize = shape.length(0);
			int inputChannels = shape.length(1);
			int rows = shape.length(2);
			int cols = shape.length(3);
			return linearAttention(batchSize, dim, inputChannels, rows, cols);
		};
	}

	default Block linearAttention(int batchSize, int dim, int inputChannels, int rows, int cols) {
		return linearAttention(batchSize, dim, 4, 32, inputChannels, rows, cols);
	}

	default Block linearAttention(int batchSize, int dim, int heads, int dimHead,
								 int inputChannels, int rows, int cols) {
		double scale = 1.0 / Math.sqrt(dimHead);
		int hiddenDim = dimHead * heads;
		int size = rows * cols;

		TraversalPolicy shape = shape(batchSize, inputChannels, rows, cols);
		TraversalPolicy componentShape = shape(batchSize, heads, dimHead, size);

		SequentialBlock attention = new SequentialBlock(shape);
		attention.add(convolution2d(dim, hiddenDim * 3, 1, 0, false));

		attention
				.reshape(batchSize, 3, hiddenDim * size)
				.enumerate(shape(batchSize, 1, hiddenDim * size))
				.reshape(3, batchSize, heads, dimHead, size);

		List<Block> qkv = attention.split(componentShape, 1);
		Block q = qkv.get(0)
//				.andThen(scale(scale))
				.reshape(batchSize, heads, dimHead * size)
				.andThen(softmax(true))
				.andThen(scale(scale))
				.reshape(batchSize, heads, dimHead, size);
		Block v = qkv.get(2);

		attention.add(softmax(true));
		attention.add(context(v, batchSize, heads, dimHead, size));
		attention.add(similarity(q, heads, dimHead, size));
		attention.reshape(batchSize, hiddenDim, rows, cols);
		attention.add(convolution2d(hiddenDim, dim, 1, 0));
		attention.add(norm());

		if (!attention.getOutputShape().equalsIgnoreAxis(shape)) {
			throw new IllegalArgumentException();
		}

		return attention;
	}

	default Block scaledDotProductAttention(int batchSize, int seqLen, int heads, int dimHead,
											PackedCollection<?> k, PackedCollection<?> v) {
		return scaledDotProductAttention(batchSize, seqLen, seqLen, heads, dimHead, k, v, null);
	}

	default Block scaledDotProductAttention(int batchSize, int seqLen, int heads, int dimHead,
											PackedCollection<?> k, PackedCollection<?> v,
											Receptor<PackedCollection<?>> attentionScores) {
		return scaledDotProductAttention(batchSize, seqLen, seqLen, heads, dimHead, k, v, attentionScores);
	}

	/**
	 * Computes scaled dot-product attention: softmax(Q @ K^T / sqrt(d_k)) @ V
	 * This implementation properly handles K and V as tensor data rather than computational blocks.
	 *
	 * @param batchSize batch dimension
	 * @param querySeqLen sequence length for queries
	 * @param contextSeqLen sequence length for context (keys/values)
	 * @param heads number of attention heads
	 * @param dimHead dimension per head
	 * @param k key tensor data (batch, heads, seqLenK, dimHead)
	 * @param v value tensor data (batch, heads, seqLenV, dimHead)
	 */
	default Block scaledDotProductAttention(int batchSize, int querySeqLen, int contextSeqLen, int heads, int dimHead,
											PackedCollection<?> k, PackedCollection<?> v,
											Receptor<PackedCollection<?>> attentionScores) {
		if (batchSize != 1) {
			throw new UnsupportedOperationException("Batches of more than 1 are not currently supported");
		}

		SequentialBlock attnBlock = new SequentialBlock(shape(batchSize, heads, querySeqLen, dimHead));

		// Q @ K^T: (batch, heads, querySeqLen, dimHead) @ (batch, heads, dimHead, contextSeqLen)
		//         = (batch, heads, querySeqLen, contextSeqLen)
		attnBlock.add(layer("qkMatmul",
				shape(batchSize, heads, querySeqLen, dimHead),
				shape(batchSize, heads, querySeqLen, contextSeqLen),
				q -> scaledDotProduct(c(q), cp(k), true)));

		// Scale by 1/sqrt(dimHead)
		attnBlock.add(scale(1.0 / Math.sqrt(dimHead)));

		// Apply softmax over last dimension (key positions) - capture these attention weights
		SequentialBlock softmaxBlock = new SequentialBlock(shape(batchSize, heads, querySeqLen, contextSeqLen));
		softmaxBlock.add(softmax(shape(batchSize, heads, querySeqLen, contextSeqLen), true));

		// Capture attention weights if requested
		if (attentionScores != null) {
			softmaxBlock.branch().andThen(attentionScores);
		}

		attnBlock.add(softmaxBlock);

		// Attention @ V: (batch, heads, querySeqLen, contextSeqLen) @ (batch, heads, contextSeqLen, dimHead)
		//              = (batch, heads, querySeqLen, dimHead)
		attnBlock.add(layer("attnValues",
				shape(batchSize, heads, querySeqLen, contextSeqLen),
				shape(batchSize, heads, querySeqLen, dimHead),
				attnWeights -> scaledDotProduct(c(attnWeights), cp(v))));

		return attnBlock;
	}

	static AttentionFeatures getInstance() {
		return new AttentionFeatures() { };
	}
}