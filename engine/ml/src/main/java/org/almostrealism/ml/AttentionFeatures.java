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
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.ArrayList;
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
public interface AttentionFeatures extends RotationFeatures, FeedForwardFeatures {

	/**
	 * Creates a layer that produces each output element by gathering the input element
	 * named by the corresponding index of {@code indices}.
	 *
	 * <p>{@code indices} is an operand of the gather, so a structural permutation expressed
	 * as arithmetic over {@link #integers(int, int)} is evaluated within the gather's own
	 * kernel. Precomputing the same permutation into a collection would instead hold an
	 * index table in memory and move it from the host.</p>
	 *
	 * @param name         the name of the resulting layer
	 * @param inputShape   the shape of the layer's input
	 * @param outputShape  the shape of the layer's output
	 * @param indices      producer of one input index per output element
	 * @param requirements optional compute requirements
	 * @return a {@link CellularLayer} gathering input elements by index
	 */
	default CellularLayer gather(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								 Producer<PackedCollection> indices, ComputeRequirement... requirements) {
		int inputSize = inputShape.getTotalSize();
		int outputSize = outputShape.getTotalSize();

		return layer(name, inputShape, outputShape,
				input -> c(shape(outputSize), c(input).reshape(shape(inputSize)), indices)
						.reshape(outputShape), requirements);
	}

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
			// No GQA - use simple attention
			return layer("attentionKeys", inputShape, outputShape, input ->
					traverse(1, keys).multiply(input)
							.traverse(2).sum()
							.divide(c(Math.sqrt(headSize)))
							.reshape(shape(seqLength, heads))
							.enumerate(1, 1)
							.reshape(outputShape), requirements);
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
	 * Creates a GQA expansion layer for per-position KV data with batch dimension.
	 *
	 * <p>Expands from (1, kvDim) to (1, dim) by duplicating each KV head's data
	 * headsPerKvGroup times. This operates on per-position 2D data (with batch dim)
	 * using explicit subset and concat to avoid traverse().repeat() which causes
	 * compilation issues.</p>
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

		int headsPerKvGroup = heads / kvHeads;
		TraversalPolicy inputShape = shape(1, kvDim);
		TraversalPolicy outputShape = shape(1, dim);

		CollectionProducer index = integers(0, dim);
		CollectionProducer outputHead = floor(index.divide(headSize));
		CollectionProducer inHeadOffset = index.mod(headSize);
		CollectionProducer kvHead = floor(outputHead.divide(headsPerKvGroup));

		return gather("gqa_expand", inputShape, outputShape,
				kvHead.multiply(headSize).add(inHeadOffset), requirements);
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
	 * Standard attention keys computation for expanded caches (no GQA).
	 *
	 * <p>This version expects keys in shape (seqLength, heads, headSize), which is the
	 * expanded format where GQA expansion has already been done at cache write time.</p>
	 *
	 * @param inputShape Shape of the query input (heads, headSize)
	 * @param keys Key tensor producer (seqLength, heads, headSize)
	 * @param requirements Compute requirements
	 * @return Attention keys layer producing scaled dot-product attention scores
	 */
	default CellularLayer attentionKeysStandard(TraversalPolicy inputShape,
												Producer<PackedCollection> keys,
												ComputeRequirement... requirements) {
		TraversalPolicy keyShape = shape(keys); // (seqLength, heads, headSize)

		if (inputShape.getDimensions() != 2 || keyShape.getDimensions() != 3)
			throw new IllegalArgumentException("Expected query (heads, headSize) and keys (seqLen, heads, headSize)");

		int heads = inputShape.length(0);
		int headSize = inputShape.length(1);

		int seqLength = keyShape.length(0);
		TraversalPolicy outputShape = shape(heads, seqLength).traverseEach();

		if (keyShape.length(1) != heads || keyShape.length(2) != headSize)
			throw new IllegalArgumentException("Key shape must match query heads and headSize");

		// Standard attention: Q @ K^T / sqrt(headSize)
		// Use permute(1, 0) to transpose (seqLength, heads) -> (heads, seqLength)
		// instead of enumerate(1, 1) which uses subset internally
		return layer("attentionKeysStd", inputShape, outputShape, input ->
				permute(
					traverse(1, keys).multiply(input)
						.traverse(2).sum()
						.divide(c(Math.sqrt(headSize)))
						.reshape(shape(seqLength, heads)),
					1, 0
				).reshape(outputShape), requirements);
	}

