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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Stream;

/**
 * The values of a producer, held constant with respect to differentiation: a stop-gradient.
 *
 * <p>The computation evaluates to exactly the values of its input, element by element, and its
 * expression is the input's own expression, so a kernel that embeds it computes nothing it would
 * not have computed for the input itself. For differentiation, however, it presents none of its
 * inputs for {@link #matchableInputs() matching}, so no target can be reached through it: its
 * {@link #delta delta} with respect to anything other than itself is zero, and the derivative of
 * every computation that consumes it leaves it out entirely instead of carrying a zero-valued term
 * (see the identity cases in {@link org.almostrealism.collect.CollectionProducer#delta}).</p>
 *
 * <p>This is for a value whose dependence on a variable is known not to contribute to the
 * derivative being taken. The maximum that a numerically stable softmax subtracts from its
 * logits is the standard case: the softmax is invariant to that shift, so its derivative through
 * the maximum is exactly zero, and differentiating through the maximum only builds work, the
 * selection of the largest element and the terms it feeds, whose contributions cancel.</p>
 *
 * <p>The constancy is a property of the producer graph, which is what every derivative rule
 * built on {@link org.almostrealism.collect.CollectionProducer#delta} consults to decide which
 * inputs to differentiate. A consumer whose Jacobian is instead obtained by differentiating its
 * own expression as a whole ({@link TraversableDeltaComputation}, the last resort when several
 * of its other inputs depend on the target) reads the expression of this computation's input as
 * it is embedded, and so differentiates through it. This is therefore safe only for a value
 * whose true contribution to the derivative is zero, where both paths agree. It is not a
 * general-purpose detach for a value whose dependence on the target does matter.</p>
 *
 * <p>Its shape is the shape of its input, which it takes from the input itself rather than
 * accepting one that would have to agree with it; an input with no shape is rejected.</p>
 *
 * @see org.almostrealism.collect.GradientFeatures#stopGradient(Producer)
 *
 * @author Michael Murray
 */
public class CollectionStopGradientComputation extends TraversableExpressionComputation {
	/**
	 * Creates a stop-gradient over the given input, with the shape of that input.
	 *
	 * @param input the producer whose values this computation passes through
	 * @throws IllegalArgumentException if {@code input} does not have a shape
	 */
	public CollectionStopGradientComputation(Producer<PackedCollection> input) {
		super("stopGradient", Shape.requireShape("Stop gradient", input), input);
	}

	/**
	 * Returns the expression of the input, unchanged.
	 *
	 * @param args the traversable arguments, where {@code args[1]} is the input
	 * @return an expression with the value of the input at every position
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new UniformCollectionExpression(getName(), getShape(), in -> in[0], args[1]);
	}

	/**
	 * Presents no inputs for matching, which is what holds this computation constant with
	 * respect to differentiation: a target is only ever found among the inputs a producer
	 * presents here (see {@link Producer#matchableInputs()}), so no target is found through
	 * this one.
	 *
	 * @return an empty stream
	 */
	@Override
	public Stream<? extends Producer<?>> matchableInputs() {
		return Stream.empty();
	}

	/**
	 * Regenerates this stop-gradient over new children, with the shape of the regenerated
	 * input and this computation's postprocessor, description, short circuit and dependent
	 * lifecycles.
	 *
	 * @param children the child processes, where {@code children.get(1)} is the input
	 * @return the regenerated stop-gradient
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CollectionStopGradientComputation((Producer<PackedCollection>) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}
}
