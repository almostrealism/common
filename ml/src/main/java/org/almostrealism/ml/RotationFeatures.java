/*
 * Copyright 2024 Michael Murray
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

	default CollectionProducer<PackedCollection<?>> applySequenceRotaryEmbeddings(
			Producer<PackedCollection<?>> x,
			PackedCollection<?> freqCis,
			int seqLen, int heads, int dimHead) {

		// Reshape x from (seqLen, heads, dimHead) to (seqLen, heads, dimHead / 2, 2)
		// treating each pair as a complex number (real, imag)
		CollectionProducer<PackedCollection<?>> xComplex =
				c(x).reshape(seqLen, heads, dimHead / 2, 2);

		// freqCis has shape (seqLen, dimHead / 2, 2)
		// We need to repeat to (seqLen, heads, dimHead/2, 2) to broadcast
		CollectionProducer freqCisReshaped =
				cp(freqCis).reshape(seqLen, dimHead / 2, 2)
						.traverse(2).repeat(heads);

		// Use multiplyComplex to apply the rotation to each position
		// This performs (a+bi) * (c+di) for each complex number pair
		CollectionProducer<PackedCollection<?>> rotated =
				multiplyComplex(xComplex.traverse(3), freqCisReshaped.traverse(3));

		// Reshape back to original shape
		return rotated.reshape(1, seqLen, heads, dimHead);
	}

	/**
	 * A sequence-aware version of ropeRotation that applies rotary embeddings to all
	 * positions in a sequence at once.
	 */
	default CellularLayer sequenceRotaryEmbedding(
			TraversalPolicy shape,  // (1, seqLen, heads, dimHead)
			PackedCollection<?> freqCis, // (seqLen, dimHead/2, 2)
			ComputeRequirement... requirements) {

		if (shape.getDimensions() != 4 || shape.length(0) != 1)
			throw new IllegalArgumentException("Expected shape (1, seqLen, heads, dimHead) but got " + shape);

		int seqLen = shape.length(1);
		int heads = shape.length(2);
		int dimHead = shape.length(3);

		if (dimHead % 2 != 0)
			throw new IllegalArgumentException("dimHead must be even");

		if (freqCis.getShape().getDimensions() != 3 ||
				freqCis.getShape().length(0) != seqLen ||
				freqCis.getShape().length(1) != dimHead/2 ||
				freqCis.getShape().length(2) != 2)
			throw new IllegalArgumentException("freqCis has incorrect shape: " + freqCis.getShape());

		return layer("sequenceRotaryEmbedding", shape, shape, input ->
						applySequenceRotaryEmbeddings(input, freqCis, seqLen, heads, dimHead).each(),
				List.of(freqCis), requirements);
	}

	static RotationFeatures getInstance() {
		return new RotationFeatures() { };
	}
}