	/**
	 * Standard attention values computation for expanded caches (no GQA).
	 *
	 * <p>This version expects values in shape (seqLength, heads, headSize), which is the
	 * expanded format where GQA expansion has already been done at cache write time.</p>
	 *
	 * @param inputShape Shape of the attention scores input (heads, seqLength)
	 * @param values Value tensor producer (seqLength, heads, headSize)
	 * @param requirements Compute requirements
	 * @return Attention values layer producing (1, dim) output
	 */
	default CellularLayer attentionValuesStandard(TraversalPolicy inputShape,
												  Producer<PackedCollection> values,
												  ComputeRequirement... requirements) {
		TraversalPolicy valueShape = shape(values); // (seqLength, heads, headSize)

		if (inputShape.getDimensions() != 2 || valueShape.getDimensions() != 3)
			throw new IllegalArgumentException("Expected attention (heads, seqLen) and values (seqLen, heads, headSize)");

		int heads = inputShape.length(0);
		int headSize = valueShape.length(2);
		int dim = heads * headSize;

		int seqLength = inputShape.length(1);

		// Preserve batch dimension in output shape for consistency with other layers
		TraversalPolicy outputShape = shape(1, dim);

		if (valueShape.length(0) != seqLength || valueShape.length(1) != heads)
			throw new IllegalArgumentException("Value shape must match attention heads and seqLength");

		// Standard attention: softmax(scores) @ V
		// output[h, i] = sum_s(attn[h, s] * values[s, h, i])
		//
		// Use the same pattern as the working attentionValues method (non-GQA case):
		// - Reshape values to (seqLen, dim), transpose to (dim, seqLen), reshape to (heads, headSize, seqLen)
		// - Expand attention (heads, seqLen) to (heads, headSize, seqLen) via traverse+repeat
		// - Element-wise multiply and sum over seqLen
		return layer("attentionValuesStd", inputShape, outputShape, input -> {
			// Reshape values from (seqLen, heads, headSize) to (seqLen, dim)
			Producer<PackedCollection> v = reshape(shape(seqLength, dim), values);
			// Transpose to (dim, seqLen), then reshape to (heads, headSize, seqLen)
			v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

			// input is (heads, seqLen)
			// traverse(1, input) iterates over heads, giving (seqLen) slices
			// repeat(headSize) expands each (seqLen) to (headSize, seqLen)
			CollectionProducer a = traverse(1, input).repeat(headSize);

			// Element-wise multiply (heads, headSize, seqLen) * (heads, headSize, seqLen)
			// Then sum over seqLen to get (heads, headSize)
			CollectionProducer o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();

			return o.reshape(shape(1, dim).traverseEach());
		}, requirements);
	}

	/**
	 * Expand value cache for Grouped Query Attention by repeating each KV head.
	 *
	 * Same logic as expandKeysForGQA - transforms (seqLength, kvHeads, headSize)
	 * to (seqLength, heads, headSize).
	 */
	default Producer<PackedCollection> expandValuesForGQA(
			Producer<PackedCollection> values,
			int seqLength, int kvHeads, int heads, int headSize, int headsPerKvGroup) {
		// Skip expansion if kvHeads == heads (no GQA)
		if (kvHeads == heads) {
			return values;
		}

		// Use traverse(2) to iterate over (seqLength, kvHeads), then repeat each (headSize) vector
		TraversalPolicy outputShape = shape(seqLength, heads, headSize);
		Producer<PackedCollection> repeated = traverse(2, values).repeat(headsPerKvGroup);
		return reshape(outputShape, repeated);
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
							double epsilon,
							ComputeRequirement... requirements) {
		return attentionImpl(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, qkNormQ, qkNormK,
				freqCis, null, position, epsilon, requirements);
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
	 * @param requirements compute requirements
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
		return attentionImpl(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				null, null, null, null, null,
				null, headGroups, position, epsilon, requirements);
	}

