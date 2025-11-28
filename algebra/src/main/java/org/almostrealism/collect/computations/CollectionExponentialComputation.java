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
import io.almostrealism.collect.ConditionalFilterExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Expression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that performs element-wise exponential function (e^x) on {@link PackedCollection}s.
 *
 * <p>This class extends {@link TraversableExpressionComputation} to provide efficient element-wise
 * application of the natural exponential function e^x, where e is Euler's number (approximately 2.71828).
 * The computation supports an optional "ignore zero" mode for optimization in sparse data scenarios.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For an input collection X with elements [x1, x2, ..., xn], the computation produces:</p>
 * <pre>
 * result[i] = e^(X[i])
 * </pre>
 * <p>where e is Euler's number (approximately 2.71828).</p>
 *
 * <h2>Ignore Zero Optimization</h2>
 * <p>When {@link #ignoreZero} is enabled, the computation skips exponential evaluation for
 * zero-valued elements, leaving them as zero in the output. This can provide performance
 * benefits for sparse data where many elements are zero:</p>
 * <pre>
 * if ignoreZero:
 *   result[i] = (X[i] == 0) ? 0 : e^(X[i])
 * else:
 *   result[i] = e^(X[i])
 * </pre>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>The derivative of the exponential function is particularly elegant:</p>
 * <pre>
 * d/dx[e^x] = e^x
 * </pre>
 * <p>The delta computation implements this rule, with the gradient being the exponential
 * of the original input multiplied by the upstream gradient.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic exponential:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(4);
 * CollectionProducer<PackedCollection> input = c(0.0, 1.0, 2.0, 3.0);
 *
 * CollectionExponentialComputation<PackedCollection> exp =
 *     new CollectionExponentialComputation<>(shape, input);
 *
 * PackedCollection result = exp.get().evaluate();
 * // Result: [1.0, 2.718, 7.389, 20.086]
 * }</pre>
 *
 * <p><strong>Exponential with ignore zero (sparse data):</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(5);
 * CollectionProducer<PackedCollection> sparseInput = c(0.0, 1.0, 0.0, 2.0, 0.0);
 *
 * CollectionExponentialComputation<PackedCollection> exp =
 *     new CollectionExponentialComputation<>(shape, true, sparseInput);
 *
 * PackedCollection result = exp.get().evaluate();
 * // Result: [0.0, 2.718, 0.0, 7.389, 0.0]  (zeros preserved)
 * }</pre>
 *
 * <p><strong>Using via CollectionFeatures:</strong></p>
 * <pre>{@code
 * // More common usage through helper methods
 * CollectionProducer<PackedCollection> x = c(-1.0, 0.0, 1.0, 2.0);
 * CollectionProducer<PackedCollection> expX = exp(x);
 * // Result: [0.368, 1.0, 2.718, 7.389]
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the number of elements</li>
 *   <li><strong>Memory:</strong> Output collection matching input shape</li>
 *   <li><strong>Parallelization:</strong> Fully parallelizable element-wise operation</li>
 *   <li><strong>Sparse Optimization:</strong> Ignore zero mode improves performance for sparse data</li>
 * </ul>
 *
 * @see TraversableExpressionComputation
 * @see CollectionLogarithmComputation
 * @see io.almostrealism.expression.Exp
 * @see org.almostrealism.collect.CollectionFeatures#exp(io.almostrealism.relation.Producer)
 *
 * @author Michael Murray
 */
public class CollectionExponentialComputation extends TraversableExpressionComputation {
	/**
	 * Flag controlling whether to skip exponential evaluation for zero-valued elements.
	 * When true, zero inputs produce zero outputs without computing e^0, improving
	 * performance for sparse data.
	 */
	private final boolean ignoreZero;

	/**
	 * Constructs an exponential computation with default behavior (no zero ignore).
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param input The {@link Producer} providing the input values for exponential computation
	 */
	public CollectionExponentialComputation(TraversalPolicy shape,
											Producer<PackedCollection> input) {
		this(shape, false, input);
	}

	/**
	 * Constructs an exponential computation with configurable zero-ignore behavior.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param ignoreZero If true, zero-valued elements are preserved as zero without evaluation
	 * @param input The {@link Producer} providing the input values for exponential computation
	 */
	public CollectionExponentialComputation(TraversalPolicy shape, boolean ignoreZero,
											Producer<PackedCollection> input) {
		this(ignoreZero ? "expIgnoreZero" : "exp", shape, ignoreZero, input);
	}

	/**
	 * Protected constructor allowing custom operation naming.
	 *
	 * @param name The operation identifier (typically "exp" or "expIgnoreZero")
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param ignoreZero If true, zero-valued elements are preserved as zero without evaluation
	 * @param input The {@link Producer} providing the input values
	 */
	protected CollectionExponentialComputation(String name, TraversalPolicy shape, boolean ignoreZero,
											   Producer<PackedCollection> input) {
		super(name, shape, MultiTermDeltaStrategy.NONE, input);
		this.ignoreZero = ignoreZero;
	}

	/**
	 * Returns whether this computation is configured to skip exponential evaluation for zeros.
	 *
	 * @return true if zeros are preserved without evaluation, false otherwise
	 */
	public boolean isIgnoreZero() {
		return ignoreZero;
	}

	/**
	 * Generates the expression that applies element-wise exponential function.
	 * Creates either a conditional filter expression (for ignoreZero mode) or
	 * a uniform collection expression (for standard mode).
	 *
	 * @param args Array of {@link TraversableExpression}s where args[1] is the input
	 * @return A {@link CollectionExpression} that computes element-wise e^x
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		if (isIgnoreZero()) {
			return new ConditionalFilterExpression("expIgnoreZero", getShape(),
						Expression::eqZero, Exp::of, false, args[1]);
		} else {
			return new UniformCollectionExpression("exp", getShape(),
						in -> Exp.of(in[0]), args[1]);
		}
	}

	/**
	 * Generates a new exponential computation with the specified child processes.
	 * Preserves all configuration including the ignoreZero flag, postprocessor,
	 * description, short circuit, and lifecycle dependencies.
	 *
	 * @param children List of child {@link Process} instances where children.get(1) is the input
	 * @return A new {@link CollectionExponentialComputation} for parallel execution
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CollectionExponentialComputation(getName(), getShape(), isIgnoreZero(),
				(Producer) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	/**
	 * Computes the derivative (delta) of this exponential computation using the chain rule.
	 * Implements the rule: d/dx[e^f(x)] = e^f(x) x f'(x)
	 *
	 * <p>The derivative of the exponential function is itself, multiplied by the
	 * derivative of the input with respect to the target. This elegant property
	 * makes exponential functions particularly useful in automatic differentiation.</p>
	 *
	 * @param target The {@link Producer} with respect to which the derivative is computed
	 * @return A {@link CollectionProducer} that computes the derivative
	 */
	@Override
	public CollectionProducer<PackedCollection> delta(Producer<?> target) {
		CollectionProducer<PackedCollection> delta = MatrixFeatures.getInstance().attemptDelta(this, target);
		if (delta != null) {
			return delta;
		}

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);
		CollectionProducer<PackedCollection> input = (CollectionProducer) getInputs().get(1);

		CollectionProducer<PackedCollection> d = input.delta(target);
		d = d.reshape(getShape().getTotalSize(), -1).traverse(0);

		CollectionProducer<PackedCollection> scale = isIgnoreZero() ? expIgnoreZero((Producer) input) : exp((Producer) input);
		scale = scale.flatten();

		return expandAndMultiply(scale, d).reshape(shape);
	}
}
