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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;

import java.util.List;
import java.util.function.Function;

/**
 * Provides Rotary Position Embedding (RoPE) implementations for transformer attention.
 *
 * <p>Rotary Position Embeddings encode positional information by rotating query and key
 * vectors in the complex plane. This approach was introduced in RoFormer and is now
 * widely used in models like Llama, Qwen, and Mistral for its advantages:</p>
 *
 * <ul>
 *   <li><strong>Relative positions:</strong> RoPE naturally encodes relative positions through rotation angles</li>
 *   <li><strong>No learned parameters:</strong> Position embeddings are computed from sinusoidal frequencies</li>
 *   <li><strong>Extrapolation:</strong> Can handle sequences longer than seen during training (with appropriate theta)</li>
 *   <li><strong>Efficiency:</strong> Applied only to Q/K, not to values or output projections</li>
 * </ul>
 *
 * <h2>RoPE Mathematics</h2>
 * <p>For a vector x at position p, RoPE applies rotation:</p>
 * <pre>
 * RoPE(x, p) = [x_0 * cos(p*theta_0) - x_1 * sin(p*theta_0),
 *              x_0 * sin(p*theta_0) + x_1 * cos(p*theta_0),
 *              x_2 * cos(p*theta_1) - x_3 * sin(p*theta_1),
 *              ...]
 * </pre>
 *
 * <p>Where theta_i = 1 / (base^(2i/d)) and base is typically 10000 (or 1000000 for extended context).</p>
 *
 * <h2>Key Methods</h2>
 * <ul>
 *   <li>{@link #ropeRotation} - Single-position rotation for autoregressive generation</li>
 *   <li>{@link #applyRotaryPositionEmbedding} - Full-sequence rotation for batch processing</li>
 *   <li>{@link #computeRotaryFreqs} - Precomputes frequency tensors from inverse frequencies</li>
 * </ul>
 *
 * <h2>Usage Example (Autoregressive)</h2>
 * <pre>{@code
 * // Precompute frequency tensor
 * PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, theta);
 *
 * // In attention layer
 * keys.add(ropeRotation(shape(kvHeads, headSize/2, 2), freqCis, position));
 * queries.add(ropeRotation(shape(heads, headSize/2, 2), freqCis, position));
 * }</pre>
 *
 * <h2>Usage Example (Full Sequence)</h2>
 * <pre>{@code
 * // Precompute inverse frequencies
 * PackedCollection invFreq = computeInvFreq(dimHead, theta);
 *
 * // Apply to full sequence
 * queries.add(applyRotaryPositionEmbedding(shape(batch, heads, seqLen, dimHead), invFreq));
 * keys.add(applyRotaryPositionEmbedding(shape(batch, heads, seqLen, dimHead), invFreq));
 * }</pre>
 *
 * @see AttentionFeatures
 * @see org.almostrealism.algebra.PairFeatures
 */
public interface RotationFeatures extends PairFeatures, LayerFeatures {

