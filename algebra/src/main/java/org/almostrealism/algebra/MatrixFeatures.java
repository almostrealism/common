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

/**
 * Provides convenient factory methods for creating matrix computations and operations.
 *
 * <p>
 * {@link MatrixFeatures} extends {@link AlgebraFeatures} to provide specialized methods for working
 * with matrices in the computation graph framework. This interface includes optimized implementations
 * of common matrix operations like multiplication, identity matrix creation, and diagonal matrices.
 * </p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * public class MatrixComputation implements MatrixFeatures {
 *     public Producer<PackedCollection> compute() {
 *         // Create identity matrix
 *         CollectionProducer I = identity(3);  // 3x3 identity
 *
 *         // Create diagonal matrix from vector
 *         CollectionProducer vec = c(1.0, 2.0, 3.0);
 *         CollectionProducer diag = diagonal(vec);
 *
 *         // Matrix-vector multiplication
 *         CollectionProducer A = c(shape(3, 3), ...);
 *         CollectionProducer x = c(shape(3), ...);
 *         CollectionProducer b = matmul(A, x);
 *
 *         return b;
 *     }
 * }
 * }</pre>
 *
 * <h2>Matrix Multiplication</h2>
 * <pre>{@code
 * // Matrix-vector multiplication: Ax = b
 * CollectionProducer A = c(shape(3, 4), ...);  // 3x4 matrix
 * CollectionProducer x = c(shape(4), ...);     // 4-vector
 * CollectionProducer b = matmul(A, x);         // 3-vector
 *
 * // Matrix-matrix multiplication: AB = C
 * CollectionProducer B = c(shape(4, 2), ...);  // 4x2 matrix
 * CollectionProducer C = matmul(A, B);         // 3x2 matrix
 * }</pre>
 *
 * <h2>Attention Mechanisms</h2>
 * <pre>{@code
 * // Scaled dot product for transformer attention
 * // Q: (batch, heads, seqLen, dim)
 * // K: (batch, heads, dim, seqLen)
 * CollectionProducer Q = ...;
 * CollectionProducer K = ...;
 * CollectionProducer scores = scaledDotProduct(Q, K);
 *
 * // With transpose (when K is stored as seqLen x dim)
 * CollectionProducer scores2 = scaledDotProduct(Q, K, true);
 * }</pre>
 *
 * @author  Michael Murray
 * @see AlgebraFeatures
 * @see CollectionProducer
 * @see TraversalPolicy
 */
public interface MatrixFeatures extends AlgebraFeatures {
	/**
	 * Creates a square identity matrix of the specified size.
	 * An identity matrix has 1s on the diagonal and 0s elsewhere.
	 *
	 * @param size  the size of the square matrix (both rows and columns)
	 * @return a producer for the size x size identity matrix
	 */
	default CollectionProducer identity(int size) {
		return identity(shape(size, size));
	}

