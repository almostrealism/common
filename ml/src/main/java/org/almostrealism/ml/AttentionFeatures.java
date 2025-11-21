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

/**
 * Provides generalized attention mechanism implementations for transformer-based models.
 *
 * <p>This interface offers a comprehensive set of methods for building modern attention
 * mechanisms including multi-head attention (MHA), grouped query attention (GQA), query-key
 * normalization (QK-Norm), rotary positional embeddings (RoPE), and various transformer
 * architectures. It extends {@link RotationFeatures} to inherit RoPE functionality.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Multi-Head Attention (MHA):</strong> Standard attention with multiple heads</li>
 *   <li><strong>Grouped Query Attention (GQA):</strong> Efficient attention with fewer KV heads</li>
 *   <li><strong>QK-Normalization:</strong> Optional query/key normalization (Qwen3, Gemma2)</li>
 *   <li><strong>Rotary Embeddings (RoPE):</strong> Position-dependent rotations</li>
 *   <li><strong>Cross-Attention:</strong> Attend over external context (encoder-decoder)</li>
 *   <li><strong>Causal Masking:</strong> Automatic masking for autoregressive generation</li>
 * </ul>
 *
 * <h2>Supported Architectures</h2>
 * <p>These methods support various transformer variants:</p>
 * <table>
 * <caption>Table</caption>
 *   <tr>
 *     <th>Model Family</th>
 *     <th>Features Used</th>
 *   </tr>
 *   <tr>
 *     <td>Llama 2/3</td>
 *     <td>MHA or GQA, RoPE (theta=10000), RMSNorm</td>
 *   </tr>
 *   <tr>
 *     <td>Qwen3</td>
 *     <td>GQA, QK-Norm, RoPE (theta=1000000)</td>
 *   </tr>
 *   <tr>
 *     <td>Gemma2</td>
 *     <td>MHA, QK-Norm, RoPE, sliding window</td>
 *   </tr>
 *   <tr>
 *     <td>Mistral</td>
 *     <td>GQA, RoPE, sliding window attention</td>
 *   </tr>
 * </table>
 *
 * <h2>Core Attention Methods</h2>
 *
 * <p><strong>1. Standard Multi-Head Attention:</strong></p>
 * <pre>{@code
 * // Llama-style attention without QK-Norm
 * Block attn = attention(
 *     heads, kvHeads, rmsAttWeight,
 *     wk, wv, wq, wo,
 *     freqCis, position, requirements
 * );
 * }</pre>
 *
 * <p><strong>2. Attention with QK-Normalization:</strong></p>
 * <pre>{@code
 * // Qwen3-style attention with QK-Norm
 * Block attn = attention(
 *     heads, kvHeads, rmsAttWeight,
 *     wk, wv, wq, wo,
 *     null, null, null,  // No biases
 *     qkNormQ, qkNormK,  // QK-Norm weights
 *     freqCis, position, requirements
 * );
 * }</pre>
 *
 * <p><strong>3. Complete Transformer Layer:</strong></p>
 * <pre>{@code
 * // Combines attention + feed-forward with residuals
 * Block layer = transformer(
 *     heads, kvHeads,
 *     rmsAttWeight, wk, wv, wq, wo,
 *     bk, bv, bq, qkNormQ, qkNormK,  // Optional params
 *     freqCis, rmsFfnWeight, w1, w2, w3,
 *     position, requirements
 * );
 * }</pre>
 *
 * <h2>Grouped Query Attention (GQA)</h2>
 * <p>GQA reduces memory and computation by using fewer key-value heads than query heads.
 * Each KV head is shared across multiple query heads:</p>
 * <pre>{@code
 * // Example: 32 query heads, 8 KV heads (4:1 ratio)
 * Block gqaAttention = attention(
 *     32,  // query heads
 *     8,   // KV heads - automatically expanded to match query heads
 *     rmsAttWeight, wk, wv, wq, wo,
 *     freqCis, position, requirements
 * );
 * }</pre>
 *
 * <h2>Usage Pattern</h2>
 * <p>Typical usage in a model implementation:</p>
 * <pre>{@code
 * public class Llama3 implements AttentionFeatures {
 *     private AutoregressiveModel model;
 *
 *     public Llama3(StateDictionary weights) {
 *         Model transformer = new Model(shape(dim));
 *
 *         for (int layer = 0; layer < config.layerCount; layer++) {
 *             // Load weights for this layer
 *             PackedCollection<?> wq = weights.get("layers." + layer + ".self_attn.q_proj.weight");
 *             // ... load other weights ...
 *
 *             // Use generalized attention method
 *             transformer.add(attention(
 *                 config.headCount, config.kvHeadCount,
 *                 rmsAttWeight, wk, wv, wq, wo,
 *                 freqCis, position, requirements
 *             ));
 *         }
 *
 *         this.model = AutoregressiveModel.of(transformer.compile(), ...);
 *     }
 * }
 * }</pre>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Generalization:</strong> Single methods support multiple architectures via optional parameters</li>
 *   <li><strong>Composability:</strong> Methods return {@link Block}s that can be chained</li>
 *   <li><strong>Hardware Acceleration:</strong> All operations compile to GPU/native code</li>
 *   <li><strong>Memory Efficiency:</strong> KV caching for autoregressive generation</li>
 * </ul>
 *
 * @see RotationFeatures
 * @see org.almostrealism.layers.LayerFeatures
 * @see org.almostrealism.model.Block
 */
