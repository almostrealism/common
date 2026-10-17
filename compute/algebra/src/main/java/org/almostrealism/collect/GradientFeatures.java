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

package org.almostrealism.collect;

import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;

/**
 * Factory interface for gradient and differentiation operations on collections.
 * This interface provides methods for computing deltas and for contracting a full
 * Jacobian with an upstream gradient, the reference that a
 * {@link ReduceAxis#OUT output-reduced} delta must agree with.
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author Michael Murray
 * @see CollectionFeatures
 */
public interface GradientFeatures extends ComparisonFeatures {

	/**
	 * Computes the derivative of a collection producer with respect to a target producer,
	 * for use in automatic differentiation and gradient computation. The
	 * {@link CollectionProducer#attemptDirectDelta direct cases} are attempted first; if
	 * unavailable, the computation falls back to the standard
	 * {@link CollectionProducer#delta} mechanism.
	 *
	 * @param producer the collection producer to differentiate
	 * @param target   the producer with respect to which the derivative is taken
	 * @return a {@link CollectionProducer} representing the derivative
	 */
	default CollectionProducer delta(Producer<PackedCollection> producer, Producer<?> target) {
		CollectionProducer p = CollectionFeatures.getInstance().c(producer);
		CollectionProducer result = p.attemptDirectDelta(target);
		if (result != null) return result;

		return p.delta(target);
	}

	/**
	 * Combines the Jacobian of a function with an upstream gradient to produce the
	 * input gradient via the chain rule. The Jacobian is computed as
	 * {@code func.delta(input)}, reshaped to {@code [outSize, inSize]} (where {@code outSize}
	 * is the size of the upstream gradient, or of the function when there is none), multiplied
	 * by the upstream gradient (broadcast over inputs), then summed over output dimensions
	 * to yield the gradient with respect to {@code input}.
	 *
	 * <p>This always materializes the full Jacobian, so it is the reference that any
	 * {@link ReduceAxis#OUT output-reduced} delta must agree with.</p>
	 *
	 * @param func     the differentiable collection producer representing the forward function
	 * @param input    the input collection producer for which the gradient is computed
	 * @param gradient the upstream gradient producer (output gradient from the next layer),
	 *                 or null to weight every output by one
	 * @return a {@link CollectionProducer} containing the gradient with respect to {@code input}
	 */
	default CollectionProducer jacobianGradient(
			CollectionProducer func,
			Producer<?> input, Producer<PackedCollection> gradient) {
		// TODO(review): wrong for the 256x64 dense weight over 64 rows (2^34 iteration space); check 32-bit index overflow
		int inSize = shape(input).getTotalSize();
		int outSize = gradient == null ? func.getShape().getTotalSize() : shape(gradient).getTotalSize();
		CollectionProducer jacobian = func.delta(input).reshape(outSize, inSize).traverse(1);
		if (gradient != null) {
			jacobian = multiplyGradient(jacobian, gradient, inSize);
		}

		return jacobian
				.traverse(0)
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(inSize))
				.each();
	}

	/**
	 * Multiplies a Jacobian-shaped producer by an upstream gradient broadcast over
	 * the input dimension. The gradient is reshaped to {@code [outSize]}, traversed
	 * at axis 1, and repeated {@code inSize} times so each input position receives
	 * the corresponding output gradient for element-wise multiplication.
	 *
	 * @param p        the Jacobian producer shaped {@code [outSize, inSize]} traversed at axis 1
	 * @param gradient the upstream gradient producer
	 * @param inSize   the number of input elements (repeat count for broadcast)
	 * @return a {@link CollectionProducer} with the gradient broadcast and applied
	 */
	default CollectionProducer multiplyGradient(
			CollectionProducer p, Producer<PackedCollection> gradient, int inSize) {
		int outSize = shape(gradient).getTotalSize();
		return p.multiply(CollectionFeatures.getInstance().c(gradient).reshape(outSize).traverse(1).repeat(inSize));
	}
}