	/**
	 * Creates a RoPE rotation layer for single-position autoregressive attention using split-half format.
	 *
	 * <p>This method applies rotary embeddings at a specific position, suitable for
	 * token-by-token generation where only one position is processed at a time.</p>
	 *
	 * <p>Split-half format pairs elements as (x[i], x[i+headSize/2]) for rotation,
	 * which matches PyTorch's Qwen/Llama RoPE implementation:</p>
	 * <pre>
	 * output[i] = x[i] * cos[i] - x[i+headSize/2] * sin[i]
	 * output[i+headSize/2] = x[i+headSize/2] * cos[i] + x[i] * sin[i]
	 * </pre>
	 *
	 * <p>Input shape interpretation (heads, freqDim, 2):</p>
	 * <ul>
	 *   <li>When used with interleaved layout (legacy): dimension 2 is [even, odd] elements</li>
	 *   <li>When used with split-half layout: dimension 2 is [firstHalf, secondHalf] elements</li>
	 * </ul>
	 *
	 * <p><strong>IMPORTANT:</strong> For Qwen/Llama models, use with split-half reshape:
	 * {@code reshape(shape(kvDim), shape(kvHeads, 2, headSize/2))} followed by
	 * permutation to get (kvHeads, headSize/2, 2).</p>
	 *
	 * @param shape Input shape (heads, headSize/2, 2) - last dimension is [x1, x2] pair
	 * @param weights Precomputed frequency tensor (seqLen, headSize/2, 2) containing [cos, sin]
	 * @param position Producer that provides the current sequence position
	 * @param requirements Compute requirements for hardware acceleration
	 * @return CellularLayer that applies RoPE rotation
	 * @throws IllegalArgumentException if shape is not 3D or doesn't end with dimension 2
	 */
	default CellularLayer ropeRotation(TraversalPolicy shape, PackedCollection weights,
									   Producer<PackedCollection> position,
									   ComputeRequirement... requirements) {
		if (shape.getDimensions() != 3 || shape.length(2) != 2)
			throw new IllegalArgumentException();

		if (weights.getShape().getDimensions() != 3 || weights.getShape().length(2) != 2)
			throw new IllegalArgumentException();

		if (shape.length(1) != weights.getShape().length(1))
			throw new IllegalArgumentException();

		int heads = shape.length(0);
		int freqDim = shape.length(1);  // headSize / 2
		int weightsFreqSize = freqDim * 2;
		int totalHeadFreq = heads * freqDim;

		// Precompute index maps to avoid subset and repeat operations at runtime
		// All maps are flat arrays for direct index-based gathering

		// For cos/sin: all heads share the same frequencies, so we expand directly
		// cosFullIndexMap[h * freqDim + f] = f * 2 (relative offset in weights for this position)
		// sinFullIndexMap[h * freqDim + f] = f * 2 + 1
		PackedCollection cosRelativeIndexMap = new PackedCollection(shape(totalHeadFreq));
		PackedCollection sinRelativeIndexMap = new PackedCollection(shape(totalHeadFreq));
		for (int h = 0; h < heads; h++) {
			for (int f = 0; f < freqDim; f++) {
				int idx = h * freqDim + f;
				cosRelativeIndexMap.setMem(idx, f * 2);      // cos at freq offset
				sinRelativeIndexMap.setMem(idx, f * 2 + 1);  // sin at freq offset
			}
		}

		// For input: x1[h,f] = input[h * freqDim * 2 + f * 2], x2[h,f] = input[h * freqDim * 2 + f * 2 + 1]
		PackedCollection x1IndexMap = new PackedCollection(shape(totalHeadFreq));
		PackedCollection x2IndexMap = new PackedCollection(shape(totalHeadFreq));
		for (int h = 0; h < heads; h++) {
			for (int f = 0; f < freqDim; f++) {
				int idx = h * freqDim + f;
				x1IndexMap.setMem(idx, h * freqDim * 2 + f * 2);      // x1 at offset 0
				x2IndexMap.setMem(idx, h * freqDim * 2 + f * 2 + 1);  // x2 at offset 1
			}
		}

		// For output interleaving: output[h, f, 0] = out1[h,f], output[h, f, 1] = out2[h,f]
		// Flat output index i maps to: h = i / (freqDim * 2), f = (i % (freqDim * 2)) / 2, comp = i % 2
		PackedCollection outputSourceMap = new PackedCollection(shape(heads * freqDim * 2));
		PackedCollection componentMap = new PackedCollection(shape(heads * freqDim * 2));
		for (int i = 0; i < heads * freqDim * 2; i++) {
			int h = i / (freqDim * 2);
			int f = (i % (freqDim * 2)) / 2;
			int comp = i % 2;
			int sourceIdx = h * freqDim + f;
			outputSourceMap.setMem(i, sourceIdx);
			componentMap.setMem(i, comp);
		}

		// Create an array of weightsFreqSize values for broadcasting without using repeat
		PackedCollection weightsFreqSizeArray = new PackedCollection(shape(totalHeadFreq));
		for (int i = 0; i < totalHeadFreq; i++) {
			weightsFreqSizeArray.setMem(i, weightsFreqSize);
		}

		return layer("ropeRotation", shape, shape, input -> {
			// weights layout: (seqLen, freqDim, 2) = (seqLen, freqDim * 2) flattened
			// We need: cos[h,f] = weights[position, f, 0], sin[h,f] = weights[position, f, 1]
			// All heads share the same cos/sin at each freq

			// Broadcast position to all elements by multiplying with precomputed array
			// posOffset[i] = position * weightsFreqSize for all i in [0, totalHeadFreq)
			// Using elementwise multiplication: position * weightsFreqSizeArray[i] = position * weightsFreqSize
			CollectionProducer posScalar = c(position);
			CollectionProducer posExpanded = c(p(weightsFreqSizeArray)).multiply(posScalar);

			// Add relative offsets to get absolute indices
			CollectionProducer cosIdx = c(p(cosRelativeIndexMap)).add(posExpanded);
			CollectionProducer sinIdx = c(p(sinRelativeIndexMap)).add(posExpanded);

			// Gather cos and sin values directly for all heads
			CollectionProducer cos = c(shape(totalHeadFreq), p(weights), cosIdx);
			CollectionProducer sin = c(shape(totalHeadFreq), p(weights), sinIdx);

			// Gather x1 and x2 from input using precomputed index maps
			CollectionProducer x1 = c(shape(totalHeadFreq), input, p(x1IndexMap));
			CollectionProducer x2 = c(shape(totalHeadFreq), input, p(x2IndexMap));

			// Apply split-half rotation:
			// out1 = x1 * cos - x2 * sin
			// out2 = x2 * cos + x1 * sin
			CollectionProducer out1 = x1.multiply(cos).subtract(x2.multiply(sin));
			CollectionProducer out2 = x2.multiply(cos).add(x1.multiply(sin));

			// Interleave out1 and out2 to create (heads, freqDim, 2)
			CollectionProducer out1Vals = c(shape(heads * freqDim * 2), out1, p(outputSourceMap));
			CollectionProducer out2Vals = c(shape(heads * freqDim * 2), out2, p(outputSourceMap));

			// Select based on component: comp==0 ? out1 : out2
			CollectionProducer compVals = c(p(componentMap));
			CollectionProducer result = greaterThan(compVals, c(0.5), out2Vals, out1Vals, true);

			return result.reshape(shape(heads, freqDim, 2));
		}, List.of(weights, cosRelativeIndexMap, sinRelativeIndexMap, x1IndexMap, x2IndexMap, outputSourceMap, componentMap, weightsFreqSizeArray), requirements);
	}

