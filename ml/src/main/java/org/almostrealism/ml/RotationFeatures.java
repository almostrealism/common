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

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;

import java.util.List;

public interface RotationFeatures extends PairFeatures, LayerFeatures {
	default CellularLayer ropeRotation(TraversalPolicy shape, PackedCollection<?> weights, Producer<PackedCollection<?>> position, ComputeRequirement... requirements) {
		if (shape.getDimensions() != 3 || shape.length(2) != 2)
			throw new IllegalArgumentException();

		if (weights.getShape().getDimensions() != 3 || weights.getShape().length(2) != 2)
			throw new IllegalArgumentException();

		if (shape.length(1) != weights.getShape().length(1))
			throw new IllegalArgumentException();

		int heads = shape.length(0);
		int headSize = shape.length(1);

		return layer("ropeRotation", shape, shape, Cell.of((input, next) -> {
			Producer<PackedCollection<?>> pos = concat(position, c(0), c(0)).reshape(3);
			CollectionProducer<PackedCollection<?>> r = subset(shape(1, headSize, 2), c(p(weights)), pos);
			CollectionProducer<PackedCollection<?>> o = multiplyComplex(traverse(1, input), r.traverse(1));
			if (next != null) return next.push(o);
			return new OperationList();
		}), null, List.of(weights), requirements);
	}

	static RotationFeatures getInstance() {
		return new RotationFeatures() { };
	}
}
