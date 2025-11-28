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

import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.HardwareFeatures;

/**
 * A {@link TraversableExpressionComputation} provides a framework for computations that operate on
 * multi-dimensional collections using traversable expressions. This abstract class serves as a base
 * for computations that process data by applying mathematical expressions across collection elements
 * in a structured, multi-dimensional manner.
 * 
 * <p>The computation works by:
 * <ul>
 *   <li>Accepting input collections with defined {@link TraversalPolicy} shapes</li>
 *   <li>Transforming inputs into {@link TraversableExpression} objects that represent the data traversal</li>
 *   <li>Applying an expression function to generate {@link CollectionExpression} results</li>
 *   <li>Supporting automatic differentiation through delta computation strategies</li>
 * </ul>
 * 
 * <h3>Usage Examples</h3>
 * 
 * <p><strong>Basic Pair Sum Example:</strong>
 * <pre>{@code
 * // Create a computation that sums pairs of elements from a 2D collection
 * TraversalPolicy inputShape = shape(3, 2);  // 3 rows, 2 columns
 * TraversalPolicy outputShape = shape(3, 1); // 3 rows, 1 column (sums)
 * 
 * DefaultTraversableExpressionComputation<PackedCollection> pairSum =
 *     new DefaultTraversableExpressionComputation<>(
 *         "pairSum", 
 *         outputShape,
 *         (args) -> DefaultCollectionExpression.create(outputShape,
 *             idx -> Sum.of(
 *                 args[1].getValueRelative(new IntegerConstant(0)),
 *                 args[1].getValueRelative(new IntegerConstant(1)))),
 *         inputProducer);
 * 
 * PackedCollection result = pairSum.get().evaluate();
 * }</pre>
 * 
 * <p><strong>Element-wise Mathematical Operations:</strong>
 * <pre>{@code
 * // Square each element in a collection
 * DefaultTraversableExpressionComputation<PackedCollection> square =
 *     new DefaultTraversableExpressionComputation<>(
 *         "square", 
 *         inputShape,
 *         (args) -> DefaultCollectionExpression.create(inputShape,
 *             idx -> Product.of(
 *                 args[1].getValueRelative(IntegerConstant.ZERO),
 *                 args[1].getValueRelative(IntegerConstant.ZERO))),
 *         inputProducer);
 * }</pre>
 * 
 * <h3>Key Features</h3>
 * <ul>
 *   <li><strong>Multi-dimensional Support:</strong> Handles collections with arbitrary dimensions</li>
 *   <li><strong>Traversal Control:</strong> Uses {@link TraversalPolicy} to define data access patterns</li>
 *   <li><strong>Expression-based:</strong> Leverages mathematical expressions for computation logic</li>
 *   <li><strong>Differentiation Support:</strong> Includes automatic differentiation through delta strategies</li>
 *   <li><strong>GPU Acceleration:</strong> Compiles to hardware-accelerated kernels when possible</li>
 * </ul>
 * 
 * <p>Implementations must provide the {@link #getExpression(TraversableExpression...)} method
 * which defines how to transform input traversable expressions into a collection expression
 * that represents the desired computation.
 * 
 * @see DefaultTraversableExpressionComputation
 * @see TraversalPolicy
 * @see TraversableExpression
 * @see CollectionExpression
 * @see MultiTermDeltaStrategy
 *
 * @author Michael Murray
 */
