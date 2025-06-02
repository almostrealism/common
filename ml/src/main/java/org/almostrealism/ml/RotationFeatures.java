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
	 * @param inputShape  (batchSize, seqLen, heads, dimHead)
	 */
	default CellularLayer applyRotaryPositionEmbedding(TraversalPolicy inputShape, PackedCollection<?> invFreq) {
		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException("Expected 4D input for sequence rotary embedding");
		}

		int batchSize = inputShape.length(0);
		int seqLen = inputShape.length(1);
		int heads = inputShape.length(2);
		int dimHead = inputShape.length(3);

		// only rotate first half
		int freqDim = invFreq.getShape().getTotalSize();
		int rotaryDim = freqDim * 2;

		// Precompute the frequency tensor
		PackedCollection<?> freqs = computeRotaryFreqs(seqLen, invFreq);

		return layer("sequenceRotaryEmbedding", inputShape, inputShape, input -> {
			// Extract the rotary part (first rotaryDim dimensions)
			CollectionProducer<PackedCollection<?>> rotaryPart =
					c(input).subset(shape(batchSize, seqLen, heads, rotaryDim), 0, 0, 0, 0);

			// Extract the non-rotary part (remaining dimensions)
			CollectionProducer<PackedCollection<?>> nonRotaryPart =
					c(input).subset(shape(batchSize, seqLen, heads, dimHead - rotaryDim),
							0, 0, 0, rotaryDim);

			// Apply rotation to the rotary part
			CollectionProducer<PackedCollection<?>> rotated = applyRotaryTransform(
					rotaryPart, cp(freqs), batchSize, seqLen, heads, rotaryDim);

			// Concatenate rotated and non-rotary parts
			return concat(inputShape, rotated, nonRotaryPart);
		}, List.of(freqs));
	}

	default CollectionProducer<PackedCollection<?>> applyRotaryTransform(
			CollectionProducer<PackedCollection<?>> input,
			CollectionProducer<PackedCollection<?>> freqs,
			int batchSize, int seqLen, int heads, int rotaryDim) {

		int halfDim = rotaryDim / 2;

		// Reshape input for rotation: (batchSize, seqLen, heads, rotaryDim)
		// Split into two halves for rotation
		CollectionProducer<PackedCollection<?>> x1 = input.subset(
				shape(batchSize, seqLen, heads, halfDim), 0, 0, 0, 0);
		CollectionProducer<PackedCollection<?>> x2 = input.subset(
				shape(batchSize, seqLen, heads, halfDim), 0, 0, 0, halfDim);

		// Repeat freqs to match input dimensions
		// (seqLen, rotaryDim) -> (batchSize, seqLen, heads, rotaryDim)
		CollectionProducer<PackedCollection<?>> expandedFreqs = freqs
				.traverse(0).repeat(batchSize)  // (batchSize, seqLen, rotaryDim)
				.traverse(2).repeat(heads);  // (batchSize, seqLen, heads, rotaryDim)

		// Extract cos and sin components
		CollectionProducer<PackedCollection<?>> cosFreqs = cos(expandedFreqs.subset(
				shape(batchSize, seqLen, heads, halfDim), 0, 0, 0, 0));
		CollectionProducer<PackedCollection<?>> sinFreqs = sin(expandedFreqs.subset(
				shape(batchSize, seqLen, heads, halfDim), 0, 0, 0, halfDim));

		// Apply rotation: x_rotated = x * cos(freq) + rotate_half(x) * sin(freq)
		// rotate_half swaps and negates: [x1, x2] -> [-x2, x1]
		CollectionProducer<PackedCollection<?>> rotated_x1 =
				x1.multiply(cosFreqs).subtract(x2.multiply(sinFreqs));
		CollectionProducer<PackedCollection<?>> rotated_x2 =
				x1.multiply(sinFreqs).add(x2.multiply(cosFreqs));

		// Concatenate the rotated halves
		return concat(shape(batchSize, seqLen, heads, rotaryDim), rotated_x1, rotated_x2);
	}

	static RotationFeatures getInstance() {
		return new RotationFeatures() { };
	}
}
