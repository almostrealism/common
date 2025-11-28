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

import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
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
 * A computation that performs repeated operations on {@link PackedCollection} data structures,
 * implementing iterative algorithms with initialization, condition checking, and expression evaluation.
 * 
 * <p>This class provides a framework for creating computations that need to iterate over data
 * with a specific pattern: initialize values, check continuation conditions, and apply expressions
 * repeatedly until the condition is no longer satisfied.
 * 
 * <p>The computation operates with three key functional components:
 * <ul>
 *   <li><strong>Initial:</strong> A {@link BiFunction} that defines how to initialize values
 *       at the start of the computation</li>
 *   <li><strong>Condition:</strong> A {@link BiFunction} that determines whether to continue
 *       the iteration based on current state</li>
 *   <li><strong>Expression:</strong> A {@link BiFunction} that defines the computation to
 *       perform in each iteration step</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong>
 * 
 * <p>Basic repeated computation with fixed iteration count:
 * <pre>{@code
 * // Create a computation that accumulates values
 * RepeatedProducerComputation<PackedCollection> accumulator = 
 *     new RepeatedProducerComputation<>(
 *         "accumulate",
 *         shape(10),  // Process 10 elements
 *         (args, index) -> e(0.0),  // Initialize to zero
 *         (args, index) -> index.lessThan(e(5)),  // Continue for 5 iterations
 *         (args, index) -> args[0].valueAt(index).add(e(1.0)),  // Add 1 each iteration
 *         inputProducer
 *     );
 * }</pre>
 * 
 * <p>Using with memory optimization:
 * <pre>{@code
 * // Create a computation with specific memory length for optimization
 * RepeatedProducerComputation<PackedCollection> optimized = 
 *     new RepeatedProducerComputation<>(
 *         "optimizedSum",
 *         shape(100),
 *         4,  // Process 4 elements per kernel thread
 *         (args, index) -> args[0].valueAt(index),  // Initialize with input
 *         (args, index) -> index.lessThan(e(25)),   // 25 iterations
 *         (args, index) -> args[0].valueAt(index).multiply(e(2.0)),  // Double each iteration
 *         dataProducer
 *     );
 * }</pre>
 * 
 * <p><strong>Memory Management:</strong>
 * The {@code memLength} parameter controls how many elements are processed by each kernel thread,
 * which can significantly impact performance. A higher value reduces the number of kernel launches
 * but increases memory usage per thread.
 * 
 * <p><strong>Thread Safety:</strong>
 * This class is designed to be thread-safe for concurrent execution on GPU kernels, but individual
 * instances should not be modified after construction.
 * 
 * @param <T> The type of {@link PackedCollection} this computation operates on
 * 
 * @see CollectionProducerComputationBase
 * @see ConstantRepeatedProducerComputation
 * @see TraversableRepeatedProducerComputation  
 * @see BiFunction
 * @see TraversableExpression
 * 
 * @author Michael Murray
 */
public class RepeatedProducerComputation<T extends PackedCollection> extends CollectionProducerComputationBase<T, T> {

	/** Function that defines how to initialize values at the start of computation. */
	protected BiFunction<TraversableExpression[], Expression, Expression> initial;
	
	/** Function that determines whether to continue the iteration based on current state. */
	private BiFunction<TraversableExpression[], Expression, Expression> condition;
	
	/** Function that defines the computation to perform in each iteration step. */
	protected BiFunction<TraversableExpression[], Expression, Expression> expression;
	
	/** The number of elements processed by each kernel thread for memory optimization. */
	private int memLength;

