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

import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A {@link RepeatedProducerComputation} with a fixed, constant number of iterations.
 * 
 * <p>This class extends the base {@link RepeatedProducerComputation} to provide a
 * concrete implementation where the number of iterations is known at construction time.
 * This allows for optimizations and simplifications in the iteration logic since
 * the loop bounds are constant rather than dynamically computed, enabling better
 * performance through loop unrolling and compile-time optimization strategies.</p>
 * 
 * <p>The constant iteration count is particularly useful for:</p>
 * <ul>
 *   <li>Mathematical algorithms with predetermined iteration requirements</li>
 *   <li>Fixed-step refinement processes and numerical methods</li>  
 *   <li>Accumulation operations with known bounds</li>
 *   <li>Reduction operations like finding maximum/minimum indices</li>
 *   <li>Performance optimization where loop unrolling is beneficial</li>
 * </ul>
 * 
 * <h3>Usage Examples</h3>
 * 
 * <p><strong>Finding the index of maximum value:</strong></p>
 * <pre>{@code
 * // Create a computation to find the index of the maximum element
 * TraversalPolicy inputShape = new TraversalPolicy(10); // 10 elements
 * TraversalPolicy outputShape = new TraversalPolicy(1); // Single index result
 * 
 * ConstantRepeatedProducerComputation<PackedCollection> indexOfMax = 
 *     new ConstantRepeatedProducerComputation<>(
 *         "indexOfMax", 
 *         outputShape, 
 *         inputShape.getSize(), // Iterate over all elements
 *         (args, index) -> e(0), // Initialize with index 0
 *         (args, index) -> {
 *             Expression<?> currentIndex = args[0].getValueRelative(e(0));
 *             return conditional(
 *                 args[1].getValueRelative(index)
 *                     .greaterThan(args[1].getValueRelative(currentIndex)),
 *                 index, 
 *                 currentIndex
 *             );
 *         },
 *         inputCollection
 *     );
 * }</pre>
 * 
 * <p><strong>Accumulation with fixed iterations:</strong></p>
 * <pre>{@code
 * // Create a computation that accumulates values over 5 iterations
 * TraversalPolicy shape = new TraversalPolicy(100);
 * 
 * ConstantRepeatedProducerComputation<PackedCollection> accumulator = 
 *     new ConstantRepeatedProducerComputation<>(
 *         "accumulate", 
 *         shape, 
 *         5, // Fixed number of iterations
 *         (args, index) -> args[0].getValueAt(index), // Initialize with input
 *         (args, index) -> args[0].getValueAt(index).add(e(1.0)), // Add 1 each iteration
 *         inputProducer
 *     );
 * }</pre>
 * 
 * <p><strong>Memory-optimized processing:</strong></p>
 * <pre>{@code
 * // Process multiple elements per kernel thread for better memory utilization
 * ConstantRepeatedProducerComputation<PackedCollection> optimized = 
 *     new ConstantRepeatedProducerComputation<>(
 *         "optimizedProcess", 
 *         new TraversalPolicy(1000), 
 *         4, // Memory length - process 4 elements per thread
 *         25, // 25 iterations
 *         (args, index) -> args[0].getValueAt(index), // Initialize with input
 *         (args, index) -> args[0].getValueAt(index).multiply(e(2.0)), // Double each iteration
 *         dataProducer
 *     );
 * }</pre>
 * 
 * <h3>Relationship to Parent Class</h3>
 * <p>This class simplifies the parent {@link RepeatedProducerComputation} by:</p>
 * <ul>
 *   <li>Eliminating the need for a condition function - the iteration count is fixed</li>
 *   <li>Providing a concrete {@link #getIndexLimit()} that returns the constant count</li>
 *   <li>Enabling more efficient differentiation through {@link ConstantRepeatedDeltaComputation}</li>
 *   <li>Allowing for compile-time optimizations due to known iteration bounds</li>
 * </ul>
 * 
 * <h3>Performance Considerations</h3>
 * <p>The constant iteration count enables several performance optimizations:</p>
 * <ul>
 *   <li><strong>Loop unrolling:</strong> Compilers can unroll loops with known bounds</li>
 *   <li><strong>Memory access patterns:</strong> Predictable access patterns improve cache performance</li>
 *   <li><strong>Kernel optimization:</strong> GPU kernels can be optimized for fixed iteration counts</li>
 *   <li><strong>Delta computation:</strong> Specialized delta computation for automatic differentiation</li>
 * </ul>
 * 
 * @see RepeatedProducerComputation
 * @see TraversableRepeatedProducerComputation
 * @see ConstantRepeatedDeltaComputation
 */
public class ConstantRepeatedProducerComputation
		extends RepeatedProducerComputation {
	
	/** 
	 * The fixed number of iterations this computation will perform.
	 * This value is set at construction time and remains constant throughout
	 * the computation's lifecycle, enabling compile-time optimizations.
	 */
	protected int count;

	/**
	 * Creates a {@link ConstantRepeatedProducerComputation} with default memory length of 1.
	 * 
	 * <p>This constructor creates a computation that will perform a fixed number of iterations
	 * on the provided input collections. The memory length defaults to 1, meaning each kernel
	 * thread processes one element at a time. This is suitable for most general-purpose
	 * repeated computations where memory optimization is not critical.</p>
	 * 
	 * <p>The computation operates by:</p>
	 * <ol>
	 *   <li>Initializing values using the {@code initial} function</li>
	 *   <li>Performing exactly {@code count} iterations</li>
	 *   <li>Applying the {@code expression} function in each iteration</li>
	 * </ol>
	 * 
	 * <p><strong>Example usage:</strong></p>
	 * <pre>{@code
	 * // Create a computation that doubles a value 3 times
	 * ConstantRepeatedProducerComputation<PackedCollection> doubler = 
	 *     new ConstantRepeatedProducerComputation<>(
	 *         "tripleDouble",
	 *         new TraversalPolicy(10), // Process 10 elements
	 *         3, // Perform 3 iterations
	 *         (args, index) -> args[0].getValueAt(index), // Initialize with input
	 *         (args, index) -> args[0].getValueAt(index).multiply(e(2.0)), // Double each time
	 *         inputProducer
	 *     );
	 * }</pre>
	 * 
	 * @param name The name identifier for this computation, used in generated code and debugging
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param count The fixed number of iterations to perform (must be positive)
	 * @param initial A function that defines how to initialize values at the start of computation.
	 *                Takes (TraversableExpression[], Expression) and returns the initial Expression
	 * @param expression A function that defines the computation to perform in each iteration step.
	 *                   Takes (TraversableExpression[], Expression) and returns the updated Expression
	 * @param args Variable number of input {@link Supplier} arguments providing the data collections
	 * 
	 * @throws IllegalArgumentException if count is not positive
	 */
	@SafeVarargs
	public ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
											   BiFunction<TraversableExpression[], Expression, Expression> initial,
											   BiFunction<TraversableExpression[], Expression, Expression> expression,
											   Producer<PackedCollection>... args) {
		this(name, shape, 1, count, initial, expression, args);
	}

	/**
	 * Creates a {@link ConstantRepeatedProducerComputation} with explicit memory length control.
	 * 
	 * <p>This constructor provides fine-grained control over memory usage by allowing specification
	 * of how many elements each kernel thread should process. A higher {@code size} value can
	 * improve performance by reducing the number of kernel launches, but increases memory usage
	 * per thread. This is the primary constructor that other constructors delegate to.</p>
	 * 
	 * <p>The {@code size} parameter (memory length) affects:</p>
	 * <ul>
	 *   <li><strong>Memory usage:</strong> Higher values increase memory per kernel thread</li>
	 *   <li><strong>Kernel launches:</strong> Fewer launches needed with higher values</li>
	 *   <li><strong>Cache efficiency:</strong> Better cache locality with appropriate sizing</li>
	 *   <li><strong>Parallelism:</strong> Balance between thread count and work per thread</li>
	 * </ul>
	 * 
	 * <p><strong>Memory optimization example:</strong></p>
	 * <pre>{@code
	 * // Process a 2D array (100x10) where each row has 10 elements
	 * TraversalPolicy inputShape = new TraversalPolicy(100, 10); // 100 rows, 10 elements each
	 * int finalDimSize = inputShape.getSize(); // 10 (size of final dimension)
	 * 
	 * ConstantRepeatedProducerComputation<PackedCollection> optimized = 
	 *     new ConstantRepeatedProducerComputation<>(
	 *         "repeatedProduct",
	 *         new TraversalPolicy(100), // Output: 100 sums (one per row)
	 *         finalDimSize, // Process 10 elements per kernel thread
	 *         5, // 5 iterations
	 *         (args, index) -> args[0].getValueAt(index), // Initialize with input
	 *         (args, index) -> args[0].getValueAt(index).multiply(e(2.0))), // Multiply each element by 2
	 *         dataProducer
	 *     );
	 * }</pre>
	 * 
	 * @param name The name identifier for this computation, used in generated code and debugging
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param size The memory length - number of elements processed by each kernel thread
	 * @param count The fixed number of iterations to perform (must be positive)
	 * @param initial A function that defines how to initialize values at the start of computation.
	 *                Takes (TraversableExpression[], Expression) and returns the initial Expression
	 * @param expression A function that defines the computation to perform in each iteration step.
	 *                   Takes (TraversableExpression[], Expression) and returns the updated Expression
	 * @param inputs Variable number of input {@link Supplier} arguments providing the data collections
	 * 
	 * @throws IllegalArgumentException if count is not positive or size is not positive
	 */
	@SafeVarargs
	public ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int size, int count,
											   BiFunction<TraversableExpression[], Expression, Expression> initial,
											   BiFunction<TraversableExpression[], Expression, Expression> expression,
											   Producer<PackedCollection>... inputs) {
		super(name, shape, size, initial,
				(args, index) ->
						index.lessThan(new IntegerConstant(count)), expression, inputs);
		this.count = count;
	}

	/**
	 * Returns the iteration limit for this computation.
	 * 
	 * <p>This method provides the concrete implementation of the abstract method from
	 * {@link RepeatedProducerComputation}. Since this class has a constant iteration
	 * count, it always returns an {@link OptionalInt} containing the fixed {@code count}
	 * value specified at construction time.</p>
	 * 
	 * <p>The index limit is used by the parent class to:</p>
	 * <ul>
	 *   <li>Generate loop bounds in the compiled kernel code</li>
	 *   <li>Enable compiler optimizations for fixed-bound loops</li>
	 *   <li>Validate iteration constraints during computation setup</li>
	 *   <li>Support automatic differentiation with known iteration counts</li>
	 * </ul>
	 * 
	 * @return An {@link OptionalInt} containing the fixed iteration count, never empty
	 */
	@Override
	protected OptionalInt getIndexLimit() { return OptionalInt.of(count); }

	/**
	 * Computes the derivative of this computation with respect to the specified target.
	 * 
	 * <p>This method implements automatic differentiation for repeated computations with
	 * constant iteration counts. It first attempts to use the base class's delta computation
	 * strategy, and if that fails, creates a specialized {@link ConstantRepeatedDeltaComputation}
	 * optimized for constant iteration scenarios.</p>
	 * 
	 * <p>The differentiation process:</p>
	 * <ol>
	 *   <li>Attempts standard delta computation via {@link #attemptDelta(Producer)}</li>
	 *   <li>If unsuccessful, creates a {@link ConstantRepeatedDeltaComputation} with:
	 *     <ul>
	 *       <li>The same iteration count as this computation</li>
	 *       <li>Specialized expression that computes derivatives at each iteration</li>
	 *       <li>Optimized handling of constant iteration bounds</li>
	 *     </ul>
	 *   </li>
	 * </ol>
	 * 
	 * <p><strong>Usage in gradient computation:</strong></p>
	 * <pre>{@code
	 * // Create a repeated computation
	 * ConstantRepeatedProducerComputation<PackedCollection> computation = ...;
	 * 
	 * // Compute derivative with respect to input
	 * CollectionProducer<PackedCollection> derivative = computation.delta(inputProducer);
	 * 
	 * // Use derivative in gradient-based optimization
	 * PackedCollection grad = derivative.get().evaluate(inputData);
	 * }</pre>
	 * 
	 * @param target The {@link Producer} to compute the derivative with respect to
	 * @return A {@link CollectionProducer} that computes the derivative of this computation
	 * @throws IllegalArgumentException if the target is not compatible with this computation
	 * 
	 * @see ConstantRepeatedDeltaComputation
	 * @see RepeatedProducerComputation#attemptDelta(Producer)
	 */
	@Override
	public CollectionProducer<PackedCollection> delta(Producer<?> target) {
		CollectionProducer<PackedCollection> delta = attemptDelta(target);
		if (delta != null) return delta;

		return ConstantRepeatedDeltaComputation.create(
				getShape(), shape(target),
				count, (args, localIndex) -> getExpression(args, null, localIndex), target,
				getInputs().stream().skip(1).toArray(Producer[]::new));
	}

	/**
	 * Generates a new instance of this computation with updated child processes.
	 * 
	 * <p>This method creates a parallel process version of this computation by constructing
	 * a new {@link ConstantRepeatedProducerComputation} with the same configuration but
	 * different input sources. This is essential for parallel processing where the same
	 * computational logic needs to be applied to different data streams or computational
	 * contexts.</p>
	 * 
	 * <p>The generated computation preserves:</p>
	 * <ul>
	 *   <li>The original computation name and shape</li>
	 *   <li>The memory length (size) and iteration count</li>
	 *   <li>The initialization and expression functions</li>
	 *   <li>All configuration parameters from the original computation</li>
	 * </ul>
	 * 
	 * <p>The first child process is skipped as it typically represents the output destination,
	 * while subsequent children become the input sources for the new computation.</p>
	 * 
	 * <p><strong>Usage in parallel processing:</strong></p>
	 * <pre>{@code
	 * // Original computation
	 * ConstantRepeatedProducerComputation<PackedCollection> original = ...;
	 * 
	 * // Create child processes for parallel execution
	 * List<Process<?, ?>> childProcesses = Arrays.asList(
	 *     outputProcess,  // Skipped (index 0)
	 *     inputProcess1,  // Used as first input
	 *     inputProcess2   // Used as second input
	 * );
	 * 
	 * // Generate parallel version
	 * ConstantRepeatedProducerComputation<PackedCollection> parallel = 
	 *     original.generate(childProcesses);
	 * }</pre>
	 * 
	 * @param children The list of child {@link Process} instances to use as inputs.
	 *                 The first element is skipped, subsequent elements become input suppliers
	 * @return A new {@link ConstantRepeatedProducerComputation} instance with the same
	 *         configuration but updated inputs from the child processes
	 * 
	 * @see RepeatedProducerComputation#generate(List)
	 * @see Process
	 */
	@Override
	public ConstantRepeatedProducerComputation generate(List<Process<?, ?>> children) {
		return new ConstantRepeatedProducerComputation(
				getName(), getShape(), getMemLength(), count,
				initial, expression,
				children.stream().skip(1).toArray(Producer[]::new));
	}
}
