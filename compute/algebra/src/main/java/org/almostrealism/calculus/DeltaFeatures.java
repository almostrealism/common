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

package org.almostrealism.calculus;

import io.almostrealism.collect.Algebraic;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Stateless building blocks for automatic differentiation of collection computations.
 *
 * <p>
 * {@link DeltaFeatures} extends {@link MatrixFeatures} with the operations that delta
 * (gradient) implementations compose when applying the chain rule. For a composite
 * function h(x) = f(g(x)), the chain rule states:<br>
 * dh/dx = (df/dg) . (dg/dx)
 * </p>
 *
 * <p>
 * The differentiation itself is behaviour of the producer being differentiated, not of
 * this mixin: {@link CollectionProducer#delta(io.almostrealism.relation.Producer)} is the
 * entry point, a computation applies its own
 * {@link CollectionProducer#getDeltaStrategy() multi-term strategy} through
 * {@link CollectionProducer#applyDeltaStrategy(io.almostrealism.relation.Producer)}, and
 * {@link org.almostrealism.collect.computations.CollectionProducerComputationBase} applies
 * the chain rule through an intermediate input by
 * {@link org.almostrealism.collect.computations.CollectionProducerComputationBase#isolatedDelta isolating}
 * that input behind an {@link InputStub}.
 * </p>
 *
 * <h2>Chain Rule Example</h2>
 * <pre>{@code
 * // Compute gradient of h(x) = ReLU(Wx + b) with respect to W
 * CollectionProducer W = v(weights);
 * CollectionProducer x = v(input);
 * CollectionProducer b = v(bias);
 *
 * // Forward pass: linear = Wx + b, h = ReLU(linear)
 * CollectionProducer linear = matmul(W, x).add(b);
 * CollectionProducer h = relu(linear);
 *
 * // Gradient computation using chain rule
 * // dh/dW = dReLU/dlinear . dlinear/dW
 * CollectionProducer gradient = h.delta(W);
 * }</pre>
 *
 * <h2>Multi-Term Delta Strategies</h2>
 * <p>
 * The {@link MultiTermDeltaStrategy} enum controls how gradients are computed for operations
 * with multiple terms:
 * </p>
 * <ul>
 *   <li><b>NONE:</b> No special handling, fails if multiple matching terms exist</li>
 *   <li><b>IGNORE:</b> For additive operations - returns identity matrix</li>
 *   <li><b>COMBINE:</b> For multiplicative operations - returns diagonal of non-target terms</li>
 * </ul>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author  Michael Murray
 * @see MatrixFeatures
 * @see InputStub
 * @see DeltaAlternate
 * @see org.almostrealism.collect.CollectionProducer#delta
 */
public interface DeltaFeatures extends MatrixFeatures {
	/**
	 * Expands a vector and performs element-wise multiplication with a matrix.
	 *
	 * <p>
	 * This utility method is used in certain gradient computations. It expands a 1D vector
	 * to match the matrix dimensions and then multiplies element-wise.
	 * </p>
	 *
	 * <p>
	 * Special case: If the matrix is an identity matrix scaled by the vector, returns
	 * a diagonal matrix instead of performing the full expansion.
	 * </p>
	 *
	 * @param vector  the vector to expand (must be 1-dimensional)
	 * @param matrix  the matrix to multiply with
	 * @param <V>  the packed collection type
	 * @return the result of element-wise multiplication
	 * @throws IllegalArgumentException if vector is not 1-dimensional
	 */
	// TODO  It seems like this should be something that is just
	// TODO  part of MatrixFeatures, or even an option for matmul
	default <V extends PackedCollection> CollectionProducer expandAndMultiply(
			CollectionProducer vector, CollectionProducer matrix) {
		if (vector.getShape().getDimensions() != 1) {
			throw new IllegalArgumentException();
		} else if (Algebraic.isIdentity(shape(vector).length(0), matrix)) {
			return diagonal(vector);
		} else {
			CollectionProducer expanded = vector.traverse(1).repeat(matrix.getShape().length(1));
			return multiply(expanded, matrix);
		}
	}

	/**
	 * Strategy for computing gradients of operations with multiple input terms.
	 *
	 * <p>
	 * Different mathematical operations require different approaches when computing
	 * gradients with respect to one of multiple terms:
	 * </p>
	 * <ul>
	 *   <li><b>NONE:</b> No multi-term handling. Returns null if the target appears in multiple terms,
	 *       preventing potentially incorrect gradient computation.</li>
	 *   <li><b>IGNORE:</b> For additive operations like (a + b + c). The gradient d(a+b)/da = I
	 *       (identity matrix), since the derivative of a sum is the sum of derivatives.</li>
	 *   <li><b>COMBINE:</b> For multiplicative operations like (a . b . c). The gradient d(a.b.c)/da
	 *       is the product of all other terms: b.c. Implemented as diagonal(b.c).</li>
	 * </ul>
	 *
	 * <h3>Examples</h3>
	 * <pre>{@code
	 * // IGNORE strategy for addition
	 * // d(x + y + z)/dx = I (identity)
	 *
	 * // COMBINE strategy for multiplication
	 * // d(x . y . z)/dx = diag(y . z)
	 * }</pre>
	 */
	enum MultiTermDeltaStrategy {
		/** No special handling - fails if target appears in multiple terms */
		NONE,
		/** Additive operations - returns identity matrix */
		IGNORE,
		/** Multiplicative operations - returns diagonal of non-target terms product */
		COMBINE
	}
}