	/**
	 * Creates a repeated computation with default memory length of 1.
	 * 
	 * <p>This constructor creates a computation that will repeatedly apply the given
	 * expression while the condition returns true, starting with values from the
	 * initial function.
	 * 
	 * @param name The name identifier for this computation, used in generated code
	 * @param shape The {@link TraversalPolicy} defining the multi-dimensional shape 
	 *              and access pattern of the output data
	 * @param initial Function to initialize values at computation start. Receives
	 *                traversable arguments and current index, returns initialization expression
	 * @param condition Function to check continuation. Receives traversable arguments  
	 *                  and current index, returns boolean expression for continuation
	 * @param expression Function defining the main computation. Receives traversable
	 *                   arguments and current index, returns computed expression
	 * @param args Variable number of input {@link Supplier}s providing evaluable collections
	 * 
	 * @see #RepeatedProducerComputation(String, TraversalPolicy, int, BiFunction, BiFunction, BiFunction, Producer[])
	 */
	@SafeVarargs
	public RepeatedProducerComputation(String name, TraversalPolicy shape,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Producer<PackedCollection>... args) {
		this(name, shape, 1, initial, condition, expression, args);
	}

	/**
	 * Creates a repeated computation with specified memory length for optimization.
	 * 
	 * <p>This constructor allows control over the memory length, which determines
	 * how many elements are processed by each kernel thread. Higher values can
	 * improve performance by reducing kernel launch overhead but increase memory
	 * usage per thread.
	 * 
	 * @param name The name identifier for this computation, used in generated code
	 * @param shape The {@link TraversalPolicy} defining the multi-dimensional shape
	 *              and access pattern of the output data  
	 * @param size The number of elements processed by each kernel thread (memory length)
	 * @param initial Function to initialize values at computation start. Receives
	 *                traversable arguments and current index, returns initialization expression
	 * @param condition Function to check continuation. Receives traversable arguments
	 *                  and current index, returns boolean expression for continuation
	 * @param expression Function defining the main computation. Receives traversable
	 *                   arguments and current index, returns computed expression
	 * @param args Variable number of input {@link Supplier}s providing evaluable collections
	 */
	@SafeVarargs
	public RepeatedProducerComputation(String name, TraversalPolicy shape, int size,
									   BiFunction<TraversableExpression[], Expression, Expression> initial,
									   BiFunction<TraversableExpression[], Expression, Expression> condition,
									   BiFunction<TraversableExpression[], Expression, Expression> expression,
									   Producer<PackedCollection>... args) {
		super(name, shape, (Producer[]) args);
		this.initial = initial;
		this.condition = condition;
		this.expression = expression;
		this.memLength = size;
	}

	/**
	 * Sets the initialization function for this computation.
	 * 
	 * <p>The initial function is called once at the beginning of the computation
	 * to set up starting values before the main iteration loop begins.
	 * 
	 * @param initial Function that receives traversable arguments and index,
	 *                returns expression for initial value setup
	 */
	protected void setInitial(BiFunction<TraversableExpression[], Expression, Expression> initial) {
		this.initial = initial;
	}

	/**
	 * Sets the condition function that controls iteration continuation.
	 * 
	 * <p>The condition function is evaluated before each iteration to determine
	 * whether the computation should continue. When it returns false, the
	 * iteration stops.
	 * 
	 * @param condition Function that receives traversable arguments and index,
	 *                  returns boolean expression indicating whether to continue
	 */
	protected void setCondition(BiFunction<TraversableExpression[], Expression, Expression> condition) {
		this.condition = condition;
	}

	/**
	 * Returns the memory length for this computation.
	 * 
	 * <p>The memory length represents the number of elements that are processed
	 * by a single kernel thread. This value affects both memory usage and 
	 * execution performance.
	 * 
	 * @return The number of elements processed per kernel thread
	 */
	@Override
	public int getMemLength() { return memLength; }

	/**
	 * Returns the optional index limit for iteration bounds.
	 * 
	 * <p>Subclasses can override this method to provide specific limits on
	 * iteration indices, which can be used for optimization or to enforce
	 * bounds checking.
	 * 
	 * @return {@link OptionalInt} containing the index limit if present,
	 *         or empty if no limit is set
	 */
	protected OptionalInt getIndexLimit() {
		return OptionalInt.empty();
	}

