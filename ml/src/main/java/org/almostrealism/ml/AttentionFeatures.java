/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.OperationWithInfo;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;

public interface AttentionFeatures extends LayerFeatures {

	default CellularLayer attentionKeys(TraversalPolicy inputShape, Producer<PackedCollection<?>> keys, Producer<PackedCollection<?>> position) {
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

		return layer("attentionKeys", inputShape, outputShape, Cell.of((input, next) -> {
			CollectionProducer<PackedCollection<?>> o = traverse(1, keys).map(v -> v.multiply(input))
					.traverse(2).sum()
					.divide(c(Math.sqrt(headSize)))
					.reshape(shape(seqLength, heads))
					.enumerate(1, 1);
			if (next != null) return next.push(o.reshape(outputShape));
			return new OperationList();
		}), null);
	}

	default CellularLayer attentionValues(TraversalPolicy inputShape, Producer<PackedCollection<?>> values, Producer<PackedCollection<?>> position) {
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

		return layer("attentionValues", inputShape, outputShape, Cell.of((input, next) -> {
			Producer<PackedCollection<?>> v = reshape(shape(seqLength, dim), values);
			v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

			CollectionProducer<PackedCollection<?>> a = traverse(1, input).expand(headSize, x -> x.repeat(headSize));
			CollectionProducer<PackedCollection<?>> o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();
			if (next != null) return next.push(o.reshape(shape(dim).traverseEach()));
			return new OperationList();
		}), null);
	}

	static AttentionFeatures getInstance() {
		return new AttentionFeatures() { };
	}
}
