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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;

/**
 * Strategy for computing gradients of operations with multiple input terms.
 *
 * <p>
 * A computation declares its strategy through
 * {@link org.almostrealism.collect.CollectionProducer#getDeltaStrategy()}, and differentiates
 * itself with respect to one of its own terms by handing itself to
 * {@link #delta(CollectionProducerComputation, Producer)}.
 * Different mathematical operations require different approaches when computing
 * gradients with respect to one of multiple terms:
 * </p>
 * <ul>
 *   <li><b>NONE:</b> No multi-term handling. Always returns null, preventing potentially
 *       incorrect gradient computation.</li>
 *   <li><b>IGNORE:</b> For additive operations like (a + b + c). The gradient d(a+b)/da = I
 *       (identity matrix), since the derivative of a sum is the sum of derivatives.</li>
 *   <li><b>COMBINE:</b> For multiplicative operations like (a . b . c). The gradient d(a.b.c)/da
 *       is the product of all other terms: b.c. Implemented as diagonal(b.c).</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * <pre>{@code
 * // IGNORE strategy for addition
 * // d(x + y + z)/dx = I (identity)
 *
 * // COMBINE strategy for multiplication
 * // d(x . y . z)/dx = diag(y . z)
 * }</pre>
 *
 * @author  Michael Murray
 * @see org.almostrealism.collect.CollectionProducer#delta(io.almostrealism.relation.Producer)
 */
public enum MultiTermDeltaStrategy implements MatrixFeatures {
	/** No special handling - never differentiates a term */
	NONE {
		@Override
		public CollectionProducer delta(CollectionProducerComputation computation, Producer<?> target) {
			return null;
		}

		@Override
		protected CollectionProducer termDelta(CollectionProducerComputation computation, Producer<?> target) {
			throw new UnsupportedOperationException(name());
		}
	},

	/** Additive operations - returns identity matrix */
	IGNORE {
		@Override
		protected CollectionProducer termDelta(CollectionProducerComputation computation, Producer<?> target) {
			return identity(shape(computation.getShape().getTotalSize(), shape(target).getTotalSize()));
		}
	},

	/** Multiplicative operations - returns diagonal of non-target terms product */
	COMBINE {
		@Override
		protected CollectionProducer termDelta(CollectionProducerComputation computation, Producer<?> target) {
			return diagonal(computation.excludeTerm(target).flatten());
		}
	};

	/**
	 * Computes the gradient of the given computation with respect to one of its own terms.
	 * For every strategy that differentiates terms, the result is null when the target
	 * appears in more than one term, and zero when it appears in none; {@link #NONE}
	 * always returns null.
	 *
	 * @param computation  the multi-term computation being differentiated
	 * @param target       the term to differentiate with respect to
	 * @return the gradient, shaped as the computation's shape followed by the target's
	 *         shape, or null if this strategy does not apply
	 */
	public CollectionProducer delta(CollectionProducerComputation computation, Producer<?> target) {
		long matches = computation.countTerms(target);
		TraversalPolicy jacobianShape = computation.getShape().append(shape(target));

		if (matches == 0) {
			return zeros(jacobianShape);
		} else if (matches > 1) {
			return null;
		}

		return termDelta(computation, target).reshape(jacobianShape);
	}

	/**
	 * Computes the gradient of the given computation with respect to a target that
	 * matches exactly one of its terms, as a matrix with one row per output element
	 * and one column per target element.
	 *
	 * @param computation  the multi-term computation being differentiated
	 * @param target       the term to differentiate with respect to
	 * @return the gradient matrix
	 */
	protected abstract CollectionProducer termDelta(CollectionProducerComputation computation, Producer<?> target);
}
