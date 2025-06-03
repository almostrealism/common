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
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;

import java.util.List;
import java.util.function.Function;

public interface RotationFeatures extends PairFeatures, LayerFeatures {
	default CellularLayer ropeRotation(TraversalPolicy shape, PackedCollection<?> weights,
									   Producer<PackedCollection<?>> position,
									   ComputeRequirement... requirements) {
		if (shape.getDimensions() != 3 || shape.length(2) != 2)
			throw new IllegalArgumentException();

		if (weights.getShape().getDimensions() != 3 || weights.getShape().length(2) != 2)
			throw new IllegalArgumentException();

		if (shape.length(1) != weights.getShape().length(1))
			throw new IllegalArgumentException();

		int heads = shape.length(0);
		int headSize = shape.length(1);

		return layer("ropeRotation", shape, shape, input -> {
			Producer<PackedCollection<?>> pos = concat(position, c(0), c(0)).reshape(3);
			CollectionProducer<PackedCollection<?>> r = subset(shape(1, headSize, 2), c(p(weights)), pos);
			return multiplyComplex(traverse(1, input), r.traverse(1));
		}, List.of(weights), requirements);
	}

	default PackedCollection<?> computeRotaryFreqs(int seqLen, PackedCollection<?> invFreq) {
		int freqDim = invFreq.getShape().getTotalSize(); // (dimHead / 4)
		int rotaryDim = freqDim * 2; // (dimHead / 2)

		PackedCollection<?> freqs = new PackedCollection<>(shape(seqLen, rotaryDim));

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

	default Function<TraversalPolicy, CellularLayer> applyRotaryPositionEmbedding(PackedCollection<?> invFreq) {
		return inputShape -> applyRotaryPositionEmbedding(inputShape, invFreq);
	}

	/**
		* Applies rotary positional embedding to a sequence input.
		*
		* @param inputShape  (batchSize, heads, seqLen, dimHead) - matches Python layout after rearrange
		*/
	default CellularLayer applyRotaryPositionEmbedding(TraversalPolicy inputShape, PackedCollection<?> invFreq) {
		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException("Expected 4D input for sequence rotary embedding");
		}

		int batchSize = inputShape.length(0);
		int heads = inputShape.length(1);
		int seqLen = inputShape.length(2);
		int dimHead = inputShape.length(3);

		// only rotate first half
		int freqDim = invFreq.getShape().getTotalSize();
		int rotaryDim = freqDim * 2;

		// Precompute the frequency tensor
		PackedCollection<?> freqs = computeRotaryFreqs(seqLen, invFreq);

		return layer("sequenceRotaryEmbedding", inputShape, inputShape, input -> {
			// Extract the rotary part (first rotaryDim dimensions)
			CollectionProducer<PackedCollection<?>> rotaryPart =
					c(input).subset(shape(batchSize, heads, seqLen, rotaryDim), 0, 0, 0, 0);

			// Extract the non-rotary part (remaining dimensions)
			CollectionProducer<PackedCollection<?>> nonRotaryPart =
					c(input).subset(shape(batchSize, heads, seqLen, dimHead - rotaryDim),
							0, 0, 0, rotaryDim);

			// Apply rotation to the rotary part
			CollectionProducer<PackedCollection<?>> rotated = applyRotaryTransform(
					rotaryPart, cp(freqs), batchSize, heads, seqLen, rotaryDim);

			// Concatenate rotated and non-rotary parts along dimension 3
			return concat(3, rotated, nonRotaryPart);
		}, List.of(freqs));
	}

	default CollectionProducer<PackedCollection<?>> applyRotaryTransform(
			CollectionProducer<PackedCollection<?>> input,
			CollectionProducer<PackedCollection<?>> freqs,
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
		// This matches Python's frequency expansion for (batch, heads, seq, dim_head) layout
		CollectionProducer<PackedCollection<?>> expandedFreqs = freqs
				.repeat(0, batchSize)    // (batchSize, seqLen, rotaryDim)
				.repeat(1, heads);       // (batchSize, heads, seqLen, rotaryDim)

		CollectionProducer<PackedCollection<?>> cosFreqs = cos(expandedFreqs);
		CollectionProducer<PackedCollection<?>> sinFreqs = sin(expandedFreqs);

		CollectionProducer<PackedCollection<?>> rotateHalfInput = rotateHalf(input, batchSize, heads, seqLen, rotaryDim);

		// Apply: input * cos(freqs) + rotate_half(input) * sin(freqs)
		return input.multiply(cosFreqs).add(rotateHalfInput.multiply(sinFreqs));
	}
	default CollectionProducer<PackedCollection<?>> rotateHalf(
			CollectionProducer<PackedCollection<?>> input,
			int batchSize, int heads, int seqLen, int rotaryDim) {
		// Reshape the input so that the last dimension splits the tensor into two halves
		CollectionProducer<PackedCollection<?>> x = input.reshape(shape(batchSize, heads, seqLen, rotaryDim / 2, 2));

		// Take the subset including column 0 of the final dimension
		// these correspond to the even elements in the original input
		CollectionProducer<PackedCollection<?>> x1 =
				x.subset(shape(batchSize, heads, seqLen, rotaryDim / 2, 1),
						0, 0, 0, 0, 0)
						.reshape(batchSize, heads, seqLen, rotaryDim / 2);

		// Take the subset including column 1 of the final dimension
		// these correspond to the odd elements in the original input
		CollectionProducer<PackedCollection<?>> x2 =
				x.subset(shape(batchSize, heads, seqLen, rotaryDim / 2, 1),
						0, 0, 0, 0, 1)
						.reshape(batchSize, heads, seqLen, rotaryDim / 2);

		// Concatenate the two halves, with the second half negated
		return concat(3, x2.minus(), x1)
				.reshape(batchSize, heads, seqLen, rotaryDim);
	}

	static RotationFeatures getInstance() {
		return new RotationFeatures() { };
	}
}