	/**
	 * Unified attention implementation supporting both standard RoPE and
	 * Multidimensional Relative Attention (MRA).
	 *
	 * <p>When {@code headGroups} is non-null, MRA mode is active and per-group
	 * {@link #mraRopeRotation} is used for Q and K. Otherwise standard
	 * {@link RotationFeatures#ropeRotation} is applied using {@code freqCis}.
	 * Optional bias ({@code bk}, {@code bv}, {@code bq}) and QK-Norm
	 * ({@code qkNormQ}, {@code qkNormK}) parameters are applied only when non-null,
	 * and are ignored in MRA mode.</p>
	 */
	private Block attentionImpl(int heads, int kvHeads,
								PackedCollection rmsAttWeight,
								PackedCollection wk, PackedCollection wv,
								PackedCollection wq, PackedCollection wo,
								PackedCollection bk, PackedCollection bv, PackedCollection bq,
								PackedCollection qkNormQ, PackedCollection qkNormK,
								CollectionProducer freqCis,
								HeadGroupConfig[] headGroups,
								Producer<PackedCollection> position,
								double epsilon,
								ComputeRequirement... requirements) {
		boolean useMRA = headGroups != null;

		int dim = rmsAttWeight.getShape().length(0);
		int headSize = useMRA ? headGroups[0].freqCis.getShape().length(1) * 2
							  : freqCis.getShape().length(1) * 2;
		int seqLen = useMRA ? headGroups[0].freqCis.getShape().length(0)
							: freqCis.getShape().length(0);
		int kvDim = dim * kvHeads / heads;
		int headsPerKvGroup = heads / kvHeads;
		boolean useGQA = kvHeads != heads;

		// For MRA: compute per-group head counts for keys and queries
		int numGroups = useMRA ? headGroups.length : 0;
		int[] kvHeadsPerGroup = null;
		int[] queryHeadsPerGroup = null;
		if (useMRA) {
			kvHeadsPerGroup = new int[numGroups];
			queryHeadsPerGroup = new int[numGroups];
			for (int g = 0; g < numGroups; g++) {
				kvHeadsPerGroup[g] = headGroups[g].headCount / headsPerKvGroup;
				queryHeadsPerGroup[g] = headGroups[g].headCount;
			}
		}

		TraversalPolicy inputShape = shape(1, dim);
		SequentialBlock attention = new SequentialBlock(inputShape);

		// Use EXPANDED caches (seqLen, heads, headSize) to avoid GQA subset/reshape issues during attention
		// GQA expansion is done at cache write time instead of read time
		PackedCollection keyCache = new PackedCollection(seqLen, heads, headSize);
		PackedCollection valueCache = new PackedCollection(seqLen, heads, headSize);

		// Zero-initialize caches to prevent garbage values from causing numerical explosions
		keyCache.clear();
		valueCache.clear();

		attention.add(rmsnorm(inputShape, rmsAttWeight, epsilon, requirements));

		SequentialBlock keys = attention.branch();
		SequentialBlock values = attention.branch();

		TraversalPolicy kvHeadShapeComplex = shape(kvHeads, headSize / 2, 2);
		TraversalPolicy kvHeadShape = shape(kvHeads, headSize);

		/* KEYS **/
		keys.add(bk != null ? dense(wk, bk) : dense(wk));
		if (qkNormK != null) {
			// QK-Norm: RMSNorm applied per-head before RoPE (NOT LayerNorm!)
			// Flatten the norm weights from (kvHeads, headSize) to (kvDim) since rmsnorm requires 1D weights
			PackedCollection flatQkNormK = qkNormK.reshape(shape(kvDim));
			keys.add((Function<TraversalPolicy, CellularLayer>) (s -> rmsnorm(s, flatQkNormK, 1e-6, requirements)));
		}
		// Use split-half reshape for RoPE (matches PyTorch's Qwen/Llama)
		keys.add(reshapeToSplitHalfRope(kvDim, kvHeads, headSize));
		if (useMRA) {
			keys.add(mraRopeRotation(kvHeads, headSize, kvHeadsPerGroup, headGroups, requirements));
		} else {
			keys.add(ropeRotation(kvHeadShapeComplex, freqCis, position));
		}
		// Reshape back to (kvHeads, headSize) then flatten to (kvDim)
		keys.add(reshapeFromSplitHalfRope(kvHeads, headSize));
		keys.add(reshape(kvHeadShape, shape(kvDim)));
		// GQA expand: duplicate each KV head's data for all query heads it serves
		// This expands from (1, kvDim) -> (1, dim) at write time
		if (useGQA) {
			keys.add(reshape(shape(kvDim), shape(1, kvDim)));  // Add batch dim for gqaExpand
			keys.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize, requirements));
		} else {
			keys.add(reshape(shape(kvDim), shape(1, dim)));  // Add batch dim
		}
		keys.andThen(into(keyCache.reshape(shape(seqLen, dim)), position));
		/* ---- **/

		/* VALUES **/
		values.add(bv != null ? dense(wv, bv) : dense(wv));
		// GQA expand: duplicate each KV head's data for all query heads it serves
		// Values go from (kvDim) -> (dim) by duplicating each KV head's values
		if (useGQA) {
			values.add(reshape(shape(kvDim), shape(1, kvDim)));  // Add batch dim for gqaExpand
			values.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize, requirements));
		} else {
			values.add(reshape(shape(kvDim), shape(1, dim)));  // Add batch dim
		}
		values.andThen(into(valueCache.reshape(shape(seqLen, dim)), position));
		/* ---- **/

		/* QUERY **/
		TraversalPolicy headShapeComplex = shape(heads, headSize / 2, 2);
		TraversalPolicy headShape = shape(heads, headSize);
		TraversalPolicy attentionShape = shape(heads, seqLen).traverseEach(); // (heads, 1, seqLen)

		attention.add(bq != null ? dense(wq, bq) : dense(wq));
		if (qkNormQ != null) {
			// QK-Norm: RMSNorm applied per-head before RoPE (NOT LayerNorm!)
			// Flatten the norm weights from (heads, headSize) to (dim) since rmsnorm requires 1D weights
			PackedCollection flatQkNormQ = qkNormQ.reshape(shape(dim));
			attention.add((Function<TraversalPolicy, CellularLayer>) (s -> rmsnorm(s, flatQkNormQ, 1e-6, requirements)));
		}
		// Use split-half reshape for RoPE (matches PyTorch's Qwen/Llama)
		attention.add(reshapeToSplitHalfRope(dim, heads, headSize));
		if (useMRA) {
			attention.add(mraRopeRotation(heads, headSize, queryHeadsPerGroup, headGroups, requirements));
		} else {
			attention.add(ropeRotation(headShapeComplex, freqCis, position));
		}
		// Reshape back to (heads, headSize) for attention computation
		attention.add(reshapeFromSplitHalfRope(heads, headSize));
		// Expanded keys cache (seqLen, heads, headSize) - use standard non-GQA attention
		attention.add(attentionKeysStandard(headShape, p(keyCache)));

		// Add dynamic causal mask: mask[i] = -10000 if i > position, else 0
		// This prevents attention from seeing future positions in the KV cache
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, position, c(-10000.0), c(0.0), false);
		// Reshape to (1, 1, seqLen) then repeat to get (heads, 1, seqLen)
		// This pattern is verified in CausalMaskIsolationTest to correctly broadcast
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		// Create a block to add the causal mask to the attention scores
		// The mask broadcasts from (heads, 1, seqLen) to match the attention shape
		attention.add(layer("causal_mask", attentionShape, attentionShape,
		                   input -> add(input, causalMask),
		                   requirements));

		attention.add(softmax(attentionShape, true));
		// Expanded values cache (seqLen, heads, headSize) - use standard non-GQA attention
		attention.add(attentionValuesStandard(attentionShape, p(valueCache)));
		attention.add(dense(wo));

		// Restore the (1, dim) shape for the transformer layer output
		attention.reshape(inputShape);
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
									PackedCollection toQkvWeight, PackedCollection toOutWeight,
									PackedCollection qNormWeight, PackedCollection qNormBias,
									PackedCollection kNormWeight, PackedCollection kNormBias,
									PackedCollection invFreq) {
		return sequenceAttention(batchSize, seqLen, dim, heads,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, ProjectionFactory.dense());
	}

	/**
	 * Creates a sequence-based multi-head attention block with fused QKV projection
	 * and customizable projection layers.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection
	 * layers are created, enabling LoRA or other adapter patterns without code duplication.</p>
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
	 * @param projectionFactory Factory for creating projection layers
	 * @return Sequence attention block
	 */
	default Block sequenceAttention(int batchSize, int seqLen, int dim, int heads,
									PackedCollection toQkvWeight, PackedCollection toOutWeight,
									PackedCollection qNormWeight, PackedCollection qNormBias,
									PackedCollection kNormWeight, PackedCollection kNormBias,
									PackedCollection invFreq,
									ProjectionFactory projectionFactory) {
		int dimHead = dim / heads;
		TraversalPolicy inputShape = shape(batchSize, seqLen, dim);

		SequentialBlock attention = new SequentialBlock(inputShape);

		// 1. Fused QKV projection
		attention.add(projectionFactory.create(inputShape, toQkvWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));

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
		PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, seqLen, dimHead));
		PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, seqLen, dimHead));

		k.andThen(into(kTensor));
		v.andThen(into(vTensor));

		// 7. Compute scaled dot-product attention using stored tensors
		q.add(scaledDotProductAttention(batchSize, seqLen, heads, dimHead, kTensor, vTensor));

		// Rearrange back to (batch, seqLen, dim)
		q.permute(0, 2, 1, 3)
			.reshape(batchSize, seqLen, dim);

		// 8. Output projection
		q.add(projectionFactory.create(shape(batchSize, seqLen, dim), toOutWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));

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
										 PackedCollection toQWeight, PackedCollection toKvWeight,
										 PackedCollection toOutWeight,
										 PackedCollection qNormWeight, PackedCollection qNormBias,
										 PackedCollection kNormWeight, PackedCollection kNormBias,
										 Block contextInput, Receptor<PackedCollection> attentionScores) {
		return sequenceCrossAttention(batchSize, querySeqLen, contextSeqLen, dim, heads,
				toQWeight, toKvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				contextInput, attentionScores, ProjectionFactory.dense());
	}

	/**
	 * Creates a cross-attention block with customizable projection layers.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection
	 * layers are created, enabling LoRA or other adapter patterns without code duplication.</p>
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
	 * @param projectionFactory Factory for creating projection layers
	 * @return Cross-attention block
	 */
	default Block sequenceCrossAttention(int batchSize, int querySeqLen, int contextSeqLen,
										 int dim, int heads,
										 PackedCollection toQWeight, PackedCollection toKvWeight,
										 PackedCollection toOutWeight,
										 PackedCollection qNormWeight, PackedCollection qNormBias,
										 PackedCollection kNormWeight, PackedCollection kNormBias,
										 Block contextInput, Receptor<PackedCollection> attentionScores,
										 ProjectionFactory projectionFactory) {
		int dimHead = dim / heads;
		TraversalPolicy queryShape = shape(batchSize, querySeqLen, dim);

		SequentialBlock crossAttention = new SequentialBlock(queryShape);

		// 1. Project main input to queries
		crossAttention.add(projectionFactory.create(queryShape, toQWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_Q));
		crossAttention.reshape(batchSize, querySeqLen, heads, dimHead);
		crossAttention.permute(0, 2, 1, 3); // (batch, heads, querySeqLen, dimHead)

		// 2. Apply Q normalization
		crossAttention.add(norm(qNormWeight, qNormBias, 1e-6));

		// 3. Process context input through separate branch for K and V
		SequentialBlock contextBranch = contextInput.branch();
		contextBranch.add(projectionFactory.create(contextInput.getOutputShape(), toKvWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_KV));
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
		PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
		PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));

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
		crossAttention.add(projectionFactory.create(queryShape, toOutWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_OUT));

		return crossAttention;
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
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   // Cross-attention weights
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   // Feed-forward weights
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias) {
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
				null, ProjectionFactory.dense());
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
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   // Cross-attention weights
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   // Feed-forward weights
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias,
								   Receptor<PackedCollection> attentionScores) {
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
				attentionScores, ProjectionFactory.dense());
	}

	/**
	 * Creates a complete transformer block with self-attention, optional cross-attention, and feed-forward.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection layers
	 * (QKV, output, FFN) are created. This enables LoRA (Low-Rank Adaptation) support
	 * without code duplication.</p>
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
	 * @param projectionFactory Factory for creating projection layers (enables LoRA support)
	 * @return Complete transformer block
	 */
	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   // Self-attention weights
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   // Cross-attention weights
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   // Feed-forward weights
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias,
								   Receptor<PackedCollection> attentionScores,
								   ProjectionFactory projectionFactory) {
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
				attentionScores, projectionFactory,
				AttentionVariant.STANDARD, null, null);
	}

	/**
	 * Constructs the self-attention sub-computation for the selected {@link AttentionVariant}.
	 *
	 * <p>This is the attention-variant seam threaded through {@link #transformerBlock}. The base
	 * implementation supports {@link AttentionVariant#STANDARD} only, delegating to
	 * {@link #sequenceAttention(int, int, int, int, PackedCollection, PackedCollection,
	 * PackedCollection, PackedCollection, PackedCollection, PackedCollection, PackedCollection,
	 * ProjectionFactory) sequenceAttention} so the default path is unchanged. Alternative variants
	 * are provided by sub-interfaces that override this method (for example
	 * {@link DifferentialAttentionFeatures}). Because the call site in {@code transformerBlock} is a
	 * virtual dispatch, a consumer that implements such a sub-interface automatically obtains the
	 * variant without forking the block builder.</p>
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
		if (variant == null || variant == AttentionVariant.STANDARD) {
			return sequenceAttention(batchSize, seqLen, dim, heads,
					toQkvWeight, toOutWeight,
					qNormWeight, qNormBias, kNormWeight, kNormBias,
					invFreq, projectionFactory);
		}

		throw new UnsupportedOperationException("Attention variant " + variant +
				" is not available on the base AttentionFeatures; implement DifferentialAttentionFeatures" +
				" (or another sub-interface) to use it");
	}

	/**
	 * Creates a complete transformer block, selecting the self-attention implementation via an
	 * {@link AttentionVariant}.
	 *
	 * <p>This is the variant-aware base overload. All other {@code transformerBlock} overloads
	 * delegate here with {@link AttentionVariant#STANDARD} and a {@code null} lambda, so the
	 * standard scaled-dot-product path is byte-for-byte identical to the previous behaviour. The
	 * only difference from the standard path is that the self-attention sub-block is built through
	 * {@link #selfAttention} rather than {@link #sequenceAttention} directly, which routes the
	 * construction to the selected variant.</p>
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
	 * @param selfQkv Self-attention fused projection weights (width depends on {@code variant})
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
	 * @param projectionFactory Factory for creating projection layers (enables LoRA support)
	 * @param variant Attention variant for the self-attention sub-block
	 * @param diffLambda Learned lambda supplied to variants that require it (may be {@code null})
	 * @param modulation Optional packed adaLN modulation, shape {@code [batch, 6, dim]}, whose six
	 *                   {@code [batch, dim]} components are scale/shift/gate for self-attention followed
	 *                   by scale/shift/gate for the feed-forward. When {@code null} no modulation is
	 *                   applied and the block is the standard pre-norm residual block (the prepend path).
	 * @return Complete transformer block
	 */
	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   // Self-attention weights
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   // Cross-attention weights
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   // Feed-forward weights
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias,
								   Receptor<PackedCollection> attentionScores,
								   ProjectionFactory projectionFactory,
								   AttentionVariant variant,
								   Producer<PackedCollection> diffLambda,
								   Producer<PackedCollection> modulation) {
		TraversalPolicy blockShape = shape(batchSize, seqLen, dim);
		SequentialBlock block = new SequentialBlock(blockShape);

		// adaLN-Zero modulation components (null when unmodulated): scale/shift/gate for self-attention
		// followed by scale/shift/gate for the feed-forward.
		Producer<PackedCollection> scaleSelf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 0);
		Producer<PackedCollection> shiftSelf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 1);
		Producer<PackedCollection> gateSelf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 2);
		Producer<PackedCollection> scaleFf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 3);
		Producer<PackedCollection> shiftFf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 4);
		Producer<PackedCollection> gateFf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 5);

		// Self-attention with pre-normalization inside residual branch
		// Python: x = x + gate_self * self_attn(scale_self * pre_norm(x) + shift_self)
		SequentialBlock selfAttentionWithNorm = new SequentialBlock(blockShape);
		selfAttentionWithNorm.add(norm(preNormWeight, preNormBias));
		if (modulation != null) {
			selfAttentionWithNorm.add(adaptiveModulate(blockShape, scaleSelf, shiftSelf));
		}
		selfAttentionWithNorm.add(selfAttention(
				batchSize, seqLen, dim, heads, variant,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq, diffLambda, projectionFactory));
		if (modulation != null) {
			selfAttentionWithNorm.add(adaptiveGate(blockShape, gateSelf));
		}
		block.add(residual(selfAttentionWithNorm));

		// Cross-attention with pre-normalization inside residual branch (if needed). adaLN modulation
		// drives only the self-attention and feed-forward sub-layers, so cross-attention is unmodulated.
		// Python: x = x + cross_attn(cross_attend_norm(x))
		if (crossAttend) {
			if (context == null) {
				throw new IllegalArgumentException("Context block cannot be null for cross-attention");
			}

			SequentialBlock crossAttentionWithNorm = new SequentialBlock(blockShape);
			crossAttentionWithNorm.add(norm(crossAttPreNormWeight, crossAttPreNormBias));
			crossAttentionWithNorm.add(sequenceCrossAttention(
					batchSize, seqLen, contextSeqLen, dim, heads,
					crossWq, crossKv, crossWo,
					crossQNormWeight, crossQNormBias,
					crossKNormWeight, crossKNormBias,
					context, attentionScores, projectionFactory));
			block.add(residual(crossAttentionWithNorm));
		}

		// Feed-forward with normalization inside residual branch
		// Python: x = x + gate_ff * ff(scale_ff * ff_norm(x) + shift_ff)
		Block feedForward = gatedLinearFeedForward(block.getOutputShape(),
				ffnNormWeight, ffnNormBias, w1, w1Bias, w2, w2Bias,
				scaleFf, shiftFf, projectionFactory);
		if (modulation == null) {
			block.add(residual(feedForward));
		} else {
			SequentialBlock gatedFeedForward = new SequentialBlock(block.getOutputShape());
			gatedFeedForward.add(feedForward);
			gatedFeedForward.add(adaptiveGate(blockShape, gateFf));
			block.add(residual(gatedFeedForward));
		}

		return block;
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
							  double epsilon,
							  ComputeRequirement... requirements) {
		int dim = rmsAttWeight.getShape().length(0);

		SequentialBlock transformer = new SequentialBlock(shape(1, dim));
		transformer.accum(attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, qkNormQ, qkNormK, freqCis, position, epsilon, requirements), requirements);
		transformer.accum(feedForward(rmsFfnWeight, w1, w2, w3, epsilon, requirements), requirements);
		return transformer;
	}

	/**
	 * Transformer layer with Multidimensional Relative Attention (MRA).
	 *
	 * <p>Combines MRA attention (per-head-group RoPE) with a standard SwiGLU
	 * feed-forward block. This is the building block for the Moonbeam MIDI
	 * transformer.</p>
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
		int dim = rmsAttWeight.getShape().length(0);

		SequentialBlock transformer = new SequentialBlock(shape(1, dim));
		transformer.accum(attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				headGroups, position, epsilon, requirements), requirements);
		transformer.accum(feedForward(rmsFfnWeight, w1, w2, w3, epsilon, requirements), requirements);
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
	 * Builds a scaled dot-product attention block using the same sequence length for queries and context.
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen    Sequence length for both queries and context
	 * @param heads     Number of attention heads
	 * @param dimHead   Dimension per head
	 * @param k         Key tensor (batch, heads, seqLen, dimHead)
	 * @param v         Value tensor (batch, heads, seqLen, dimHead)
	 * @return Block computing softmax(Q @ K^T / sqrt(dimHead)) @ V
	 */
	default Block scaledDotProductAttention(int batchSize, int seqLen, int heads, int dimHead,
											PackedCollection k, PackedCollection v) {
		return scaledDotProductAttention(batchSize, seqLen, seqLen, heads, dimHead, k, v, null);
	}

	/**
	 * Builds a scaled dot-product attention block with optional attention score capture.
	 *
	 * @param batchSize       Batch dimension
	 * @param seqLen          Sequence length for both queries and context
	 * @param heads           Number of attention heads
	 * @param dimHead         Dimension per head
	 * @param k               Key tensor (batch, heads, seqLen, dimHead)
	 * @param v               Value tensor (batch, heads, seqLen, dimHead)
	 * @param attentionScores Optional receptor to receive the attention weight matrix; may be null
	 * @return Block computing softmax(Q @ K^T / sqrt(dimHead)) @ V
	 */
	default Block scaledDotProductAttention(int batchSize, int seqLen, int heads, int dimHead,
											PackedCollection k, PackedCollection v,
											Receptor<PackedCollection> attentionScores) {
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
											PackedCollection k, PackedCollection v,
											Receptor<PackedCollection> attentionScores) {
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