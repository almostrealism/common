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

import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A specialized computation class that performs repeated operations on {@link PackedCollection} data structures.
 * This class implements iterative computations with user-defined initialization values, loop conditions, and
 * expressions that are executed repeatedly until the condition is satisfied.
 * 
 * <p>{@code RepeatedProducerComputation} is designed for implementing algorithms that require iterative processing,
 * such as numerical iterations, convergence algorithms, recursive computations, and loop-based transformations.
 * It provides a flexible framework for defining complex repeated operations while leveraging hardware acceleration
 * and parallel processing capabilities.</p>
 * 
 * <h2>Key Components:</h2>
 * <ul>
 *   <li><strong>Initial Function:</strong> Defines the starting values for the computation using a {@link BiFunction}
 *       that takes traversable expressions and an index to produce initial values</li>
 *   <li><strong>Condition Function:</strong> Specifies the continuation condition as a {@link BiFunction} that
 *       determines whether the iteration should continue based on current state and index</li>
 *   <li><strong>Expression Function:</strong> Defines the computation performed in each iteration, transforming
 *       the current values based on input arguments and loop index</li>
 *   <li><strong>Memory Management:</strong> Configurable memory length for efficient buffer allocation and reuse</li>
 * </ul>
 * 
 * <h2>Usage Examples:</h2>
 * 
 * <h3>Basic Iterative Computation:</h3>
 * <pre>{@code
 * // Create a computation that sums values iteratively
 * TraversalPolicy shape = new TraversalPolicy(100); // Process 100 elements
 * Producer<PackedCollection<?>> input = ...; // Input data source
 * 
 * RepeatedProducerComputation<PackedCollection<?>> sumComputation = 
 *     new RepeatedProducerComputation<>("iterativeSum", shape,
 *         // Initial: start with zeros  
 *         (args, index) -> new DoubleConstant(0.0),
 *         // Condition: continue for 10 iterations
 *         (args, index) -> index.lessThan(new IntegerConstant(10)),
 *         // Expression: add input value to current sum
 *         (args, index) -> args[0].getValueRelative(index).add(args[1].getValueRelative(index)),
 *         input);
 * 
 * PackedCollection<?> result = sumComputation.get().evaluate();
 * }</pre>
 * 
 * <h3>Convergence Algorithm:</h3>
 * <pre>{@code
 * // Newton-Raphson style iterative refinement
 * RepeatedProducerComputation<PackedCollection<?>> refinement = 
 *     new RepeatedProducerComputation<>("newtonRaphson", shape,
 *         // Initial: use input as starting guess
 *         (args, index) -> args[0].getValueRelative(index),
 *         // Condition: check convergence or max iterations
 *         (args, index) -> index.lessThan(new IntegerConstant(100)).and(
 *             args[0].getValueRelative(index).subtract(args[1].getValueRelative(index))
 *                 .abs().greaterThan(new DoubleConstant(1e-6))),
 *         // Expression: Newton-Raphson update formula
 *         (args, index) -> args[0].getValueRelative(index).subtract(
 *             function(args[0].getValueRelative(index)).divide(
 *                 derivative(args[0].getValueRelative(index)))),
 *         initialGuess);
 * }</pre>
 * 
 * <h2>Architecture:</h2>
 * <p>This class extends {@link CollectionProducerComputationBase} and integrates with the Almost Realism
 * computation framework to provide:</p>
 * <ul>
 *   <li><strong>Hardware Acceleration:</strong> Automatic GPU/CPU optimization for parallel execution</li>
 *   <li><strong>Memory Efficiency:</strong> Intelligent buffer management and memory pooling</li>
 *   <li><strong>Scope Management:</strong> Integration with the {@link io.almostrealism.scope.Repeated} scope
 *       for efficient kernel generation</li>
 *   <li><strong>Delta Computation:</strong> Support for automatic differentiation and gradient calculation</li>
 * </ul>
 * 
 * <h2>Thread Safety:</h2>
 * <p>This class is <strong>not thread-safe</strong>. Instances should not be shared between threads without
 * external synchronization. However, once compiled via {@link #get()}, the resulting {@link Evaluable}
 * can be used concurrently across multiple threads.</p>
 * 
 * <h2>Performance Considerations:</h2>
 * <ul>
 *   <li><strong>Memory Length:</strong> The {@code memLength} parameter controls buffer allocation efficiency.
 *       Larger values may improve performance for memory-intensive operations but increase memory usage</li>
 *   <li><strong>Condition Complexity:</strong> Simple conditions are preferred as they are evaluated frequently
 *       during iteration and complex conditions can impact performance</li>
 *   <li><strong>Index Limits:</strong> Use {@link #getIndexLimit()} to provide hints for optimization when
 *       the maximum number of iterations is known</li>
 * </ul>
 * 
 * @param <T> The type of {@link PackedCollection} this computation produces and operates on
 * 
 * @author Michael Murray
 * @since 0.69
 * 
 * @see CollectionProducerComputationBase
 * @see ConstantRepeatedProducerComputation
 * @see TraversableRepeatedProducerComputation
 * @see PackedCollection
 * @see TraversalPolicy
 * @see io.almostrealism.expression.Expression
 * @see io.almostrealism.collect.TraversableExpression
 */
public class RepeatedProducerComputation<T extends PackedCollection<?>> extends CollectionProducerComputationBase<T, T> {

	/**
	 * Function that computes the initial values for the repeated computation.
	 * This function is called at the beginning of the iteration to establish
	 * starting values based on the input arguments and index position.
	 * 
	 * @see #setInitial(BiFunction)
	 */
	protected BiFunction<TraversableExpression[], Expression, Expression> initial;
	
	/**
	 * Function that determines whether the iteration should continue.
	 * This function is evaluated before each iteration step to decide
	 * if the computation should proceed. When this function returns false,
	 * the iteration terminates.
	 * 
	 * @see #setCondition(BiFunction)
	 */
	private BiFunction<TraversableExpression[], Expression, Expression> condition;
	
	/**
	 * Function that defines the computation performed in each iteration step.
	 * This function transforms the current state based on input arguments
	 * and the current iteration index, producing the values for the next iteration.
	 */
	protected BiFunction<TraversableExpression[], Expression, Expression> expression;
	
	/**
	 * The memory length allocated for this computation, controlling buffer size
	 * and memory management efficiency. Larger values may improve performance
	 * for memory-intensive operations but require more memory allocation.
	 * 
	 * @see #getMemLength()
	 */
	private int memLength;

	/**
	 * Constructs a {@code RepeatedProducerComputation} with default memory length of 1.
	 * This constructor is suitable for simple repeated operations that don't require
	 * extensive memory buffering.
	 * 
	 * @param name       Human-readable name for this computation, used in debugging and profiling
	 * @param shape      The {@link TraversalPolicy} defining the output shape and data access patterns
	 * @param initial    Function to compute initial values, taking traversable expressions and index
	 *                   as parameters and returning the starting {@link Expression}
	 * @param condition  Function to evaluate continuation condition, returning a boolean {@link Expression}
	 *                   that determines whether iteration should continue
	 * @param expression Function defining the computation for each iteration step, transforming current
	 *                   values based on input arguments and iteration index
	 * @param args       Variable number of {@link Supplier}s providing {@link Evaluable} inputs to
	 *                   the computation
	 * 
	 * @see #RepeatedProducerComputation(String, TraversalPolicy, int, BiFunction, BiFunction, BiFunction, Supplier[])
	 */
	@SafeVarargs
	public RepeatedProducerComputation(String name, TraversalPolicy shape,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(name, shape, 1, initial, condition, expression, args);
	}

	/**
	 * Constructs a {@code RepeatedProducerComputation} with configurable memory length.
	 * This constructor provides full control over memory allocation and is recommended
	 * for performance-critical applications or computations requiring large intermediate buffers.
	 * 
	 * @param name       Human-readable name for this computation, used in debugging and profiling
	 * @param shape      The {@link TraversalPolicy} defining the output shape and data access patterns
	 * @param size       Memory length for buffer allocation, controlling memory efficiency and performance
	 *                   characteristics. Larger values may improve performance but increase memory usage
	 * @param initial    Function to compute initial values, taking traversable expressions and index
	 *                   as parameters and returning the starting {@link Expression}. May be {@code null}
	 *                   if no initialization is required
	 * @param condition  Function to evaluate continuation condition, returning a boolean {@link Expression}
	 *                   that determines whether iteration should continue. Should return {@code false}
	 *                   when the computation should terminate
	 * @param expression Function defining the computation for each iteration step, transforming current
	 *                   values based on input arguments and iteration index. This function is called
	 *                   for each iteration until the condition becomes false
	 * @param args       Variable number of {@link Supplier}s providing {@link Evaluable} inputs to
	 *                   the computation. These will be available as traversable expressions in the
	 *                   initial, condition, and expression functions
	 * 
	 * @throws IllegalArgumentException if shape is null or has invalid dimensions
	 * 
	 * @see TraversalPolicy
	 * @see TraversableExpression
	 * @see Expression
	 */
	@SafeVarargs
	public RepeatedProducerComputation(String name, TraversalPolicy shape, int size,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(name, shape, (Supplier[]) args);
		this.initial = initial;
		this.condition = condition;
		this.expression = expression;
		this.memLength = size;
	}

	/**
	 * Sets the initial value function for this repeated computation.
	 * This method allows runtime modification of the initialization logic,
	 * enabling dynamic behavior based on computation context or external conditions.
	 * 
	 * <p>The initial function is called once at the beginning of the iteration
	 * to establish starting values. It receives the input arguments as traversable
	 * expressions and an index expression to compute appropriate initial values.</p>
	 * 
	 * @param initial The new initial value function, or {@code null} to disable initialization.
	 *                The function should take an array of {@link TraversableExpression}s
	 *                representing input arguments and an {@link Expression} representing
	 *                the current index, returning an {@link Expression} for the initial value
	 * 
	 * @see #initial
	 */
	protected void setInitial(BiFunction<TraversableExpression[], Expression, Expression> initial) {
		this.initial = initial;
	}

	/**
	 * Sets the condition function that determines iteration continuation.
	 * This method allows runtime modification of the termination criteria,
	 * enabling adaptive algorithms that can change their stopping conditions
	 * based on intermediate results or external factors.
	 * 
	 * <p>The condition function is evaluated before each iteration step.
	 * When it returns a false expression, the iteration terminates. The function
	 * receives the current state as traversable expressions and the iteration index.</p>
	 * 
	 * @param condition The new condition function that determines whether to continue iteration.
	 *                  The function should take an array of {@link TraversableExpression}s
	 *                  representing the current state and an {@link Expression} representing
	 *                  the iteration index, returning a boolean {@link Expression}
	 * 
	 * @see #condition
	 */
	protected void setCondition(BiFunction<TraversableExpression[], Expression, Expression> condition) {
		this.condition = condition;
	}

	/**
	 * Returns the memory length allocated for this computation.
	 * The memory length controls buffer allocation and affects both performance
	 * and memory usage characteristics of the computation.
	 * 
	 * @return The memory length as configured during construction, representing
	 *         the buffer size used for intermediate computations
	 * 
	 * @see #memLength
	 * @see MemoryDataComputation#getMemLength()
	 */
	@Override
	public int getMemLength() { return memLength; }

	/**
	 * Returns an optional limit on the number of iterations for optimization purposes.
	 * Subclasses can override this method to provide hints to the computation engine
	 * about the expected maximum number of iterations, enabling better optimization
	 * and resource allocation.
	 * 
	 * <p>The default implementation returns an empty {@link OptionalInt}, indicating
	 * that no specific limit is known. This allows the computation to run until
	 * the condition function returns false.</p>
	 * 
	 * @return An {@link OptionalInt} containing the maximum expected number of iterations
	 *         if known, or empty if no limit is specified
	 * 
	 * @see OptionalInt
	 */
	protected OptionalInt getIndexLimit() {
		return OptionalInt.empty();
	}

	/**
	 * Generates metadata for this computation operation.
	 * The metadata provides information about the computation for profiling,
	 * debugging, and optimization purposes. It includes the function name
	 * and operation type classification.
	 * 
	 * @return {@link OperationMetadata} describing this computation, including
	 *         function name and operation type. If no metadata was previously
	 *         set, creates default metadata with "Repeated" as the operation type
	 * 
	 * @see OperationMetadata
	 * @see #getFunctionName()
	 */
	@Override
	public OperationMetadata getMetadata() {
		OperationMetadata metadata = super.getMetadata();
		if (metadata == null)
			metadata = new OperationMetadata(getFunctionName(), "Repeated");
		return metadata;
	}

	/**
	 * Generates the computation scope for kernel execution.
	 * This method creates a {@link io.almostrealism.scope.Repeated} scope that defines
	 * the iterative structure of the computation, including initialization, condition
	 * checking, and iteration body execution.
	 * 
	 * <p>The generated scope handles:</p>
	 * <ul>
	 *   <li>Variable allocation and initialization using the {@link #initial} function</li>
	 *   <li>Loop condition evaluation using the {@link #condition} function</li>
	 *   <li>Iteration body execution using the {@link #expression} function</li>
	 *   <li>Memory management and buffer allocation based on {@link #memLength}</li>
	 * </ul>
	 * 
	 * @param context The {@link KernelStructureContext} providing compilation context
	 *                and kernel configuration information
	 * 
	 * @return A {@link Scope} representing the repeated computation structure,
	 *         ready for kernel compilation and execution
	 * 
	 * @see io.almostrealism.scope.Repeated
	 * @see KernelStructureContext
	 * @see Scope
	 */
	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Repeated<T> scope = new Repeated<>(getFunctionName(), getMetadata());
		scope.setInterval(e(getMemLength()));

		String i = getVariablePrefix() + "_i";
		scope.setIndex(new Variable<>(i));

		DefaultIndex ref = new DefaultIndex(i);
		getIndexLimit().ifPresent(ref::setLimit);
		scope.setCondition(condition.apply(getTraversableArguments(ref), ref));

		Expression index = new KernelIndex(context).divide(e(getShape().getSize())).multiply(e(getShape().getSize()));

		if (initial != null) {
			for (int j = 0; j < getMemLength(); j++) {
				Expression<?> out = getDestination(new KernelIndex(context), e(0), e(j));
				Expression<?> val = initial.apply(getTraversableArguments(index), ref.add(j));
				scope.getStatements().add(out.assign(val));
			}
		}

		OperationMetadata bodyMetadata = new OperationMetadata
				(getFunctionName() + "_body",
				"Repeated (Body)");

		Scope<T> body = new Scope<>(getFunctionName() + "_body", bodyMetadata);
		for (int j = 0; j < getMemLength(); j++) {
			Expression<?> out = getDestination(new KernelIndex(context), ref, e(j));
			Expression<?> val = getExpression(index, ref.add(j));
			body.getStatements().add(out.assign(val));
		}

		scope.add(body);
		return scope;
	}

	/**
	 * Computes the expression for a specific global and local index combination.
	 * This is a convenience method that delegates to the overloaded version
	 * with traversable arguments, using the default argument retrieval mechanism.
	 * 
	 * @param globalIndex The global index expression within the overall computation space
	 * @param localIndex  The local index expression within the current iteration
	 * 
	 * @return An {@link Expression} representing the computed value at the specified indices
	 * 
	 * @see #getExpression(TraversableExpression[], Expression, Expression)
	 * @see #getTraversableArguments(Expression)
	 */
	protected Expression<?> getExpression(Expression globalIndex, Expression localIndex) {
		return getExpression(getTraversableArguments(globalIndex), globalIndex, localIndex);
	}

	/**
	 * Computes the expression for a specific iteration step with provided arguments.
	 * This method applies the {@link #expression} function to transform the current
	 * state based on the input arguments and iteration context.
	 * 
	 * <p>This method is called for each iteration step and defines the core computation
	 * logic. The provided arguments represent the current state of input data,
	 * while the indices specify the position within the global computation space
	 * and the current iteration.</p>
	 * 
	 * @param args        Array of {@link TraversableExpression}s representing the current
	 *                    input arguments and intermediate values
	 * @param globalIndex The global index expression within the overall computation space
	 * @param localIndex  The local index expression within the current iteration step
	 * 
	 * @return An {@link Expression} representing the computed value for this iteration step
	 * 
	 * @see #expression
	 * @see TraversableExpression
	 */
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		return expression.apply(args, localIndex);
	}

	/**
	 * Computes the destination expression for storing computation results.
	 * This method generates the appropriate memory reference for storing the
	 * computed value at the specified position, handling both kernel-based
	 * and regular array access patterns.
	 * 
	 * @param globalIndex The global index expression within the overall computation space
	 * @param localIndex  The local index expression within the current iteration
	 * @param offset      The offset expression for multi-dimensional data access
	 * 
	 * @return An {@link Expression} representing the memory location where the
	 *         computed result should be stored
	 * 
	 * @see ArrayVariable
	 * @see KernelIndex
	 */
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset)	{
		if (globalIndex instanceof KernelIndex) {
			return ((ArrayVariable) getOutputVariable()).referenceRelative(offset, (KernelIndex) globalIndex);
		} else {
			return ((ArrayVariable) getOutputVariable()).referenceRelative(offset);
		}
	}

	/**
	 * Generates a new instance of this computation with updated child processes.
	 * This method is used during computation tree optimization and reconstruction,
	 * allowing the computation to be rebuilt with modified dependencies while
	 * preserving the original configuration.
	 * 
	 * @param children List of child {@link Process}es that will provide input to
	 *                 the new computation instance. The first element is typically
	 *                 the computation itself, so it's skipped when creating the
	 *                 new instance
	 * 
	 * @return A new {@code RepeatedProducerComputation} instance with the same
	 *         configuration as this instance but using the provided children
	 *         as input suppliers
	 * 
	 * @see Process
	 * @see #generate(List)
	 */
	@Override
	public RepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new RepeatedProducerComputation<>(
				null, getShape(), getMemLength(),
				initial, condition, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
