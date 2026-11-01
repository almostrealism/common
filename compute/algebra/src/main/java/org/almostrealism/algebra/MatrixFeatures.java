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
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.WeightedSumExpression;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.DiagonalMatrixComputation;
import org.almostrealism.algebra.computations.IdentityMatrixComputation;
import org.almostrealism.algebra.computations.SegmentedWeightedSumComputation;
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
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
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
	 * Scales each row of a matrix by the corresponding element of a vector, computing
	 * {@code diagonal(vector) . matrix} without forming the diagonal matrix.
	 *
	 * <p>
	 * The vector is expanded along the columns of the matrix and multiplied element-wise.
	 * When the matrix is an identity whose size matches the vector, the result is simply
	 * {@link #diagonal(Producer) diagonal(vector)}. Delta implementations use this to apply
	 * the chain rule for element-wise functions: d f(u)/dx = diag(f'(u)) . du/dx.
	 * </p>
	 *
	 * @param vector  the row scale factors (must be 1-dimensional)
	 * @param matrix  the matrix whose rows are scaled
	 * @return the row-scaled matrix
	 * @throws IllegalArgumentException if vector is not 1-dimensional
	 */
	default CollectionProducer expandAndMultiply(
			CollectionProducer vector, CollectionProducer matrix) {
		if (vector.getShape().getDimensions() != 1) {
			throw new IllegalArgumentException("Row scale factors must be 1-dimensional, not " + vector.getShape());
		} else if (Algebraic.isIdentity(shape(vector).length(0), matrix)) {
			return diagonal(vector);
		} else {
			CollectionProducer expanded = vector.traverse(1).repeat(matrix.getShape().length(1));
			return multiply(expanded, matrix);
		}
	}

	/**
	 * Multiplies a matrix by the transpose of another, computing {@code a · bᵀ} as an
	 * element-wise product of repeated rows followed by a sum, so that both operands are
	 * read along their rows and neither transpose is materialized.
	 *
	 * <p>
	 * Entry {@code (i, j)} of the result is the dot product of row {@code i} of {@code a}
	 * with row {@code j} of {@code b}.
	 * </p>
	 *
	 * @param a  the left matrix, of shape {@code (p, k)}
	 * @param b  the right matrix, of shape {@code (q, k)}, whose transpose is applied
	 * @return the product, of shape {@code (p, q)}
	 * @throws IllegalArgumentException if either operand is not 2-dimensional or
	 *                                  their rows differ in length
	 */
	default CollectionProducer multiplyTransposed(CollectionProducer a, CollectionProducer b) {
		TraversalPolicy aShape = shape(a);
		TraversalPolicy bShape = shape(b);
		if (aShape.getDimensions() != 2 || bShape.getDimensions() != 2 ||
				aShape.length(1) != bShape.length(1)) {
			throw new IllegalArgumentException("Cannot multiply " + aShape + " by the transpose of " + bShape);
		}

		return a.traverse(1).repeat(bShape.length(0))
				.multiply(b.traverse(0).repeat(aShape.length(0)))
				.traverse(2).sum();
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
	 *   <li>A matrix whose {@link TraversalOrdering} {@link TraversalOrdering#segments(int)
	 *       enumerates its rows} (a sparse matrix) -> {@link SegmentedWeightedSumComputation},
	 *       which visits only the stored entries</li>
	 *   <li>Identity matrix detection -> returns input</li>
	 *   <li>Diagonal matrix detection -> optimized scalar multiplication</li>
	 *   <li>Vector-specific path for a batch of vectors -> uses element-wise multiply + sum
	 *       instead of full matmul</li>
	 *   <li>A single vector times a matrix of several rows -> weighted sum, one kernel in which
	 *       each output sums its row</li>
	 * </ul>
	 * </p>
	 *
	 * <p>
	 * A single vector times a matrix of several rows takes the weighted sum rather than the
	 * multiply + sum. The elementwise product of a single vector has as many elements as the
	 * matrix, a multiple of the result, so it is computed as a kernel of its own and summed by a
	 * second; the weighted sum computes each output directly, which halves the kernels of a
	 * product that runs once per step of a single-position decode. The weighted sum is built in
	 * the {@code (m, 1)} shape the multiply + sum gives, so the result is the computation itself
	 * rather than a reshaped view of it. A matrix of one row is a dot product, which the multiply
	 * + sum already reduces in one step, and keeps that form.
	 * </p>
	 *
	 * <p>
	 * On the vector-specific path the whole matrix is repeated for every vector explicitly
	 * (along a new leading axis, whatever axis the matrix is traversed along) rather than left to an implicit broadcast against the repeated vectors, which, for a
	 * batch holding exactly as many vectors as the matrix has rows, would pair each matrix
	 * row with one vector instead of applying every row to every vector.
	 * </p>
	 *
	 *
	 * @param matrix  the matrix (left operand)
	 * @param vector  the vector or matrix (right operand)
	 * @return a producer for the multiplication result
	 * @throws IllegalArgumentException if shapes are incompatible
	 */
	default CollectionProducer matmul(Producer<PackedCollection> matrix, Producer<PackedCollection> vector) {
		TraversalPolicy mShape = shape(matrix);
		TraversalPolicy vShape = shape(vector);

		// A column vector (n, 1) whose row count matches the contraction dimension
		// is one vector of n elements, whichever path follows; a single element
		// is left to the scalar shortcut below
		if (vShape.getDimensions() == 2 && vShape.length(1) == 1 && vShape.length(0) > 1
				&& mShape.getDimensions() == 2 && vShape.length(0) == mShape.length(1)) {
			vShape = vShape.trim();
			vector = reshape(vShape, vector);
		}

 		if (Algebraic.isZero(vector) || Algebraic.isZero(matrix)) {
			if (vShape.getDimensions() == 1) {
				return zeros(shape(mShape.length(0), 1));
			}

			return zeros(shape(mShape.length(0), vShape.length(1)));
		}

		// A matrix whose ordering enumerates its rows (a sparse matrix) is multiplied by visiting
		// only the entries it stores, never forming a logical index of the matrix
		TraversalOrdering mOrder = mShape.getOrder();
		boolean rowsEnumerated = mOrder != null && mShape.getDimensions() == 2
				&& mOrder.segments(mShape.length(1)).isPresent();
		// A batch of row vectors traversed along its rows is enumerated as columns below, even when
		// its batch size happens to equal the column count; only a vector or a column-oriented
		// matrix (traversed along axis 0) takes this branch, which contracts over axis 0
		if (rowsEnumerated && vShape.getDimensions() <= 2 && vShape.length(0) == mShape.length(1)
				&& (vShape.getDimensions() == 1 || vShape.getTraversalAxis() < 1)) {
			return new SegmentedWeightedSumComputation("matmul",
					vShape.getDimensions() == 1 ? 1 : vShape.length(1), matrix, vector);
		} else if (rowsEnumerated && vShape.getDimensions() == 2 && vShape.getTraversalAxis() >= 1
				&& vShape.length(1) == mShape.length(1)) {
			// A batch of row vectors, as a dense layer passes its input, is the same product
			// over the batch as columns, transposed back into rows, in the shape of the dense product
			int batch = vShape.length(0);
			int rows = mShape.length(0);
			if (batch == 1) {
				return new SegmentedWeightedSumComputation("matmul", 1, matrix,
						reshape(shape(vShape.length(1)), vector)).reshape(shape(rows, 1));
			}

			return new SegmentedWeightedSumComputation("matmul", batch, matrix,
					c(vector).traverse(0).transpose()).traverse(0).transpose().reshape(shape(batch, rows, 1));
		} else if (mShape.getTotalSizeLong() == 1 || vShape.getTotalSizeLong() == 1) {
			return multiply(c(matrix), c(vector));
		} else if (mShape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		boolean each = vShape.getTraversalAxis() >= (vShape.getDimensions() - 1);
		boolean onlyColumns = vShape.getDimensions() > 1 && vShape.length(vShape.getDimensions() - 1) == 1;
		boolean singleRow = vShape.getDimensions() == 1;
		boolean isVectorPath = each || onlyColumns || singleRow;

		// If the matrix is being multiplied by a vector, or by
		// a batch of vectors, rather than a matrix (or batch of
		// matrices) then simple multiplication followed by a sum
		// is a sufficient alternative to matrix multiplication
		// via genuine weighted sum
		if (isVectorPath) {
			// For large output dimensions (>1000), the repeat+multiply+sum
			// path creates enormous expression trees that scale O(n^2) in compile time.
			// In that case, fall through to the weightedSum path below WITHOUT preprocessing.
			// Empirically: 1000 outputs ~3s, 5000 outputs ~106s, 10000 outputs ~10+ minutes.
			int outputSize = mShape.length(0);
			if (outputSize > 1 && outputSize <= 1000 && vShape.getTotalSizeLong() == mShape.length(1)) {
				// A single vector: each output sums one row of the matrix against a (1, n)
				// row holding the vector, computed directly in the (m, 1) shape multiply + sum
				// gives so that the result is itself the computation rather than a view of it
				TraversalPolicy resultShape = shape(outputSize, 1);
				TraversalPolicy groupShape = shape(1, mShape.length(1));
				return weightedSum("matmul", resultShape,
						resultShape, resultShape.withRate(0, 1, outputSize),
						groupShape, groupShape,
						c(matrix), reshape(groupShape, vector));
			} else if (outputSize <= 1000) {
				// Only do preprocessing for the repeat+multiply+sum path
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
				CollectionProducer b = repeat(outputSize, vector);

				long vectors = vShape.getTotalSizeLong() / mShape.length(1);
				CollectionProducer a = vectors > 1 && vShape.getTotalSizeLong() % mShape.length(1) == 0 ?
						repeat(0, (int) vectors, matrix).reshape(b.getShape()) : c(matrix);
				return multiply(traverseEach(a), traverseEach(b)).traverse(batchAxis).sum();
			}
			// For large outputs, continue to weightedSum path with original vShape/vector
		}

		// Check if this is a batch of vectors falling through from the vector path (each/onlyColumns/singleRow).
		// In that case, the batch dimension should be preserved at the front.
		// Handle both 2D (batch, n) and 3D (batch, n, 1) batched vectors.
		// Also handle single 2D vectors (1, n) which should be treated as column vectors with p=1.
		// The singleVector2D check doesn't require isVectorPath because (1, n) inputs may not
		// satisfy any of the vector path conditions (traversal axis, onlyColumns, singleRow).
		boolean batchedVectors2D = isVectorPath && vShape.getDimensions() == 2 && vShape.length(0) > 1;
		boolean batchedVectors3D = isVectorPath && onlyColumns && vShape.getDimensions() == 3 && vShape.length(0) > 1;
		boolean singleVector2D = vShape.getDimensions() == 2 && vShape.length(0) == 1 && vShape.length(1) == mShape.length(1);
		TraversalPolicy vectorShape;
		if (batchedVectors2D) {
			// Batch of column vectors: (batch, n) -> (batch, n, 1)
			// This ensures b=batch, p=1 for proper matrix-vector batch multiplication
			vectorShape = shape(vShape.length(0), vShape.length(1), 1);
		} else if (batchedVectors3D) {
			// Batch of column vectors with trailing 1: (batch, n, 1) -> keep as (batch, n, 1)
			vectorShape = shape(vShape.length(0), vShape.length(1), 1);
		} else if (singleVector2D) {
			// Single column vector: (1, n) -> (1, n, 1)
			// This ensures b=1, p=1 for proper matrix-vector multiplication
			vectorShape = shape(1, vShape.length(1), 1);
		} else {
			vectorShape = padDimensions(vShape, 1, 2, true);
			vectorShape = padDimensions(vectorShape, 3);
		}

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
			// For batched 2D vectors, include the batch dimension
			if (batchedVectors2D) {
				finalShape = p == 1 ? shape(b, m) : shape(b, m, p);
			} else if (singleVector2D) {
				// Single vector: (1, n) -> output (1, m) to maintain batch structure
				finalShape = shape(1, m);
			} else {
				finalShape = shape(m, p);
			}
		} else if (batchedVectors3D) {
			// For batched 3D vectors with trailing 1, output is 2D (batch, m)
			finalShape = p == 1 ? shape(b, m) : shape(b, m, p);
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
	 * Returns a singleton instance of {@link MatrixFeatures}.
	 *
	 * @return a MatrixFeatures instance
	 */
	static MatrixFeatures getInstance() {
		return new MatrixFeatures() {};
	}
}
