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
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

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
 * <p>Subclasses provide the cofactor g'(f(x)) via {@link #getCofactor(CollectionProducer)},
 * and this base class handles the multiplication with f'(x) and proper reshaping.</p>
 *
 * @see CollectionSineComputation
 * @see CollectionCosineComputation
 * @see TraversableExpressionComputation
 *
 * @author Michael Murray
 */
public abstract class UnaryCollectionComputation extends TraversableExpressionComputation {

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
		super(name, shape, MultiTermDeltaStrategy.NONE, input);
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
	 * <p>For a function g(f(x)), this returns g'(f(x)) as a CollectionProducer.
	 * For example:</p>
	 * <ul>
	 *   <li>Sine: cofactor is cos(input)</li>
	 *   <li>Cosine: cofactor is -sin(input)</li>
	 *   <li>Exponential: cofactor is exp(input)</li>
	 * </ul>
	 *
	 * @param input The input producer (f(x) in the chain rule)
	 * @return A {@link CollectionProducer} representing the cofactor g'(f(x))
	 */
	protected abstract CollectionProducer getCofactor(CollectionProducer input);

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
	 * {@link #getCofactor(CollectionProducer)}, then multiplied by the input's delta.</p>
	 *
	 * @param target The {@link Producer} with respect to which the derivative is computed
	 * @return A {@link CollectionProducer} that computes the derivative
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		CollectionProducer delta = MatrixFeatures.getInstance().attemptDelta(this, target);
		if (delta != null) {
			return delta;
		}

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);
		CollectionProducer input = (CollectionProducer) getInputs().get(1);

		// Chain rule: g'(f) * f'(x)
		CollectionProducer d = input.delta(target);
		d = d.reshape(getShape().getTotalSize(), -1).traverse(0);

		CollectionProducer cofactor = getCofactor(input).flatten();

		return expandAndMultiply(cofactor, d).reshape(shape);
	}
}
