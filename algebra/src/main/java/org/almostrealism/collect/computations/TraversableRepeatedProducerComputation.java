/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.RelativeTraversableExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A specialized {@link RepeatedProducerComputation} that implements {@link TraversableExpression}
 * for efficient traversal operations on collections with fixed iteration counts.
 * 
 * <p>This class extends {@link ConstantRepeatedProducerComputation} to provide traversable
 * functionality, allowing the computation to be used in expression trees that require
 * efficient value access patterns. It is particularly useful for operations that need
 * to access values at computed indices during repeated iterations, such as reduction
 * operations, iterative refinement algorithms, and accumulation computations.</p>
 * 
 * <p>The key difference from the base {@link RepeatedProducerComputation} is that this
 * class returns {@link TraversableExpression} results, enabling more efficient composition
 * with other traversable operations in complex computation graphs. This allows for
 * optimized memory access patterns and better integration with parallel processing
 * strategies.</p>
 * 
 * <h3>Core Functionality</h3>
 * <p>The computation operates with a fixed number of iterations, applying an expression
 * function repeatedly while maintaining traversable access to intermediate and final
 * results. The computation workflow follows this pattern:</p>
 * <ol>
 *   <li>Initialize values using the provided initial function</li>
 *   <li>Apply the expression function for each iteration (count times)</li>
 *   <li>Generate flattened expressions for optimization</li>
 *   <li>Provide traversable access to results via index-based retrieval</li>
 * </ol>
 * 
 * <h3>Usage Example - Index of Maximum Value</h3>
 * <p>A typical usage pattern is finding the index of maximum values in collections,
 * as implemented in {@link org.almostrealism.collect.CollectionFeatures#indexOfMax(io.almostrealism.relation.Producer)}:</p>
 * <pre><code>
 * // Create computation to find index of maximum value
 * TraversableRepeatedProducerComputation&lt;?&gt; indexOfMax = 
 *     new TraversableRepeatedProducerComputation&lt;&gt;("indexOfMax", shape.replace(shape(1)), size,
 *         // Initial: start with index 0
 *         (args, index) -&gt; e(0),
 *         // Expression: compare values and update index if larger value found
 *         (args, currentIndex) -&gt; index -&gt;
 *             conditional(args[1].getValueRelative(index)
 *                         .greaterThan(args[1].getValueRelative(currentIndex)),
 *                         index, currentIndex),
 *         input);
 * </code></pre>
 * 
 * <h3>Performance Characteristics</h3>
 * <p>This computation includes several optimization features:</p>
 * <ul>
 *   <li><strong>Isolation Control:</strong> Automatically determines when to isolate
 *       computations based on iteration count ({@link #isolationCountThreshold}) and
 *       memory requirements ({@link io.almostrealism.code.MemoryProvider#MAX_RESERVATION})</li>
 *   <li><strong>Memory Efficiency:</strong> Leverages traversable expressions for
 *       optimized memory access patterns during repeated operations</li>
 *   <li><strong>Parallel Processing:</strong> Designed to work efficiently with
 *       parallel computation contexts and GPU acceleration</li>
 *   <li><strong>Expression Optimization:</strong> Uses expression flattening and
 *       generation for improved kernel compilation</li>
 * </ul>
 * 
 * <h3>Relationship to Other Classes</h3>
 * <p>This class fits into the computation hierarchy as follows:</p>
 * <ul>
 *   <li>Extends {@link ConstantRepeatedProducerComputation} for fixed iteration behavior</li>
 *   <li>Implements {@link TraversableExpression} for efficient index-based access</li>
 *   <li>Used as base class for {@link AggregatedProducerComputation} for aggregation operations</li>
 *   <li>Integrates with {@link org.almostrealism.collect.CollectionFeatures} for high-level operations</li>
 * </ul>
 * 
 * @param <T> The type of {@link PackedCollection} this computation operates on
 * 
 * @see RepeatedProducerComputation
 * @see ConstantRepeatedProducerComputation  
 * @see TraversableExpression
 * @see AggregatedProducerComputation
 * @see org.almostrealism.collect.CollectionFeatures#indexOfMax(io.almostrealism.relation.Producer)
 * 
 * @author Michael Murray
 */
public class TraversableRepeatedProducerComputation<T extends PackedCollection<?>>
		extends ConstantRepeatedProducerComputation<T> implements TraversableExpression<Double> {
	
	/**
	 * The threshold for the number of iterations above which this computation
	 * becomes a candidate for isolation during execution.
	 * 
	 * <p>When the iteration count exceeds this threshold, the computation may be
	 * isolated into a separate kernel or execution context for better performance
	 * and memory management. This helps prevent excessive resource consumption
	 * from computations with very high iteration counts.</p>
	 * 
	 * <p>The default value of 16 provides a balance between overhead of isolation
	 * and benefits of separate execution. Set to {@link Integer#MAX_VALUE} to
	 * effectively disable isolation based on iteration count.</p>
	 */
	public static int isolationCountThreshold = 16; // Integer.MAX_VALUE;

	private BiFunction<TraversableExpression[], Expression, TraversableExpression<Double>> expression;

	/**
	 * Constructs a new TraversableRepeatedProducerComputation with the specified
	 * parameters for repeated operations on collections.
	 * 
	 * <p>This constructor sets up a computation that will perform a fixed number of
	 * iterations, applying the provided expression function to transform data while
	 * maintaining traversable access to results. The computation follows a pattern
	 * of initialization followed by iterative application of the expression.</p>
	 * 
	 * <h4>Parameter Usage Example</h4>
	 * <p>For finding the index of maximum value in a collection:</p>
	 * <pre><code>
	 * new TraversableRepeatedProducerComputation&lt;&gt;(
	 *     "indexOfMax",                    // name: operation identifier
	 *     shape.replace(shape(1)),         // shape: output dimensions
	 *     collection.getSize(),            // count: iterate over all elements
	 *     (args, index) -&gt; e(0),          // initial: start with index 0
	 *     (args, currentIndex) -&gt; index -&gt; // expression: compare and update
	 *         conditional(args[1].getValueRelative(index)
	 *                     .greaterThan(args[1].getValueRelative(currentIndex)),
	 *                     index, currentIndex),
	 *     input                            // arguments: input collection
	 * );
	 * </code></pre>
	 * 
	 * @param name A descriptive name for this computation, used for debugging and
	 *             optimization. Should be camelCase (e.g., "indexOfMax", "sumReduce")
	 * @param shape The {@link TraversalPolicy} defining the output dimensions and
	 *              traversal pattern. This determines how results are accessed and stored
	 * @param count The fixed number of iterations to perform. Must be non-negative.
	 *              Higher values may trigger isolation based on {@link #isolationCountThreshold}
	 * @param initial A function that initializes the computation state. Takes the input
	 *                arguments and current index, returns the initial value expression.
	 *                Called once before the iteration loop begins
	 * @param expression The core computation function applied on each iteration. Takes
	 *                   input arguments and current accumulated value, returns a
	 *                   {@link TraversableExpression} for the next iteration state
	 * @param arguments Variable number of input suppliers, typically {@link io.almostrealism.relation.Producer}
	 *                  instances that provide the data to be processed
	 * 
	 * @throws IllegalArgumentException if count is negative or if any required parameters are null
	 * 
	 * @see io.almostrealism.relation.Producer
	 * @see org.almostrealism.collect.CollectionProducer
	 * @see TraversalPolicy
	 */
	@SafeVarargs
	public TraversableRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
												  BiFunction<TraversableExpression[], Expression, Expression> initial,
												  BiFunction<TraversableExpression[], Expression, TraversableExpression<Double>> expression,
												  Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(name, shape, count, initial, null, arguments);
		this.expression = expression;
		this.count = count;
	}

	/**
	 * Retrieves the computed value at the specified multi-dimensional position.
	 * 
	 * <p>This method converts the provided positional coordinates into a linear
	 * index using the computation's {@link TraversalPolicy} shape, then delegates
	 * to {@link #getValueAt(Expression)} for the actual value retrieval.</p>
	 * 
	 * <p>The position array should match the dimensionality of the computation's
	 * output shape. For example, for a 2D result with shape [4, 3], valid
	 * positions include [0, 0], [1, 2], [3, 1], etc.</p>
	 * 
	 * @param pos Variable number of position coordinates, where each coordinate
	 *            corresponds to a dimension in the output shape. The number of
	 *            coordinates must match the shape's dimension count
	 * @return An {@link Expression} representing the computed value at the specified position
	 * 
	 * @throws IllegalArgumentException if the number of position coordinates doesn't
	 *                                  match the output shape dimensions
	 * @throws IndexOutOfBoundsException if any coordinate exceeds the corresponding
	 *                                   dimension bounds
	 * 
	 * @see #getValueAt(Expression)
	 * @see TraversalPolicy#index(Expression[]) 
	 */
	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	/**
	 * Computes and returns the value at the specified linear index through
	 * repeated application of the computation expression.
	 * 
	 * <p>This method implements the core computation logic by:</p>
	 * <ol>
	 *   <li>Converting input arguments to traversable expressions at the given index</li>
	 *   <li>Initializing the computation state using the initial function</li>
	 *   <li>Iteratively applying the expression function for the specified count</li>
	 *   <li>Flattening and optimizing expressions at each iteration</li>
	 * </ol>
	 * 
	 * <p>The repeated application allows for complex accumulation patterns,
	 * reduction operations, and iterative refinement algorithms. Each iteration
	 * can access and modify the accumulated state while maintaining efficient
	 * expression representation through flattening.</p>
	 * 
	 * <h4>Computation Flow Example</h4>
	 * <p>For a sum reduction with count=3:</p>
	 * <pre><code>
	 * // Initial: value = 0
	 * // Iteration 1: value = expression.apply(args, 0).getValueAt(0) -&gt; flattened
	 * // Iteration 2: value = expression.apply(args, previous).getValueAt(1) -&gt; flattened  
	 * // Iteration 3: value = expression.apply(args, previous).getValueAt(2) -&gt; flattened
	 * // Return: final flattened value expression
	 * </code></pre>
	 * 
	 * @param index The linear index position for which to compute the value.
	 *              This index is used both for accessing input data and for
	 *              iteration control within the expression function
	 * @return An {@link Expression} representing the computed value after
	 *         all iterations are complete
	 * 
	 * @see #getTraversableArguments(Expression)
	 * @see Expression#generate(java.util.List)
	 * @see Expression#flatten()
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversableExpression args[] = getTraversableArguments(index);

		Expression value = initial.apply(args, e(0));

		for (int i = 0; i < count; i++) {
			value = expression.apply(args, value).getValueAt(e(i));
			value = value.generate(value.flatten());
		}

		return value;
	}

	/**
	 * Generates the expression used in the computation kernel for parallel execution.
	 * 
	 * <p>This method is called during kernel compilation to produce the actual
	 * computation expression that will be executed in parallel contexts. It handles
	 * the integration between the repeated computation logic and the underlying
	 * collection variable system.</p>
	 * 
	 * <p>The method extracts the current accumulated value from the first argument
	 * (which represents the output collection) and applies the computation expression
	 * to generate the result for the specified local index position.</p>
	 * 
	 * @param args The traversable expressions representing input arguments,
	 *             where args[0] typically represents the output collection
	 * @param globalIndex The global index in the overall computation space,
	 *                    used for coordination across parallel execution units
	 * @param localIndex The local index within the current execution context,
	 *                   used to determine which iteration result to compute
	 * @return An {@link Expression} representing the computation result for
	 *         the specified local index position
	 * 
	 * @see io.almostrealism.collect.CollectionVariable#reference(io.almostrealism.expression.Expression)
	 * @see io.almostrealism.collect.RelativeTraversableExpression
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		CollectionVariable variable = (CollectionVariable) RelativeTraversableExpression.getExpression(args[0]);
		Expression currentValue = variable.reference(
				new KernelIndex().multiply(variable.length())); // TODO Should this be globalIndex instead of KernelIndex?
		return expression.apply(args, currentValue).getValueAt(localIndex);
	}

	/**
	 * Determines whether this computation should be isolated during execution
	 * based on resource requirements and iteration complexity.
	 * 
	 * <p>Isolation decisions are made based on two primary factors:</p>
	 * <ul>
	 *   <li><strong>Memory Requirements:</strong> If the output size exceeds
	 *       {@link io.almostrealism.code.MemoryProvider#MAX_RESERVATION}, the computation
	 *       cannot be safely isolated due to memory constraints</li>
	 *   <li><strong>Iteration Complexity:</strong> If the iteration count exceeds
	 *       {@link #isolationCountThreshold}, isolation may provide performance benefits</li>
	 * </ul>
	 * 
	 * <p>Isolation can improve performance for complex computations by:</p>
	 * <ul>
	 *   <li>Reducing memory pressure on the main computation context</li>
	 *   <li>Enabling specialized optimization strategies</li>
	 *   <li>Preventing resource contention with other computations</li>
	 *   <li>Allowing for different execution strategies (CPU vs GPU)</li>
	 * </ul>
	 * 
	 * @param context The {@link ProcessContext} in which the computation will execute,
	 *                providing information about available resources and execution environment
	 * @return {@code true} if this computation should be isolated for execution,
	 *         {@code false} if it can be executed within the main computation context
	 * 
	 * @see io.almostrealism.code.MemoryProvider#MAX_RESERVATION
	 * @see #isolationCountThreshold
	 * @see ConstantRepeatedProducerComputation#isIsolationTarget(ProcessContext)
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		if (getOutputSize() > MemoryProvider.MAX_RESERVATION) return false;
		return super.isIsolationTarget(context) || count > isolationCountThreshold;
	}

	/**
	 * Creates a new instance of this computation with the specified child processes,
	 * preserving the original configuration while updating the argument suppliers.
	 * 
	 * <p>This method is used during computation graph optimization and compilation
	 * to create specialized versions of the computation with different input sources.
	 * The new instance maintains the same name, shape, iteration count, and expression
	 * functions while substituting the input arguments.</p>
	 * 
	 * <p>The first child process is typically skipped (using {@code skip(1)}) because
	 * it usually represents the output destination rather than an input source. The
	 * remaining processes are used as the new argument suppliers for the computation.</p>
	 * 
	 * <h4>Usage in Computation Graphs</h4>
	 * <p>This method enables dynamic reconfiguration of computation pipelines:</p>
	 * <pre><code>
	 * // Original computation with producer inputs
	 * TraversableRepeatedProducerComputation original = new TraversableRepeatedProducerComputation(...);
	 * 
	 * // Create new version with different input processes
	 * List&lt;Process&lt;?, ?&gt;&gt; newInputs = Arrays.asList(outputProcess, inputProcess1, inputProcess2);
	 * TraversableRepeatedProducerComputation optimized = original.generate(newInputs);
	 * </code></pre>
	 * 
	 * @param children The list of child processes to use as input sources for
	 *                 the new computation instance. The first element is typically
	 *                 the output process and is skipped when creating argument suppliers
	 * @return A new {@link TraversableRepeatedProducerComputation} instance with
	 *         the same configuration but different input sources
	 * 
	 * @see io.almostrealism.compute.Process
	 * @see java.util.stream.Stream#skip(long)
	 * @see java.util.function.Supplier
	 */
	@Override
	public TraversableRepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new TraversableRepeatedProducerComputation<>(getName(), getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
