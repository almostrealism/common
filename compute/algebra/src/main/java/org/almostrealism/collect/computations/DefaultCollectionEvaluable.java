/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.MemoryData;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * DefaultCollectionEvaluable is a concrete implementation of {@link Evaluable} that provides
 * accelerated computation evaluation for {@link PackedCollection} objects. It serves as a bridge
 * between computation contexts and packed collections, handling memory management, shape processing,
 * and output post-processing for collection-based computations.
 * 
 * <p>This class extends {@link AcceleratedComputationEvaluable} to leverage hardware acceleration
 * capabilities while implementing collection-specific logic for destination creation and output
 * processing. It is designed to work seamlessly with the AlmostRealism computation framework's
 * collection processing pipeline.</p>
 * 
 * <h3>Key Features:</h3>
 * <ul>
 *   <li><strong>Shape Management:</strong> Handles {@link TraversalPolicy} for defining collection dimensions and traversal patterns</li>
 *   <li><strong>Destination Factory:</strong> Provides flexible destination object creation through factory functions</li>
 *   <li><strong>Post-processing:</strong> Supports custom output post-processing via {@link BiFunction}</li>
 *   <li><strong>Hardware Acceleration:</strong> Leverages accelerated computation capabilities for performance</li>
 * </ul>
 * 
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Create a compute context
 * ComputeContext<MemoryData> context = Hardware.getLocalHardware().getComputer().getContext(computation);
 * 
 * // Define shape for a 2D collection (10x20 elements)
 * TraversalPolicy shape = new TraversalPolicy(10, 20);
 * 
 * // Create destination factory
 * IntFunction<MyCollection> destinationFactory = len -> new MyCollection(shape);
 * 
 * // Create post-processor
 * BiFunction<MemoryData, Integer, MyCollection> postprocessor = 
 *     (data, offset) -> new MyCollection(shape, 0, data, offset);
 * 
 * // Create evaluable
 * DefaultCollectionEvaluable<MyCollection> evaluable = new DefaultCollectionEvaluable<>(
 *     context, shape, computation, destinationFactory, postprocessor);
 * 
 * // Compile and evaluate
 * evaluable.compile();
 * MyCollection result = evaluable.evaluate();
 * }</pre>
 * 
 * <h3>Integration with CollectionProducerComputation:</h3>
 * <p>This class is typically instantiated by {@link org.almostrealism.collect.CollectionProducerComputation}
 * in its {@code get()} method, providing a standardized way to create evaluable instances for
 * collection computations:</p>
 * <pre>{@code
 * @Override
 * default Evaluable<T> get() {
 *     ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(this);
 *     AcceleratedComputationEvaluable<T> ev = new DefaultCollectionEvaluable<>(
 *         ctx, getShape(), this, this::createDestination, this::postProcessOutput);
 *     ev.compile();
 *     return ev;
 * }
 * }</pre>
 * 
 * @param <T> the type of {@link PackedCollection} that this evaluable produces
 * 
 * @author Michael Murray
 * @see AcceleratedComputationEvaluable
 * @see PackedCollection
 * @see TraversalPolicy
 * @see org.almostrealism.collect.CollectionProducerComputation
 * @since 0.69
 */
