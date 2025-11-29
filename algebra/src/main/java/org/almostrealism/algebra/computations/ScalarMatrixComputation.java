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

package org.almostrealism.algebra.computations;

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.DiagonalCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.Optional;

/**
 * A computation that creates a diagonal matrix where all diagonal elements have the same scalar value.
 *
 * <p>
 * {@link ScalarMatrixComputation} generates a square matrix where:
 * <ul>
 *   <li>All diagonal elements (i, i) have the same value s</li>
 *   <li>Off-diagonal elements are zero</li>
 * </ul>
 * This is equivalent to s . I where I is the identity matrix.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Create a 3x3 matrix with 5.0 on the diagonal
 * CollectionProducer<PackedCollection> scalar = c(5.0);
 * ScalarMatrixComputation<PackedCollection> comp =
 *     new ScalarMatrixComputation<>(shape(3, 3).traverseEach(), scalar);
 *
 * // Result:
 * // [5  0  0]
 * // [0  5  0]
 * // [0  0  5]
 *
 * // Equivalent to 5.0 * I
 * }</pre>
 *
 * @author  Michael Murray
 * @see IdentityMatrixComputation
 * @see DiagonalMatrixComputation
 */
public class ScalarMatrixComputation extends MatrixExpressionComputation {
	/**
	 * Creates a scalar matrix computation with default name "scalarMatrix".
	 *
	 * @param shape  the shape of the output matrix (should be square)
	 * @param scalar  producer for the scalar value (must produce exactly 1 value)
	 * @throws IllegalArgumentException if scalar does not produce exactly 1 value
	 */
	public ScalarMatrixComputation(TraversalPolicy shape, Producer<PackedCollection> scalar) {
		this("scalarMatrix", shape, scalar);
	}

	/**
	 * Creates a scalar matrix computation with a custom name.
	 *
	 * @param name  descriptive name for this computation
	 * @param shape  the shape of the output matrix (should be square)
	 * @param scalar  producer for the scalar value (must produce exactly 1 value)
	 * @throws IllegalArgumentException if scalar does not produce exactly 1 value
	 */
	public ScalarMatrixComputation(String name, TraversalPolicy shape,
								   Producer<PackedCollection> scalar) {
		super(name, shape, scalar);

		if (shape.getTotalSizeLong() == 1) {
			warn("ScalarMatrixComputation will be identical to input");
		}

		if (shape(scalar).getTotalSize() != 1) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Protected constructor for subclasses that don't require a scalar input (e.g., IdentityMatrixComputation).
	 *
	 * @param name  descriptive name for this computation
	 * @param shape  the shape of the output matrix
	 */
	protected ScalarMatrixComputation(String name, TraversalPolicy shape) {
		super(name, shape);
	}

	/**
	 * Generates the expression that creates the scalar matrix.
	 * Creates a diagonal matrix with the scalar value repeated on the diagonal.
	 *
	 * @param args  traversable expressions [this, scalar_value]
	 * @return the collection expression for the scalar matrix
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		ConstantCollectionExpression scalar = new ConstantCollectionExpression(shape(1), args[1].getValueAt(e(0)));
		return new DiagonalCollectionExpression(getShape(), scalar);
	}

	/**
	 * Checks if this computation represents a zero matrix.
	 * Returns true if the superclass indicates zero or if the scalar input is zero.
	 *
	 * @return true if this is a zero matrix (scalar value is 0)
	 */
	@Override
	public boolean isZero() {
		return super.isZero() || Algebraic.isZero(getInputs().get(1));
	}

	/**
	 * Checks if this computation represents a diagonal matrix.
	 * Scalar matrices are always diagonal by definition.
	 *
	 * @param width  the expected width of the diagonal matrix
	 * @return true if this is a diagonal matrix with the specified width
	 */
	@Override
	public boolean isDiagonal(int width) {
		return super.isDiagonal(width) || width == getShape().length(0);
	}

	/**
	 * Returns the scalar value on the diagonal for optimization purposes.
	 *
	 * @param width  the width to check
	 * @return Optional containing the scalar producer if width matches the matrix dimensions,
	 *         or the superclass result otherwise
	 */
	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		if (width == getShape().length(0)) {
			return Optional.of(getInputs().get(1));
		}

		return super.getDiagonalScalar(width);
	}

	/**
	 * Generates the parallel process for this scalar matrix computation.
	 *
	 * @param children  child processes
	 * @return a new scalar matrix computation with the child scalar producer
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new ScalarMatrixComputation(getShape(), (Producer<PackedCollection>) children.get(1));
	}
}
