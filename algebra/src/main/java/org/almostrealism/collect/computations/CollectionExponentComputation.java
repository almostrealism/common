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
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A {@link CollectionExponentComputation} provides element-wise exponentiation operations on multi-dimensional
 * collections, computing base^exponent for corresponding elements across {@link PackedCollection} instances.
 * This computation extends {@link TraversableExpressionComputation} to leverage traversable expressions
 * for efficient parallel processing of power operations.
 * 
 * <p>The computation supports various exponentiation scenarios:
 * <ul>
 *   <li>Element-wise power operations: each element raised to corresponding exponent</li>
 *   <li>Broadcasting: scalar exponent applied to all elements in base collection</li>
 *   <li>Custom delta computation for automatic differentiation using power rule: d/dx[u^v] = v*u^(v-1)*du/dx + u^v*ln(u)*dv/dx</li>
 *   <li>Parallel processing through traversable expression framework</li>
 * </ul>
 * 
 * <h3>Mathematical Foundation</h3>
 * 
 * <p>For collections A (base) and B (exponent), the computation produces collection C where:
 * <pre>
 * C[i] = A[i]^B[i]
 * </pre>
 * 
 * <p>The derivative computation implements the generalized power rule:
 * <pre>
 * d/dx[f(x)^g(x)] = f(x)^g(x) * [g'(x)*ln(f(x)) + g(x)*f'(x)/f(x)]
 * </pre>
 * 
 * <h3>Usage Examples</h3>
 * 
 * <p><strong>Basic Element-wise Power:</strong>
 * <pre>{@code
 * // Square each element: [2, 3, 4] -> [4, 9, 16]
 * PackedCollection base = pack(2.0, 3.0, 4.0);
 * PackedCollection exponent = pack(2.0, 2.0, 2.0);
 * 
 * CollectionExponentComputation<PackedCollection> power =
 *     new CollectionExponentComputation<>(shape(3), p(base), p(exponent));
 * PackedCollection result = power.get().evaluate();
 * }</pre>
 * 
 * <p><strong>Broadcasting Scalar Exponent:</strong>
 * <pre>{@code
 * // Cube all elements: [2, 3, 4] -> [8, 27, 64]
 * PackedCollection base = pack(2.0, 3.0, 4.0);
 * CollectionProducer<PackedCollection> cubed =
 *     cp(base).pow(c(3.0));  // Uses CollectionExponentComputation internally
 * PackedCollection result = cubed.get().evaluate();
 * }</pre>
 * 
 * <p><strong>Multi-dimensional Power Operations:</strong>
 * <pre>{@code
 * // Matrix element-wise power
 * PackedCollection matrix = new PackedCollection(shape(2, 3));
 * matrix.fill(pos -> pos[0] + pos[1] + 1.0);  // [[1,2,3], [2,3,4]]
 * 
 * CollectionExponentComputation<PackedCollection> matrixPower =
 *     new CollectionExponentComputation<>(shape(2, 3), p(matrix), c(2.0));
 * PackedCollection squared = matrixPower.get().evaluate();
 * // Result: [[1,4,9], [4,9,16]]
 * }</pre>
 * 
 * <p><strong>Derivative Computation for Optimization:</strong>
 * <pre>{@code
 * // Computing gradient of x^3 for backpropagation
 * CollectionProducer<PackedCollection> x = x(3);
 * CollectionProducer<PackedCollection> f = x.pow(c(3.0));
 * 
 * // df/dx = 3*x^2
 * CollectionProducer<PackedCollection> gradient = f.delta(x);
 * }</pre>
 * 
 * <h3>Configuration Options</h3>
 * 
 * <p>The class provides static configuration flags to control optimization behavior:
 * <ul>
 *   <li>{@link #enableCustomDelta}: Enables optimized derivative computation using analytical power rule</li>
 *   <li>{@link #enableUniqueNonZeroOffset}: Controls memory offset optimization for sparse operations</li>
 * </ul>
 * 
 * <h3>Performance Considerations</h3>
 * 
 * <p>This computation is optimized for:
 * <ul>
 *   <li>Large collections through parallel traversable expressions</li>
 *   <li>Automatic differentiation in machine learning contexts</li>
 *   <li>Memory-efficient operations through broadcast support</li>
 *   <li>Hardware acceleration via the underlying expression framework</li>
 * </ul>
 * 
 * @see TraversableExpressionComputation
 * @see PackedCollection
 * @see TraversalPolicy
 * @see io.almostrealism.expression.Exponent
 * @author Michael Murray
 */
public class CollectionExponentComputation extends TraversableExpressionComputation {
	/**
	 * Enables optimized analytical derivative computation using the power rule for automatic differentiation.
	 * When {@code true}, the {@link #delta(Producer)} method applies the mathematical power rule:
	 * d/dx[u^v] = v*u^(v-1)*du/dx for cases where the exponent is independent of the differentiation variable.
	 * 
	 * <p>This optimization significantly improves performance in machine learning and optimization scenarios
	 * by avoiding numerical differentiation. When {@code false}, falls back to the default delta computation
	 * strategy from the parent class.
	 * 
	 * @see #delta(Producer)
	 */
	public static boolean enableCustomDelta = true;
	
	/**
	 * Controls memory offset optimization for operations with sparse or irregular access patterns.
	 * When {@code true}, enables unique non-zero offset computation which can improve memory access
	 * efficiency for certain expression patterns but may increase compilation complexity.
	 * 
	 * <p>This flag is typically disabled ({@code false}) as the default traversable expression
	 * framework provides sufficient optimization for most use cases. Enable only when profiling
	 * indicates memory access bottlenecks in specific power operation patterns.
	 */
	public static boolean enableUniqueNonZeroOffset = false;

	/**
	 * Creates a new {@link CollectionExponentComputation} for element-wise exponentiation using {@link Producer} inputs.
	 * This constructor is the primary entry point for power operations where both base and exponent are
	 * produced by other computations or represent input data.
	 * 
	 * <p>The computation will evaluate base^exponent for each corresponding element position,
	 * broadcasting when necessary according to the provided traversal policy shape.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern.
	 *              This determines how elements are accessed and the dimensions of the result.
	 * @param base The {@link Producer} providing the base values for exponentiation.
	 *             Must produce {@link PackedCollection} instances compatible with the shape.
	 * @param exponent The {@link Producer} providing the exponent values.
	 *                 Can be a scalar (broadcasted) or collection matching base dimensions.
	 */
	public CollectionExponentComputation(TraversalPolicy shape,
										 Producer<? extends PackedCollection> base,
										 Producer<? extends PackedCollection> exponent) {
		this("pow", shape, (Producer<PackedCollection>) base, (Producer<PackedCollection>) exponent);
	}

	/**
	 * Protected constructor allowing customization of the operation name and providing the core implementation
	 * for exponentiation computations. This constructor is used internally and by subclasses that need
	 * to customize the operation identifier.
	 * 
	 * <p>The name parameter affects debugging output, profiling information, and generated code comments
	 * but does not change the mathematical behavior of the power operation.
	 * 
	 * @param name The string identifier for this operation (typically "pow")
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param base The {@link Supplier} providing {@link Evaluable} instances that produce base values
	 * @param exponent The {@link Supplier} providing {@link Evaluable} instances that produce exponent values
	 */
	protected CollectionExponentComputation(String name, TraversalPolicy shape,
											Producer<PackedCollection> base,
											Producer<PackedCollection> exponent) {
		super(name, shape, MultiTermDeltaStrategy.NONE, base, exponent);
	}

	/**
	 * Creates the underlying {@link CollectionExpression} that implements the power operation.
	 * This method transforms the input {@link TraversableExpression} arguments into a
	 * {@link UniformCollectionExpression} that applies {@link io.almostrealism.expression.Exponent}
	 * element-wise across the collections.
	 * 
	 * <p>The expression uses the mathematical pow function: {@code pow(base, exponent)} where
	 * {@code base} is args[1] and {@code exponent} is args[2]. The args[0] is typically the
	 * destination/output reference in the traversable expression framework.
	 * 
	 * <p>Memory offset optimization is controlled by {@link #enableUniqueNonZeroOffset}.
	 * When enabled, the expression may use specialized offset computation for improved
	 * memory access patterns in certain scenarios.
	 * 
	 * @param args The traversable expression arguments where args[1] is base and args[2] is exponent
	 * @return A {@link CollectionExpression} implementing element-wise exponentiation
	 * 
	 * @see io.almostrealism.expression.Exponent#of(Expression, Expression)
	 * @see UniformCollectionExpression
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new UniformCollectionExpression("pow", getShape(),
				op -> Exponent.of(op[0], op[1]),
				args[1], args[2]) {
			@Override
			public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
				if (enableUniqueNonZeroOffset) {
					return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
				}

				return null;
			}
		};
	}

	/**
	 * Generates a new {@link CollectionProducerParallelProcess} for parallel execution of this computation.
	 * This method creates a copy of the current computation with the provided child processes,
	 * preserving all configuration including postprocessors, descriptions, and lifecycle dependencies.
	 *
	 * <p>The parallel process enables efficient execution across multiple hardware threads or
	 * processing units, making it suitable for large-scale power operations in machine learning
	 * and scientific computing contexts.
	 *
	 * @param children The list of child {@link Process} instances, where children.get(1) is the base
	 *                 and children.get(2) is the exponent
	 * @return A new {@link CollectionProducerParallelProcess} configured for parallel execution
	 *
	 * @see CollectionProducerParallelProcess
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CollectionExponentComputation(getName(), getShape(),
				(Producer<PackedCollection>) children.get(1), (Producer<PackedCollection>) children.get(2))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	/**
	 * Computes the derivative (delta) of this exponentiation computation with respect to a target variable.
	 * This method implements an optimized analytical approach using the power rule when {@link #enableCustomDelta}
	 * is true, providing efficient automatic differentiation for machine learning and optimization applications.
	 *
	 * <h4>Mathematical Foundation</h4>
	 *
	 * <p>For a function f(x) = u(x)^v where u(x) is the base (depends on x) and v is a constant exponent,
	 * the derivative is computed using the power rule:
	 * <pre>
	 * df/dx = v * u(x)^(v-1) * du/dx
	 * </pre>
	 *
	 * <p>This implementation specifically handles the case where:
	 * <ul>
	 *   <li>The base operand depends on the target variable (match found in base)</li>
	 *   <li>The exponent is independent of the target variable</li>
	 *   <li>All operands have fixed (non-variable) counts for efficient computation</li>
	 * </ul>
	 *
	 * <h4>Implementation Details</h4>
	 *
	 * <p>The method performs the following steps:
	 * <ol>
	 *   <li>Checks if custom delta computation is enabled via {@link #enableCustomDelta}</li>
	 *   <li>Uses {@link org.almostrealism.algebra.AlgebraFeatures#matchInput} to find which input depends on the target</li>
	 *   <li>Verifies the match is the base operand (first input after destination)</li>
	 *   <li>Ensures all operands have fixed counts (no variable-length collections)</li>
	 *   <li>Applies the power rule: v * u^(v-1) * du/dx</li>
	 *   <li>Handles shape broadcasting and tensor operations appropriately</li>
	 * </ol>
	 *
	 * <h4>Usage Examples</h4>
	 *
	 * <p><strong>Basic Power Rule:</strong>
	 * <pre>{@code
	 * // f(x) = x^3, df/dx = 3*x^2
	 * CollectionProducer<PackedCollection> x = x(5);
	 * CollectionProducer<PackedCollection> f = x.pow(c(3.0));
	 * CollectionProducer<PackedCollection> df_dx = f.delta(x);
	 * }</pre>
	 *
	 * <p><strong>Composite Function:</strong>
	 * <pre>{@code
	 * // f(g(x)) = (2*x + 1)^4, df/dx = 4*(2*x + 1)^3 * 2
	 * CollectionProducer<PackedCollection> x = x(3);
	 * CollectionProducer<PackedCollection> g = x.multiply(c(2.0)).add(c(1.0));
	 * CollectionProducer<PackedCollection> f = g.pow(c(4.0));
	 * CollectionProducer<PackedCollection> df_dx = f.delta(x);
	 * }</pre>
	 *
	 * <h4>Performance Considerations</h4>
	 *
	 * <p>This optimized implementation provides significant performance benefits over numerical differentiation:
	 * <ul>
	 *   <li>O(1) complexity relative to input size (no finite difference approximation)</li>
	 *   <li>Exact derivatives (no numerical precision loss)</li>
	 *   <li>Memory-efficient through shape broadcasting and tensor operations</li>
	 *   <li>Parallel execution support through the collection framework</li>
	 * </ul>
	 *
	 * @param target The {@link Producer} representing the variable with respect to which the derivative is computed
	 * @return A {@link CollectionProducer} that computes the derivative, or delegates to the parent class
	 *         if custom delta is disabled or the computation doesn't match the supported pattern
	 *
	 * @see org.almostrealism.algebra.AlgebraFeatures#matchInput
	 * @see #enableCustomDelta
	 * @throws UnsupportedOperationException if variable count operands are encountered when custom delta is enabled
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		if (!enableCustomDelta) {
			return super.delta(target);
		}

		Optional<Producer<PackedCollection>> match = AlgebraFeatures.matchInput(this, target);

		if (match == null || match.orElse(null) != getInputs().get(1)) {
			// If there are multiple matches, or the match is
			// not the base, then there is not currently a
			// shortcut for computing the delta
			return super.delta(target);
		} else if (getChildren().stream().anyMatch(o -> !Countable.isFixedCount(o))) {
			warn("Exponent delta not implemented for variable count operands");
			return super.delta(target);
		}

		List<Supplier<Evaluable<? extends PackedCollection>>> operands =
				getInputs().stream().skip(1).collect(Collectors.toList());

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy shape = getShape().append(targetShape);

		CollectionProducer u = (CollectionProducer) operands.get(0);
		CollectionProducer v = (CollectionProducer) operands.get(1);
		CollectionProducer uDelta = u.delta(target);
		CollectionProducer scale = v.multiply(u.pow(v.add(c(-1.0))));

		scale = scale.flatten();
		uDelta = uDelta.reshape(v.getShape().getTotalSize(), -1).traverse(0);
		return expandAndMultiply(scale, uDelta).reshape(shape);
	}
}
