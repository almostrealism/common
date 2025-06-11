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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerForMemoryData;

import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A {@link DynamicCollectionProducer} provides a flexible way to create {@link CollectionProducer}s
 * that can dynamically generate {@link PackedCollection}s based on runtime functions and inputs.
 * It extends {@link DynamicProducerForMemoryData} and implements {@link CollectionProducer},
 * offering both kernel-based and function-based computation modes.
 * 
 * <p>This class is particularly useful for creating collections with complex computation logic
 * that needs to be determined at runtime, or when working with variable input collections
 * that influence the output generation.</p>
 * 
 * <h3>Usage Examples:</h3>
 * 
 * <h4>Simple Static Function:</h4>
 * <pre>{@code
 * // Create a producer that generates a constant collection
 * TraversalPolicy shape = new TraversalPolicy(3, 4);
 * DynamicCollectionProducer<PackedCollection<?>> producer = 
 *     new DynamicCollectionProducer<>(shape, args -> new PackedCollection<>(shape, 1.0, 2.0, 3.0));
 * PackedCollection<?> result = producer.get().evaluate();
 * }</pre>
 * 
 * <h4>Kernel-based Computation:</h4>
 * <pre>{@code
 * // Create a producer for GPU/parallel computation
 * DynamicCollectionProducer<PackedCollection<?>> kernelProducer = 
 *     new DynamicCollectionProducer<>(shape, computationFunction, true);
 * }</pre>
 * 
 * <h4>Dynamic Input-based Function:</h4>
 * <pre>{@code
 * // Create a producer that depends on input collections
 * Function<PackedCollection<?>[], Function<Object[], PackedCollection<?>>> inputFunction = 
 *     inputs -> args -> processInputs(inputs[0], inputs[1]);
 * Producer<?> input1 = someProducer();
 * Producer<?> input2 = anotherProducer();
 * DynamicCollectionProducer<PackedCollection<?>> dynamicProducer = 
 *     new DynamicCollectionProducer<>(shape, inputFunction, false, true, input1, input2);
 * }</pre>
 * 
 * @param <T> The type of {@link PackedCollection} this producer generates, must extend {@code PackedCollection<?>}
 * 
 * @see CollectionProducer
 * @see DynamicProducerForMemoryData  
 * @see PackedCollection
 * @see TraversalPolicy
 * 
 * @author Michael Murray
 */
public class DynamicCollectionProducer<T extends PackedCollection<?>> extends DynamicProducerForMemoryData<T> implements CollectionProducer<T> {
	/** The shape/traversal policy that defines the dimensions of the output collection */
	private TraversalPolicy shape;
	
	/** Whether this producer should use kernel-based (GPU/parallel) computation */
	private boolean kernel;
	
	/** Whether this producer has a fixed count (deterministic output size) */
	private boolean fixedCount;

	/** Function that takes input collections and returns a function for generating output */
	private Function<PackedCollection<?>[], Function<Object[], T>> inputFunction;
	
	/** Array of producer arguments that will be evaluated to provide inputs to the computation */
	private Producer<?> args[];

	/**
	 * Creates a DynamicCollectionProducer with the simplest configuration.
	 * Uses kernel-based computation by default.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 */
	public DynamicCollectionProducer(TraversalPolicy shape, Function<Object[], T> function) {
		this(shape, function, true);
	}

	/**
	 * Creates a DynamicCollectionProducer with specified kernel usage.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @param kernel Whether to use kernel-based (GPU/parallel) computation
	 */
	public DynamicCollectionProducer(TraversalPolicy shape, Function<Object[], T> function, boolean kernel) {
		this(shape, function, kernel, true);
	}

	/**
	 * Creates a DynamicCollectionProducer with specified kernel usage and count behavior.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function The function that generates the output collection from input arguments
	 * @param kernel Whether to use kernel-based (GPU/parallel) computation
	 * @param fixedCount Whether this producer has a deterministic output size
	 */
	public DynamicCollectionProducer(TraversalPolicy shape, Function<Object[], T> function,
									 boolean kernel, boolean fixedCount) {
		this(shape, inputs -> function, kernel, fixedCount, new Producer[0]);
	}

	/**
	 * Creates a DynamicCollectionProducer that depends on input collections from other producers.
	 * This constructor allows the output function to depend on the results of evaluating
	 * other producers, enabling complex chained computations.
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function A function that takes input collections and returns a function for output generation
	 * @param kernel Whether to use kernel-based (GPU/parallel) computation
	 * @param fixedCount Whether this producer has a deterministic output size
	 * @param argument The first producer argument to be evaluated as input
	 * @param args Additional producer arguments to be evaluated as inputs
	 */
	public DynamicCollectionProducer(TraversalPolicy shape, Function<PackedCollection<?>[], Function<Object[], T>> function,
										boolean kernel, boolean fixedCount, Producer argument, Producer<?>... args) {
		this(shape, function, kernel, fixedCount, Stream.concat(Stream.of(argument), Stream.of(args)).toArray(Producer[]::new));
	}

