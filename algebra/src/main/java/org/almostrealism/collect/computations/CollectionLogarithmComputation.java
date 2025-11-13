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
import io.almostrealism.expression.Logarithm;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that performs element-wise natural logarithm (ln) on {@link PackedCollection}s.
 *
 * <p>This class extends {@link TraversableExpressionComputation} to provide efficient element-wise
 * application of the natural logarithm function ln(x), which is the logarithm to base e
 * (where e ~= 2.71828). The computation is the inverse of the exponential function.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For an input collection X with elements [x1, x2, ..., xn], the computation produces:</p>
 * <pre>
 * result[i] = ln(X[i])
 * </pre>
 * <p>where ln is the natural logarithm (logarithm base e).</p>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>The derivative of the natural logarithm is:</p>
 * <pre>
 * d/dx[ln(x)] = 1/x
 * </pre>
 * <p>When {@link #enableCustomDelta} is true, the delta computation implements this rule
 * analytically using x^(-1) for efficient gradient calculation in automatic differentiation.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic natural logarithm:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(4);
 * CollectionProducer<PackedCollection<?>> input = c(1.0, 2.718, 7.389, 20.086);
 *
 * CollectionLogarithmComputation<PackedCollection<?>> log =
 *     new CollectionLogarithmComputation<>(shape, input);
 *
 * PackedCollection<?> result = log.get().evaluate();
 * // Result: [0.0, 1.0, 2.0, 3.0]
 * }</pre>
 *
 * <p><strong>Using via CollectionFeatures:</strong></p>
 * <pre>{@code
 * // More common usage through helper methods
 * CollectionProducer<PackedCollection<?>> x = c(1.0, Math.E, Math.E * Math.E);
 * CollectionProducer<PackedCollection<?>> logX = log(x);
 * // Result: [0.0, 1.0, 2.0]
 * }</pre>
 *
 * <p><strong>Computing gradients:</strong></p>
 * <pre>{@code
 * // f(x) = ln(x), df/dx = 1/x
 * CollectionProducer<PackedCollection<?>> x = x(5);
 * CollectionProducer<PackedCollection<?>> f = log(x);
 * CollectionProducer<PackedCollection<?>> df_dx = f.delta(x);
 * // Result: element-wise 1/x
 * }</pre>
 *
 * <h2>Domain and Range</h2>
 * <ul>
 *   <li><strong>Domain:</strong> (0, infinity) - input values must be positive</li>
 *   <li><strong>Range:</strong> (-infinity, infinity) - all real numbers</li>
 *   <li><strong>Special values:</strong> ln(1) = 0, ln(e) = 1</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the number of elements</li>
 *   <li><strong>Memory:</strong> Output collection matching input shape</li>
 *   <li><strong>Parallelization:</strong> Fully parallelizable element-wise operation</li>
 *   <li><strong>Differentiation:</strong> Optimized analytical derivative when enabled</li>
 * </ul>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see TraversableExpressionComputation
 * @see CollectionExponentialComputation
 * @see io.almostrealism.expression.Logarithm
 * @see org.almostrealism.collect.CollectionFeatures#log(io.almostrealism.relation.Producer)
 *
 * @author Michael Murray
 */
public class CollectionLogarithmComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	/**
	 * Enables optimized analytical derivative computation using the logarithm rule.
	 * When true, the {@link #delta(Producer)} method applies d/dx[ln(x)] = 1/x
	 * for efficient automatic differentiation.
	 */
	public static boolean enableCustomDelta = true;

	/**
	 * Constructs a logarithm computation with default name "log".
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param input The {@link Producer} providing the input values (must be positive)
	 */
	public CollectionLogarithmComputation(TraversalPolicy shape,
										  Producer<PackedCollection<?>> input) {
		this("log", shape, input);
	}

	/**
	 * Protected constructor allowing custom operation naming.
	 *
	 * @param name The operation identifier (typically "log")
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param input The {@link Producer} providing the input values
	 */
	protected CollectionLogarithmComputation(String name, TraversalPolicy shape,
											   Producer<PackedCollection<?>> input) {
		super(name, shape, MultiTermDeltaStrategy.NONE, input);
	}

	/**
	 * Generates the expression that applies element-wise natural logarithm.
	 *
	 * @param args Array of {@link TraversableExpression}s where args[1] is the input
	 * @return A {@link CollectionExpression} that computes element-wise ln(x)
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new UniformCollectionExpression("log", getShape(), in -> Logarithm.of(in[0]), args[1]);
	}

	/**
	 * Generates a new logarithm computation with the specified child processes.
	 * Preserves all configuration including postprocessor, description, short circuit,
	 * and lifecycle dependencies.
	 *
	 * @param children List of child {@link Process} instances where children.get(1) is the input
	 * @return A new {@link CollectionLogarithmComputation} for parallel execution
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new CollectionLogarithmComputation<>(getName(), getShape(),
				(Producer) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	/**
	 * Computes the derivative (delta) of this logarithm computation using the chain rule.
	 * Implements the rule: d/dx[ln(f(x))] = (1/f(x)) x f'(x)
	 *
	 * <p>The derivative of the natural logarithm is 1/x, so for a composite function
	 * ln(f(x)), the chain rule gives (1/f(x)) x f'(x). This is computed efficiently
	 * using x^(-1) for the reciprocal operation.</p>
	 *
	 * @param target The {@link Producer} with respect to which the derivative is computed
	 * @return A {@link CollectionProducer} that computes the derivative, or delegates
	 *         to the parent if custom delta is disabled
	 */
	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (!enableCustomDelta) {
			return super.delta(target);
		}

		CollectionProducer<T> delta = MatrixFeatures.getInstance().attemptDelta(this, target);
		if (delta != null) {
			return delta;
		}

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);
		CollectionProducer<T> input = (CollectionProducer) getInputs().get(1);

		CollectionProducer<T> d = input.delta(target);
		d = d.reshape(getShape().getTotalSize(), -1).traverse(0);

		CollectionProducer<T> scale = pow(input, c(-1));
		scale = scale.flatten();

		return expandAndMultiply(scale, d).reshape(shape);
	}
}
