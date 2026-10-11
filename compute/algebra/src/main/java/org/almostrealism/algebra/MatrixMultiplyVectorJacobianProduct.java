/*
 * Copyright 2026 Michael Murray
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
import org.almostrealism.calculus.VectorJacobianProduct;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * The {@link VectorJacobianProduct} of a matrix multiply, which computes the gradient
 * with respect to either operand directly from the upstream gradient, without forming
 * the product's Jacobian.
 *
 * <p>The product is {@code y_r = W · x_r} for every row {@code x_r} of the rows operand,
 * with {@code W} of shape {@code (m, n)}, the rows holding {@code R} rows of {@code n}
 * values (in any shape with {@code R * n} elements) and the upstream gradient holding
 * {@code R} rows {@code g_r} of {@code m} values. The vector-Jacobian products are</p>
 * <pre>
 * dW   = Σ_r g_r ⊗ x_r      (summed over every row, shape (m, n))
 * dx_r = Wᵀ · g_r           (per row, shape of rows)
 * </pre>
 * <p>Each is an iteration space of {@code R * m * n} terms, where contracting the dense
 * Jacobian needs {@code R * m} times as many for the weight and {@code R * n} times as
 * many for the rows.</p>
 *
 * <p>The gradient is only produced when the target is exactly one operand and cannot
 * appear in the other one; any other target (for example, one that the rows were
 * computed from) yields {@code null} so that the caller uses the full Jacobian.</p>
 *
 * @author Michael Murray
 * @see MatrixFeatures#matmul(Producer, Producer)
 */
public class MatrixMultiplyVectorJacobianProduct implements VectorJacobianProduct, MatrixFeatures {
	/** The matrix operand {@code W}, of shape {@code (m, n)}. */
	private final Producer<PackedCollection> matrix;

	/** The rows {@code x_r} that the matrix is applied to. */
	private final Producer<PackedCollection> rows;

	/**
	 * Creates the vector-Jacobian product of multiplying {@code matrix} by {@code rows}.
	 *
	 * @param matrix the matrix operand {@code W}, of shape {@code (m, n)}
	 * @param rows   the rows {@code x_r} that the matrix is applied to
	 */
	public MatrixMultiplyVectorJacobianProduct(Producer<PackedCollection> matrix,
											   Producer<PackedCollection> rows) {
		this.matrix = matrix;
		this.rows = rows;
	}

	/**
	 * Computes the gradient of the matrix multiply with respect to {@code target}.
	 *
	 * @param target   the operand to differentiate with respect to
	 * @param gradient the upstream gradient of the product, with {@code R * m} elements
	 * @return the gradient with respect to {@code target}, in the shape of the target,
	 *         or {@code null} if the target is not one of the operands
	 */
	@Override
	public CollectionProducer apply(Producer<?> target, Producer<PackedCollection> gradient) {
		TraversalPolicy mShape = shape(matrix);
		if (mShape.getDimensions() != 2) return null;

		int m = mShape.length(0);
		int n = mShape.length(1);
		long rowElements = shape(rows).getTotalSizeLong();
		if (rowElements % n != 0) return null;

		int r = (int) (rowElements / n);
		if (shape(gradient).getTotalSizeLong() != (long) r * m) return null;

		long targetSize = shape(target).getTotalSizeLong();
		CollectionProducer g = c(gradient).reshape(shape(r, m));

		if (targetSize == mShape.getTotalSizeLong() &&
				matrix.refersTo(target) && rows.cannotMatch(target)) {
			CollectionProducer gt = g.enumerate(1, 1).reshape(shape(m, r));
			CollectionProducer xt = c(rows).reshape(shape(r, n)).enumerate(1, 1).reshape(shape(n, r));
			return multiplyTransposed(gt, xt).reshape(shape(target));
		} else if (targetSize == rowElements &&
				rows.refersTo(target) && matrix.cannotMatch(target)) {
			CollectionProducer wt = c(matrix).reshape(shape(m, n)).enumerate(1, 1).reshape(shape(n, m));
			return multiplyTransposed(g, wt).reshape(shape(target));
		}

		return null;
	}
}
