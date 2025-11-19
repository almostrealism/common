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

package org.almostrealism.hardware;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.ComputableParallelProcess;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.uml.Multiple;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A {@link DynamicProducer} specialized for {@link MemoryData}, providing destination factory support
 * and integration with hardware-accelerated computation workflows.
 *
 * <p>{@link DynamicProducerForMemoryData} wraps arbitrary functions that produce {@link MemoryData},
 * enabling them to participate in computation graphs alongside hardware-accelerated operations. It
 * extends the basic {@link DynamicProducer} with:
 * <ul>
 *   <li><strong>Destination factories:</strong> Can create output {@link MemoryBank}s for batch operations</li>
 *   <li><strong>Operation metadata:</strong> Provides profiling information (name, description)</li>
 *   <li><strong>{@code into()} support:</strong> Enables writing results directly into target memory</li>
 * </ul>
 *
 * <h2>Core Concept: Bridging Java and Hardware</h2>
 *
 * <p>Not all operations can or should be hardware-accelerated. {@link DynamicProducerForMemoryData}
 * allows pure Java functions to coexist with accelerated operations in the same computation graph:
 *
 * <pre>{@code
 * // Hardware-accelerated operation
 * Producer<PackedCollection<?>> scaled = multiply(input, c(2.0));
 *
 * // Java function wrapped as producer
 * Producer<PackedCollection<?>> normalized = new DynamicProducerForMemoryData<>(
 *     args -> {
 *         PackedCollection<?> data = (PackedCollection<?>) args[0];
 *         double max = Arrays.stream(data.toArray()).max().orElse(1.0);
 *         return data.divide(max);  // Pure Java normalization
 *     }
 * );
 *
 * // Compose into single graph
 * Producer<PackedCollection<?>> result = normalize(scale(input));
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Wrapping Suppliers</h3>
 * <pre>{@code
 * // Constant value producer
 * Producer<PackedCollection<?>> constant = new DynamicProducerForMemoryData<>(
 *     () -> new PackedCollection<>(1000).fill(42.0)
 * );
 *
 * // Lazy initialization
 * Producer<Vector> lazyVector = new DynamicProducerForMemoryData<>(
 *     () -> {
 *         Vector v = new Vector();
 *         v.setX(computeX());
 *         v.setY(computeY());
 *         return v;
 *     }
 * );
 * }</pre>
 *
 * <h3>Argument-Dependent Functions</h3>
 * <pre>{@code
 * // Function that processes evaluation arguments
 * Producer<PackedCollection<?>> processor = new DynamicProducerForMemoryData<>(
 *     args -> {
 *         PackedCollection<?> input = (PackedCollection<?>) args[0];
 *         double threshold = (Double) args[1];
 *
 *         PackedCollection<?> output = new PackedCollection<>(input.getMemLength());
 *         for (int i = 0; i < input.getMemLength(); i++) {
 *             output.setMem(i, input.toDouble(i) > threshold ? 1.0 : 0.0);
 *         }
 *         return output;
 *     }
 * );
 *
 * // Evaluate with arguments
 * PackedCollection<?> result = processor.get().evaluate(data, 0.5);
 * }</pre>
 *
 * <h3>With Destination Factory</h3>
 * <pre>{@code
 * // Producer that can create output banks for batch operations
 * Producer<PackedCollection<?>> batchProcessor = new DynamicProducerForMemoryData<>(
 *     args -> processSingle((PackedCollection<?>) args[0]),
 *     size -> PackedCollection.bank(size, 1000)  // Destination factory
 * );
 *
 * // Use with .into() for batch writing
 * MemoryBank<PackedCollection<?>> outputBank = PackedCollection.bank(100, 1000);
 * batchProcessor.get().into(outputBank).evaluate(inputBatch);
 * }</pre>
 *
 * <h2>Destination Factory</h2>
 *
 * <p>The optional destination factory ({@code IntFunction<MemoryBank<T>>}) enables batch operations:
 * <ul>
 *   <li>Called when {@code createDestination(int size)} is invoked on the evaluable</li>
 *   <li>Should create a {@link MemoryBank} with the specified number of elements</li>
 *   <li>Used by batch processing frameworks to allocate output storage</li>
 * </ul>
 *
 * <h2>Operation Metadata</h2>
 *
 * <p>Metadata is automatically extracted for profiling and debugging:
 * <ul>
 *   <li><strong>Anonymous functions:</strong> Metadata uses "dynamic" as name</li>
 *   <li><strong>Named classes:</strong> Metadata uses class name via {@link OperationInfo}</li>
 *   <li>Appears in profiling output, operation graphs, and debug logs</li>
 * </ul>
 *
 * <h2>Integration with Evaluable</h2>
 *
 * <p>The returned {@link Evaluable} supports:
 * <ul>
 *   <li><strong>{@code evaluate(args)}:</strong> Execute the wrapped function</li>
 *   <li><strong>{@code createDestination(size)}:</strong> Create output bank (if factory provided)</li>
 *   <li><strong>{@code into(destination)}:</strong> Wrap to write results into destination</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 *
 * <ul>
 *   <li><strong>No hardware acceleration:</strong> Functions execute on CPU only</li>
 *   <li><strong>No compilation overhead:</strong> Direct function invocation (fast startup)</li>
 *   <li><strong>Use for:</strong> Complex logic, I/O, random number generation, control flow</li>
 *   <li><strong>Avoid for:</strong> Simple array operations (prefer accelerated producers)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Thread safety depends on the wrapped function. The wrapper itself is thread-safe,
 * but if the function accesses shared mutable state, external synchronization is required.</p>
 *
 * @param <T> The type of {@link MemoryData} produced by this producer
 *
 * @see DynamicProducer
 * @see MemoryData
 * @see MemoryBank
 * @see Evaluable
 */
public class DynamicProducerForMemoryData<T extends MemoryData> extends DynamicProducer<T>
		implements ComputableParallelProcess<Process<?, ?>, Evaluable<? extends T>> {

	private final OperationMetadata metadata;
	private final IntFunction<MemoryBank<T>> destination;

	/**
	 * Creates a producer from a simple supplier (no argument dependency).
	 *
	 * <p>The supplier is called each time {@code evaluate()} is invoked, ignoring any arguments.</p>
	 *
	 * @param supplier Function that produces the {@link MemoryData} result
	 */
	public DynamicProducerForMemoryData(Supplier<T> supplier) {
		this((Object args[]) -> supplier.get());
	}

	/**
	 * Creates a producer from a supplier with destination factory support.
	 *
	 * @param supplier Function that produces the {@link MemoryData} result
	 * @param destination Factory for creating output {@link MemoryBank}s
	 */
	public DynamicProducerForMemoryData(Supplier<T> supplier, IntFunction<MemoryBank<T>> destination) {
		this(args -> supplier.get(), destination);
	}

	/**
	 * Creates a producer from an argument-dependent function.
	 *
	 * <p>The function receives evaluation arguments and produces a result based on them.</p>
	 *
	 * @param function Function mapping evaluation arguments to {@link MemoryData}
	 */
	public DynamicProducerForMemoryData(Function<Object[], T> function) {
		this(function, null);
	}

	/**
	 * Creates a producer with only a destination factory (for subclasses that override {@code get()}).
	 *
	 * @param destination Factory for creating output {@link MemoryBank}s
	 */
	protected DynamicProducerForMemoryData(IntFunction<MemoryBank<T>> destination) {
		this((Function<Object[], T>) null, destination);
	}

	/**
	 * Creates a producer from an argument-dependent function with destination factory support.
	 *
	 * <p>This is the most general constructor, allowing both custom evaluation logic and
	 * batch operation support.</p>
	 *
	 * @param function Function mapping evaluation arguments to {@link MemoryData}
	 * @param destination Factory for creating output {@link MemoryBank}s (may be null)
	 */
	public DynamicProducerForMemoryData(Function<Object[], T> function, IntFunction<MemoryBank<T>> destination) {
		super(function);
		this.destination = destination;

		if (getFunction() == null) {
			this.metadata = OperationInfo.metadataForProcess(this, new OperationMetadata("dynamic", "dynamic"));
		} else {
			this.metadata = OperationInfo.metadataForProcess(this,
					new OperationMetadata(OperationInfo.name(getFunction()), OperationInfo.display(getFunction())));
		}
	}

	/**
	 * Returns operation metadata for profiling and debugging.
	 *
	 * @return The metadata containing operation name and description
	 */
	@Override
	public OperationMetadata getMetadata() {
		return metadata;
	}

	/**
	 * Returns the operation count (always 1 for single-result producers).
	 *
	 * @return 1
	 */
	@Override
	public long getCountLong() { return 1; }

	/**
	 * Returns the destination factory for creating output {@link MemoryBank}s.
	 *
	 * @return The factory, or null if not specified
	 */
	public IntFunction<MemoryBank<T>> getDestinationFactory() { return destination; }

	/**
	 * Returns child processes (empty for dynamic producers).
	 *
	 * @return Empty collection
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() {
		return Collections.emptyList();
	}

	/**
	 * Returns an isolated copy of this process (returns self since there are no dependencies).
	 *
	 * @return This producer
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}

	/**
	 * Compiles this producer into an {@link Evaluable} with destination and {@code into()} support.
	 *
	 * <p>The returned evaluable wraps the base evaluation logic with:
	 * <ul>
	 *   <li><strong>createDestination(size):</strong> Uses destination factory if provided</li>
	 *   <li><strong>into(destination):</strong> Creates {@link DestinationEvaluable} for writing to banks</li>
	 *   <li><strong>evaluate(args):</strong> Delegates to the wrapped function</li>
	 * </ul>
	 *
	 * @return An evaluable that can create destinations and write to target memory
	 */
	@Override
	public Evaluable<T> get() {
		Evaluable<T> e = super.get();

		return new Evaluable<T>() {
			@Override
			public Multiple<T> createDestination(int size) {
				if (destination == null) {
					throw new UnsupportedOperationException();
				} else {
					return destination.apply(size);
				}
			}

			@Override
			public Evaluable<T> into(Object destination) {
				return new DestinationEvaluable(e, (MemoryBank) destination);
			}

			@Override
			public T evaluate(Object... args) { return e.evaluate(args); }
		};
	}

	/**
	 * Returns a short description of this operation for logging and debugging.
	 *
	 * @return The short description from metadata
	 */
	@Override
	public String describe() {
		return getMetadata().getShortDescription();
	}
}
