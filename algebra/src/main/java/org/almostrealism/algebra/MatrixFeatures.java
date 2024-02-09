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

package org.almostrealism.algebra;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

public interface MatrixFeatures extends CollectionFeatures {
	default <T extends PackedCollection<?>> CollectionProducerComputation<T> matmul(Producer<T> matrix, Producer<T> vector) {
		TraversalPolicy shape = shape(matrix);
		TraversalPolicy vshape = shape(vector);
		if (shape.getDimensions() != 2)
			throw new IllegalArgumentException();

		if (vshape.getTraversalAxis() < (vshape.getDimensions() - 1)) {
			System.out.println("WARN: Matrix multiplication with vector on axis " + vshape.getTraversalAxis());
		}

		int d = shape.length(0);
		return multiply(traverseEach(matrix), traverseEach(repeat(d, vector))).traverse(1).sum();
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> mproduct(Producer<T> a, Producer<T> b) {
		int n = shape(a).length(0);
		int m = shape(a).length(1);
		int p = shape(b).length(1);

		return c(c(b).enumerate(1, 1)
				.reshape(p, m)
				.traverse(1)
				.expand(n, v -> v.repeat(n))
				.reshape(p, n, m)
				.traverse(1)
				.map(v -> multiply(v, c(a)))
				.reshape(p, n, m).sum(2)
				.enumerate(1, 1)
				.reshape(n, p));
	}

	static MatrixFeatures getInstance() {
		return new MatrixFeatures() {};
	}
}
