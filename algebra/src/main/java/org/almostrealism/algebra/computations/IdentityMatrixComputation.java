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

package org.almostrealism.algebra.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Computable;
import io.almostrealism.compute.Process;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.Optional;

/**
 * A computation that produces an identity matrix.
 *
 * <p>
 * {@link IdentityMatrixComputation} generates a square matrix where:
 * <ul>
 *   <li>Diagonal elements (i, i) are 1.0</li>
 *   <li>Off-diagonal elements are 0.0</li>
 * </ul>
 *
 * <p>
 * The identity matrix has the property that I . A = A . I = A for any matrix A
 * with compatible dimensions. This makes it the multiplicative identity for
 * matrix operations.
 * </p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Create 3x3 identity matrix
 * IdentityMatrixComputation<PackedCollection> I =
 *     new IdentityMatrixComputation<>(shape(3, 3).traverseEach());
 *
 * // Result:
 * // [1  0  0]
 * // [0  1  0]
 * // [0  0  1]
 *
 * // Verify identity property
 * A.multiply(I).equals(A);  // true
 * I.multiply(A).equals(A);  // true
 * }</pre>
 *
 * @author  Michael Murray
 * @see org.almostrealism.algebra.MatrixFeatures#identity(int)
 * @see org.almostrealism.algebra.MatrixFeatures#identity(TraversalPolicy)
 * @see DiagonalMatrixComputation
 */
public class IdentityMatrixComputation extends ScalarMatrixComputation {
	/**
	 * Creates an identity matrix computation.
	 *
	 * @param shape  the shape of the output matrix (should be square for a proper identity matrix)
	 */
	public IdentityMatrixComputation(TraversalPolicy shape) {
		super("identity", shape);
	}

	/**
	 * Generates the expression that creates the identity matrix.
	 * Uses the built-in identity expression generator.
	 *
	 * @param args  traversable expressions (unused for identity matrix)
	 * @return the collection expression for the identity matrix
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return ident(getShape().traverse(1));
	}

	/**
	 * Indicates that this computation is not a zero matrix.
	 *
	 * @return false (identity matrix is not zero)
	 */
	@Override
	public boolean isZero() { return false; }

	/**
	 * Checks if this computation represents an identity matrix of the specified width.
	 *
	 * @param width  the expected width of the identity matrix
	 * @return true if this is a square identity matrix with the specified dimensions
	 */
	@Override
	public boolean isIdentity(int width) {
		return width == getShape().length(0) && width == getShape().length(1);
	}

	/**
	 * Returns the diagonal scalar value for optimization purposes.
	 * For an identity matrix, all diagonal elements are 1.0.
	 *
	 * @param width  the width to check
	 * @return Optional containing a constant 1.0 producer if this is an identity matrix,
	 *         or the superclass result otherwise
	 */
	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		if (isIdentity(width)) {
			return Optional.of(c(1.0));
		}

		return super.getDiagonalScalar(width);
	}

	/**
	 * Generates the parallel process for this identity matrix computation.
	 * Since the identity matrix has no dependencies, returns itself.
	 *
	 * @param children  child processes (unused)
	 * @return this computation as a parallel process
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return this;
	}
}