	/**
	 * Creates an identity matrix with the specified shape.
	 * An identity matrix has 1s on the diagonal and 0s elsewhere.
	 *
	 * @param shape  the shape of the matrix (must be 2-dimensional)
	 * @return a producer for the identity matrix
	 * @throws IllegalArgumentException if the shape is not 2-dimensional
	 */
	default CollectionProducer identity(TraversalPolicy shape) {
		if (shape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		return new IdentityMatrixComputation(shape.traverseEach());
	}

	/**
	 * Creates a diagonal matrix from a vector.
	 * The resulting matrix has the vector's values on the diagonal and 0s elsewhere.
	 *
	 * <p>
	 * For example, given vector [1, 2, 3], creates the matrix:
	 * <pre>
	 * [1  0  0]
	 * [0  2  0]
	 * [0  0  3]
	 * </pre>
	 * </p>
	 *
	 * @param vector  the vector containing diagonal values
	 * @return a producer for the diagonal matrix
	 * @throws IllegalArgumentException if the input is not 1-dimensional
	 */
	default CollectionProducer diagonal(Producer<PackedCollection> vector) {
		TraversalPolicy shape = shape(vector);

		if (shape.getDimensions() != 1) {
			throw new IllegalArgumentException();
		} else if (shape.length(0) == 1) {
			return c(vector);
		}

		TraversalPolicy diagonalShape = shape(shape.length(0), shape.length(0)).traverse(1);
		return new DiagonalMatrixComputation(diagonalShape, vector);
	}

	/**
	 * Performs matrix multiplication between a matrix and a vector or between two matrices.
	 *
	 * <p>
	 * This method supports several multiplication modes:
	 * <ul>
	 *   <li>Matrix-vector multiplication: (M x N) . (N) -> (M)</li>
	 *   <li>Matrix-matrix multiplication: (M x N) . (N x P) -> (M x P)</li>
	 *   <li>Batched multiplication with automatic shape inference</li>
	 * </ul>
	 * </p>
	 *
	 * <p>
	 * The implementation includes several optimizations:
	 * <ul>
	 *   <li>Zero matrix detection -> returns zero result</li>
	 *   <li>Identity matrix detection -> returns input</li>
	 *   <li>Diagonal matrix detection -> optimized scalar multiplication</li>
	 *   <li>Vector-specific path -> uses element-wise multiply + sum instead of full matmul</li>
	 * </ul>
	 * </p>
	 *
	 * @param matrix  the matrix (left operand)
	 * @param vector  the vector or matrix (right operand)
	 * @return a producer for the multiplication result
	 * @throws IllegalArgumentException if shapes are incompatible
	 */
	default CollectionProducer matmul(Producer<PackedCollection> matrix, Producer<PackedCollection> vector) {
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
				vector = reshape(vShape, vector);
			}

			if (vShape.length(0) == 1) {
				vShape = shape(vShape.length(1));
				vector = reshape(vShape, vector);
			}

			if (vShape.getTraversalAxis() != vShape.getDimensions() - 1) {
				vShape = vShape.traverse(vShape.getDimensions() - 1);
				vector = reshape(vShape, vector);
			}

			int batchAxis = vShape.getDimensions();
			int outputSize = mShape.length(0);
			CollectionProducer a = c(matrix);
			CollectionProducer b = repeat(outputSize, vector);
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
			return multiply(c(vector), (Producer<PackedCollection>) scalar.get());
		}

