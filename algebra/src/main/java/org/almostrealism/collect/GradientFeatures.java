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
	 * Computes the derivative (delta) of a producer with respect to a target.
	 *
	 * @param producer the producer to differentiate
	 * @param target the target with respect to which to compute the derivative
	 * @return a {@link CollectionProducer} that generates the derivative
	 */
	default CollectionProducer delta(Producer<PackedCollection> producer, Producer<?> target) {
		CollectionProducer result = MatrixFeatures.getInstance().attemptDelta(producer, target);
		if (result != null) return result;

		return CollectionFeatures.getInstance().c(producer).delta(target);
	}

	/**
	 * Combines a function's gradient with the input and upstream gradient.
	 * This implements the chain rule for backpropagation.
	 *
	 * @param func the function whose gradient to combine
	 * @param input the input to the function
	 * @param gradient the upstream gradient
	 * @return a {@link CollectionProducer} that generates the combined gradient
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
	 * Multiplies a gradient matrix with the upstream gradient.
	 *
	 * @param p the gradient matrix
	 * @param gradient the upstream gradient
	 * @param inSize the input size
	 * @return a {@link CollectionProducer} that generates the multiplied gradient
	 */
	default CollectionProducer multiplyGradient(
			CollectionProducer p, Producer<PackedCollection> gradient, int inSize) {
		int outSize = shape(gradient).getTotalSize();
		return p.multiply(CollectionFeatures.getInstance().c(gradient).reshape(outSize).traverse(1).repeat(inSize));
	}
}
