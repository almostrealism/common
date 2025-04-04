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

package org.almostrealism.algebra;

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.WeightedSumExpression;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.DiagonalMatrixComputation;
import org.almostrealism.algebra.computations.IdentityMatrixComputation;
import org.almostrealism.calculus.DeltaAlternate;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Optional;

public interface MatrixFeatures extends AlgebraFeatures {
	default <T extends PackedCollection<?>> CollectionProducer<T> identity(int size) {
		return identity(shape(size, size));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> identity(TraversalPolicy shape) {
		if (shape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		return new IdentityMatrixComputation<>(shape.traverseEach());
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> diagonal(Producer<T> vector) {
		TraversalPolicy shape = shape(vector);

		if (shape.getDimensions() != 1) {
			throw new IllegalArgumentException();
		} else if (shape.length(0) == 1) {
			return c(vector);
		}

		TraversalPolicy diagonalShape = shape(shape.length(0), shape.length(0)).traverse(1);
		return new DiagonalMatrixComputation<>(diagonalShape, vector);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> matmul(Producer<T> matrix, Producer<T> vector) {
		TraversalPolicy shape = shape(matrix);
		TraversalPolicy vshape = shape(vector);

 		if (Algebraic.isZero(vector) || Algebraic.isZero(matrix)) {
			if (vshape.getDimensions() == 1) {
				return zeros(shape(shape.length(0), 1));
			}

			return zeros(shape(shape.length(0), vshape.length(1)));
		}

		if (shape.getTotalSizeLong() == 1 || vshape.getTotalSizeLong() == 1) {
			return multiply(c(matrix), c(vector));
		} else if (shape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		CollectionProducer<PackedCollection<?>> a;
		CollectionProducer<PackedCollection<?>> b;

		int m = shape.length(0);
		int n = shape.length(1);

		if (vshape.getTraversalAxis() < (vshape.getDimensions() - 1)) {
			if (WeightedSumExpression.enableCollectionExpression) {
				TraversalPolicy weightShape = padDimensions(vshape, 1, 2, true);
				int p = weightShape.length(1);

				if (Algebraic.isIdentity(vshape.length(0), matrix)) {
					return c(vector);
				} else if (Algebraic.isIdentity(shape.length(0), vector)) {
					return c(matrix);
				}

				// Is the matrix just a scalar transform?
				Optional<Computable> scalar =
						Algebraic.getDiagonalScalar(vshape.length(0), matrix);
				if (scalar.isPresent() && scalar.get() instanceof Producer) {
					return multiply(c(vector), (Producer) scalar.get());
				}

				// Is the vector just a scalar transform?
				scalar =
						Algebraic.getDiagonalScalar(shape.length(0), vector);
				if (scalar.isPresent() && scalar.get() instanceof Producer) {
					return multiply(c(matrix), (Producer) scalar.get());
				}

				if (Algebraic.isDiagonal(vshape.length(0), matrix) ||
						Algebraic.isDiagonal(shape.length(0), vector)) {
					console.features(MatrixFeatures.class)
							.log("Matrix multiplication by diagonal matrix");
				}

				return weightedSum("matmul",
						shape(m, p).withRate(1, n, p),
						shape(1, p),
						shape(1, n), shape(n, 1),
						matrix, reshape(weightShape, vector));
			}

			// warn("Matrix multiplication with vector on axis " + vshape.getTraversalAxis());

			int p = vshape.length(1);

			a = c(matrix).repeat(p);
			b = c(vector).enumerate(1, 1)
					.reshape(p, n)
					.traverse(1)
					.repeat(m)
					.reshape(p, m, n)
					.traverse(1);
			CollectionProducer<PackedCollection<?>> product = multiply(traverseEach(a), traverseEach(b));
			return (CollectionProducer) product
					.reshape(p, m, n).sum(2)
					.traverse(0)
					.enumerate(1, 1)
					.reshape(m, p);
		} else {
			a = c(matrix);
			b = repeat(m, vector);
		}

		return multiply(traverseEach(a), traverseEach(b)).traverse(1).sum();
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducer<T> mproduct(Producer<T> a, Producer<T> b) {
		if (WeightedSumExpression.enableCollectionExpression) {
			return matmul(traverse(0, a), traverse(0, b));
		}
		
		int m = shape(a).length(0);
		int n = shape(a).length(1);
		int p = shape(b).length(1);

		return (CollectionProducer) c(b).enumerate(1, 1)
				.reshape(p, n)
				.traverse(1)
				.repeat(m)
				.reshape(p, m, n)
				.traverse(1)
				.multiply(c(a).repeat(p))
				.reshape(p, m, n).sum(2)
				.enumerate(1, 1)
				.reshape(m, p);
	}


	default <T extends Shape<?>> CollectionProducer<T> attemptDelta(Producer<T> producer,
																	Producer<?> target) {
		if (producer instanceof DeltaAlternate) {
			CollectionProducer<T> alt = ((DeltaAlternate) producer).getDeltaAlternate();
			if (alt != null) return alt.delta(target);
		}

		TraversalPolicy shape = shape(producer);
		TraversalPolicy targetShape = shape(target);

		if (AlgebraFeatures.cannotMatch(producer, target)) {
			return (CollectionProducer)
					zeros(shape.append(targetShape));
		} else if (AlgebraFeatures.match(producer, target)) {
			return (CollectionProducer)
					identity(shape(shape.getTotalSize(), targetShape.getTotalSize()))
							.reshape(shape.append(targetShape));
		}

		return null;
	}

	static MatrixFeatures getInstance() {
		return new MatrixFeatures() {};
	}
}
