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

import io.almostrealism.collect.IdentityCollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

public interface MatrixFeatures extends CollectionFeatures {
	default <T extends PackedCollection<?>> CollectionProducer<T> identity(int size) {
		return identity(shape(size, size));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> identity(TraversalPolicy shape) {
		if (shape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		return new TraversableExpressionComputation<>("identity", shape.traverseEach(),
				(args) -> new IdentityCollectionExpression(shape.traverse(1)));
	}


	default <T extends PackedCollection<?>> CollectionProducer<T> matmul(Producer<T> matrix, Producer<T> vector) {
		TraversalPolicy shape = shape(matrix);
		TraversalPolicy vshape = shape(vector);
		if (shape.getDimensions() != 2)
			throw new IllegalArgumentException();

		CollectionProducer<PackedCollection<?>> a;
		CollectionProducer<PackedCollection<?>> b;

		int n = shape.length(0);

		if (vshape.getTraversalAxis() < (vshape.getDimensions() - 1)) {
			// System.out.println("WARN: Matrix multiplication with vector on axis " + vshape.getTraversalAxis());

			int m = shape.length(1);
			int p = vshape.length(1);

			a = c(matrix).repeat(p);
			b = c(vector).enumerate(1, 1)
					.reshape(p, m)
					.traverse(1)
					.repeat(n)
					.reshape(p, n, m)
					.traverse(1);
			CollectionProducer<PackedCollection<?>> product = multiply(traverseEach(a), traverseEach(b));
			return (CollectionProducer) product
					.reshape(p, n, m).sum(2)
					.traverse(0)
					.enumerate(1, 1)
					.reshape(n, p);
		} else {
			a = c(matrix);
			b = repeat(n, vector);
		}

		return multiply(traverseEach(a), traverseEach(b)).traverse(1).sum();
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> mproduct(Producer<T> a, Producer<T> b) {
		int n = shape(a).length(0);
		int m = shape(a).length(1);
		int p = shape(b).length(1);

		return (CollectionProducer) c(b).enumerate(1, 1)
				.reshape(p, m)
				.traverse(1)
				.repeat(n)
				.reshape(p, n, m)
				.traverse(1)
				.multiply(c(a).repeat(p))
				.reshape(p, n, m).sum(2)
				.enumerate(1, 1)
				.reshape(n, p);
	}

	static MatrixFeatures getInstance() {
		return new MatrixFeatures() {};
	}
}
