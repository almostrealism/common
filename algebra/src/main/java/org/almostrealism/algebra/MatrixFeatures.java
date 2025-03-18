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
		TraversalPolicy mShape = shape(matrix);
		TraversalPolicy vShape = shape(vector);

 		if (Algebraic.isZero(vector) || Algebraic.isZero(matrix)) {
			if (vShape.getDimensions() == 1) {
				return zeros(shape(mShape.length(0), 1));
			}

			return zeros(shape(mShape.length(0), vShape.length(1)));
		}

		if (mShape.getTotalSizeLong() == 1 || vShape.getTotalSizeLong() == 1) {
			return multiply(c(matrix), c(vector));
		} else if (mShape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		boolean each = vShape.getTraversalAxis() >= (vShape.getDimensions() - 1);
		boolean onlyColumns = vShape.getDimensions() > 1 && vShape.length(vShape.getDimensions() - 1) == 1;
		boolean singleRow = vShape.getDimensions() == 1;

		// If the matrix is being multiplied by a vector, or by
		// a batch of vectors, rather than a matrix (or batch of
		// matrices) then simple multiplication followed by a sum
		// is a sufficient alternative to matrix multiplication
		// via genuine weighted sum
		if (each || onlyColumns || singleRow) {
			if (onlyColumns) {
				vShape = vShape.trim();
				vShape = vShape.traverse(vShape.getDimensions() - 1);
				vector = reshape(vShape, vector);
			}

			int batchAxis = vShape.getDimensions();
			int outputSize = mShape.length(0);
			CollectionProducer<PackedCollection<?>> a = c(matrix);
			CollectionProducer<PackedCollection<?>> b = repeat(outputSize, vector);
			return multiply(traverseEach(a), traverseEach(b)).traverse(batchAxis).sum();
		}

		TraversalPolicy vectorShape = padDimensions(vShape, 1, 2, true);
		vectorShape = padDimensions(vectorShape, 3);

		int b = vectorShape.length(0);
		int m = mShape.length(0);
		int n = mShape.length(1);
		int p = vectorShape.length(2);

		if (Algebraic.isIdentity(vectorShape.length(1), matrix)) {
			return c(vector);
		} else if (Algebraic.isIdentity(vectorShape.length(1), vector)) {
			return c(matrix);
		}

		// Is the matrix just a scalar transform?
		Optional<Computable> scalar =
				Algebraic.getDiagonalScalar(vectorShape.length(1), matrix);
		if (scalar.isPresent() && scalar.get() instanceof Producer) {
			return multiply(c(vector), (Producer) scalar.get());
		}

		// Is the vector just a scalar transform?
		scalar =
				Algebraic.getDiagonalScalar(mShape.length(0), vector);
		if (b == 1 && scalar.isPresent() && scalar.get() instanceof Producer) {
			return multiply(c(matrix), (Producer) scalar.get());
		}

		if (Algebraic.isDiagonal(vectorShape.length(1), matrix) ||
				Algebraic.isDiagonal(mShape.length(0), vector)) {
			console.features(MatrixFeatures.class)
					.log("Matrix multiplication by diagonal matrix");
		}

		TraversalPolicy matrixShape = shape(1, m, n, 1);
		vectorShape = shape(b, 1, n, p);

		TraversalPolicy resultShape = shape(b, m, 1, p);
		TraversalPolicy matrixPositions =
				resultShape
						.withRate(0, 1, b)
						.withRate(3, n, p);
		TraversalPolicy vectorPositions =
				resultShape
						.withRate(1, 1, m);
		TraversalPolicy matrixGroupShape =
				shape(1, 1, n, 1);
		TraversalPolicy vectorGroupShape =
				shape(1, 1, n, 1);

		TraversalPolicy finalShape;
		if (vShape.getDimensions() == 1) {
			finalShape = shape(m);
		} else if (vShape.getDimensions() == 2) {
			finalShape = shape(m, p);
		} else {
			finalShape = shape(b, m, p);
		}

		return weightedSum("matmul",
				matrixPositions, vectorPositions,
				matrixGroupShape, vectorGroupShape,
				reshape(matrixShape, matrix),
				reshape(vectorShape, vector)).reshape(finalShape.traverseEach());
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