public class DefaultCollectionEvaluable<T extends PackedCollection>
		extends AcceleratedComputationEvaluable<T> implements Evaluable<T> {
	/**
	 * Flag to enable or disable the destination factory functionality.
	 * When enabled, the {@link #destinationFactory} will be used for creating
	 * destination objects in {@link #createDestination(int)}. When disabled,
	 * the method falls back to manual shape calculation and PackedCollection creation.
	 * 
	 * @see #createDestination(int)
	 */
	public static boolean enableDestinationFactory = true;

	/**
	 * The traversal policy that defines the shape and dimensions of collections
	 * produced by this evaluable. This policy is used for both destination creation
	 * and output post-processing to ensure proper collection structure.
	 */
	private final TraversalPolicy shape;
	
	/**
	 * Factory function for creating destination objects of type T.
	 * This function takes an integer length parameter and returns a new
	 * instance of the target collection type. Used when {@link #enableDestinationFactory}
	 * is true.
	 */
	private final IntFunction<T> destinationFactory;
	
	/**
	 * Post-processor function for transforming raw memory data into the target
	 * collection type. Takes {@link MemoryData} and an offset, returning a
	 * properly constructed collection instance. If null, a default PackedCollection
	 * will be created.
	 */
	private final BiFunction<MemoryData, Integer, T> postprocessor;

	/**
	 * Constructs a new DefaultCollectionEvaluable with the specified parameters.
	 * This constructor initializes all the necessary components for accelerated
	 * collection computation evaluation.
	 * 
	 * @param context the compute context that provides hardware acceleration capabilities
	 *                and manages the execution environment for the computation
	 * @param shape the traversal policy defining the shape, dimensions, and traversal
	 *              pattern for collections produced by this evaluable
	 * @param c the computation to be evaluated, which defines the actual operations
	 *          to be performed on the data
	 * @param destinationFactory factory function for creating destination objects of type T.
	 *                          This function should take an integer length and return a new
	 *                          collection instance with appropriate capacity
	 * @param postprocessor function for post-processing computation output. Takes raw
	 *                     {@link MemoryData} and an offset, returning a properly constructed
	 *                     collection instance. May be null for default processing
	 * 
	 * @throws IllegalArgumentException if context, shape, or computation is null
	 * 
	 * @see AcceleratedComputationEvaluable#AcceleratedComputationEvaluable(ComputeContext, Computation)
	 */
	public DefaultCollectionEvaluable(ComputeContext<MemoryData> context,
									  TraversalPolicy shape,
									  Computation<T> c,
									  IntFunction<T> destinationFactory,
									  BiFunction<MemoryData, Integer, T> postprocessor) {
		super(context, c);
		this.shape = shape;
		this.destinationFactory = destinationFactory;
		this.postprocessor = postprocessor;
	}

	/**
	 * Creates a destination collection with the specified length.
	 * This method handles the creation of appropriately sized and shaped collections
	 * to serve as destinations for computation results.
	 * 
	 * <p>The behavior depends on the {@link #enableDestinationFactory} flag:</p>
	 * <ul>
	 *   <li>If enabled and {@link #destinationFactory} is available, uses the factory function</li>
	 *   <li>Otherwise, performs manual shape calculation based on the computation characteristics</li>
	 * </ul>
	 * 
	 * <p>For manual shape calculation, the method delegates to
	 * {@link CollectionProducerComputation#shapeForLength(TraversalPolicy, int, boolean, int)}
	 * 
	 * @param len the total length/size of the destination collection to create.
	 *            This represents the total number of elements the collection should accommodate
	 * 
	 * @return a new collection instance of type T with appropriate shape and capacity
	 *         for the specified length
	 * 
	 * @throws IllegalArgumentException if len is negative or if shape calculations result in invalid dimensions
	 */
	@Override
	public Multiple<T> createDestination(int len) {
		if (enableDestinationFactory) {
			return (Multiple<T>) destinationFactory.apply(len);
		}

		TraversalPolicy shape =
				CollectionProducerComputation.shapeForLength(this.shape,
						this.shape.getCount(),
						Countable.isFixedCount(getComputation()),
						len);
		return (Multiple<T>) new PackedCollection(shape);
	}

	/**
	 * Post-processes the raw computation output into a properly structured collection.
	 * This method transforms the raw {@link MemoryData} result from the computation
	 * into a typed collection instance, handling memory layout and shape information.
	 * 
	 * <p>The processing logic:</p>
	 * <ul>
	 *   <li>If a custom {@link #postprocessor} is available, delegates to that function</li>
	 *   <li>Otherwise, creates a new {@link PackedCollection} with the evaluable's shape,
	 *       wrapping the provided memory data at the specified offset</li>
	 * </ul>
	 * 
	 * <p>This method is typically called by the parent {@link AcceleratedComputationEvaluable}
	 * after computation execution to convert raw memory data into the expected collection type.</p>
	 * 
	 * @param output the raw memory data containing the computation results.
	 *               This data contains the actual computed values in memory
	 * @param offset the byte offset within the memory data where the relevant
	 *               data begins. This allows for sharing memory buffers between
	 *               multiple collections or accessing specific portions of larger buffers
	 * 
	 * @return a new collection instance of type T that properly wraps the memory data
	 *         with the correct shape and access patterns
	 * 
	 * @throws IllegalArgumentException if output is null or if the memory layout
	 *                                  is incompatible with the expected shape
	 * 
	 * @see #postprocessor
	 * @see #shape
	 * @see PackedCollection#PackedCollection(TraversalPolicy, int, MemoryData, int)
	 */
	@Override
	protected T postProcessOutput(MemoryData output, int offset) {
		if (postprocessor == null) {
			return (T) new PackedCollection(shape, 0, output, offset);
		} else {
			return postprocessor.apply(output, offset);
		}
	}
}
