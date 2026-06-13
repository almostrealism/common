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
import org.almostrealism.algebra.MatrixFeatures;

/**
 * Factory interface for gradient and differentiation operations on collections.
 * This interface provides methods for computing deltas, combining gradients,
 * and multiplying gradients for backpropagation.
 *
 * @author Michael Murray
 * @see CollectionFeatures
 */
public interface GradientFeatures extends ComparisonFeatures {

	/**
	 * Computes the derivative of a collection producer with respect to a target producer,
	 * for use in automatic differentiation and gradient computation. A matrix-based delta
	 * is attempted first; if unavailable, the computation falls back to the standard
	 * {@link CollectionProducer#delta} mechanism.
	 *
	 * @param producer the collection producer to differentiate
	 * @param target   the producer with respect to which the derivative is taken
	 * @return a {@link CollectionProducer} representing the derivative
	 */
	default CollectionProducer delta(Producer<PackedCollection> producer, Producer<?> target) {
		CollectionProducer result = MatrixFeatures.getInstance().attemptDelta(producer, target);
		if (result != null) return result;

		return CollectionFeatures.getInstance().c(producer).delta(target);
	}

	/**
	 * Combines the Jacobian of a function with an upstream gradient to produce the
	 * input gradient via the chain rule. The Jacobian is computed as
	 * {@code func.delta(input)}, reshaped to {@code [outSize, inSize]}, multiplied
	 * by the upstream gradient (broadcast over inputs), then summed over output dimensions
	 * to yield the gradient with respect to {@code input}.
	 *
	 * @param func     the differentiable collection producer representing the forward function
	 * @param input    the input collection producer for which the gradient is computed
	 * @param gradient the upstream gradient producer (output gradient from the next layer)
	 * @return a {@link CollectionProducer} containing the gradient with respect to {@code input}
	 */
	default CollectionProducer combineGradient(
			CollectionProducer func,
			Producer<PackedCollection> input, Producer<PackedCollection> gradient) {
		int inSize = shape(input).getTotalSize();
		int outSize = shape(gradient).getTotalSize();
		return multiplyGradient(func.delta(input).reshape(outSize, inSize)
				.traverse(1), gradient, inSize)
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