public interface AttentionFeatures extends RotationFeatures {

	/**
	 * Creates an attention keys layer function that can be applied to different input shapes.
	 *
	 * @param keys The key tensor producer (seqLength, kvHeads, headSize)
	 * @param requirements Compute requirements
	 * @return A function that creates an attention keys layer for a given input shape
	 */
	default Function<TraversalPolicy, CellularLayer> attentionKeys(Producer<PackedCollection<?>> keys,
																   ComputeRequirement... requirements) {
		return inputShape -> attentionKeys(inputShape, keys, requirements);
	}

	/**
	 * Creates a layer that computes attention scores by multiplying queries with keys.
	 * Handles Grouped Query Attention (GQA) by automatically expanding KV heads to match query heads.
	 *
	 * <p>This implements the Q @ K^T operation in attention, producing attention scores
	 * before softmax normalization. The output is scaled by 1/sqrt(headSize).</p>
	 *
	 * @param inputShape Shape of the query input (heads, headSize)
	 * @param keys Key tensor producer (seqLength, kvHeads, headSize)
	 * @param requirements Compute requirements
	 * @return Attention keys layer producing (heads, seqLength) scores
	 */
	default CellularLayer attentionKeys(TraversalPolicy inputShape,
										Producer<PackedCollection<?>> keys,
										ComputeRequirement... requirements) {
		TraversalPolicy keyShape = shape(keys); // (seqLength, kvHeads, headSize)

		if (inputShape.getDimensions() != 2 || keyShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = inputShape.length(1);
		int dim = heads * headSize;

		int seqLength = keyShape.length(0);
		int kvHeads = keyShape.length(1);
		TraversalPolicy outputShape = shape(heads, seqLength).traverseEach();

		if (keyShape.length(2) != headSize)
			throw new IllegalArgumentException("Key head size mismatch");

		// Handle Grouped Query Attention (GQA): expand KV heads to match query heads
		if (kvHeads != heads && heads % kvHeads != 0) {
			throw new IllegalArgumentException("heads must be divisible by kvHeads for GQA");
		}

		// Expand KV heads if needed (GQA), otherwise use keys directly
		int headsPerKvGroup = heads / kvHeads;
		final Producer<PackedCollection<?>> expandedKeys = (kvHeads != heads)
				? expandKeysForGQA(keys, seqLength, kvHeads, heads, headSize, headsPerKvGroup)
				: keys;

		// TODO  divide(c(Math.sqrt(headSize))) is better to include
		// TODO  outside this method rather than within the layer
		return layer("attentionKeys", inputShape, outputShape, input ->
				traverse(1, expandedKeys).map(v -> v.multiply(input))
						.traverse(2).sum()
						.divide(c(Math.sqrt(headSize)))
						.reshape(shape(seqLength, heads))
						.enumerate(1, 1)
						.reshape(outputShape), requirements);
	}

	/**
	 * Expand KV cache for Grouped Query Attention by repeating each KV head.
	 *
	 * Transforms (seqLength, kvHeads, headSize) to (seqLength, heads, headSize)
	 * where each KV head is repeated (heads/kvHeads) times.
	 *
	 * Example for 14 heads, 2 KV heads (7:1 ratio):
	 * - kvHead[0] -> queryHeads[0..6]
	 * - kvHead[1] -> queryHeads[7..13]
	 */
	default Producer<PackedCollection<?>> expandKeysForGQA(
			Producer<PackedCollection<?>> keys,
			int seqLength, int kvHeads, int heads, int headSize, int headsPerKvGroup) {
		// (seqLength, kvHeads, headSize) -> (seqLength, kvHeads, headsPerKvGroup, headSize)
		// traverse(2) traverses first 2 dims, repeat inserts new dimension
		Producer<PackedCollection<?>> repeated = traverse(2, keys).repeat(headsPerKvGroup);

		// (seqLength, kvHeads, headsPerKvGroup, headSize) -> (seqLength, heads, headSize)
		return reshape(shape(seqLength, heads, headSize), repeated);
	}

	/**
	 * Creates an attention values layer function that can be applied to different input shapes.
	 *
	 * @param values The value tensor producer (seqLength, kvHeads, headSize)
	 * @param requirements Compute requirements
	 * @return A function that creates an attention values layer for a given input shape
	 */
	default Function<TraversalPolicy, CellularLayer> attentionValues(Producer<PackedCollection<?>> values,
																     ComputeRequirement... requirements) {
		return inputShape -> attentionValues(inputShape, values, requirements);
	}

	/**
	 * Creates a layer that applies attention weights to values.
	 * Handles Grouped Query Attention (GQA) by automatically expanding KV heads to match query heads.
	 *
	 * <p>This implements the Attention @ V operation, producing the final attended output
	 * by weighted combination of value vectors according to attention scores.</p>
	 *
	 * @param inputShape Shape of the attention scores input (heads, seqLength)
	 * @param values Value tensor producer (seqLength, kvHeads, headSize)
	 * @param requirements Compute requirements
	 * @return Attention values layer producing (dim) output
	 */
	default CellularLayer attentionValues(TraversalPolicy inputShape,
										  Producer<PackedCollection<?>> values,
										  ComputeRequirement... requirements) {
		TraversalPolicy valueShape = shape(values); // (seqLength, kvHeads, headSize)

		if (inputShape.getDimensions() != 2 || valueShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = valueShape.length(2);
		int dim = heads * headSize;

		int seqLength = inputShape.length(1);
		int kvHeads = valueShape.length(1);
		TraversalPolicy outputShape = shape(dim);

		if (valueShape.length(0) != seqLength)
			throw new IllegalArgumentException("Value sequence length mismatch");

		// Handle Grouped Query Attention (GQA): expand KV heads to match query heads
		if (kvHeads != heads && heads % kvHeads != 0) {
			throw new IllegalArgumentException("heads must be divisible by kvHeads for GQA");
		}

		// Expand KV heads if needed (GQA), otherwise use values directly
		int headsPerKvGroup = heads / kvHeads;
		final Producer<PackedCollection<?>> expandedValues = (kvHeads != heads)
				? expandValuesForGQA(values, seqLength, kvHeads, heads, headSize, headsPerKvGroup)
				: values;

		return layer("attentionValues", inputShape, outputShape, input -> {
			Producer<PackedCollection<?>> v = reshape(shape(seqLength, dim), expandedValues);
			v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

			CollectionProducer<PackedCollection<?>> a = traverse(1, input).repeat(headSize);
			CollectionProducer<PackedCollection<?>> o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();
			return o.reshape(shape(dim).traverseEach());
		}, requirements);
	}

	/**
	 * Expand value cache for Grouped Query Attention by repeating each KV head.
	 *
	 * Same logic as expandKeysForGQA - transforms (seqLength, kvHeads, headSize)
	 * to (seqLength, heads, headSize).
	 */
	default Producer<PackedCollection<?>> expandValuesForGQA(
			Producer<PackedCollection<?>> values,
			int seqLength, int kvHeads, int heads, int headSize, int headsPerKvGroup) {
		// (seqLength, kvHeads, headSize) -> (seqLength, kvHeads, headsPerKvGroup, headSize)
		Producer<PackedCollection<?>> repeated = traverse(2, values).repeat(headsPerKvGroup);

		// (seqLength, kvHeads, headsPerKvGroup, headSize) -> (seqLength, heads, headSize)
		return reshape(shape(seqLength, heads, headSize), repeated);
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
				null, null, null, null, null, freqCis, position, requirements);
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
	 * @param bk Key projection bias (null if not used)
	 * @param bv Value projection bias (null if not used)
	 * @param bq Query projection bias (null if not used)
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
							PackedCollection<?> bk, PackedCollection<?> bv,
							PackedCollection<?> bq,
							PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,
							PackedCollection<?> freqCis,
							Producer<PackedCollection<?>> position,
							ComputeRequirement... requirements) {
		int dim = rmsAttWeight.getShape().length(0);
		int headSize = freqCis.getShape().length(1) * 2; // freqCis is (seqLen, headSize/2, 2)
		int seqLen = freqCis.getShape().length(0);
		int kvDim = dim * kvHeads / heads;

		SequentialBlock attention = new SequentialBlock(shape(dim));

		PackedCollection<?> keyCache = new PackedCollection<>(seqLen, kvHeads, headSize);
		PackedCollection<?> valueCache = new PackedCollection<>(seqLen, kvHeads, headSize);

		// Zero-initialize caches to prevent garbage values from causing numerical explosions
		keyCache.clear();
		valueCache.clear();

		attention.add(rmsnorm(rmsAttWeight, requirements));

		SequentialBlock keys = attention.branch();
		SequentialBlock values = attention.branch();

		TraversalPolicy kvHeadShapeComplex = shape(kvHeads, headSize / 2, 2);
		TraversalPolicy kvHeadShape = shape(kvHeads, headSize);

		/* KEYS **/
		keys.add(bk != null ? dense(wk, bk) : dense(wk));
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
		values.add(bv != null ? dense(wv, bv) : dense(wv));
		values.andThen(into(valueCache.reshape(shape(seqLen, kvDim)), position));
		/* ---- **/

		/* QUERY **/
		TraversalPolicy headShapeComplex = shape(heads, headSize / 2, 2);
		TraversalPolicy headShape = shape(heads, headSize);
		TraversalPolicy attentionShape = shape(heads, seqLen).traverseEach(); // (heads, 1, seqLen)

		attention.add(bq != null ? dense(wq, bq) : dense(wq));
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

		// Add dynamic causal mask: mask[i] = -10000 if i > position, else 0
		// This prevents attention from seeing future positions in the KV cache
		CollectionProducer<?> indices = integers(0, seqLen);
		CollectionProducer<PackedCollection<?>> maskRow =
			greaterThan(indices, position, c(-10000.0), c(0.0), false);
		// Reshape to (1, 1, seqLen) and repeat for all heads -> (heads, 1, seqLen)
		CollectionProducer<PackedCollection<?>> causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		// Create a block to add the causal mask to the attention scores
		attention.add(layer("causal_mask", attentionShape, attentionShape,
		                   input -> add(input, causalMask),
		                   requirements));

		attention.add(softmax(attentionShape, true));
		attention.add(attentionValues(attentionShape, p(valueCache)));
		attention.add(dense(wo));
		/* ---- **/

		return attention;
	}

	/**
	 * Creates a sequence-based multi-head attention block with fused QKV projection.
	 *
	 * <p>This implements full-sequence attention (not autoregressive) with:
	 * <ul>
	 *   <li>Fused QKV projection for efficiency</li>
	 *   <li>QK normalization for stability</li>
	 *   <li>Rotary positional embeddings (RoPE)</li>
	 *   <li>Scaled dot-product attention</li>
	 * </ul></p>
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen Sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQkvWeight Fused QKV projection weights
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights
	 * @param qNormBias Query normalization biases
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @return Sequence attention block
	 */
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

	/**
	 * Creates a cross-attention block for attending over external context.
	 *
	 * <p>Cross-attention allows the model to attend to a different sequence (context) than
	 * the main input. This is used in encoder-decoder architectures where the decoder
	 * attends to encoder outputs.</p>
	 *
	 * <p>Implementation details:
	 * <ul>
	 *   <li>Queries (Q) come from the main input</li>
	 *   <li>Keys (K) and Values (V) come from the context input</li>
	 *   <li>QK normalization for stability</li>
	 *   <li>No rotary embeddings (context positions are independent)</li>
	 * </ul></p>
	 *
	 * @param batchSize Batch dimension
	 * @param querySeqLen Query sequence length
	 * @param contextSeqLen Context sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQWeight Query projection weights
	 * @param toKvWeight Fused KV projection weights for context
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights
	 * @param qNormBias Query normalization biases
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases
	 * @param contextInput Context block to attend over
	 * @param attentionScores Optional receptor to capture attention weights
	 * @return Cross-attention block
	 */
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

	/**
	 * Creates a SwiGLU feed-forward block with RMSNorm (simplified version without biases).
	 * Delegates to the full feedForward method with null biases.
	 *
	 * @param rms RMSNorm weights
	 * @param w1 Gate projection weights
	 * @param w2 Down projection weights
	 * @param w3 Up projection weights
	 * @param requirements Compute requirements
	 * @return Feed-forward block
	 */
	default Block feedForward(
			PackedCollection<?> rms,
			PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
			ComputeRequirement... requirements) {
		int dim = w2.getShape().length(0);
		return feedForward(shape(dim), rms, null,
				w1, w2, w3, null, null, null,
				requirements);
	}

	/**
	 * Creates a SwiGLU feed-forward block with optional biases.
	 *
	 * <p>Implements the SwiGLU activation: FFN(x) = (SiLU(x @ W1 + b1) * (x @ W3 + b3)) @ W2 + b2
	 * This is the standard feed-forward layer used in modern transformers.</p>
	 *
	 * @param shape Input/output shape
	 * @param normWeights Normalization weights (RMSNorm or LayerNorm)
	 * @param normBiases Normalization biases (null for RMSNorm)
	 * @param w1 Gate projection weights
	 * @param w2 Down projection weights
	 * @param w3 Up projection weights
	 * @param w1Bias Gate projection bias (null if not used)
	 * @param w2Bias Down projection bias (null if not used)
	 * @param w3Bias Up projection bias (null if not used)
	 * @param requirements Compute requirements
	 * @return Feed-forward block
	 */
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

	/**
	 * Creates a gated linear unit (GLU) function that can be applied to different input shapes.
	 *
	 * @param weight Linear projection weights (projects to 2x output dimension)
	 * @param bias Linear projection bias
	 * @return A function that creates a GLU block for a given input shape
	 */
	default Function<TraversalPolicy, Block> gatedLinear(PackedCollection<?> weight,
														 PackedCollection<?> bias) {
		return inputShape -> gatedLinear(inputShape, weight, bias);
	}

	/**
	 * Creates a gated linear unit (GLU) block with SiLU activation.
	 *
	 * <p>Implements GLU(x) = Linear(x)_left * SiLU(Linear(x)_right)
	 * The linear projection outputs 2x the input dimension, which is then split
	 * into two equal parts for gating.</p>
	 *
	 * @param inputShape Input shape
	 * @param weight Linear projection weights
	 * @param bias Linear projection bias
	 * @return Gated linear block
	 */
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

	/**
	 * Creates a gated linear feed-forward function with normalization.
	 *
	 * @param normWeights Normalization weights
	 * @param normBiases Normalization biases
	 * @param weightIn Input projection weights (GLU)
	 * @param biasIn Input projection bias
	 * @param weightOut Output projection weights
	 * @param biasOut Output projection bias
	 * @param requirements Compute requirements
	 * @return A function that creates a gated linear FFN block for a given input shape
	 */
	default Function<TraversalPolicy, Block> gatedLinearFeedForward(PackedCollection<?> normWeights, PackedCollection<?> normBiases,
																	 PackedCollection<?> weightIn, PackedCollection<?> biasIn,
																	 PackedCollection<?> weightOut, PackedCollection<?> biasOut,
																	ComputeRequirement... requirements) {
		return inputShape ->
				gatedLinearFeedForward(inputShape, normWeights, normBiases,
										weightIn, biasIn, weightOut, biasOut,
										requirements);
	}

	/**
	 * Creates a gated linear feed-forward block with normalization.
	 *
	 * <p>Combines normalization, gated linear unit, and output projection:
	 * FFN(x) = Linear_out(GLU(Norm(x)))</p>
	 *
	 * @param inputShape Input/output shape
	 * @param normWeights Normalization weights
	 * @param normBiases Normalization biases
	 * @param weightIn Input projection weights (GLU)
	 * @param biasIn Input projection bias
	 * @param weightOut Output projection weights
	 * @param biasOut Output projection bias
	 * @param requirements Compute requirements
	 * @return Gated linear feed-forward block
	 */
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

	/**
	 * Creates a complete transformer block with self-attention, optional cross-attention, and feed-forward.
	 * Simplified version without attention score capturing - delegates to the full transformerBlock method.
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention layer
	 * @param contextSeqLen Context sequence length (for cross-attention)
	 * @param context Context input block (for cross-attention)
	 * @param preNormWeight Self-attention pre-normalization weights
	 * @param preNormBias Self-attention pre-normalization biases
	 * @param selfQkv Self-attention QKV projection weights
	 * @param selfWo Self-attention output projection weights
	 * @param selfQNormWeight Self-attention Q normalization weights
	 * @param selfQNormBias Self-attention Q normalization biases
	 * @param selfKNormWeight Self-attention K normalization weights
	 * @param selfKNormBias Self-attention K normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @param crossAttPreNormWeight Cross-attention pre-normalization weights
	 * @param crossAttPreNormBias Cross-attention pre-normalization biases
	 * @param crossWq Cross-attention Q projection weights
	 * @param crossKv Cross-attention KV projection weights
	 * @param crossWo Cross-attention output projection weights
	 * @param crossQNormWeight Cross-attention Q normalization weights
	 * @param crossQNormBias Cross-attention Q normalization biases
	 * @param crossKNormWeight Cross-attention K normalization weights
	 * @param crossKNormBias Cross-attention K normalization biases
	 * @param ffnNormWeight Feed-forward pre-normalization weights
	 * @param ffnNormBias Feed-forward pre-normalization biases
	 * @param w1 Feed-forward gate projection weights
	 * @param w2 Feed-forward output projection weights
	 * @param w1Bias Feed-forward gate projection bias
	 * @param w2Bias Feed-forward output projection bias
	 * @return Complete transformer block
	 */
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

	/**
	 * Creates a complete transformer block with self-attention, optional cross-attention, and feed-forward.
	 * This is the full version that supports capturing attention scores via a Receptor.
	 *
	 * <p>The transformer block structure:
	 * <pre>
	 * x = x + self_attn(norm(x))
	 * x = x + cross_attn(norm(x), context)  [optional]
	 * x = x + ffn(norm(x))
	 * </pre></p>
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention layer
	 * @param contextSeqLen Context sequence length (for cross-attention)
	 * @param context Context input block (for cross-attention)
	 * @param preNormWeight Self-attention pre-normalization weights
	 * @param preNormBias Self-attention pre-normalization biases
	 * @param selfQkv Self-attention QKV projection weights
	 * @param selfWo Self-attention output projection weights
	 * @param selfQNormWeight Self-attention Q normalization weights
	 * @param selfQNormBias Self-attention Q normalization biases
	 * @param selfKNormWeight Self-attention K normalization weights
	 * @param selfKNormBias Self-attention K normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @param crossAttPreNormWeight Cross-attention pre-normalization weights
	 * @param crossAttPreNormBias Cross-attention pre-normalization biases
	 * @param crossWq Cross-attention Q projection weights
	 * @param crossKv Cross-attention KV projection weights
	 * @param crossWo Cross-attention output projection weights
	 * @param crossQNormWeight Cross-attention Q normalization weights
	 * @param crossQNormBias Cross-attention Q normalization biases
	 * @param crossKNormWeight Cross-attention K normalization weights
	 * @param crossKNormBias Cross-attention K normalization biases
	 * @param ffnNormWeight Feed-forward pre-normalization weights
	 * @param ffnNormBias Feed-forward pre-normalization biases
	 * @param w1 Feed-forward gate projection weights
	 * @param w2 Feed-forward output projection weights
	 * @param w1Bias Feed-forward gate projection bias
	 * @param w2Bias Feed-forward output projection bias
	 * @param attentionScores Optional receptor to capture cross-attention scores
	 * @return Complete transformer block
	 */
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
				null, null, null, null, null, freqCis, rmsFfnWeight, w1, w2, w3, position, requirements);
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
							  PackedCollection<?> bk, PackedCollection<?> bv,
							  PackedCollection<?> bq,
							  PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,
							  PackedCollection<?> freqCis,
							  PackedCollection<?> rmsFfnWeight,
							  PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
							  Producer<PackedCollection<?>> position,
							  ComputeRequirement... requirements) {
		int dim = rmsAttWeight.getShape().length(0);
		SequentialBlock transformer = new SequentialBlock(shape(dim));
		transformer.accum(attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, qkNormQ, qkNormK, freqCis, position, requirements), requirements);
		transformer.accum(feedForward(rmsFfnWeight, w1, w2, w3, requirements), requirements);
		return transformer;
	}

	/**
	 * Creates a context layer function for linear attention mechanisms.
	 * Computes the context matrix used in linear attention variants.
	 *
	 * @param v Value block
	 * @param batchSize Batch dimension
	 * @param heads Number of attention heads
	 * @param dimHead Dimension per head
	 * @param size Spatial size (rows * cols)
	 * @return Context layer function
	 */
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

	/**
	 * Creates a linear attention function for image/spatial inputs.
	 * Linear attention has O(n) complexity compared to O(n^2) for standard attention.
	 *
	 * @param dim Output dimension
	 * @return Function that creates linear attention block from input shape
	 */
	default Function<TraversalPolicy, Block> linearAttention(int dim) {
		return shape -> {
			int batchSize = shape.length(0);
			int inputChannels = shape.length(1);
			int rows = shape.length(2);
			int cols = shape.length(3);
			return linearAttention(batchSize, dim, inputChannels, rows, cols);
		};
	}

	/**
	 * Creates a linear attention block with default head configuration (4 heads, 32 dim per head).
	 *
	 * @param batchSize Batch dimension
	 * @param dim Output dimension
	 * @param inputChannels Input channels
	 * @param rows Spatial height
	 * @param cols Spatial width
	 * @return Linear attention block
	 */
	default Block linearAttention(int batchSize, int dim, int inputChannels, int rows, int cols) {
		return linearAttention(batchSize, dim, 4, 32, inputChannels, rows, cols);
	}

	/**
	 * Creates a linear attention block with configurable head dimensions.
	 *
	 * <p>Linear attention uses a linear kernel feature map to approximate attention,
	 * reducing complexity from O(n^2) to O(n). This is particularly useful for
	 * high-resolution spatial inputs where standard attention would be prohibitive.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param dim Output dimension
	 * @param heads Number of attention heads
	 * @param dimHead Dimension per head
	 * @param inputChannels Input channels
	 * @param rows Spatial height
	 * @param cols Spatial width
	 * @return Linear attention block
	 */
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

	/**
	 * Returns a default instance of AttentionFeatures.
	 * Useful for static access to attention mechanisms without implementing the interface.
	 *
	 * @return A new AttentionFeatures instance
	 */
	static AttentionFeatures getInstance() {
		return new AttentionFeatures() { };
	}
}