		// Is the vector just a scalar transform?
		scalar =
				Algebraic.getDiagonalScalar(mShape.length(0), vector);
		if (b == 1 && scalar.isPresent() && scalar.get() instanceof Producer) {
			return multiply(c(matrix), (Producer<PackedCollection>) scalar.get());
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

	/**
	 * Performs matrix multiplication (deprecated version).
	 *
	 * @param a  the first matrix
	 * @param b  the second matrix
	 * @return a producer for the matrix product
	 * @deprecated Use {@link #matmul(Producer, Producer)} instead, which includes optimizations
	 *             for identity matrices, diagonal matrices, and zero matrices
	 */
	@Deprecated
	default CollectionProducer mproduct(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		if (WeightedSumExpression.enableCollectionExpression) {
			return matmul(traverse(0, a), traverse(0, b));
		}

		int m = shape(a).length(0);
		int n = shape(a).length(1);
		int p = shape(b).length(1);

		return c(b).enumerate(1, 1)
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


	/**
	 * Computes the scaled dot product of two collections for attention mechanisms.
	 *
	 * <p>
	 * This method is designed for transformer attention calculations where queries (Q)
	 * are multiplied with keys (K) to produce attention scores. The inputs are expected
	 * to have shape (batch, heads, seqLen, dim) for efficient batched multi-head attention.
	 * </p>
	 *
	 * @param a  query matrix with shape (batch, heads, seqLenA, dim)
	 * @param b  key matrix with shape (batch, heads, dim, seqLenB)
	 * @return attention scores with shape (batch, heads, seqLenA, seqLenB)
	 */
	// TODO  This should support any shapes that can be coerced to
	// TODO  (N, A, D) and (N, D, B) for constant values N, D, A and B
	default CollectionProducer scaledDotProduct(
			CollectionProducer a,
			CollectionProducer b) {
		return scaledDotProduct(a, b, false);
	}

	/**
	 * Computes the scaled dot product of two collections for attention mechanisms with optional transpose.
	 *
	 * <p>
	 * This method is designed for transformer attention calculations where queries (Q)
	 * are multiplied with keys (K) to produce attention scores. When transposeB is true,
	 * the key matrix is expected in shape (batch, heads, seqLenB, dim) and will be
	 * transposed internally to (batch, heads, dim, seqLenB) for the multiplication.
	 * </p>
	 *
	 * <p>
	 * The transpose option is useful when key matrices are stored in the same layout
	 * as query matrices, avoiding the need for an explicit transpose operation.
	 * </p>
	 *
	 * @param a          query matrix with shape (batch, heads, seqLenA, dim)
	 * @param b          key matrix with shape (batch, heads, dim, seqLenB) or (batch, heads, seqLenB, dim)
	 * @param transposeB if true, b is transposed from (batch, heads, seqLenB, dim) to (batch, heads, dim, seqLenB)
	 * @return attention scores with shape (batch, heads, seqLenA, seqLenB)
	 */
	// TODO  This should support any shapes that can be coerced to
	// TODO  (N, A, D) and (N, D, B) for constant values N, D, A and B
	default CollectionProducer scaledDotProduct(
			CollectionProducer a,
			CollectionProducer b,
			boolean transposeB) {
		TraversalPolicy leftShape = a.getShape();
		TraversalPolicy rightShape = b.getShape();

		int batchSize = leftShape.length(0);
		int heads = leftShape.length(1);
		int seqLenA = leftShape.length(2);
		int seqLenB = transposeB ? rightShape.length(2) : rightShape.length(3);
		int dim = leftShape.length(3);

		TraversalPolicy resultShape;
		TraversalPolicy leftPosition;
		TraversalPolicy rightPosition;
		TraversalPolicy groupShape;

		if (!transposeB) {
			leftShape = shape(batchSize, heads, seqLenA, dim, 1);
			rightShape = shape(batchSize, heads, 1, dim, seqLenB);

			resultShape = shape(batchSize, heads, seqLenA, 1, seqLenB);
			leftPosition = leftShape.repeat(4, seqLenB);
			rightPosition = rightShape.repeat(2, seqLenA);
			groupShape = shape(1, 1, 1, dim, 1);
		} else {
			leftShape = shape(batchSize, heads, seqLenA, 1, dim);
			rightShape = shape(batchSize, heads, 1, seqLenB, dim);

			resultShape = shape(batchSize, heads, seqLenA, seqLenB, 1);
			leftPosition = leftShape.repeat(3, seqLenB);
			rightPosition = rightShape.repeat(2, seqLenA);
			groupShape = shape(1, 1, 1, 1, dim);
		}

		TraversalPolicy outputShape = shape(batchSize, heads, seqLenA, seqLenB).traverseEach();

		return weightedSum("scaledDotProduct",
				resultShape,
				leftPosition, rightPosition,
				groupShape, groupShape,
				reshape(leftShape, c(a)),
				reshape(rightShape, c(b)))
				.reshape(outputShape);
	}

	/**
	 * Attempts to compute the delta (Jacobian/gradient) of a producer with respect to a target.
	 *
	 * <p>
	 * This method is used in automatic differentiation to compute gradients. It handles
	 * several special cases:
	 * <ul>
	 *   <li>If the producer implements {@link DeltaAlternate}, uses the alternative delta computation</li>
	 *   <li>If the producer cannot match the target, returns zeros</li>
	 *   <li>If the producer matches the target exactly, returns an identity matrix</li>
	 *   <li>Otherwise, returns null to indicate no simplified delta is available</li>
	 * </ul>
	 * </p>
	 *
	 * @param producer  the producer to differentiate
	 * @param target    the target variable to differentiate with respect to
	 * @return the delta computation, or null if no simplified form is available
	 */
	default CollectionProducer attemptDelta(Producer<PackedCollection> producer,
																		 Producer<?> target) {
		if (producer instanceof DeltaAlternate) {
			CollectionProducer alt = ((DeltaAlternate) producer).getDeltaAlternate();
			if (alt != null) return alt.delta(target);
		}

		TraversalPolicy shape = shape(producer);
		TraversalPolicy targetShape = shape(target);

		if (AlgebraFeatures.cannotMatch(producer, target)) {
			return zeros(shape.append(targetShape));
		} else if (AlgebraFeatures.match(producer, target)) {
			return identity(shape(shape.getTotalSize(), targetShape.getTotalSize()))
					.reshape(shape.append(targetShape));
		}

		return null;
	}

	/**
	 * Returns a singleton instance of {@link MatrixFeatures}.
	 *
	 * @return a MatrixFeatures instance
	 */
	static MatrixFeatures getInstance() {
		return new MatrixFeatures() {};
	}
}
