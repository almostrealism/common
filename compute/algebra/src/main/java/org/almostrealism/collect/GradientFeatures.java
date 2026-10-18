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
	 * for use in automatic differentiation and gradient computation, by way of
	 * {@link CollectionProducer#delta(Producer)}.
	 *
	 * @param producer the collection producer to differentiate
	 * @param target   the producer with respect to which the derivative is taken
	 * @return a {@link CollectionProducer} representing the derivative
	 */
	default CollectionProducer delta(Producer<PackedCollection> producer, Producer<?> target) {
		return CollectionFeatures.getInstance().c(producer).delta(target);
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
	 * Computes a {@link ReduceAxis reduced} delta of a collection producer by contracting
	 * its full Jacobian, {@code func.delta(target)}: the
	 * {@link #jacobianGradient vector-Jacobian product} for {@link ReduceAxis#OUT}, and the
	 * Jacobian-vector product for {@link ReduceAxis#IN}. This always materializes the
	 * Jacobian, so it is the fallback for a producer that has no reduced delta of its own,
	 * and the reference that any such delta must agree with.
	 *
	 * @param func   the collection producer to differentiate
	 * @param target the producer with respect to which to differentiate
	 * @param axis   the axis of the Jacobian to sum away, {@link ReduceAxis#OUT} or
	 *               {@link ReduceAxis#IN}
	 * @param weight the weight of each entry along that axis, or null to weight every
	 *               entry by one
	 * @return the reduced delta, in the shape of the axis that remains
	 * @throws IllegalArgumentException if {@code axis} is {@link ReduceAxis#NONE}, for
	 *                                  which there is nothing to contract
	 */
	default CollectionProducer jacobianContraction(CollectionProducer func, Producer<?> target,
												  ReduceAxis axis, Producer<PackedCollection> weight) {
		if (axis == ReduceAxis.NONE) {
			throw new IllegalArgumentException("The Jacobian of " + func.describe() + " has no axis to contract");
		} else if (axis == ReduceAxis.OUT) {
			return jacobianGradient(func, target, weight).reshape(axis.resultShape(func.getShape(), shape(target)));
		}

		int outSize = func.getShape().getTotalSize();
		int inSize = shape(target).getTotalSize();
		CollectionProducer jacobian = func.delta(target).reshape(outSize, inSize).traverse(1);
		if (weight != null) {
			jacobian = jacobian.multiply(CollectionFeatures.getInstance().c(weight).reshape(shape(inSize)).repeat(outSize));
		}

		return jacobian.traverse(1).sum().reshape(axis.resultShape(func.getShape(), shape(target)));
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