public abstract class TraversableExpressionComputation
		extends CollectionProducerComputationAdapter implements HardwareFeatures {

	/**
	 * The strategy used for handling multi-term delta (derivative) computations.
	 * This controls how automatic differentiation is performed when multiple
	 * input terms are present in the computation.
	 * 
	 * @see MultiTermDeltaStrategy
	 */
	private final MultiTermDeltaStrategy deltaStrategy;

	/**
	 * Constructs a new TraversableExpressionComputation with default delta strategy.
	 * This constructor uses {@link MultiTermDeltaStrategy#NONE} as the default
	 * strategy for handling multi-term derivatives.
	 * 
	 * @param name The name of this computation, used for debugging and identification
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param args Variable number of input suppliers that provide evaluable collections
	 */
	@SafeVarargs
	public TraversableExpressionComputation(String name, TraversalPolicy shape,
											Producer<PackedCollection>... args) {
		this(name, shape, MultiTermDeltaStrategy.NONE, args);
	}

	/**
	 * Constructs a new TraversableExpressionComputation with a specified delta strategy.
	 * This constructor allows full control over how automatic differentiation is handled
	 * for computations involving multiple input terms.
	 * 
	 * @param name The name of this computation, used for debugging and identification.
	 *             If null, a warning will be logged.
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern.
	 *              This determines how the output collection is structured and accessed.
	 * @param deltaStrategy The {@link MultiTermDeltaStrategy} to use for handling derivatives
	 *                     when multiple input terms are present. Controls automatic differentiation behavior.
	 * @param args Variable number of input suppliers that provide evaluable collections.
	 *             These represent the inputs to the computation and will be transformed
	 *             into traversable expressions.
	 */
	@SafeVarargs
	public TraversableExpressionComputation(String name, TraversalPolicy shape,
											MultiTermDeltaStrategy deltaStrategy,
											Producer<PackedCollection>... args) {
		super(name, shape, validateArgs(args));
		this.deltaStrategy = deltaStrategy;

		if (name == null) {
			warn("Name is null for " + getClass().getSimpleName());
		}
	}

	/**
	 * Defines the mathematical expression to be applied to the input traversable expressions.
	 * This is the core method that subclasses must implement to define their specific computation logic.
	 * 
	 * <p>The method receives an array of {@link TraversableExpression} objects representing the inputs
	 * that have been processed according to the computation's traversal policy. It should return
	 * a {@link CollectionExpression} that represents the mathematical operation to be performed.
	 * 
	 * <p><strong>Example Implementation:</strong>
	 * <pre>{@code
	 * @Override
	 * protected CollectionExpression getExpression(TraversableExpression... args) {
	 *     // For a sum operation: add the first two elements from each input
	 *     return DefaultCollectionExpression.create(getShape(),
	 *         idx -> Sum.of(
	 *             args[1].getValueRelative(new IntegerConstant(0)),
	 *             args[1].getValueRelative(new IntegerConstant(1))));
	 * }
	 * }</pre>
	 * 
	 * @param args Array of {@link TraversableExpression} objects representing the inputs.
	 *             The first element (args[0]) is typically the destination, while subsequent
	 *             elements (args[1], args[2], ...) are the actual input data expressions.
	 * @return A {@link CollectionExpression} representing the mathematical operation to be performed
	 */
	protected abstract CollectionExpression getExpression(TraversableExpression... args);

	/**
	 * Determines whether the output is relative to the traversal axis.
	 * Returns true if the output depends on the current position in the traversal,
	 * false if it produces fixed-count output regardless of position.
	 * 
	 * @return true if output is relative to traversal position, false for fixed count output
	 */
	@Override
	protected boolean isOutputRelative() { return !isFixedCount(); }

	/**
	 * Checks if this computation produces constant values.
	 * A computation is considered constant if all its inputs (excluding the first destination input)
	 * are constant. This information is used for optimization purposes.
	 * 
	 * @return true if all input computations are constant, false otherwise
	 */
	@Override
	public boolean isConstant() {
		return getInputs().stream().skip(1)
				.map(c -> c instanceof Computable && ((Computable) c).isConstant())
				.reduce(true, (a, b) -> a && b);
	}

	/**
	 * Indicates whether this computation supports the chain rule for automatic differentiation.
	 * TraversableExpressionComputation implementations generally support chain rule
	 * for computing derivatives through the computation graph.
	 * 
	 * @return true, indicating chain rule support is available
	 */
	@Override
	public boolean isChainRuleSupported() { return true; }

	/**
	 * Gets the delta strategy used for handling multi-term derivatives.
	 * This strategy controls how automatic differentiation is performed when
	 * the computation involves multiple terms that need to be differentiated.
	 * 
	 * @return The {@link MultiTermDeltaStrategy} for this computation
	 * @see MultiTermDeltaStrategy
	 */
	@Override
	public MultiTermDeltaStrategy getDeltaStrategy() { return deltaStrategy; }

	/**
	 * Computes the derivative (delta) of this computation with respect to a target producer.
	 * This method implements automatic differentiation by creating a new computation that
	 * represents the gradient of the current computation with respect to the specified target.
	 * 
	 * <p>The method first attempts to compute a delta using standard differentiation techniques.
	 * If that fails, it falls back to creating a {@link TraversableDeltaComputation} that
	 * can handle more complex differentiation scenarios.
	 * 
	 * @param target The {@link Producer} with respect to which the derivative should be computed
	 * @return A {@link CollectionProducer} representing the computed derivative
	 * @see TraversableDeltaComputation
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		CollectionProducer delta = attemptDelta(target);
		if (delta != null) return delta;

		delta = TraversableDeltaComputation.create(
				"\u03B4" + getName(), getShape(), shape(target),
				this::getExpression, target,
				getInputs().stream().skip(1).toArray(Producer[]::new));
		return delta;
	}

	/**
	 * Gets the value at the specified position in the output collection.
	 * This method converts multi-dimensional position coordinates to a linear index
	 * and then retrieves the value at that index.
	 * 
	 * @param pos Variable number of {@link Expression} objects representing coordinates
	 *            in the multi-dimensional output space
	 * @return An {@link Expression} representing the value at the specified position
	 */
	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	/**
	 * Gets the value at the specified linear index in the output collection.
	 * This method creates traversable expressions for the inputs and then uses
	 * the computation's expression function to determine the value at the given index.
	 * 
	 * @param index An {@link Expression} representing the linear index into the output collection
	 * @return An {@link Expression} representing the value at the specified index
	 */
	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(getTraversableArguments(index)).getValueAt(index);
	}

	/**
	 * Gets the value at the specified relative index within the current traversal context.
	 * This method is used when accessing values relative to the current position
	 * during traversal, rather than using absolute indexing.
	 * 
	 * @param index An {@link Expression} representing the relative index offset
	 * @return An {@link Expression} representing the value at the relative position
	 */
	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(getTraversableArguments(new IntegerConstant(0))).getValueRelative(index);
	}

	/**
	 * Checks whether the computation's output contains data at the specified index.
	 * This method is used to determine if a particular index position is valid
	 * and contains meaningful data within the computation's output space.
	 * 
	 * @param index An {@link Expression} representing the index to check
	 * @return An {@link Expression} of {@link Boolean} indicating whether the index contains data
	 */
	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return getExpression(getTraversableArguments(index)).containsIndex(index);
	}

	/**
	 * Computes a unique non-zero offset for memory access optimization.
	 * This method is used by the kernel compilation system to optimize memory access patterns
	 * by determining unique offsets that avoid memory conflicts.
	 * 
	 * @param globalIndex The global index context for the computation
	 * @param localIndex The local index context within the current work group
	 * @param targetIndex The target index for which to compute the offset
	 * @return An {@link Expression} representing the unique non-zero offset
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return getExpression(getTraversableArguments(targetIndex))
				.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}
}