	/**
	 * Primary constructor that creates a DynamicCollectionProducer with full configuration control.
	 * This constructor handles the most complex case where the output depends on multiple input
	 * collections that are produced by evaluating the provided producer arguments.
	 * 
	 * <p>The workflow is:
	 * <ol>
	 * <li>The {@code args} producers are evaluated to get input collections</li>
	 * <li>These input collections are passed to the {@code function}</li>
	 * <li>The function returns another function that generates the final output</li>
	 * </ol></p>
	 * 
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param function A function that takes input collections and returns a function for output generation
	 * @param kernel Whether to use kernel-based (GPU/parallel) computation
	 * @param fixedCount Whether this producer has a deterministic output size
	 * @param args Array of producers whose outputs will be used as inputs to the function
	 */
	public DynamicCollectionProducer(TraversalPolicy shape, Function<PackedCollection<?>[], Function<Object[], T>> function,
									 boolean kernel, boolean fixedCount, Producer args[]) {
		super(args.length > 0 ? null : function.apply(null),
				len -> new PackedCollection(shape.prependDimension(len)));
		this.shape = shape;
		this.kernel = kernel;
		this.fixedCount = fixedCount;

		this.inputFunction = function;
		this.args = args;
	}

	/**
	 * Returns the {@link TraversalPolicy} that defines the shape and dimensions 
	 * of the collections produced by this producer.
	 * 
	 * @return The traversal policy defining the output collection structure
	 */
	@Override
	public TraversalPolicy getShape() { return shape; }

	/**
	 * Returns the total number of elements in the output collection.
	 * This is calculated from the shape's total size.
	 * 
	 * @return The total size of the output collection
	 */
	@Override
	public long getOutputSize() { return getShape().getTotalSize(); }

	/**
	 * Indicates whether this producer generates collections with a fixed, 
	 * deterministic size. When true, the output size can be determined 
	 * at compilation time without evaluating the function.
	 * 
	 * @return true if the output size is fixed and deterministic
	 */
	@Override
	public boolean isFixedCount() { return fixedCount; }

	/**
	 * Returns the function that will be used to generate output collections.
	 * If this producer has input arguments, this method creates a composite function
	 * that first evaluates the input producers and then applies the input function.
	 * 
	 * <p>The returned function workflow:
	 * <ol>
	 * <li>If args are present: evaluate all input producers to get PackedCollections</li>
	 * <li>Pass these input collections to the inputFunction to get the final generator function</li>
	 * <li>Apply the generator function to produce the output</li>
	 * </ol></p>
	 * 
	 * @return A function that takes Object[] arguments and returns the output collection
	 */
	@Override
	protected Function<Object[], T> getFunction() {
		if (args != null && args.length > 0) {
			Evaluable eval[] = Stream.of(args).map(Producer::get).toArray(Evaluable[]::new);
			return args -> {
				PackedCollection[] inputs = Stream.of(eval)
						.map(ev -> ev.evaluate(args))
						.toArray(PackedCollection[]::new);
				Function<Object[], T> func = inputFunction.apply(inputs);
				return func.apply(args);
			};
		}

		return super.getFunction();
	}

	/**
	 * Creates a new {@link CollectionProducer} that traverses along the specified axis.
	 * This operation changes how the collection is accessed and iterated, effectively
	 * creating a view that processes the collection differently along the given dimension.
	 * 
	 * @param axis The axis along which to traverse the collection
	 * @return A new ReshapeProducer configured for axis traversal
	 */
	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	/**
	 * Creates a new {@link CollectionProducer} with the specified shape.
	 * This operation reshapes the output collection to match the provided
	 * {@link TraversalPolicy} without changing the underlying data generation logic.
	 * 
	 * @param shape The new {@link TraversalPolicy} defining the desired collection shape
	 * @return A new ReshapeProducer configured with the specified shape
	 */
	@Override
	public CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}

	/**
	 * Returns an {@link Evaluable} that can be used to execute this producer and generate
	 * the output collection. The behavior depends on the kernel configuration:
	 * 
	 * <ul>
	 * <li><strong>Kernel mode (kernel=true):</strong> Uses the parent class implementation,
	 *     which typically provides GPU/parallel execution capabilities</li>
	 * <li><strong>Function mode (kernel=false):</strong> Returns a direct function reference
	 *     that executes on the current thread</li>
	 * </ul>
	 * 
	 * @return An evaluable that produces collections of type T when executed
	 */
	@Override
	public Evaluable<T> get() {
		if (kernel) {
			return super.get();
		} else {
			return getFunction()::apply;
		}
	}
}
