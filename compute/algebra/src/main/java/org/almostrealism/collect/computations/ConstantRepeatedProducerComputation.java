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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;

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
 * <p>This class is {@code abstract}: the fixed iteration count is supplied through the
 * protected constructors, and a concrete subclass (such as {@link IndexOfMaxComputation} or
 * {@link CumulativeProductComputation}) implements the initial value and the iteration step,
 * so that the behaviour of every instance is fixed by its class. The condition is implemented
 * here: the loop runs while its index is below the count.</p>
 *
 * <h3>Subclass Implementation Pattern</h3>
 * <pre>{@code
 * // A concrete fixed-iteration accumulation over all elements of a group
 * public class AccumulateComputation extends ConstantRepeatedProducerComputation {
 *     public AccumulateComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
 *         super("accumulate", shape, shape.getSize(), input);
 *     }
 *
 *     @Override
 *     protected Expression<?> getInitialValue(TraversableExpression[] args, Expression index) {
 *         return args[1].getValueAt(index);                         // Initialize with input
 *     }
 *
 *     @Override
 *     protected Expression<?> getExpression(TraversableExpression[] args,
 *                                           Expression globalIndex, Expression localIndex) {
 *         return args[1].getValueAt(localIndex).add(e(1.0));        // Add 1 each iteration
 *     }
 *
 *     @Override
 *     public AccumulateComputation generate(List<Process<?, ?>> children) {
 *         return new AccumulateComputation(getShape(),
 *             (Producer<PackedCollection>) children.get(1));
 *     }
 * }
 * }</pre>
 *
 * <h3>Relationship to Parent Class</h3>
 * <p>This class simplifies the parent {@link RepeatedProducerComputation} by:</p>
 * <ul>
 *   <li>Implementing {@link #getCondition} - the iteration count is fixed</li>
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
public abstract class ConstantRepeatedProducerComputation
		extends RepeatedProducerComputation {
	
	/** 
	 * The fixed number of iterations this computation will perform.
	 * This value is set at construction time and remains constant throughout
	 * the computation's lifecycle, enabling compile-time optimizations.
	 */
	protected int count;

	/**
	 * Creates a {@link ConstantRepeatedProducerComputation} with a memory length of 1.
	 *
	 * @param name The name identifier for this computation, used in generated code and debugging
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param count The fixed number of iterations to perform (must be positive)
	 * @param args Variable number of input {@link Producer} arguments providing the data collections
	 */
	@SafeVarargs
	protected ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
											   Producer<PackedCollection>... args) {
		this(name, shape, 1, count, args);
	}

	/**
	 * Creates a {@link ConstantRepeatedProducerComputation} with explicit memory length control.
	 * 
	 * <p>This constructor provides fine-grained control over memory usage by allowing specification
	 * of how many elements each kernel thread should process. A higher {@code size} value can
	 * improve performance by reducing the number of kernel launches, but increases memory usage
	 * per thread. This is the primary constructor that other constructors delegate to.</p>
	 *
	 * @param name The name identifier for this computation, used in generated code and debugging
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param size The memory length - number of elements processed by each kernel thread
	 * @param count The fixed number of iterations to perform (must be positive)
	 * @param inputs Variable number of input {@link Producer} arguments providing the data collections
	 */
	@SafeVarargs
	protected ConstantRepeatedProducerComputation(String name, TraversalPolicy shape, int size, int count,
											   Producer<PackedCollection>... inputs) {
		super(name, shape, size, inputs);
		this.count = count;
	}

	/**
	 * Continues while the loop index is below the fixed iteration count.
	 *
	 * @param args  the traversable arguments
	 * @param index the loop index
	 * @return {@code index < count}
	 */
	@Override
	protected Expression<Boolean> getCondition(TraversableExpression[] args, Expression index) {
		return index.lessThan(new IntegerConstant(count));
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
	 * constant iteration counts. It is reached when the base class's
	 * {@link #deltaDependent} finds no identity, elementwise or chain rule case, and creates a
	 * specialized {@link ConstantRepeatedDeltaComputation} optimized for constant iteration
	 * scenarios.</p>
	 *
	 * <p>The differentiation process:</p>
	 * <ol>
	 *   <li>Creates a {@link ConstantRepeatedDeltaComputation} with:
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
	 * CollectionProducer derivative = computation.delta(inputProducer);
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
	 * @see #deltaDependent
	 */
	@Override
	protected CollectionProducer deltaMatrix(Producer<?> target) {
		return new ConstantRepeatedDeltaComputation(this, target);
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
	 *   <li>The concrete subclass, which fixes the initial value and iteration step</li>
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
	 * @return A new {@link ConstantRepeatedProducerComputation} instance of the same class with
	 *         the same configuration but updated inputs from the child processes
	 *
	 * @see RepeatedProducerComputation#generate(List)
	 * @see Process
	 */
	@Override
	public abstract ConstantRepeatedProducerComputation generate(List<Process<?, ?>> children);
}
