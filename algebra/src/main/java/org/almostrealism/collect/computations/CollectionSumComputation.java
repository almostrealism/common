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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A specialized computation that performs element-wise addition of multiple {@link PackedCollection} instances.
 * This class extends {@link TransitiveDeltaExpressionComputation} to provide efficient parallel computation
 * of collection sums with automatic differentiation support.
 * 
 * <p>The {@code CollectionSumComputation} is the underlying implementation for the {@code add()} operations
 * in the collection framework. It handles the addition of any number of collections with compatible shapes,
 * performing element-wise addition across all corresponding elements.</p>
 * 
 * <h3>Key Features</h3>
 * <ul>
 *   <li><strong>Element-wise Addition:</strong> Adds corresponding elements from multiple collections</li>
 *   <li><strong>Shape Broadcasting:</strong> Supports addition of collections with compatible shapes</li>
 *   <li><strong>Automatic Differentiation:</strong> Inherits delta computation capabilities for gradient calculation</li>
 *   <li><strong>Parallel Execution:</strong> Generates optimized parallel processes for hardware acceleration</li>
 *   <li><strong>Variable Arguments:</strong> Accepts any number of input collections</li>
 * </ul>
 * 
 * <h3>Usage Examples</h3>
 * 
 * <p><strong>Basic Addition:</strong></p>
 * <pre>{@code
 * // Adding two collections element-wise
 * TraversalPolicy shape = shape(3, 4); // 3x4 matrix
 * PackedCollection<?> a = new PackedCollection<>(shape).fill(pos -> 1.0);
 * PackedCollection<?> b = new PackedCollection<>(shape).fill(pos -> 2.0);
 * 
 * CollectionProducer<PackedCollection<?>> result = 
 *     new CollectionSumComputation<>(shape, cp(a), cp(b));
 * PackedCollection<?> sum = result.get().evaluate(); // Each element = 3.0
 * }</pre>
 * 
 * <p><strong>Multiple Collection Addition:</strong></p>
 * <pre>{@code
 * // Adding three or more collections
 * TraversalPolicy shape = shape(2, 3);
 * CollectionProducer<PackedCollection<?>> multiSum = 
 *     new CollectionSumComputation<>(shape, 
 *         cp(collection1), cp(collection2), cp(collection3));
 * }</pre>
 * 
 * <p><strong>Broadcasting Addition:</strong></p>
 * <pre>{@code
 * // Adding collections with compatible shapes
 * TraversalPolicy matrixShape = shape(3, 4);
 * TraversalPolicy vectorShape = shape(3);
 * 
 * CollectionProducer<PackedCollection<?>> broadcast = 
 *     new CollectionSumComputation<>(matrixShape, 
 *         matrixProducer, 
 *         vectorProducer.repeat(4)); // Vector repeated to match matrix columns
 * }</pre>
 * 
 * <p><strong>Usage in Collection Framework:</strong></p>
 * <pre>{@code
 * // This class is typically used through the CollectionFeatures.add() method:
 * CollectionProducer<PackedCollection<?>> sum = add(producer1, producer2, producer3);
 * // Which internally creates: new CollectionSumComputation<>(shape, producer1, producer2, producer3)
 * }</pre>
 * 
 * <h3>Implementation Details</h3>
 * 
 * <p>The computation works by:</p>
 * <ol>
 *   <li>Accepting multiple input collections through {@link Producer} or {@link Supplier} arguments</li>
 *   <li>Using the {@link #getExpression(TraversableExpression...)} method to create a sum expression</li>
 *   <li>Generating optimized parallel processes via {@link #generate(List)} for execution</li>
 *   <li>Supporting automatic differentiation through the transitive delta strategy</li>
 * </ol>
 * 
 * <p>The class leverages the {@link ExpressionFeatures#sum(io.almostrealism.collect.TraversalPolicy, TraversableExpression[])}
 * method to create the underlying mathematical expression that represents the element-wise addition operation.</p>
 * 
 * <h3>Performance Considerations</h3>
 * 
 * <p>This computation is optimized for:</p>
 * <ul>
 *   <li>Parallel execution on multi-core processors and GPUs</li>
 *   <li>Memory-efficient operations through producer pattern</li>
 *   <li>Lazy evaluation with compilation to native code when possible</li>
 *   <li>Automatic vectorization of operations for SIMD instructions</li>
 * </ul>
 * 
 * @param <T> The type of {@link PackedCollection} being computed, must extend {@code PackedCollection<?>}
 * 
 * @author Michael Murray
 * @see TransitiveDeltaExpressionComputation
 * @see CollectionFeatures#add(Producer...)
 * @see PackedCollection
 * @see TraversalPolicy
 */
public class CollectionSumComputation<T extends PackedCollection<?>> extends TransitiveDeltaExpressionComputation<T> {

	/**
	 * Creates a new CollectionSumComputation with the specified shape and producer arguments.
	 * This is the primary constructor for creating sum computations from {@link Producer} instances.
	 * 
	 * <p>This constructor is typically used when you have {@link CollectionProducer} instances
	 * that you want to add together. The computation will perform element-wise addition of all
	 * the provided producers when evaluated.</p>
	 * 
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * TraversalPolicy shape = shape(3, 3);
	 * PackedCollection<?> a = new PackedCollection<>(shape).randFill();
	 * PackedCollection<?> b = new PackedCollection<>(shape).randFill();
	 * 
	 * CollectionSumComputation<?> sum = new CollectionSumComputation<>(shape, cp(a), cp(b));
	 * PackedCollection<?> result = sum.get().evaluate();
	 * }</pre>
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern.
	 *              This should be compatible with all input producer shapes.
	 * @param arguments Variable number of {@link Producer} instances to be added together.
	 *                  Each producer should generate collections compatible with the specified shape.
	 * 
	 * @throws IllegalArgumentException if the shape is null or if any producer is incompatible
	 * @see CollectionFeatures#add(Producer...)
	 */
	public CollectionSumComputation(TraversalPolicy shape, Producer<? extends PackedCollection<?>>... arguments) {
		this("add", shape, arguments);
	}

	/**
	 * Creates a new CollectionSumComputation with the specified shape and supplier arguments.
	 * This constructor accepts {@link Supplier} instances that provide {@link Evaluable} objects,
	 * offering more flexibility in lazy evaluation scenarios.
	 * 
	 * <p>This constructor is useful when you need to defer the creation of the actual
	 * computation components until evaluation time, or when working with complex computation
	 * graphs that require delayed binding.</p>
	 * 
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * TraversalPolicy shape = shape(2, 4);
	 * CollectionProducer<PackedCollection<?>> producer1 = cp(new PackedCollection<>(shape).randFill());
	 * CollectionProducer<PackedCollection<?>> producer2 = cp(new PackedCollection<>(shape).randFill());
	 * 
	 * Supplier<Evaluable<PackedCollection<?>>> supplier1 = producer1::get;
	 * Supplier<Evaluable<PackedCollection<?>>> supplier2 = producer2::get;
	 * 
	 * CollectionSumComputation<?> sum = new CollectionSumComputation<>(shape, supplier1, supplier2);
	 * }</pre>
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of {@link Supplier} instances that provide {@link Evaluable}
	 *                  collections to be added together
	 * 
	 * @see Supplier
	 * @see Evaluable
	 */
	public CollectionSumComputation(TraversalPolicy shape,
										Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		this("add", shape, arguments);
	}

	/**
	 * Protected constructor that allows customization of the computation name.
	 * This constructor is used internally and by subclasses that need to specify
	 * a different name for the computation operation.
	 * 
	 * <p>The name parameter is used for debugging, profiling, and code generation purposes.
	 * It appears in generated code and performance reports. The default implementations
	 * use "add" as the operation name.</p>
	 * 
	 * @param name The string identifier for this computation operation, used in generated code
	 *             and debugging output
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of {@link Supplier} instances providing the collections
	 *                  to be added together
	 * 
	 * @see TransitiveDeltaExpressionComputation#TransitiveDeltaExpressionComputation(String, TraversalPolicy, Supplier[])
	 */
	protected CollectionSumComputation(String name, TraversalPolicy shape,
										   Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(name, shape, arguments);
	}

	/**
	 * Creates the mathematical expression that represents the element-wise addition operation.
	 * This method is called during the expression building phase to construct the underlying
	 * computation graph that will be compiled and executed.
	 * 
	 * <p>The implementation uses the {@link ExpressionFeatures#sum(io.almostrealism.collect.TraversalPolicy, TraversableExpression[])}
	 * method to create a sum expression from all the input arguments (excluding the first argument
	 * which is typically the destination). The resulting expression performs element-wise addition
	 * across all corresponding positions in the input collections.</p>
	 * 
	 * <p><strong>Expression Generation Process:</strong></p>
	 * <ol>
	 *   <li>Skip the first argument (destination/output collection)</li>
	 *   <li>Convert remaining arguments to {@link TraversableExpression} array</li>
	 *   <li>Create a sum expression using the computation's shape policy</li>
	 *   <li>Return the expression for compilation and execution</li>
	 * </ol>
	 * 
	 * @param args Array of {@link TraversableExpression} representing the input collections.
	 *             The first element is typically the destination, remaining elements are the
	 *             collections to be summed together.
	 * 
	 * @return A {@link CollectionExpression} representing the element-wise sum of the input collections
	 * 
	 * @see ExpressionFeatures#sum(io.almostrealism.collect.TraversalPolicy, TraversableExpression[])
	 * @see TraversableExpression
	 * @see CollectionExpression
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return sum(getShape(), Stream.of(args).skip(1).toArray(TraversableExpression[]::new));
	}

	/**
	 * Generates a parallel process for executing the collection sum computation.
	 * This method is responsible for creating the actual executable process that will
	 * perform the element-wise addition operation on the target hardware.
	 * 
	 * <p>The implementation converts the child processes (excluding the destination) into
	 * {@link Producer} instances and uses the {@link #add(List)} method to create an
	 * optimized parallel process. This process can be executed on various hardware
	 * backends including CPU, GPU, and other accelerators.</p>
	 * 
	 * <p><strong>Process Generation Steps:</strong></p>
	 * <ol>
	 *   <li>Extract child processes from the computation graph</li>
	 *   <li>Skip the first child (destination) and convert others to producers</li>
	 *   <li>Create a {@link CollectionProducerParallelProcess} using the add operation</li>
	 *   <li>Return the process ready for hardware execution</li>
	 * </ol>
	 * 
	 * <p><strong>Performance Note:</strong> The returned parallel process is optimized for
	 * the target hardware and may include vectorization, memory coalescing, and other
	 * hardware-specific optimizations.</p>
	 * 
	 * @param children List of child {@link Process} instances representing the input computations.
	 *                 The first process is typically the destination, remaining processes
	 *                 provide the collections to be summed.
	 * 
	 * @return A {@link CollectionProducerParallelProcess} that can execute the sum operation
	 *         on the target hardware platform
	 * 
	 * @see CollectionProducerParallelProcess
	 * @see Process
	 * @see Producer
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		List<Producer<?>> args = children.stream().skip(1)
				.map(p -> (Producer<?>) p).collect(Collectors.toList());
		return (CollectionProducerParallelProcess) add(args);
	}
}
