/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A specialized repeated computation that computes derivatives (deltas) for operations
 * with constant iteration counts in automatic differentiation scenarios.
 *
 * <p>This class extends {@link ConstantRepeatedProducerComputation} to implement delta
 * computation for repeated operations. It applies the chain rule across multiple iterations,
 * computing the gradient of a repeated computation with respect to a target producer.</p>
 *
 * <h2>Core Functionality</h2>
 * <p>The computation evaluates derivatives by:</p>
 * <ol>
 *   <li>Performing the base repeated computation over a fixed number of iterations</li>
 *   <li>At each iteration, computing the delta of the expression with respect to the target</li>
 *   <li>Accessing the target's gradient values for gradient composition</li>
 *   <li>Accumulating results across all iterations</li>
 * </ol>
 *
 * <h2>Shape Management</h2>
 * <p>The output shape is constructed by appending the target shape to the delta shape:</p>
 * <pre>
 * output_shape = delta_shape.append(target_shape)
 * </pre>
 * <p>This allows for proper gradient accumulation in multi-dimensional computations
 * where gradients need to be computed with respect to tensors of various shapes.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a delta computation for a repeated operation
 * TraversalPolicy deltaShape = shape(10);      // Shape of the original computation output
 * TraversalPolicy targetShape = shape(5);      // Shape of the target w.r.t. which delta is computed
 * int iterationCount = 3;                      // Number of iterations
 *
 * ConstantRepeatedDeltaComputation<PackedCollection> delta =
 *     new ConstantRepeatedDeltaComputation<>(
 *         deltaShape, targetShape, iterationCount,
 *         (args, index) -> computeExpression(args, index),  // Expression function
 *         targetProducer,                                   // Target for differentiation
 *         inputProducers...                                  // Input data producers
 *     );
 *
 * // Evaluate to get the gradient
 * PackedCollection gradient = delta.get().evaluate(inputs...);
 * }</pre>
 *
 * <h2>Integration with Automatic Differentiation</h2>
 * <p>This class is typically created by {@link ConstantRepeatedProducerComputation#delta(Producer)}
 * when computing derivatives of repeated operations. It ensures proper gradient flow through
 * iterative computations by maintaining the relationship between iteration indices and
 * gradient values.</p>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see ConstantRepeatedProducerComputation
 * @see TraversableDeltaComputation
 * @see org.almostrealism.collect.CollectionProducer#delta(Producer)
 *
 * @author Michael Murray
 */
public class ConstantRepeatedDeltaComputation<T extends PackedCollection> extends ConstantRepeatedProducerComputation<T> implements TraversableExpression<Double> {
	/** The shape of the delta (gradient) computation output, before appending target dimensions. */
	private TraversalPolicy deltaShape, targetShape;

	/** The expression function that defines the computation at each iteration. */
	private BiFunction<TraversableExpression[], Expression, Expression> expression;

	/** The target producer with respect to which the derivative is being computed. */
	private Producer<?> target;

	/** The collection variable representing the target during scope preparation. */
	private CollectionVariable<?> targetVariable;

	/** The current row index used for destination addressing in multi-iteration contexts. */
	private Expression row;

	/** Optional fallback expression for value retrieval when direct computation is not available. */
	private TraversableExpression<Double> fallback;

	/**
	 * Constructs a delta computation with default memory length of 1.
	 * This constructor delegates to the full constructor with size=1, making each
	 * kernel thread process one element at a time.
	 *
	 * @param deltaShape The shape of the delta computation output (before appending target dimensions)
	 * @param targetShape The shape of the target with respect to which delta is computed
	 * @param count The fixed number of iterations to perform
	 * @param expression The expression function defining the computation at each iteration
	 * @param target The producer with respect to which the derivative is computed
	 * @param inputs Variable number of input producers providing the data collections
	 */
	@SafeVarargs
	public ConstantRepeatedDeltaComputation(TraversalPolicy deltaShape, TraversalPolicy targetShape, int count,
											BiFunction<TraversableExpression[], Expression, Expression> expression,
											Producer<?> target,
											Producer<PackedCollection>... inputs) {
		this(deltaShape, targetShape, 1, count, expression, target, inputs);
	}

	/**
	 * Constructs a delta computation with explicit memory length control.
	 * This is the primary constructor that sets up all parameters for delta computation.
	 *
	 * <p>The output shape is automatically constructed as deltaShape.append(targetShape),
	 * allowing gradients to properly accumulate across the target's dimensions.</p>
	 *
	 * @param deltaShape The shape of the delta computation output (before appending target dimensions)
	 * @param targetShape The shape of the target with respect to which delta is computed
	 * @param size The memory length - number of elements processed by each kernel thread
	 * @param count The fixed number of iterations to perform
	 * @param expression The expression function defining the computation at each iteration
	 * @param target The producer with respect to which the derivative is computed
	 * @param inputs Variable number of input producers providing the data collections
	 */
	@SafeVarargs
	public ConstantRepeatedDeltaComputation(TraversalPolicy deltaShape, TraversalPolicy targetShape, int size, int count,
											BiFunction<TraversableExpression[], Expression, Expression> expression,
											Producer<?> target,
											Producer<PackedCollection>... inputs) {
		super(null, deltaShape.append(targetShape), size, count, null, null, inputs);
		this.deltaShape	= deltaShape;
		this.targetShape = targetShape;
		this.expression = expression;
		this.target = target;
	}

	/**
	 * Sets a fallback traversable delta computation for value retrieval.
	 * The fallback is used when {@link #getValueAt(Expression)} is called,
	 * delegating to the fallback's implementation. This is useful for integrating
	 * with other delta computation strategies.
	 *
	 * <p>The fallback is added as a dependent lifecycle to ensure proper
	 * resource management and cleanup coordination.</p>
	 *
	 * @param fallback The fallback delta computation to use for value access
	 * @see #getValueAt(Expression)
	 */
	protected void setFallback(TraversableDeltaComputation<T> fallback) {
		addDependentLifecycle(fallback);
		this.fallback = fallback;
	}


	/**
	 * Prepares the argument map for kernel execution.
	 * Delegates to the parent implementation to set up all necessary argument mappings.
	 *
	 * @param map The argument map for tracking kernel arguments
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	/**
	 * Prepares the scope for kernel compilation by setting up the target variable.
	 * This method obtains the {@link CollectionVariable} for the target producer,
	 * which will be used during expression evaluation to compute deltas.
	 *
	 * @param manager The scope input manager for handling argument preparation
	 * @param context The kernel structure context providing compilation information
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		targetVariable = (CollectionVariable<?>) manager.argumentForInput(getNameProvider()).apply((Supplier) target);
	}

	/**
	 * Resets the computation arguments and clears the target variable.
	 * This method should be called when argument values change to ensure
	 * subsequent evaluations use updated arguments.
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();
		targetVariable = null;
	}

	/**
	 * Generates the delta expression for the computation at the specified indices.
	 * This method applies the expression function, computes its delta with respect
	 * to the target variable, and retrieves the gradient value at the current position.
	 *
	 * <p>The delta computation follows this pattern:</p>
	 * <pre>
	 * result = expression(args, row + localIndex).delta(target).getValueRelative(row + localIndex)
	 * </pre>
	 *
	 * @param args The traversable expressions representing input arguments
	 * @param globalIndex The global index in the overall computation space
	 * @param localIndex The local index within the current iteration
	 * @return An {@link Expression} representing the delta value at the specified position
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		return expression.apply(args, row.add(localIndex))
				.delta(targetVariable)
				.getValueRelative(row.add(localIndex));
	}

	/**
	 * Retrieves the value at the specified multi-dimensional position.
	 * Converts the position coordinates to a linear index and delegates
	 * to {@link #getValueAt(Expression)}.
	 *
	 * @param pos Variable number of position coordinates
	 * @return An {@link Expression} representing the value at the specified position
	 * @see #getValueAt(Expression)
	 */
	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	/**
	 * Retrieves the value at the specified linear index.
	 * Delegates to the fallback delta computation if available, otherwise returns null.
	 *
	 * @param index The linear index position
	 * @return An {@link Expression} representing the value at the index,
	 *         or null if no fallback is set
	 * @see #setFallback(TraversableDeltaComputation)
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		return fallback == null ? null : fallback.getValueAt(index);
	}

	/**
	 * Computes the destination expression for writing delta results.
	 * Calculates the row index based on the global index and iteration count,
	 * then delegates to the parent implementation with the adjusted offset.
	 *
	 * <p>The row calculation accounts for multiple iterations by multiplying
	 * the global index by the iteration count, ensuring proper addressing
	 * across all gradient accumulation iterations.</p>
	 *
	 * @param globalIndex The global index in the overall computation space
	 * @param localIndex The local index within the current iteration
	 * @param offset The offset within the memory block (not used in this implementation)
	 * @return An {@link Expression} representing the destination memory location
	 */
	@Override
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset) {
		row = globalIndex.multiply(e(count));
		return super.getDestination(globalIndex, localIndex, row.add(localIndex));
	}

	/**
	 * Generates a new instance of this delta computation with updated child processes.
	 * This method creates a new computation with the same configuration but different
	 * input sources, which is essential for parallel processing and computation graph optimization.
	 *
	 * @param children The list of child processes to use as inputs for the new instance
	 * @return A new {@link ConstantRepeatedDeltaComputation} with the same configuration
	 *         but updated inputs from the child processes
	 */
	@Override
	public ConstantRepeatedDeltaComputation<T> generate(List<Process<?, ?>> children) {
		return new ConstantRepeatedDeltaComputation<>(
				deltaShape, targetShape,
				getMemLength(), count,
				expression, target,
				children.stream().skip(1).toArray(Producer[]::new));
	}

	/**
	 * Factory method for creating a {@link ConstantRepeatedDeltaComputation} with default
	 * memory length. This is a convenience method that simplifies creation of delta
	 * computations for repeated operations.
	 *
	 * <p>Usage Example:</p>
	 * <pre>{@code
	 * ConstantRepeatedDeltaComputation<PackedCollection> delta =
	 *     ConstantRepeatedDeltaComputation.create(
	 *         shape(10), shape(5), 3,
	 *         (args, index) -> computeExpression(args, index),
	 *         targetProducer,
	 *         inputProducers...
	 *     );
	 * }</pre>
	 *
	 * @param <T> The type of {@link PackedCollection} produced
	 * @param deltaShape The shape of the delta computation output
	 * @param targetShape The shape of the target for differentiation
	 * @param count The number of iterations
	 * @param expression The expression function for each iteration
	 * @param target The target producer for differentiation
	 * @param arguments Input producers providing data collections
	 * @return A new {@link ConstantRepeatedDeltaComputation} instance
	 */
	public static <T extends PackedCollection> ConstantRepeatedDeltaComputation<T> create(
			TraversalPolicy deltaShape, TraversalPolicy targetShape, int count,
			BiFunction<TraversableExpression[], Expression, Expression> expression,
			Producer<?> target,
			Producer<PackedCollection>... arguments) {
		return new ConstantRepeatedDeltaComputation<>(
				deltaShape, targetShape, count, expression,
				target, arguments);
	}
}
