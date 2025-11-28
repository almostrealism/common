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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

/**
 * Base class for matrix-related computations using traversable expressions.
 *
 * <p>
 * {@link MatrixExpressionComputation} provides a common foundation for implementing
 * matrix operations like identity matrices, diagonal matrices, and scalar matrices.
 * Subclasses implement specific matrix operations by overriding
 * {@link #getExpression(io.almostrealism.collect.TraversableExpression...)}.
 * </p>
 *
 * @param <T>  the packed collection type
 * @author  Michael Murray
 * @see IdentityMatrixComputation
 * @see DiagonalMatrixComputation
 * @see ScalarMatrixComputation
 */
public abstract class MatrixExpressionComputation<T extends PackedCollection> extends TraversableExpressionComputation<T> {
	/**
	 * Creates a new matrix expression computation.
	 *
	 * @param name  descriptive name for this computation
	 * @param shape  the shape of the output matrix
	 * @param args  input producers for the matrix computation
	 */
	@SafeVarargs
	public MatrixExpressionComputation(String name, TraversalPolicy shape,
									   Producer<PackedCollection>... args) {
		super(name, shape, args);
	}
}
