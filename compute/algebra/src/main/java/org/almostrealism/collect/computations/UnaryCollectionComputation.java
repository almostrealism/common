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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Function;

/**
 * Abstract base class for unary element-wise computations on {@link PackedCollection}s
 * that implement Producer-level differentiation.
 *
 * <p>This class provides the common infrastructure for unary mathematical functions
 * (sin, cos, tan, etc.) that need efficient automatic differentiation. By implementing
 * delta() at the Producer level rather than relying on Expression-level differentiation,
 * subclasses benefit from:</p>
 * <ul>
 *   <li>Isolation boundaries that prevent exponential expression tree growth</li>
 *   <li>Separate kernel compilation for cofactor and delta terms</li>
 *   <li>Significantly improved backward pass compilation and evaluation time</li>
 * </ul>
 *
 * <h2>Chain Rule Implementation</h2>
 * <p>For a function g(f(x)), the chain rule states:</p>
 * <pre>
 * d/dx[g(f(x))] = g'(f(x)) * f'(x)
 * </pre>
 * <p>Subclasses provide the cofactor g'(f(x)) via {@link #getCofactor()},
 * and this base class handles the multiplication with f'(x) and proper reshaping.</p>
 *
 * <h2>Regeneration</h2>
 * <p>A subclass also says how to {@link #create(TraversalPolicy, Producer) create} the same function
 * over another input, and this base class regenerates the computation over new children with it,
 * carrying over the postprocessor, description, short circuit and dependent lifecycles.</p>
 *
 * @see CollectionSineComputation
 * @see CollectionCosineComputation
 * @see CollectionHyperbolicTangentComputation
 * @see TraversableExpressionComputation
 *
 * @author Michael Murray
 */
public abstract class UnaryCollectionComputation extends TraversableExpressionComputation {

	/** Function applied element-wise to each input expression (e.g., {@code Sine::of}). */
	private final Function<Expression<Double>, Expression<Double>> expressionFunction;

	/**
	 * Constructs a unary collection computation.
	 *
	 * @param name The operation name (e.g., "sin", "cos")
	 * @param shape The {@link TraversalPolicy} defining the output shape
	 * @param expressionFunction The function to apply element-wise (e.g., Sine::of)
	 * @param input The {@link Producer} providing the input values
	 */
	protected UnaryCollectionComputation(String name,
										 TraversalPolicy shape,
										 Function<Expression<Double>, Expression<Double>> expressionFunction,
										 Producer<PackedCollection> input) {
		super(name, shape, input);
		this.expressionFunction = expressionFunction;
	}

	/**
	 * Returns the expression function applied element-wise.
	 *
	 * @return The unary expression function
	 */
	protected Function<Expression<Double>, Expression<Double>> getExpressionFunction() {
		return expressionFunction;
	}

	/**
	 * Returns the cofactor for the chain rule derivative.
	 *
	 * <p>For a function g(f(x)), where f(x) is this computation's input ({@code getInputs().get(1)}),
	 * this returns g'(f(x)) as a CollectionProducer. For example:</p>
	 * <ul>
	 *   <li>Sine: cofactor is cos(input)</li>
	 *   <li>Cosine: cofactor is -sin(input)</li>
	 *   <li>Hyperbolic tangent: cofactor is 1 - tanh(input)^2</li>
	 * </ul>
	 *
	 * @return A {@link CollectionProducer} representing the cofactor g'(f(x))
	 */
	protected abstract CollectionProducer getCofactor();

	/**
	 * Creates the computation of the same function over another input, which
	 * {@link #generate(List)} uses to rebuild this computation over its regenerated children.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape
	 * @param input The {@link Producer} providing the input values
	 * @return A new computation of this function over {@code input}
	 */
	protected abstract UnaryCollectionComputation create(TraversalPolicy shape, Producer<PackedCollection> input);

	/**
	 * Generates the expression that applies the element-wise function.
	 *
	 * @param args Array of {@link TraversableExpression}s where args[1] is the input
	 * @return A {@link CollectionExpression} that computes the element-wise operation
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new UniformCollectionExpression(getName(), getShape(),
				in -> expressionFunction.apply(in[0]), args[1]);
	}

	/**
	 * Computes the derivative using the chain rule: g'(f(x)) * f'(x).
	 *
	 * <p>This method implements differentiation at the Producer level, creating isolated
	 * computation boundaries. The cofactor is computed as a separate kernel via
	 * {@link #getCofactor()}, then multiplied by the input's delta.</p>
	 *
	 * @param target The {@link Producer} with respect to which the derivative is computed
	 * @return A {@link CollectionProducer} that computes the derivative
	 */
	@Override
	protected CollectionProducer deltaDependent(Producer<?> target, ReduceAxis axis, Producer<PackedCollection> weight) {
		if (axis != ReduceAxis.NONE) {
			return super.deltaDependent(target, axis, weight);
		}

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);

		// Chain rule: g'(f) * f'(x)
		CollectionProducer d = c(getInputs().get(1)).delta(target);
		d = d.reshape(getShape().getTotalSize(), -1).traverse(0);

		CollectionProducer cofactor = getCofactor().flatten();

		return expandAndMultiply(cofactor, d).reshape(shape);
	}

	/**
	 * Regenerates this computation over new children: the same function, {@link #create created}
	 * over the regenerated input, with this computation's postprocessor, description, short circuit
	 * and dependent lifecycles.
	 *
	 * @param children The child processes, where {@code children.get(1)} is the input
	 * @return The regenerated computation
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return create(getShape(), (Producer<PackedCollection>) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}
}