	/**
	 * Returns metadata describing this computation for profiling and debugging.
	 * 
	 * <p>The metadata includes the function name and type information that
	 * can be used by profiling tools and debugging systems to identify
	 * and categorize this computation.
	 * 
	 * @return {@link OperationMetadata} containing function name and type "Repeated"
	 */
	@Override
	public OperationMetadata getMetadata() {
		OperationMetadata metadata = super.getMetadata();
		if (metadata == null)
			metadata = new OperationMetadata(getFunctionName(), "Repeated");
		return metadata;
	}

	/**
	 * Generates the execution scope for this repeated computation.
	 * 
	 * <p>This method creates a {@link Repeated} scope that contains the initialization
	 * and iteration logic. The scope includes:
	 * <ul>
	 *   <li>Initialization statements using the initial function</li>
	 *   <li>A condition check for continuation</li>
	 *   <li>The main computation body with the expression function</li>
	 * </ul>
	 * 
	 * <p>The generated scope will be compiled into kernel code for execution
	 * on the target hardware platform.
	 * 
	 * @param context The {@link KernelStructureContext} providing information about
	 *                the kernel structure and execution environment
	 * @return A {@link Scope} containing the complete repeated computation logic
	 */
	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Repeated<T> scope = new Repeated<>(getFunctionName(), getMetadata());
		scope.setInterval(e(getMemLength()));

		String i = getNameProvider().getVariablePrefix() + "_i";
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
	 * Gets the expression for the computation at specified indices.
	 * 
	 * <p>This method provides a convenient overload that automatically obtains
	 * the traversable arguments for the given global index before calling the
	 * main expression function.
	 * 
	 * @param globalIndex The global index in the overall computation space
	 * @param localIndex The local index within the current iteration
	 * @return The computed {@link Expression} result
	 * 
	 * @see #getExpression(TraversableExpression[], Expression, Expression)
	 */
	protected Expression<?> getExpression(Expression globalIndex, Expression localIndex) {
		return getExpression(getTraversableArguments(globalIndex), globalIndex, localIndex);
	}

	/**
	 * Gets the expression for the computation with explicit traversable arguments.
	 * 
	 * <p>This method applies the computation expression function with the provided
	 * arguments and local index. This is the core method where the actual
	 * computation logic is evaluated.
	 * 
	 * @param args Array of {@link TraversableExpression}s representing input data
	 * @param globalIndex The global index in the overall computation space (unused in base implementation)  
	 * @param localIndex The local index within the current iteration
	 * @return The computed {@link Expression} result from applying the expression function
	 */
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		return expression.apply(args, localIndex);
	}

	/**
	 * Gets the destination expression for writing computation results.
	 * 
	 * <p>This method determines where to write the computation result based on
	 * the global index, local index, and offset. It handles both kernel-indexed
	 * and regular array variable references.
	 * 
	 * @param globalIndex The global index, potentially a {@link KernelIndex}
	 * @param localIndex The local index within the iteration  
	 * @param offset The offset within the memory block
	 * @return An {@link Expression} representing the destination memory location
	 */
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset)	{
		Expression k = globalIndex instanceof KernelIndex ? globalIndex : new KernelIndex();
		Expression len = ((ArrayVariable<?>) getOutputVariable()).length();
		return ((ArrayVariable) getOutputVariable()).reference(k.multiply(len).add(offset));
	}

	/**
	 * Generates a new instance of this computation with updated child processes.
	 * 
	 * <p>This method is used by the computation framework to create new instances
	 * when the computation graph is modified or optimized. The new instance
	 * maintains the same configuration (initial, condition, expression functions)
	 * but uses the provided child processes as inputs.
	 * 
	 * @param children List of {@link Process} instances to use as inputs for the new computation
	 * @return A new {@link RepeatedProducerComputation} instance with the same configuration
	 *         but updated inputs
	 */
	@Override
	public RepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new RepeatedProducerComputation<>(
				null, getShape(), getMemLength(),
				initial, condition, expression,
				children.stream().skip(1).toArray(Producer[]::new));
	}
}
