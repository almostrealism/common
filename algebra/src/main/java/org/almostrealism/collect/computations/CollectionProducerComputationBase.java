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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.CollectionUtils;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.IndexSet;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.calculus.DeltaAlternate;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract base class for collection-based computation operations in the Almost Realism framework.
 * This class provides the fundamental infrastructure for computations that operate on packed collections
 * of data with support for hardware acceleration, memory management, and multi-dimensional traversal.
 * 
 * <p>This class serves as the cornerstone for implementing efficient, parallelizable computations on
 * multi-dimensional data structures. It integrates with the hardware acceleration system to provide
 * high-performance computation capabilities while maintaining a clean abstraction for collection operations.</p>
 * 
 * <h2>Key Features:</h2>
 * <ul>
 *   <li><strong>Hardware Acceleration:</strong> Leverages GPU and CPU parallelization</li>
 *   <li><strong>Memory Management:</strong> Intelligent destination buffer management and reuse</li>
 *   <li><strong>Shape Management:</strong> Flexible handling of multi-dimensional data structures via 
 *       {@link TraversalPolicy}</li>
 *   <li><strong>Delta Computation:</strong> Support for derivative and gradient calculations</li>
 *   <li><strong>Lifecycle Management:</strong> Comprehensive resource management and cleanup</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Example of a concrete implementation for element-wise addition
 * public class AdditionComputation extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
 *     public AdditionComputation(TraversalPolicy shape, 
 *                               Supplier<Evaluable<? extends PackedCollection<?>>> a,
 *                               Supplier<Evaluable<? extends PackedCollection<?>>> b) {
 *         super("addition", shape, a, b);
 *     }
 *     
 *     @Override
 *     public Scope<Void> getScope(KernelStructureContext context) {
 *         // Implementation of the computation kernel
 *         // ...
 *     }
 * }
 * 
 * // Usage
 * TraversalPolicy shape = new TraversalPolicy(100, 50); // 100x50 matrix
 * Producer<PackedCollection<?>> sourceA = ...; // First input producer
 * Producer<PackedCollection<?>> sourceB = ...; // Second input producer
 * AdditionComputation computation = new AdditionComputation(shape, sourceA, sourceB);
 * PackedCollection<?> result = computation.get().evaluate();
 * }</pre>
 * 
 * <h2>Thread Safety:</h2>
 * <p>This class is <strong>not thread-safe</strong>. Instances should not be shared across threads
 * without external synchronization. However, once a computation is compiled and obtained via {@link #get()},
 * the resulting {@link Evaluable} can be safely used concurrently.</p>
 * 
 * <h2>Memory Considerations:</h2>
 * <p>This class manages memory allocation automatically through the {@link #adjustDestination(MemoryBank, Integer)}
 * method. Large computations may hit memory limits defined by {@link MemoryProvider#MAX_RESERVATION}.
 * Applications should call {@link #destroy()} when computations are no longer needed to free resources.</p>
 * 
 * @param <I> Input collection type, must extend {@link PackedCollection}
 * @param <O> Output collection type, must extend {@link PackedCollection}
 * 
 * @author Michael Murray
 * @since 0.69
 * 
 * @see CollectionProducerComputation
 * @see TraversalPolicy  
 * @see PackedCollection
 */
public abstract class CollectionProducerComputationBase<I extends PackedCollection<?>, O extends PackedCollection<?>>
												extends ProducerComputationBase<I, O>
												implements CollectionProducerComputation<O>, IndexSet,
															DeltaAlternate<O>, MemoryDataComputation<O>,
															HardwareFeatures {
	/**
	 * Global flag to enable logging of destination buffer operations.
	 * When enabled, provides detailed logging of memory allocation and shape adjustments
	 * during computation execution. Primarily used for debugging memory management issues.
	 * 
	 * @see #destinationLog(Runnable)
	 * @see #shapeForLength(int)
	 */
	public static boolean enableDestinationLogging = false;

	/** The human-readable name of this computation, used for debugging and profiling. */
	private String name;
	
	/** The traversal policy defining the multi-dimensional shape and access pattern of the output. */
	private TraversalPolicy shape;
	
	/** Optional post-processor function to transform raw memory data into the final output type. */
	private BiFunction<MemoryData, Integer, O> postprocessor;
	
	/** Optional short-circuit evaluable that bypasses normal computation for optimization. */
	private Evaluable<O> shortCircuit;
	
	/** List of dependent lifecycle objects that need to be managed alongside this computation. */
	private List<ScopeLifecycle> dependentLifecycles;

	/** Cached hardware-accelerated evaluable instance for this computation. */
	private HardwareEvaluable<O> evaluable;

	/** Alternative producer for delta computations (derivatives/gradients). */
	private CollectionProducer<O> deltaAlternate;
	
	/** Custom description function for generating human-readable computation descriptions. */
	private Function<List<String>, String> description;

	/**
	 * Protected default constructor for subclasses that need to perform custom initialization
	 * before calling the main constructor.
	 */
	protected CollectionProducerComputationBase() {
	}

	/**
	 * Creates a new {@link CollectionProducerComputation} with the specified parameters.
	 * This is the primary constructor used by concrete implementations.
	 * 
	 * <p>The output shape must have a positive total size, and all arguments must be non-null.
	 * The first argument position is reserved for the destination buffer, which is automatically
	 * managed by this base class.</p>
	 * 
	 * @param name A human-readable name for this computation, used in debugging and profiling.
	 *             May be null, in which case a default name will be generated.
	 * @param outputShape The {@link TraversalPolicy} defining the multi-dimensional shape of the output.
	 *                   Must have a total size greater than zero.
	 * @param arguments Variable argument list of {@link Supplier}s that provide the input {@link Evaluable}s.
	 *                 Each supplier must be non-null and produce a valid evaluable when called.
	 * 
	 * @throws IllegalArgumentException if the output shape has a total size of zero or less
	 * @throws NullPointerException if any argument supplier is null
	 * 
	 * @see #validateArgs(Supplier[])
	 * @see TraversalPolicy#getTotalSizeLong()
	 */
	@SafeVarargs
	public CollectionProducerComputationBase(String name, TraversalPolicy outputShape,
											 Supplier<Evaluable<? extends I>>... arguments) {
		this();

		if (outputShape.getTotalSizeLong() <= 0) {
			throw new IllegalArgumentException("Output shape must have a total size greater than 0");
		}

		this.name = name;
		this.shape = outputShape.withOrder(null);
		this.setInputs((Supplier[]) CollectionUtils.include(new Supplier[0], new MemoryDataDestinationProducer<>(this, this::adjustDestination), arguments));
		init();
	}

	/**
	 * Returns the name of this computation.
	 * If no explicit name was provided during construction, returns the default name
	 * from the parent class.
	 * 
	 * @return The name of this computation, never null
	 */
	@Override
	public String getName() {
		return name == null ? super.getName() : name;
	}

	/**
	 * Prepares the operation metadata by adding shape information to the base metadata.
	 * 
	 * @param metadata The base metadata to enhance
	 * @return Enhanced metadata including shape information
	 */
	@Override
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		return super.prepareMetadata(metadata).withShape(getShape()).withSignature(signature());
	}

	/**
	 * Retrieves the input arguments as a {@link List} of {@link ArrayVariable} for {@link Double}s.
	 * This is used internally for {@link io.almostrealism.scope.Scope} preparation and argument handling.
	 * 
	 * @return {@link List} of input argument variables
	 */
	protected List<ArrayVariable<Double>> getInputArguments() {
		return (List) getInputs().stream().map(this::getArgumentForInput).collect(Collectors.toList());
	}

	/**
	 * Returns the delta alternate producer for this computation.
	 * The delta alternate is used for derivative and gradient calculations
	 * in automatic differentiation scenarios.
	 * 
	 * @return The delta alternate producer, or null if none is set
	 * @see #setDeltaAlternate(CollectionProducer)
	 */
	@Override
	public CollectionProducer<O> getDeltaAlternate() {
		return deltaAlternate;
	}

	/**
	 * Sets the delta alternate producer for this computation.
	 * This is used in automatic differentiation to provide an alternative
	 * computation path for calculating derivatives and gradients.
	 * 
	 * @param deltaAlternate The delta alternate producer to set
	 * @see #getDeltaAlternate()
	 */
	public void setDeltaAlternate(CollectionProducer<O> deltaAlternate) {
		this.deltaAlternate = deltaAlternate;
	}

	/**
	 * Adds a dependent lifecycle object to this computation.
	 * Dependent lifecycles are managed alongside this computation's lifecycle,
	 * ensuring proper resource allocation and cleanup.
	 * 
	 * @param lifecycle The lifecycle object to add as a dependency
	 * @return This computation instance for method chaining
	 * @see #addAllDependentLifecycles(Iterable)
	 * @see #getDependentLifecycles()
	 */
	public CollectionProducerComputationBase<I, O> addDependentLifecycle(ScopeLifecycle lifecycle) {
		if (dependentLifecycles == null) {
			dependentLifecycles = new ArrayList<>();
		}

		dependentLifecycles.add(lifecycle);
		return this;
	}

	/**
	 * Adds multiple dependent lifecycle objects to this computation.
	 * Convenient method for adding several lifecycle dependencies at once.
	 * 
	 * @param lifecycles An iterable collection of lifecycle objects to add
	 * @return This computation instance for method chaining
	 * @see #addDependentLifecycle(ScopeLifecycle)
	 */
	public CollectionProducerComputationBase<I, O> addAllDependentLifecycles(Iterable<ScopeLifecycle> lifecycles) {
		lifecycles.forEach(this::addDependentLifecycle);
		return this;
	}

	/**
	 * Returns the list of dependent lifecycle objects for this computation.
	 * These objects are managed alongside this computation's lifecycle.
	 * 
	 * @return Unmodifiable list of dependent lifecycles, empty if none exist
	 */
	public List<ScopeLifecycle> getDependentLifecycles() {
		return dependentLifecycles == null ? Collections.emptyList() : dependentLifecycles;
	}

	/**
	 * Prepares the scope for kernel compilation by setting up input management
	 * and configuring argument ordering hints.
	 * 
	 * <p>This method ensures that dependent lifecycles are properly prepared
	 * and sets a sort hint to prioritize the result argument in generated kernels.</p>
	 * 
	 * @param manager The scope input manager for handling argument preparation
	 * @param context The kernel structure context providing compilation information
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		if (dependentLifecycles != null) ScopeLifecycle.prepareScope(dependentLifecycles.stream(), manager, context);
	}

	/**
	 * Prepares arguments for kernel execution by setting up the argument mapping
	 * and ensuring dependent lifecycles are properly configured.
	 * 
	 * @param map The argument map for tracking kernel arguments
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);

		if (dependentLifecycles != null)
			ScopeLifecycle.prepareArguments(dependentLifecycles.stream(), map);
	}

	/**
	 * Resets the computation arguments and invalidates cached evaluables.
	 * This method should be called when argument values change to ensure
	 * that subsequent evaluations use the updated arguments.
	 * 
	 * <p>This method also resets all dependent lifecycles and marks the
	 * cached evaluable as outdated, forcing recompilation if needed.</p>
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();

		if (dependentLifecycles != null)
			ScopeLifecycle.resetArguments(dependentLifecycles.stream());

		// this.evaluable = null;
	}

	/**
	 * Adjusts the destination buffer to match the required length and shape.
	 * This method handles intelligent memory management by reusing existing buffers
	 * when possible and allocating new ones only when necessary.
	 * 
	 * <p>The adjustment logic:</p>
	 * <ul>
	 *   <li>If length is null or zero, destroys the existing buffer and returns null</li>
	 *   <li>If existing buffer is too small, destroys it and creates a new one</li>
	 *   <li>If existing buffer has the exact shape, returns it unchanged</li>
	 *   <li>Otherwise, returns a range view of the existing buffer</li>
	 * </ul>
	 * 
	 * @param existing The existing memory bank, may be null
	 * @param len The required length for the destination buffer
	 * @return Adjusted memory bank, or null if length is invalid
	 * @throws IllegalArgumentException if len is null
	 * @see CollectionProducerComputation#shapeForLength(TraversalPolicy, int, boolean, int)
	 */
	protected MemoryBank<?> adjustDestination(MemoryBank<?> existing, Integer len) {
		if (len == null) {
			throw new IllegalArgumentException();
		} else if (len <= 0) {
			existing.getRootDelegate().destroy();
			return null;
		}

		TraversalPolicy shape = CollectionProducerComputation.shapeForLength(
				getShape(), getCount(), isFixedCount(), len);

		if (!(existing instanceof PackedCollection) || existing.getMem() == null ||
				((PackedCollection) existing).getShape().getTotalSize() < shape.getTotalSize()) {
			if (existing != null) existing.getRootDelegate().destroy();
			return new PackedCollection<>(shape);
		}

		if (((PackedCollection<?>) existing).getShape().equals(shape))
			return existing;

		return ((PackedCollection) existing).range(shape);
	}

	/**
	 * Creates a destination buffer of the specified length.
	 * This method delegates to the underlying destination provider to create
	 * an appropriately sized output buffer.
	 * 
	 * @param len The required length of the destination buffer
	 * @return A new destination buffer of the specified length
	 */
	@Override
	public O createDestination(int len) {
		return (O) getDestination().createDestination(len);
	}

	/**
	 * Returns the traversal policy that defines the multi-dimensional shape
	 * and access pattern of the output data.
	 * 
	 * @return The traversal policy for this computation's output
	 */
	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	/**
	 * Returns the memory length required for this computation.
	 * This represents the number of elements operated on by one kernel thread.
	 * 
	 * @return The memory length in elements per kernel thread
	 */
	@Override
	public int getMemLength() {
		return getShape().getSize();
	}

	/**
	 * Returns the number of kernel threads that will be used for this computation.
	 * This is derived from the traversal policy and represents the
	 * total count of kernel threads that will be executed.
	 * 
	 * @return The number of kernel threads that will be used, or 0 if shape is null
	 */
	@Override
	public long getCountLong() {
		return Optional.ofNullable(getShape())
				.map(TraversalPolicy::getCountLong)
				.orElse(0L);
	}

	@Override
	public boolean isFixedCount() {
		return getShape().isFixedCount() && super.isFixedCount();
	}

	/**
	 * Returns the maximum parallelism level for this computation.
	 * If the output size exceeds the maximum reservation limit, returns -1
	 * to indicate that independent compilation is not possible.
	 * 
	 * @return The parallelism level, or -1 if the computation is too large
	 * @see MemoryProvider#MAX_RESERVATION
	 */
	@Override
	public long getParallelism() {
		if (getOutputSize() > MemoryProvider.MAX_RESERVATION) {
			// This cannot even be independently compiled, so it is
			// false to claim that it has any level of parallelism
			return -1;
		}

		return super.getParallelism();
	}

	/**
	 * Determines whether the computation contains the specified index.
	 * Delegates to the parent interface's default implementation.
	 * 
	 * @param index The index expression to check
	 * @return Boolean expression indicating whether the index is contained
	 */
	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return CollectionProducerComputation.super.containsIndex(index);
	}

	/**
	 * Returns the total output size of this computation.
	 * This represents the total number of elements in the output collection.
	 * 
	 * @return The total output size
	 */
	@Override
	public long getOutputSize() {
		return getShape().getTotalSizeLong();
	}

	/**
	 * Creates an isolated process for this computation if possible.
	 * If the total size exceeds the maximum reservation limit, returns this
	 * computation unchanged and logs a warning.
	 * 
	 * @return An isolated process, or this computation if isolation is not possible
	 * @see MemoryProvider#MAX_RESERVATION
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends O>> isolate() {
		if (getShape().getTotalSizeLong() > MemoryProvider.MAX_RESERVATION) {
			warn("Cannot isolate a process with a total size greater than " + MemoryProvider.MAX_RESERVATION);
			return this;
		}

		return new CollectionProducerComputation.IsolatedProcess<>(this);
	}

	/**
	 * Returns the post-processor function for transforming raw output data.
	 * The post-processor can be used to apply final transformations to the
	 * computation result before returning it to the caller.
	 * 
	 * @return The post-processor function, or null if none is set
	 * @see #setPostprocessor(BiFunction)
	 */
	public BiFunction<MemoryData, Integer, O> getPostprocessor() {
		return postprocessor;
	}

	/**
	 * Sets the post-processor function for transforming raw output data.
	 * This function will be applied to the raw memory data to produce the final output.
	 * 
	 * @param postprocessor The post-processor function to set
	 * @return This computation instance for method chaining
	 * @see #getPostprocessor()
	 */
	public CollectionProducerComputationBase<I, O> setPostprocessor(BiFunction<MemoryData, Integer, O> postprocessor) {
		this.postprocessor = postprocessor;
		return this;
	}

	/**
	 * Returns the short-circuit evaluable for this computation.
	 * The short-circuit evaluable can be used to bypass normal computation
	 * for optimization purposes, such as when the result is known in advance.
	 * 
	 * @return The short-circuit evaluable, or null if none is set
	 * @see #setShortCircuit(Evaluable)
	 */
	public Evaluable<O> getShortCircuit() { return shortCircuit; }

	/**
	 * Sets the short-circuit evaluable for this computation.
	 * When set, this evaluable can be used to bypass normal computation
	 * for optimization purposes.
	 * 
	 * @param shortCircuit The short-circuit evaluable to set
	 * @return This computation instance for method chaining
	 * @see #getShortCircuit()
	 */
	public CollectionProducerComputationBase<I, O> setShortCircuit(Evaluable<O> shortCircuit) {
		this.shortCircuit = shortCircuit;
		return this;
	}

	/**
	 * Returns the custom description function for this computation.
	 * This function can be used to generate human-readable descriptions
	 * of the computation for debugging and profiling purposes.
	 * 
	 * @return The description function, or null if none is set
	 * @see #setDescription(Function)
	 */
	public Function<List<String>, String> getDescription() {
		return description;
	}

	/**
	 * Sets the custom description function for this computation.
	 * This function will be used to generate human-readable descriptions
	 * based on the computation's child components.
	 * 
	 * @param description The description function to set
	 * @return This computation instance for method chaining
	 * @see #getDescription()
	 */
	public CollectionProducerComputationBase<I, O> setDescription(Function<List<String>, String> description) {
		this.description = description;
		return this;
	}

	/**
	 * Creates traversable expressions for the computation arguments.
	 * This method sets up the argument access patterns for kernel execution,
	 * allowing efficient traversal of multi-dimensional input data.
	 * 
	 * @param index The index expression for the current computation position
	 * @return Array of traversable expressions for each input argument
	 */
	protected TraversableExpression[] getTraversableArguments(Expression<?> index) {
		TraversableExpression vars[] = new TraversableExpression[getInputs().size()];
		for (int i = 0; i < vars.length; i++) {
			vars[i] = TraversableExpression.traverse(getArgumentForInput(getInputs().get(i)));
		}
		return vars;
	}

	/**
	 * Retrieves the collection variable for the specified argument index.
	 * This method provides access to the underlying collection structure
	 * of input arguments when they are collection-based.
	 * 
	 * @param argIndex The index of the argument to retrieve
	 * @return The collection variable, or null if the argument is not a collection variable
	 */
	public CollectionVariable<?> getCollectionArgumentVariable(int argIndex) {
		ArrayVariable<?> arg = getArgumentForInput(getInputs().get(argIndex));

		if (arg instanceof CollectionVariable) {
			return (CollectionVariable<?>) arg;
		} else {
			return null;
		}
	}

	/**
	 * Protected method to obtain the evaluable for this computation.
	 * This method handles compute requirements setup and delegates to the
	 * parent class implementation while ensuring proper resource management.
	 * 
	 * @return The evaluable for this computation
	 */
	protected Evaluable<O> getEvaluable() {
		try {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().pushRequirements(getComputeRequirements());
			}

			return CollectionProducerComputation.super.get();
		} finally {
			if (getComputeRequirements() != null) {
				Hardware.getLocalHardware().getComputer().popRequirements();
			}
		}
	}

	/**
	 * Returns the evaluable instance for this computation.
	 * This method implements lazy evaluation and caching of the hardware-accelerated
	 * evaluable. The evaluable is created once and reused for subsequent calls
	 * unless the computation arguments are reset.
	 * 
	 * <p>The method handles:</p>
	 * <ul>
	 *   <li>Optimization checks (delegates to optimized version if available)</li>
	 *   <li>Lazy creation of hardware evaluables with destination processing</li>
	 *   <li>Proper shape alignment and traversal axis selection</li>
	 * </ul>
	 * 
	 * @return The cached or newly created evaluable for this computation
	 * @see HardwareEvaluable
	 * @see #getEvaluable()
	 */
	@Override
	public Evaluable<O> get() {
		if (optimized != null & optimized != this) {
			warn("This Computation should not be used, as an optimized version already exists");
			return (Evaluable<O>) optimized.get();
		}

		if (evaluable == null) {
			this.evaluable = new HardwareEvaluable<>(
					this::getEvaluable,
					getDestination(),
					getShortCircuit(), true);
			this.evaluable.setDestinationProcessor(destination -> {
				if (destination instanceof Shape) {
					Shape out = (Shape) destination;

					int targetSize = getMemLength();

					if (getCountLong() > 1 || isFixedCount() || (out.getShape().getCountLong() > 1 && getCountLong() == 1)) {
						for (int axis = out.getShape().getDimensions(); axis >= 0; axis--) {
							if (out.getShape().traverse(axis).getSize() == targetSize) {
								return (O) (axis == out.getShape().getTraversalAxis() ? out : out.traverse(axis));
							}
						}
					}

					if (targetSize > 1 && ((Shape) destination).getShape().getSize() != targetSize) {
						throw new IllegalArgumentException();
					}
				}

				return destination;
			});
		}

		return evaluable;
	}

	/**
	 * Post-processes the raw computation output using the configured post-processor.
	 * If no post-processor is set, delegates to the parent class implementation.
	 * 
	 * @param output The raw memory data from the computation
	 * @param offset The offset within the output data
	 * @return The post-processed output of type O
	 * @see #setPostprocessor(BiFunction)
	 */
	@Override
	public O postProcessOutput(MemoryData output, int offset) {
		return getPostprocessor() == null ? CollectionProducerComputation.super.postProcessOutput(output, offset) : getPostprocessor().apply(output, offset);
	}

	/**
	 * Attempt to convert this computation to a {@link RepeatedProducerComputationAdapter} for
	 * sequential execution in a loop.
	 * 
	 * <p><strong>Note:</strong> Subclasses that can be meaningfully converted should
	 * override this method to provide the appropriate {@link RepeatedProducerComputationAdapter}
	 * implementation.
	 *
	 * @return Never returns normally in the base class implementation
	 * @throws UnsupportedOperationException Always thrown, as this operation is not
	 *         directly supported by the base class
	 * 
	 * @see RepeatedProducerComputationAdapter
	 * @see CollectionProducerComputationAdapter#toRepeated()
	 */
	public RepeatedProducerComputationAdapter<O> toRepeated() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Destroys this computation and frees associated resources.
	 * This method performs cleanup of the destination producer, purges
	 * cached {@link Evaluable}s, and calls the parent class cleanup.
	 * 
	 * <p>After calling this method, the computation should not be used again.</p>
	 * 
	 * @see ProducerCache#purgeEvaluableCache(Supplier)
	 */
	@Override
	public void destroy() {
		super.destroy();
		((MemoryDataDestinationProducer) getInputs().get(0)).destroy();
		ProducerCache.purgeEvaluableCache(this);
	}

	/**
	 * Delegates producer substitution to the parent interface implementation.
	 * This method is used in optimization scenarios where producers need to be
	 * replaced with more efficient alternatives.
	 * 
	 * @param original The original producer to replace
	 * @param actual The actual producer to use instead
	 * @param <T> The type of the producer
	 * @return The result of the delegation
	 */
	@Override
	public <T> Producer<?> delegate(Producer<T> original, Producer<T> actual) {
		return CollectionProducerComputation.super.delegate(original, actual);
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;

		return signature + getShape().toStringDetail();
	}

	/**
	 * Provides a description of this computation including its shape information.
	 * Extends the parent class description with the output shape details.
	 * 
	 * @return A string description of the computation and its shape
	 */
	@Override
	public String describe() {
		return super.describe() + " " + getShape();
	}

	/**
	 * Generates a description of this computation based on its child components.
	 * Uses the custom description function if available, otherwise delegates
	 * to the parent class implementation.
	 * 
	 * @param children List of string descriptions from child components
	 * @return A human-readable description of the computation
	 * @see #setDescription(Function)
	 */
	@Override
	public String description(List<String> children) {
		return description == null ? super.description(children) : description.apply(children);
	}

	/**
	 * Validates that all supplied argument suppliers are non-null.
	 * This utility method is used during computation construction to ensure
	 * all required arguments are properly provided.
	 * 
	 * @param args Variable argument array of suppliers to validate
	 * @return The same array of suppliers after validation
	 * @throws NullPointerException if any supplier is null
	 */
	public static Supplier[] validateArgs(Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		Stream.of(args).forEach(Objects::requireNonNull);
		return args;
	}

	/**
	 * Executes the given {@link Runnable} with destination logging temporarily enabled.
	 * This utility method is useful for debugging memory allocation and shape
	 * adjustment operations without permanently enabling global logging.
	 * 
	 * <p>The previous logging state is restored after execution, regardless
	 * of whether the runnable completes normally or throws an exception.</p>
	 * 
	 * @param r The runnable to execute with logging enabled
	 * @see #enableDestinationLogging
	 */
	public static void destinationLog(Runnable r) {
		boolean log = enableDestinationLogging;

		try {
			enableDestinationLogging = true;
			r.run();
		} finally {
			enableDestinationLogging = log;
		}
	}

	public static <T extends PackedCollection<?>> CollectionProducer<T> assignDeltaAlternate(
			CollectionProducer<T> producer, CollectionProducer<T> alternate) {
		Producer computation;

		if (producer instanceof ReshapeProducer) {
			computation = ((ReshapeProducer) producer).getComputation();
		} else {
			computation = producer;
		}

		if (computation instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase<?, T>) computation).setDeltaAlternate(alternate);
		} else {
			throw new IllegalArgumentException();
		}

		return producer;
	}
}
