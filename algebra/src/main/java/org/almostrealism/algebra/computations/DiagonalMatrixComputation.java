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
import io.almostrealism.collect.DiagonalCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that creates a diagonal matrix from a vector of diagonal values.
 *
 * <p>
 * {@link DiagonalMatrixComputation} generates a square matrix where:
 * <ul>
 *   <li>Diagonal elements (i, i) contain the values from the input vector</li>
 *   <li>Off-diagonal elements are zero</li>
 * </ul>
 * </p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Create diagonal matrix from vector [1, 2, 3]
 * CollectionProducer<PackedCollection<?>> diagonal = c(1.0, 2.0, 3.0);
 * DiagonalMatrixComputation<PackedCollection<?>> comp =
 *     new DiagonalMatrixComputation<>(shape(3, 3).traverse(1), diagonal);
 *
 * // Result:
 * // [1  0  0]
 * // [0  2  0]
 * // [0  0  3]
 * }</pre>
 *
 * @param <T>  the packed collection type
 * @author  Michael Murray
 * @see org.almostrealism.algebra.MatrixFeatures#diagonal(Producer)
 * @see IdentityMatrixComputation
 */
public class DiagonalMatrixComputation<T extends PackedCollection<?>> extends MatrixExpressionComputation<T> {
	/**
	 * Creates a diagonal matrix computation with default name "diagonal".
	 *
	 * @param shape  the shape of the output matrix (should be square)
	 * @param values  producer for the diagonal values
	 */
	public DiagonalMatrixComputation(TraversalPolicy shape, Producer<T> values) {
		this("diagonal", shape, values);
	}

	/**
	 * Creates a diagonal matrix computation with a custom name.
	 *
	 * @param name  descriptive name for this computation
	 * @param shape  the shape of the output matrix (should be square)
	 * @param values  producer for the diagonal values
	 */
	public DiagonalMatrixComputation(String name, TraversalPolicy shape, Producer<T> values) {
		super(name, shape, (Producer) values);
	}

	/**
	 * Generates the expression that creates the diagonal matrix.
	 * Uses {@link DiagonalCollectionExpression} for efficient diagonal matrix representation.
	 *
	 * @param args  traversable expressions [this, diagonal_values]
	 * @return the collection expression for the diagonal matrix
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new DiagonalCollectionExpression(getShape(), args[1]);
	}

	/**
	 * Checks if this computation represents a diagonal matrix.
	 * Returns true if the width matches the matrix dimension.
	 *
	 * @param width  the expected width of the diagonal matrix
	 * @return true if this is a diagonal matrix with the specified width
	 */
	@Override
	public boolean isDiagonal(int width) {
		return super.isDiagonal(width) || width == getShape().length(0);
	}

	/**
	 * Generates the parallel process for this diagonal matrix computation.
	 *
	 * @param children  child processes
	 * @return the parallel process implementation
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProducerParallelProcess) diagonal((Producer) children.get(1));
	}
}
