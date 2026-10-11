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

package org.almostrealism.calculus;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * A structure-aware vector-Jacobian product: given a target to differentiate with
 * respect to and the upstream gradient of a computation's output, produces the
 * gradient with respect to the target directly, without forming the computation's
 * full Jacobian.
 *
 * <p>The generic backward pass contracts the upstream gradient {@code g} with the
 * Jacobian {@code J = d(out)/d(target)} after materializing {@code J} densely. For
 * operations whose Jacobian has known structure that is wasteful: the Jacobian of a
 * matrix multiply with respect to its matrix is block-diagonal, so the contraction
 * {@code gᵀJ} reduces to an outer product that costs a tiny fraction of the dense
 * form. A computation that knows such a shortcut carries a {@link VectorJacobianProduct}
 * and exposes it through
 * {@link CollectionProducer#attemptVectorJacobianProduct(Producer, Producer)}.</p>
 *
 * <p>Implementations must either return a producer whose values equal the dense
 * contraction exactly (up to floating point rounding), or return {@code null} when
 * the shortcut does not apply to the given target, in which case the caller falls
 * back to the dense Jacobian.</p>
 *
 * @author Michael Murray
 * @see DeltaAlternate
 * @see org.almostrealism.collect.GradientFeatures#combineGradient
 */
@FunctionalInterface
public interface VectorJacobianProduct {
	/**
	 * Computes the vector-Jacobian product for the given target.
	 *
	 * @param target   the producer with respect to which the gradient is taken
	 * @param gradient the upstream gradient of the computation's output, with the
	 *                 same total size as that output
	 * @return the gradient with respect to {@code target}, with the same total size
	 *         as {@code target}, or {@code null} if this product does not apply
	 */
	CollectionProducer apply(Producer<?> target, Producer<PackedCollection> gradient);
}
