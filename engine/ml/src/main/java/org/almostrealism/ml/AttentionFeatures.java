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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *             PackedCollection wq = weights.get("layers." + layer + ".self_attn.q_proj.weight");
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
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @see RotationFeatures
 * @see org.almostrealism.layers.LayerFeatures
 * @see org.almostrealism.model.Block
 */
public interface AttentionFeatures extends SequenceAttentionFeatures {

	/**
	 * Creates a layer that reshapes input for split-half RoPE format.
	 *
	 * <p>Transforms from flat dimension layout to (heads, freqDim, 2) where:
	 * <ul>
	 *   <li>Dimension 2 index 0 contains first half of each head (elements 0 to headSize/2-1)</li>
	 *   <li>Dimension 2 index 1 contains second half of each head (elements headSize/2 to headSize-1)</li>
	 * </ul>
	 *
	 * <p>This is the format expected by PyTorch's Qwen/Llama RoPE implementation.</p>
	 *
	 * <p>The permutation is {@code output[h, f, 0] = input[h * headSize + f]} and
	 * {@code output[h, f, 1] = input[h * headSize + freqDim + f]}, expressed as arithmetic
	 * over the output index so that the gather indices are computed by the graph.</p>
	 *
	 * @param flatDim Input dimension (heads * headSize)
	 * @param heads Number of attention heads
	 * @param headSize Size of each head (must be even)
	 * @return CellularLayer that transforms to split-half format
	 */
	default CellularLayer reshapeToSplitHalfRope(int flatDim, int heads, int headSize) {
		if (headSize <= 0 || headSize % 2 != 0) {
			throw new IllegalArgumentException("headSize must be a positive even number, got " + headSize);
		}

		int freqDim = headSize / 2;
		TraversalPolicy inputShape = shape(1, flatDim);
		TraversalPolicy outputShape = shape(heads, freqDim, 2);

		CollectionProducer index = integers(0, heads * freqDim * 2);
		CollectionProducer head = floor(index.divide(freqDim * 2));
		CollectionProducer withinHead = index.mod(freqDim * 2);
		CollectionProducer freq = floor(withinHead.divide(2.0));
		CollectionProducer half = withinHead.mod(2.0);

		return gather("reshapeToSplitHalfRope", inputShape, outputShape,
				head.multiply(headSize).add(half.multiply(freqDim)).add(freq));
	}

	/**
	 * Creates a layer that reshapes from split-half RoPE format back to flat dimension.
	 *
	 * <p>Transforms from (heads, freqDim, 2) back to (flatDim) where elements are
	 * interleaved per head as [firstHalf, secondHalf].</p>
	 *
	 * <p>The permutation is {@code output[h, f] = input[h, f, 0]} for the first half and
	 * {@code output[h, freqDim + f] = input[h, f, 1]} for the second, expressed as arithmetic
	 * over the output index so that the gather indices are computed by the graph.</p>
	 *
	 * @param heads Number of attention heads
	 * @param headSize Size of each head (must be even)
	 * @return CellularLayer that transforms from split-half format to flat
	 */
	default CellularLayer reshapeFromSplitHalfRope(int heads, int headSize) {
		if (headSize <= 0 || headSize % 2 != 0) {
			throw new IllegalArgumentException("headSize must be a positive even number, got " + headSize);
		}

		int freqDim = headSize / 2;
		TraversalPolicy inputShape = shape(heads, freqDim, 2);
		TraversalPolicy outputShape = shape(heads, headSize);

		CollectionProducer index = integers(0, heads * headSize);
		CollectionProducer head = floor(index.divide(headSize));
		CollectionProducer withinHead = index.mod(headSize);
		CollectionProducer half = floor(withinHead.divide(freqDim));
		CollectionProducer freq = withinHead.mod(freqDim);

		return gather("reshapeFromSplitHalfRope", inputShape, outputShape,
				head.multiply(freqDim * 2).add(freq.multiply(2.0)).add(half));
	}