	/**
	 * Computes rotary frequency tensor from inverse frequencies for full-sequence RoPE.
	 *
	 * <p>This precomputes position * inv_freq for all positions, producing a tensor
	 * that can be used with {@link #applyRotaryPositionEmbedding} for batch processing.</p>
	 *
	 * @param seqLen Maximum sequence length to compute frequencies for
	 * @param invFreq Inverse frequency tensor of shape (dimHead/4)
	 * @return Frequency tensor of shape (seqLen, dimHead/2) ready for cos/sin computation
	 */
	default PackedCollection computeRotaryFreqs(int seqLen, PackedCollection invFreq) {
		int freqDim = invFreq.getShape().getTotalSize(); // (dimHead / 4)
		int rotaryDim = freqDim * 2; // (dimHead / 2)

		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim));

		// Compute position * inv_freq for each position and frequency
		for (int pos = 0; pos < seqLen; pos++) {
			for (int f = 0; f < freqDim; f++) {
				double freq_val = pos * invFreq.toDouble(f);
				freqs.setMem(pos * rotaryDim + f, freq_val);
				freqs.setMem(pos * rotaryDim + f + freqDim, freq_val);
			}
		}

		return freqs;
	}

	/**
	 * Creates a function that applies rotary position embedding to any compatible input shape.
	 *
	 * @param invFreq Inverse frequency tensor for computing rotations
	 * @return Function mapping input shape to a CellularLayer that applies RoPE
	 */
	default Function<TraversalPolicy, CellularLayer> applyRotaryPositionEmbedding(PackedCollection invFreq) {
		return inputShape -> applyRotaryPositionEmbedding(inputShape, invFreq);
	}

	/**
	 * Applies rotary positional embedding to a full sequence input.
	 *
	 * <p>This method applies RoPE to all positions in a sequence simultaneously,
	 * suitable for batch processing or full-sequence attention. The rotation is applied
	 * only to the first {@code rotaryDim} dimensions; remaining dimensions pass through unchanged.</p>
	 *
	 * <p>Implementation:</p>
	 * <ol>
	 *   <li>Precomputes frequency tensor from invFreq for all positions</li>
	 *   <li>Expands frequencies to match batch and head dimensions</li>
	 *   <li>Applies rotation: x * cos(freqs) + rotate_half(x) * sin(freqs)</li>
	 *   <li>Concatenates rotated and non-rotated portions</li>
	 * </ol>
	 *
	 * @param inputShape Shape (batchSize, heads, seqLen, dimHead)
	 * @param invFreq Inverse frequency tensor of shape (dimHead/4)
	 * @return CellularLayer that applies RoPE to the input
	 * @throws IllegalArgumentException if inputShape is not 4-dimensional
	 */
	default CellularLayer applyRotaryPositionEmbedding(TraversalPolicy inputShape, PackedCollection invFreq) {
		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException("Expected 4D input for sequence rotary embedding");
		}

		int batchSize = inputShape.length(0);
		int heads = inputShape.length(1);
		int seqLen = inputShape.length(2);
		int dimHead = inputShape.length(3);

		// Precompute the frequency tensor
		PackedCollection freqs = computeRotaryFreqs(seqLen, invFreq);
		int rotaryDim = freqs.getShape().length(1);
		if (freqs.getShape().length(0) != seqLen) {
			throw new IllegalArgumentException();
		}

		return layer("sequenceRotaryEmbedding", inputShape, inputShape, input -> {
			// Extract the rotary part (first rotaryDim dimensions)
			CollectionProducer rotaryPart =
					c(input).subset(shape(batchSize, heads, seqLen, rotaryDim), 0, 0, 0, 0);

			// Extract the non-rotary part (remaining dimensions)
			CollectionProducer nonRotaryPart =
					c(input).subset(shape(batchSize, heads, seqLen, dimHead - rotaryDim),
							0, 0, 0, rotaryDim);

			// Apply rotation to the rotary part
			CollectionProducer rotated = applyRotaryTransform(
					rotaryPart, cp(freqs), batchSize, heads, seqLen, rotaryDim);

			// Concatenate rotated and non-rotary parts along dimension 3
			return concat(3, rotated, nonRotaryPart);
		}, List.of(freqs));
	}

	/**
	 * Applies the rotary transform to an input tensor.
	 *
	 * <p>This implements the core RoPE formula:
	 * output = input * cos(freqs) + rotate_half(input) * sin(freqs)</p>
	 *
	 * @param input Input tensor (batchSize, heads, seqLen, rotaryDim)
	 * @param freqs Frequency tensor (seqLen, rotaryDim)
	 * @param batchSize Batch dimension
	 * @param heads Number of attention heads
	 * @param seqLen Sequence length
	 * @param rotaryDim Dimension to apply rotation (typically dimHead/2)
	 * @return Rotated tensor with same shape as input
	 */
	default CollectionProducer applyRotaryTransform(
			CollectionProducer input,
			CollectionProducer freqs,
			int batchSize, int heads, int seqLen, int rotaryDim) {
	
		// Validate input shapes
		if (input.getShape().getDimensions() != 4) {
			throw new IllegalArgumentException("Input must have 4 dimensions, got: " + input.getShape());
		}
	
		if (input.getShape().length(0) != batchSize || input.getShape().length(1) != heads ||
			input.getShape().length(2) != seqLen || input.getShape().length(3) != rotaryDim) {
			throw new IllegalArgumentException("Input shape " + input.getShape() +
				" doesn't match expected (" + batchSize + ", " + heads + ", " + seqLen + ", " + rotaryDim + ")");
		}

		// Expand freqs from (seqLen, rotaryDim) to (batchSize, heads, seqLen, rotaryDim)
		CollectionProducer expandedFreqs = freqs
				.repeat(0, batchSize)    // (batchSize, seqLen, rotaryDim)
				.repeat(1, heads);       // (batchSize, heads, seqLen, rotaryDim)

		CollectionProducer cosFreqs = cos(expandedFreqs);
		CollectionProducer sinFreqs = sin(expandedFreqs);

		CollectionProducer rotateHalfInput =
				rotateHalf(input, batchSize, heads, seqLen, rotaryDim);

		// input * cos(freqs) + rotate_half(input) * sin(freqs)
		return input.multiply(cosFreqs).add(rotateHalfInput.multiply(sinFreqs));
	}

	/**
	 * Rotates the input tensor by swapping and negating halves.
	 *
	 * <p>For input [..., d], returns [..., -x2, x1] where:</p>
	 * <ul>
	 *   <li>x1 is the first half: input[..., 0:d/2]</li>
	 *   <li>x2 is the second half: input[..., d/2:d]</li>
	 * </ul>
	 *
	 * <p>This is the "rotate_half" operation from the original RoPE implementation,
	 * used to compute the imaginary component of the complex rotation.</p>
	 *
	 * @param input Input tensor (batchSize, heads, seqLen, rotaryDim)
	 * @param batchSize Batch dimension
	 * @param heads Number of attention heads
	 * @param seqLen Sequence length
	 * @param rotaryDim Dimension being rotated (must be even)
	 * @return Tensor with halves swapped and first half negated
	 */
	default CollectionProducer rotateHalf(
			CollectionProducer input,
			int batchSize, int heads, int seqLen, int rotaryDim) {
		int halfDim = rotaryDim / 2;
		
		// Extract first half (x1)
		CollectionProducer x1 =
				input.subset(shape(batchSize, heads, seqLen, halfDim),
						0, 0, 0, 0);
		
		// Extract second half (x2)
		CollectionProducer x2 =
				input.subset(shape(batchSize, heads, seqLen, halfDim),
						0, 0, 0, halfDim);
		
		// Return concatenation of [-x2, x1] along dimension 3
		return concat(3, x2.minus(), x1);
	}

	/**
	 * Returns a default instance of RotationFeatures.
	 * Useful for static access to RoPE utilities without implementing the interface.
	 *
	 * @return A new RotationFeatures instance
	 */
	static RotationFeatures getInstance() {
		return new RotationFeatures() { };
	}
}