	/**
	 * Creates an attention keys layer function that can be applied to different input shapes.
	 *
	 * @param keys The key tensor producer (seqLength, kvHeads, headSize)
	 * @param requirements Compute requirements
	 * @return A function that creates an attention keys layer for a given input shape
	 */
	default Function<TraversalPolicy, CellularLayer> attentionKeys(Producer<PackedCollection> keys,
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
										Producer<PackedCollection> keys,
										ComputeRequirement... requirements) {
		TraversalPolicy keyShape = shape(keys); // (seqLength, kvHeads, headSize)

		if (inputShape.getDimensions() != 2 || keyShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = inputShape.length(1);

		int seqLength = keyShape.length(0);
		int kvHeads = keyShape.length(1);
		TraversalPolicy outputShape = shape(heads, seqLength).traverseEach();

		if (keyShape.length(2) != headSize)
			throw new IllegalArgumentException("Key head size mismatch");

		// Handle Grouped Query Attention (GQA)
		if (kvHeads != heads && heads % kvHeads != 0) {
			throw new IllegalArgumentException("heads must be divisible by kvHeads for GQA");
		}

		int headsPerKvGroup = heads / kvHeads;
		int kvDim = kvHeads * headSize;

		if (kvHeads == heads) {
			// No GQA: the query is repeated once per cached row explicitly, for the reason
			// given in attentionScores (a (heads, headSize) query is a prefix of the
			// (seqLength, heads, headSize) cache whenever heads == seqLength).
			return layer("attentionKeys", inputShape, outputShape, input -> {
				Producer<PackedCollection> query = repeat(seqLength, reshape(shape(heads, headSize), input));
				return multiply(traverseEach(keys), traverseEach(query))
						.traverse(2).sum()
						.divide(c(Math.sqrt(headSize)))
						.reshape(shape(seqLength, heads))
						.enumerate(1, 1)
						.reshape(outputShape);
			}, requirements);
		} else {
			// GQA: Compact keys (seqLen, kvHeads, headSize), Query (heads, headSize)
			// Compute attention scores per kvHead group using subset operations
			// This avoids traverse().repeat() which causes compilation issues

			return layer("attentionKeysGQA", inputShape, outputShape, input -> {
				// For each kvHead, compute attention scores for its query head group
				List<CollectionProducer> groupScores = new ArrayList<>();

				for (int kv = 0; kv < kvHeads; kv++) {
					// Extract query slice: (headsPerKvGroup, headSize)
					CollectionProducer queryGroup = c(input)
							.subset(shape(headsPerKvGroup, headSize), kv * headsPerKvGroup, 0);

					// Extract keys for this kvHead: (seqLen, headSize)
					int keyOffset = kv * headSize;
					CollectionProducer keysForKv = c(keys)
							.reshape(shape(seqLength, kvDim))
							.subset(shape(seqLength, headSize), 0, keyOffset);

					// Compute scores per query head in this group
					List<CollectionProducer> headScores = new ArrayList<>();
					for (int g = 0; g < headsPerKvGroup; g++) {
						// Extract single query: (headSize)
						CollectionProducer query = queryGroup.subset(shape(1, headSize), g, 0)
								.reshape(shape(headSize));

						// Compute dot product with all keys: query @ keysForKv^T
						// query: (headSize), keysForKv: (seqLen, headSize)
						// output: (seqLen)
						CollectionProducer dotProducts = traverse(1, keysForKv)
								.multiply(query)
								.sum()
								.divide(c(Math.sqrt(headSize)));

						// dotProducts: (seqLen)
						headScores.add(dotProducts.reshape(shape(1, seqLength)));
					}

					// Concatenate head scores: (headsPerKvGroup, seqLen)
					CollectionProducer kvScores = concat(0, headScores.toArray(new CollectionProducer[0]));
					groupScores.add(kvScores);
				}

				// Concatenate all group scores: (heads, seqLen)
				CollectionProducer allScores = concat(0, groupScores.toArray(new CollectionProducer[0]));

				return allScores.reshape(outputShape);
			}, requirements);
		}
	}

	/**
	 * Creates a GQA expansion layer for per-position KV data.
	 *
	 * <p>Expands from (1, kvDim) to (1, dim) by duplicating each KV head's data
	 * headsPerKvGroup times: {@link org.almostrealism.layers.LayerFeatures#repeatEach repeatEach}
	 * over runs of {@code headSize} elements, with the input and output kept as one
	 * per-position vector each.</p>
	 *
	 * <p>For Qwen3 with heads=14, kvHeads=2 (7:1 ratio):
	 * - Input: (1, 128) = (1, 2 * 64)
	 * - Output: (1, 896) = (1, 14 * 64)
	 * - kvHead[0] data -> queryHeads[0..6]
	 * - kvHead[1] data -> queryHeads[7..13]</p>
	 *
	 * @param kvDim Input dimension (kvHeads * headSize)
	 * @param dim Output dimension (heads * headSize)
	 * @param kvHeads Number of KV heads
	 * @param heads Number of query heads
	 * @param headSize Size per head
	 * @param requirements Compute requirements
	 * @return CellularLayer that expands KV data for GQA
	 */
	default CellularLayer gqaExpand(int kvDim, int dim, int kvHeads, int heads, int headSize,
									ComputeRequirement... requirements) {
		if (kvHeads == heads) {
			// No expansion needed - identity layer
			return layer("gqa_identity", shape(1, kvDim), shape(1, dim),
					input -> c(input), requirements);
		}

		return repeatEach(shape(1, kvDim), shape(1, dim), headSize, heads / kvHeads, requirements);
	}

	/**
	 * Creates an attention values layer function that can be applied to different input shapes.
	 *
	 * @param values The value tensor producer (seqLength, kvHeads, headSize)
	 * @param requirements Compute requirements
	 * @return A function that creates an attention values layer for a given input shape
	 */
	default Function<TraversalPolicy, CellularLayer> attentionValues(Producer<PackedCollection> values,
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
	 * @return Attention values layer producing (1, dim) output to preserve batch dimension
	 */
	default CellularLayer attentionValues(TraversalPolicy inputShape,
										  Producer<PackedCollection> values,
										  ComputeRequirement... requirements) {
		TraversalPolicy valueShape = shape(values); // (seqLength, kvHeads, headSize)

		if (inputShape.getDimensions() != 2 || valueShape.getDimensions() != 3)
			throw new IllegalArgumentException();

		int heads = inputShape.length(0);
		int headSize = valueShape.length(2);
		int dim = heads * headSize;

		int seqLength = inputShape.length(1);
		int kvHeads = valueShape.length(1);

		// Preserve batch dimension in output shape for consistency with other layers
		TraversalPolicy outputShape = shape(1, dim);

		if (valueShape.length(0) != seqLength)
			throw new IllegalArgumentException("Value sequence length mismatch");

		// Handle Grouped Query Attention (GQA)
		if (kvHeads != heads && heads % kvHeads != 0) {
			throw new IllegalArgumentException("heads must be divisible by kvHeads for GQA");
		}

		int headsPerKvGroup = heads / kvHeads;

		if (kvHeads == heads) {
			// No GQA - use simple attention
			return layer("attentionValues", inputShape, outputShape, input -> {
				Producer<PackedCollection> v = reshape(shape(seqLength, dim), values);
				v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

				CollectionProducer a = traverse(1, input).repeat(headSize);
				CollectionProducer o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();
				return o.reshape(shape(1, dim).traverseEach());
			}, requirements);
		} else {
			// GQA: Compact values (seqLen, kvHeads, headSize), Attention (heads, seqLen)
			// Compute weighted values per kvHead group using subset operations
			// This avoids traverse().repeat() which causes compilation issues

			int kvDim = kvHeads * headSize;

			return layer("attentionValuesGQA", inputShape, outputShape, input -> {
				// For each kvHead, compute weighted values for its query head group
				List<CollectionProducer> groupOutputs = new ArrayList<>();

				for (int kv = 0; kv < kvHeads; kv++) {
					// Extract attention slice: (headsPerKvGroup, seqLen)
					CollectionProducer attnGroup = c(input)
							.reshape(shape(heads, seqLength))
							.subset(shape(headsPerKvGroup, seqLength), kv * headsPerKvGroup, 0);

					// Extract values for this kvHead: (seqLen, headSize)
					int valOffset = kv * headSize;
					CollectionProducer valuesForKv = c(values)
							.reshape(shape(seqLength, kvDim))
							.subset(shape(seqLength, headSize), 0, valOffset);

					// Compute weighted sum: attnGroup @ valuesForKv
					// attnGroup: (headsPerKvGroup, seqLen)
					// valuesForKv: (seqLen, headSize)
					// output: (headsPerKvGroup, headSize)

					// For each query head in this group, compute weighted sum independently
					List<CollectionProducer> headOutputs = new ArrayList<>();
					for (int g = 0; g < headsPerKvGroup; g++) {
						// Extract attention for this head: (seqLen)
						CollectionProducer attnHead = attnGroup.subset(shape(1, seqLength), g, 0)
								.reshape(shape(seqLength));

						// Compute weighted sum: sum_s(attn[s] * values[s, :])
						// attnHead: (seqLen), valuesForKv: (seqLen, headSize)
						// Reshape attnHead to (seqLen, 1) for broadcasting
						CollectionProducer attnCol = attnHead.reshape(shape(seqLength, 1));

						// Multiply: (seqLen, 1) * (seqLen, headSize) = (seqLen, headSize) with broadcast
						CollectionProducer weighted = multiply(attnCol, valuesForKv);

						// Transpose to (headSize, seqLen) and sum over seqLen
						CollectionProducer weightedT = weighted.enumerate(1, 1)
								.reshape(shape(headSize, seqLength));
						CollectionProducer headOut = weightedT.traverse(1).sum();
						// headOut: (headSize)

						headOutputs.add(headOut.reshape(shape(1, headSize)));
					}

					// Concatenate head outputs: (headsPerKvGroup, headSize)
					CollectionProducer groupOut = concat(0, headOutputs.toArray(new CollectionProducer[0]));
					groupOutputs.add(groupOut);
				}

				// Concatenate all group outputs: (heads, headSize)
				CollectionProducer allOutputs = concat(0, groupOutputs.toArray(new CollectionProducer[0]));

				return allOutputs.reshape(shape(1, dim).traverseEach());
			}, requirements);
		}
	}

	/**
	 * Attention scores of one query per head against every row of a key cache.
	 *
	 * <p>The input holds one query per head, {@code (heads, headSize)}; the cache holds one
	 * key per head for every position, {@code (seqLength, heads * headSize)} or
	 * {@code (seqLength, heads, headSize)}. The result is
	 * {@code scores[h, s] = q_h · k_h[s]}, the unscaled dot products: dividing by
	 * {@code sqrt(headSize)}, masking and softmax are separate stages, so each can be
	 * seen where the attention structure is described. The query is repeated once per cached
	 * row, multiplied element-wise with the cache and reduced over {@code headSize}, giving
	 * {@code (seqLength, heads)}, and the transpose orders the scores per head.</p>
	 *
	 * <p>The repetition is explicit rather than left to the broadcast of
	 * {@code multiply(cache, query)}: that broadcast matches leading dimensions, so when the
	 * head count equals the sequence length the {@code (heads, headSize)} query is a prefix of
	 * the {@code (seqLength, heads, headSize)} cache and every query element would be spread
	 * across a run of cache elements instead of the whole query being tiled across the rows.</p>
	 *
	 * @param inputShape Shape of the query input (heads, headSize)
	 * @param keys Key cache with {@code seqLength * heads * headSize} elements
	 * @param requirements Compute requirements
	 * @return Layer producing the (heads, seqLength) scores
	 * @throws IllegalArgumentException if the cache does not hold whole rows of the query size
	 */
	default CellularLayer attentionScores(TraversalPolicy inputShape,
										  Producer<PackedCollection> keys,
										  ComputeRequirement... requirements) {
		if (inputShape.getDimensions() != 2)
			throw new IllegalArgumentException("Expected query (heads, headSize), got " + inputShape);

		int heads = inputShape.length(0);
		int headSize = inputShape.length(1);
		int total = shape(keys).getTotalSize();
		if (total % (heads * headSize) != 0)
			throw new IllegalArgumentException("Key cache of " + total
					+ " elements does not hold whole rows of " + heads + " heads of " + headSize);

		int seqLength = total / (heads * headSize);
		TraversalPolicy cacheShape = shape(seqLength, heads, headSize);
		TraversalPolicy outputShape = shape(heads, seqLength).traverseEach();

		return layer("attentionScores", inputShape, outputShape, input -> {
			Producer<PackedCollection> cache = reshape(cacheShape, keys);
			Producer<PackedCollection> query = repeat(seqLength, reshape(shape(heads, headSize), input));
			CollectionProducer products = multiply(traverseEach(cache), traverseEach(query));
			return permute(products.traverse(2).sum().reshape(shape(seqLength, heads)), 1, 0)
					.reshape(outputShape);
		}, requirements);
	}

	/**
	 * Additive causal mask for single-query attention scores: every column after the current
	 * position receives {@code -10000}, so its softmax weight underflows to zero, and every
	 * column up to and including the position is left unchanged. One {@code (1, seqLength)}
	 * row is broadcast to every head; the broadcast layout is the one pinned by
	 * {@code CausalMaskIsolationTest}.
	 *
	 * @param shape Shape of the scores, (heads, seqLength)
	 * @param position Producer of the current position, shape (1)
	 * @param requirements Compute requirements
	 * @return Layer adding the mask to the scores
	 */
	default CellularLayer causalMask(TraversalPolicy shape, Producer<PackedCollection> position,
									 ComputeRequirement... requirements) {
		if (shape.getDimensions() != 2)
			throw new IllegalArgumentException("Expected scores (heads, seqLength), got " + shape);

		int heads = shape.length(0);
		int seqLength = shape.length(1);
		TraversalPolicy each = shape(heads, seqLength).traverseEach();

		CollectionProducer indices = integers(0, seqLength);
		CollectionProducer maskRow = greaterThan(indices, position, c(-10000.0), c(0.0), false);
		CollectionProducer mask = maskRow.reshape(1, 1, seqLength).repeat(heads);
		return layer("causalMask", shape, each, input -> add(reshape(each, input), mask), requirements);
	}

	/**
	 * Weighted sum of a value cache by per-head attention weights.
	 *
	 * <p>The input holds one distribution over positions per head, {@code (heads, seqLength)};
	 * the cache holds one value per head for every position, {@code (seqLength, heads * headSize)}
	 * or {@code (seqLength, heads, headSize)}. The result is
	 * {@code output[h * headSize + i] = sum_s weights[h, s] * values[s, h, i]}, returned as
	 * {@code (1, heads * headSize)} so it feeds the output projection directly. The cache is
	 * transposed to {@code (heads, headSize, seqLength)} and the weights expanded to the same
	 * shape, so their product reduces over the sequence axis.</p>
	 *
	 * @param inputShape Shape of the attention weights (heads, seqLength)
	 * @param values Value cache with {@code seqLength * heads * headSize} elements
	 * @param requirements Compute requirements
	 * @return Layer producing the (1, heads * headSize) context
	 * @throws IllegalArgumentException if the cache does not hold {@code seqLength} whole rows
	 */
	default CellularLayer weightedValues(TraversalPolicy inputShape,
										 Producer<PackedCollection> values,
										 ComputeRequirement... requirements) {
		if (inputShape.getDimensions() != 2)
			throw new IllegalArgumentException("Expected attention weights (heads, seqLength), got " + inputShape);

		int heads = inputShape.length(0);
		int seqLength = inputShape.length(1);
		int total = shape(values).getTotalSize();
		if (total % (seqLength * heads) != 0)
			throw new IllegalArgumentException("Value cache of " + total
					+ " elements does not hold " + seqLength + " rows of " + heads + " heads");

		int headSize = total / (seqLength * heads);
		int dim = heads * headSize;
		TraversalPolicy outputShape = shape(1, dim);

		return layer("weightedValues", inputShape, outputShape, input -> {
			Producer<PackedCollection> v = reshape(shape(seqLength, dim), values);
			v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

			CollectionProducer a = traverse(1, input).repeat(headSize);
			CollectionProducer o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();

			return o.reshape(shape(1, dim).traverseEach());
		}, requirements);
	}

	/**
	 * Standard multi-head attention without QK-Norm or GQA.
	 * Delegates to the full attention method with null optional parameters.
	 */
	default Block attention(int heads,
							PackedCollection rmsAttWeight,
							PackedCollection wk, PackedCollection wv,
							PackedCollection wq, PackedCollection wo,
							CollectionProducer freqCis,
							Producer<PackedCollection> position,
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
							PackedCollection rmsAttWeight,
							PackedCollection wk, PackedCollection wv,
							PackedCollection wq, PackedCollection wo,
							PackedCollection bk, PackedCollection bv,
							PackedCollection bq,
							PackedCollection qkNormQ, PackedCollection qkNormK,
							CollectionProducer freqCis,
							Producer<PackedCollection> position,
							ComputeRequirement... requirements) {
		return attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo, bk, bv, bq,
				qkNormQ, qkNormK, freqCis, position, 1e-5, requirements);
	}

	/**
	 * Multi-head attention with optional QK-Norm, GQA, and configurable RMSNorm epsilon.
	 *
	 * <p>The structure is the {@code attention} layer of {@link #ATTENTION_ASSET} (or
	 * {@code attention_qk_norm} when normalization weights are given); this method only
	 * allocates the key and value caches, binds the arguments and builds the layer. QK-Norm
	 * weights are {@code (heads, headSize)} and {@code (kvHeads, headSize)}: each head is
	 * normalized by its own root mean square, as in Qwen3.</p>
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
	 * @param epsilon RMSNorm epsilon (e.g., 1e-5 for Llama, 1e-6 for Qwen3)
	 * @param requirements compute requirements, applied to every layer the attention asset builds
	 * @return Attention block
	 * @throws IllegalArgumentException if only one of the QK-Norm weights is given
	 */
	default Block attention(int heads, int kvHeads,
							PackedCollection rmsAttWeight,
							PackedCollection wk, PackedCollection wv,
							PackedCollection wq, PackedCollection wo,
							PackedCollection bk, PackedCollection bv,
							PackedCollection bq,
							PackedCollection qkNormQ, PackedCollection qkNormK,
							CollectionProducer freqCis,
							Producer<PackedCollection> position,
							double epsilon,
							ComputeRequirement... requirements) {
		return attentionLayer(qkNormQ == null ? "attention" : "attention_qk_norm",
				attentionArguments(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
						bk, bv, bq, qkNormQ, qkNormK, freqCis, position, epsilon),
				requirements);
	}

	/**
	 * Attention with per-head-group rotary embeddings (Multidimensional Relative Attention).
	 *
	 * <p>Unlike standard attention which applies a single RoPE uniformly to all heads,
	 * MRA partitions heads into groups and applies per-group RoPE with different theta
	 * values and attribute-derived position IDs. This is the key architectural novelty
	 * of the Moonbeam MIDI Foundation Model.</p>
	 *
	 * <p>The flow is identical to standard attention except for the RoPE application:</p>
	 * <ol>
	 *   <li>Q/K projection (standard)</li>
	 *   <li>Split Q/K into head groups along head dimension</li>
	 *   <li>Apply per-group RoPE with the group's precomputed freqCis and position</li>
	 *   <li>Concatenate rotated groups back</li>
	 *   <li>Standard scaled dot-product attention (unchanged)</li>
	 * </ol>
	 *
	 * @param heads total number of query attention heads
	 * @param kvHeads number of key/value heads (for GQA)
	 * @param rmsAttWeight pre-attention RMSNorm weights
	 * @param wk key projection weights
	 * @param wv value projection weights
	 * @param wq query projection weights
	 * @param wo output projection weights
	 * @param headGroups per-head-group RoPE configuration (freqCis + position per group)
	 * @param position sequential position for KV cache indexing and causal masking
	 * @param epsilon RMSNorm epsilon
	 * @param requirements compute requirements, applied to every layer the attention asset builds
	 * @return attention block with MRA
	 */
	default Block attention(int heads, int kvHeads,
							PackedCollection rmsAttWeight,
							PackedCollection wk, PackedCollection wv,
							PackedCollection wq, PackedCollection wo,
							HeadGroupConfig[] headGroups,
							Producer<PackedCollection> position,
							double epsilon,
							ComputeRequirement... requirements) {
		return attentionLayer("attention_mra",
				attentionArguments(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo, headGroups, position, epsilon),
				requirements);
	}

	/**
	 * Classpath location of the asset describing the autoregressive attention structure. Its
	 * {@code attention}, {@code attention_qk_norm} and {@code attention_mra} layers are what the
	 * {@link #attention} methods build; the asset is the single definition of how the token
	 * vector flows through normalization, projections, rotation, the KV cache, scores, mask,
	 * softmax, weighted values and the output projection.
	 */
	String ATTENTION_ASSET = "/pdsl/attention.pdsl";

	/**
	 * Binds the arguments every layer of {@link #ATTENTION_ASSET} takes: the head geometry,
	 * the normalization and projection weights, the position, the RMSNorm epsilon, and a
	 * freshly allocated key cache and value cache of {@code (seqLen, heads * headSize)} rows —
	 * the state the block writes on every forward pass and reads on every later one.
	 *
	 * @param heads number of query heads
	 * @param kvHeads number of key/value heads
	 * @param rmsAttWeight pre-attention RMSNorm weights, whose length is the model dimension
	 * @param wk key projection weights
	 * @param wv value projection weights
	 * @param wq query projection weights
	 * @param wo output projection weights
	 * @param seqLen number of cache rows (the maximum sequence length)
	 * @param position producer of the current position
	 * @param epsilon RMSNorm epsilon
	 * @return the argument bindings, to be completed with the layer-specific weights
	 * @throws IllegalArgumentException if the head count is not positive, the model dimension
	 *         is not a multiple of the head count, or the query head count is not a positive
	 *         multiple of the KV head count
	 */
	default Map<String, Object> attentionArguments(int heads, int kvHeads,
												   PackedCollection rmsAttWeight,
												   PackedCollection wk, PackedCollection wv,
												   PackedCollection wq, PackedCollection wo,
												   int seqLen, Producer<PackedCollection> position,
												   double epsilon) {
		int dim = rmsAttWeight.getShape().length(0);
		if (heads <= 0) {
			throw new IllegalArgumentException("Heads must be positive, got " + heads);
		} else if (dim % heads != 0) {
			throw new IllegalArgumentException("Model dimension " + dim
					+ " is not a multiple of " + heads + " heads");
		} else if (kvHeads <= 0 || heads % kvHeads != 0) {
			throw new IllegalArgumentException(heads + " query heads is not a positive multiple of "
					+ kvHeads + " KV heads");
		}

		PackedCollection keyCache = new PackedCollection(shape(seqLen, dim));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, dim));
		keyCache.clear();
		valueCache.clear();

		Map<String, Object> args = new HashMap<>();
		args.put("heads", heads);
		args.put("kv_heads", kvHeads);
		args.put("head_size", dim / heads);
		args.put("rms_att_weight", rmsAttWeight);
		args.put("wq", wq);
		args.put("wk", wk);
		args.put("wv", wv);
		args.put("wo", wo);
		args.put("position", position);
		args.put("epsilon", epsilon);
		args.put("key_cache", keyCache);
		args.put("value_cache", valueCache);
		return args;
	}

	/**
	 * Binds the arguments of the {@code attention} layer of {@link #ATTENTION_ASSET}, or of its
	 * {@code attention_qk_norm} layer when QK-Norm weights are given, for every layer that contains
	 * that attention stage: the common bindings and caches of the overload above, completed with
	 * the projection biases ({@code null} when the model has none), the rotary frequency table
	 * (one row per cache row) and the {@code (heads, headSize)} and {@code (kvHeads, headSize)}
	 * QK-Norm weights ({@code null} without QK-Norm). Parameters are as in {@link #attention}.
	 *
	 * @return the argument bindings
	 * @throws IllegalArgumentException if only one of the QK-Norm weights is given, or the head
	 *         geometry is invalid
	 */
	default Map<String, Object> attentionArguments(int heads, int kvHeads,
												   PackedCollection rmsAttWeight,
												   PackedCollection wk, PackedCollection wv,
												   PackedCollection wq, PackedCollection wo,
												   PackedCollection bk, PackedCollection bv,
												   PackedCollection bq,
												   PackedCollection qkNormQ, PackedCollection qkNormK,
												   CollectionProducer freqCis,
												   Producer<PackedCollection> position,
												   double epsilon) {
		if ((qkNormQ == null) != (qkNormK == null)) {
			throw new IllegalArgumentException("QK-Norm requires both query and key weights");
		}

		Map<String, Object> args = attentionArguments(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				freqCis.getShape().length(0), position, epsilon);
		args.put("bq", bq);
		args.put("bk", bk);
		args.put("bv", bv);
		args.put("freq_cis", freqCis);
		if (qkNormQ != null) {
			args.put("qk_norm_q", qkNormQ);
			args.put("qk_norm_k", qkNormK);
		}
		return args;
	}

	/**
	 * Binds the arguments of the {@code attention_mra} layer of {@link #ATTENTION_ASSET}, for every
	 * layer that contains that attention stage: the common bindings and caches, completed with the
	 * query head groups and the KV head groups derived from them. The first group's frequency
	 * table sets the cache length. Parameters are as in the head-group {@link #attention}.
	 *
	 * @return the argument bindings
	 */
	default Map<String, Object> attentionArguments(int heads, int kvHeads,
												   PackedCollection rmsAttWeight,
												   PackedCollection wk, PackedCollection wv,
												   PackedCollection wq, PackedCollection wo,
												   HeadGroupConfig[] headGroups,
												   Producer<PackedCollection> position,
												   double epsilon) {
		Map<String, Object> args = attentionArguments(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				headGroups[0].freqCis.getShape().length(0), position, epsilon);
		args.put("q_head_groups", headGroups);
		args.put("kv_head_groups", HeadGroupConfig.forKvHeads(headGroups, heads / kvHeads));
		return args;
	}

	/**
	 * Builds one layer of {@link #ATTENTION_ASSET} for a {@code (1, dim)} token vector.
	 *
	 * @param layer the layer name: {@code attention}, {@code attention_qk_norm} or
	 *              {@code attention_mra}
	 * @param args the bindings from {@link #attentionArguments}, completed with the
	 *             layer's own weights
	 * @param requirements compute requirements, applied to every layer {@link #ATTENTION_ASSET}
	 *                     constructs for {@code layer}
	 * @return the attention block
	 */
	default Block attentionLayer(String layer, Map<String, Object> args, ComputeRequirement... requirements) {
		int dim = ((PackedCollection) args.get("rms_att_weight")).getShape().length(0);
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(ATTENTION_ASSET), layer, shape(1, dim), args, requirements);
	}

	/**
	 * Classpath location of the asset describing the pre-norm transformer layer: a residual
	 * attention stage followed by a residual SwiGLU feed-forward stage. Its {@code transformer},
	 * {@code transformer_qk_norm} and {@code transformer_mra} layers are what the
	 * {@link #transformer} methods build. They call the layers of {@link #ATTENTION_ASSET} and
	 * {@link #FEED_FORWARD_ASSET}, which this asset imports, so parsing it alone resolves all
	 * three into one program.
	 */
	String TRANSFORMER_ASSET = "/pdsl/transformer.pdsl";

	/**
	 * Builds one layer of {@link #TRANSFORMER_ASSET} for a {@code (1, dim)} token vector. That
	 * asset imports {@link #ATTENTION_ASSET} and {@link #FEED_FORWARD_ASSET}, whose layers it
	 * composes, so parsing it alone yields the program that holds all three.
	 *
	 * @param layer the layer name: {@code transformer}, {@code transformer_qk_norm} or
	 *              {@code transformer_mra}
	 * @param attentionArgs the bindings of the layer's attention stage, from
	 *                      {@link #attentionArguments}
	 * @param rmsFfnWeight pre-FFN RMSNorm weights
	 * @param w1 FFN gate projection weights, shape {@code [hidden_dim, dim]}
	 * @param w2 FFN down projection weights, shape {@code [dim, hidden_dim]}
	 * @param w3 FFN up projection weights, shape {@code [hidden_dim, dim]}
	 * @param requirements compute requirements, applied to every layer the assets construct for
	 *                     {@code layer}
	 * @return the transformer layer block
	 */
	default Block transformerLayer(String layer, Map<String, Object> attentionArgs,
								   PackedCollection rmsFfnWeight,
								   PackedCollection w1, PackedCollection w2, PackedCollection w3,
								   ComputeRequirement... requirements) {
		Map<String, Object> args = new HashMap<>(attentionArgs);
		args.put("rms_ffn_weight", rmsFfnWeight);
		args.put("w1", w1);
		args.put("w2", w2);
		args.put("w3", w3);

		int dim = ((PackedCollection) args.get("rms_att_weight")).getShape().length(0);
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(TRANSFORMER_ASSET),
				layer, shape(1, dim), args, requirements);
	}

	/**
	 * Creates the self-attention sub-block for the requested {@link AttentionVariant}.
	 *
	 * <p>This is the attention-variant seam threaded through
	 * {@link TransformerBlockFeatures#transformerBlock}. The base implementation supports
	 * {@link AttentionVariant#STANDARD} only, delegating to {@link #sequenceAttention} so the
	 * default path is unchanged. Alternative variants are provided by sub-interfaces that override
	 * the fully specified overload (for example {@link DifferentialAttentionFeatures}); because the
	 * call site in the block builder is a virtual dispatch, a consumer that implements such a
	 * sub-interface automatically obtains the variant without forking the block builder.</p>
	 *
	 * @param batchSize         batch dimension
	 * @param seqLen            sequence length
	 * @param dim               model dimension
	 * @param heads             number of attention heads
	 * @param variant           the attention variant to construct ({@code null} is treated as
	 *                          {@link AttentionVariant#STANDARD})
	 * @param toQkvWeight       fused projection weights ({@code dim*3} for STANDARD, wider variants
	 *                          define their own width)
	 * @param toOutWeight       output projection weights
	 * @param qNormWeight       query normalization weights
	 * @param qNormBias         query normalization biases
	 * @param kNormWeight       key normalization weights
	 * @param kNormBias         key normalization biases
	 * @param invFreq           RoPE inverse frequencies
	 * @param diffLambda        learned lambda for variants that require it (unused by STANDARD,
	 *                          may be {@code null})
	 * @param projectionFactory factory for creating projection layers
	 * @return the self-attention block for the requested variant
	 */
	default Block selfAttention(int batchSize, int seqLen, int dim, int heads,
								AttentionVariant variant,
								PackedCollection toQkvWeight, PackedCollection toOutWeight,
								PackedCollection qNormWeight, PackedCollection qNormBias,
								PackedCollection kNormWeight, PackedCollection kNormBias,
								PackedCollection invFreq,
								Producer<PackedCollection> diffLambda,
								ProjectionFactory projectionFactory) {
		return selfAttention(batchSize, seqLen, dim, heads, variant,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, diffLambda, projectionFactory, NormalizationType.LAYER, null);
	}

	/**
	 * Creates the self-attention sub-block for the requested variant, with a selectable query/key
	 * normalization family and an optional padding mask, without causal masking. Routes to the
	 * causal overload with {@code causal = false}.
	 *
	 * @param batchSize         batch dimension
	 * @param seqLen            sequence length
	 * @param dim               model dimension
	 * @param heads             number of attention heads
	 * @param variant           the attention variant to construct ({@code null} is treated as
	 *                          {@link AttentionVariant#STANDARD})
	 * @param toQkvWeight       fused projection weights ({@code dim*3} for STANDARD, wider variants
	 *                          define their own width)
	 * @param toOutWeight       output projection weights
	 * @param qNormWeight       query normalization weights
	 * @param qNormBias         query normalization biases ({@code null} for none)
	 * @param kNormWeight       key normalization weights
	 * @param kNormBias         key normalization biases ({@code null} for none)
	 * @param invFreq           RoPE inverse frequencies
	 * @param diffLambda        learned lambda for variants that require it (unused by STANDARD,
	 *                          may be {@code null})
	 * @param projectionFactory factory for creating projection layers
	 * @param qkNorm            family of the query/key normalization
	 * @param paddingMask       per-position validity, shape {@code (batch, seqLen)}, or
	 *                          {@code null} for no masking
	 * @return the self-attention block for the requested variant
	 */
	default Block selfAttention(int batchSize, int seqLen, int dim, int heads,
								AttentionVariant variant,
								PackedCollection toQkvWeight, PackedCollection toOutWeight,
								PackedCollection qNormWeight, PackedCollection qNormBias,
								PackedCollection kNormWeight, PackedCollection kNormBias,
								PackedCollection invFreq,
								Producer<PackedCollection> diffLambda,
								ProjectionFactory projectionFactory,
								NormalizationType qkNorm,
								Producer<PackedCollection> paddingMask) {
		return selfAttention(batchSize, seqLen, dim, heads, variant,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, diffLambda, projectionFactory, qkNorm, paddingMask, false);
	}

	/**
	 * Creates the self-attention sub-block for the requested variant, with a selectable query/key
	 * normalization family, an optional padding mask and optional causal masking.
	 *
	 * <p>This is the overload that sub-interfaces override to supply a variant; every shorter
	 * {@code selfAttention} overload routes here, so an override receives every call. An override
	 * must either honour {@code causal} or reject {@code causal = true} explicitly; it must never
	 * build non-causal attention when causal attention was requested.</p>
	 *
	 * @param batchSize         batch dimension
	 * @param seqLen            sequence length
	 * @param dim               model dimension
	 * @param heads             number of attention heads
	 * @param variant           the attention variant to construct ({@code null} is treated as
	 *                          {@link AttentionVariant#STANDARD})
	 * @param toQkvWeight       fused projection weights ({@code dim*3} for STANDARD, wider variants
	 *                          define their own width)
	 * @param toOutWeight       output projection weights
	 * @param qNormWeight       query normalization weights
	 * @param qNormBias         query normalization biases ({@code null} for none)
	 * @param kNormWeight       key normalization weights
	 * @param kNormBias         key normalization biases ({@code null} for none)
	 * @param invFreq           RoPE inverse frequencies
	 * @param diffLambda        learned lambda for variants that require it (unused by STANDARD,
	 *                          may be {@code null})
	 * @param projectionFactory factory for creating projection layers
	 * @param qkNorm            family of the query/key normalization
	 * @param paddingMask       per-position validity, shape {@code (batch, seqLen)}, or
	 *                          {@code null} for no masking
	 * @param causal            whether each position may attend only to itself and earlier
	 *                          positions
	 * @return the self-attention block for the requested variant
	 */
	default Block selfAttention(int batchSize, int seqLen, int dim, int heads,
								AttentionVariant variant,
								PackedCollection toQkvWeight, PackedCollection toOutWeight,
								PackedCollection qNormWeight, PackedCollection qNormBias,
								PackedCollection kNormWeight, PackedCollection kNormBias,
								PackedCollection invFreq,
								Producer<PackedCollection> diffLambda,
								ProjectionFactory projectionFactory,
								NormalizationType qkNorm,
								Producer<PackedCollection> paddingMask,
								boolean causal) {
		if (variant == null || variant == AttentionVariant.STANDARD) {
			return sequenceAttention(batchSize, seqLen, dim, heads,
					toQkvWeight, toOutWeight,
					qNormWeight, qNormBias, kNormWeight, kNormBias,
					invFreq, projectionFactory, qkNorm, paddingMask, null, 0.0, causal);
		}

		throw new UnsupportedOperationException("Attention variant " + variant +
				" is not available on the base AttentionFeatures; implement DifferentialAttentionFeatures" +
				" (or another sub-interface) to use it");
	}

	/**
	 * Standard transformer layer without QK-Norm or GQA.
	 * Delegates to the full transformer method with null optional parameters.
	 */
	default Block transformer(int heads,
							  PackedCollection rmsAttWeight,
							  PackedCollection wk, PackedCollection wv,
							  PackedCollection wq, PackedCollection wo,
							  CollectionProducer freqCis,
							  PackedCollection rmsFfnWeight,
							  PackedCollection w1, PackedCollection w2, PackedCollection w3,
							  Producer<PackedCollection> position,
							  ComputeRequirement... requirements) {
		return transformer(heads, heads, rmsAttWeight, wk, wv, wq, wo,
				null, null, null, null, null, freqCis, rmsFfnWeight, w1, w2, w3, position, 1e-5, requirements);
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
							  PackedCollection rmsAttWeight,
							  PackedCollection wk, PackedCollection wv,
							  PackedCollection wq, PackedCollection wo,
							  PackedCollection bk, PackedCollection bv,
							  PackedCollection bq,
							  PackedCollection qkNormQ, PackedCollection qkNormK,
							  CollectionProducer freqCis,
							  PackedCollection rmsFfnWeight,
							  PackedCollection w1, PackedCollection w2, PackedCollection w3,
							  Producer<PackedCollection> position,
							  ComputeRequirement... requirements) {
		return transformer(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo, bk, bv, bq,
				qkNormQ, qkNormK, freqCis, rmsFfnWeight, w1, w2, w3, position, 1e-5, requirements);
	}

	/**
	 * Transformer layer with configurable RMSNorm epsilon.
	 *
	 * <p>The structure is the {@code transformer} layer of {@link #TRANSFORMER_ASSET} (or
	 * {@code transformer_qk_norm} when normalization weights are given): a residual attention
	 * stage and a residual SwiGLU feed-forward stage. This method only binds the arguments
	 * (allocating the attention caches) and builds the layer.</p>
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
	 * @param rmsFfnWeight Pre-FFN RMSNorm weights
	 * @param w1 FFN gate projection
	 * @param w2 FFN down projection
	 * @param w3 FFN up projection
	 * @param position Current position in sequence
	 * @param epsilon RMSNorm epsilon (e.g., 1e-5 for Llama, 1e-6 for Qwen3)
	 * @param requirements compute requirements, applied to every layer the assets build
	 * @return Complete transformer layer block
	 * @throws IllegalArgumentException if only one of the QK-Norm weights is given
	 */
	default Block transformer(int heads, int kvHeads,
							  PackedCollection rmsAttWeight,
							  PackedCollection wk, PackedCollection wv,
							  PackedCollection wq, PackedCollection wo,
							  PackedCollection bk, PackedCollection bv,
							  PackedCollection bq,
							  PackedCollection qkNormQ, PackedCollection qkNormK,
							  CollectionProducer freqCis,
							  PackedCollection rmsFfnWeight,
							  PackedCollection w1, PackedCollection w2, PackedCollection w3,
							  Producer<PackedCollection> position,
							  double epsilon,
							  ComputeRequirement... requirements) {
		return transformerLayer(qkNormQ == null ? "transformer" : "transformer_qk_norm",
				attentionArguments(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
						bk, bv, bq, qkNormQ, qkNormK, freqCis, position, epsilon),
				rmsFfnWeight, w1, w2, w3, requirements);
	}

	/**
	 * Transformer layer with Multidimensional Relative Attention (MRA).
	 *
	 * <p>The structure is the {@code transformer_mra} layer of {@link #TRANSFORMER_ASSET}: MRA
	 * attention (per-head-group RoPE) and a standard SwiGLU feed-forward stage, each residual.
	 * This is the building block for the Moonbeam MIDI transformer.</p>
	 *
	 * @param heads number of query attention heads
	 * @param kvHeads number of key/value heads (for GQA)
	 * @param rmsAttWeight pre-attention RMSNorm weights
	 * @param wk key projection weights
	 * @param wv value projection weights
	 * @param wq query projection weights
	 * @param wo output projection weights
	 * @param headGroups per-head-group RoPE configuration
	 * @param rmsFfnWeight pre-FFN RMSNorm weights
	 * @param w1 FFN gate projection weights
	 * @param w2 FFN down projection weights
	 * @param w3 FFN up projection weights
	 * @param position sequential position for KV cache and causal masking
	 * @param epsilon RMSNorm epsilon
	 * @param requirements compute requirements
	 * @return transformer block with MRA attention
	 */
	default Block transformer(int heads, int kvHeads,
							  PackedCollection rmsAttWeight,
							  PackedCollection wk, PackedCollection wv,
							  PackedCollection wq, PackedCollection wo,
							  HeadGroupConfig[] headGroups,
							  PackedCollection rmsFfnWeight,
							  PackedCollection w1, PackedCollection w2, PackedCollection w3,
							  Producer<PackedCollection> position,
							  double epsilon,
							  ComputeRequirement... requirements) {
		return transformerLayer("transformer_mra",
				attentionArguments(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo, headGroups, position, epsilon),
				rmsFfnWeight, w1, w2, w3, requirements);
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

		TraversalPolicy outputShape = shape(batchSize, heads, dimHead, dimHead);
		return compose("context", v, outputShape, (a, b) -> {
			CollectionProducer pa = c(a)
					.traverse(3)
					.repeat(dimHead);
			CollectionProducer pb = c(b)
					.traverse(2)
					.repeat(dimHead);
			// sum(4) reduces axis 4 but keeps dimension of size 1, so reshape to remove it
			return multiply(pa, pb).sum(4).reshape(outputShape);
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