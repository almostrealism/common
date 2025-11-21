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

package org.almostrealism.hardware;

import io.almostrealism.code.Computation;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.ComputationBase;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Abstract base class for {@link OperationComputation}s that return void and compile to {@link Runnable}s.
 *
 * <p>{@link OperationComputationAdapter} provides a foundation for implementing operations that modify
 * state or perform side effects rather than producing values. It extends {@link ComputationBase} with:
 * <ul>
 *   <li><strong>Automatic compilation:</strong> Compiles to {@link Runnable} via {@link ComputerFeatures}</li>
 *   <li><strong>Dependency management:</strong> Supports additional computations beyond direct inputs</li>
 *   <li><strong>Count inference:</strong> Automatically determines operation count from inputs and dependencies</li>
 * </ul>
 *
 * <h2>Core Concept: Side-Effect Operations</h2>
 *
 * <p>Unlike computations that produce values, {@link OperationComputationAdapter} models operations
 * that perform actions:</p>
 * <pre>{@code
 * // Value-producing computation
 * Computation<PackedCollection<?>> producer = multiply(a, b);
 * PackedCollection<?> result = producer.get().evaluate(args);
 *
 * // Side-effect operation (extends OperationComputationAdapter)
 * OperationComputation<Void> operation = updateState(destination, newValue);
 * operation.get().run();  // Modifies destination in-place
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>In-Place Updates</h3>
 * <pre>{@code
 * public class IncrementOperation extends OperationComputationAdapter<PackedCollection<?>> {
 *     private final Producer<PackedCollection<?>> target;
 *
 *     public IncrementOperation(Producer<PackedCollection<?>> target) {
 *         super(target);  // Register as input
 *         this.target = target;
 *     }
 *
 *     @Override
 *     public Scope<Void> getScope(KernelStructureContext context) {
 *         // Generate code that increments target in-place
 *         return new Scope<>(context) {
 *             @Override
 *             public Runnable getRunnable() {
 *                 return () -> {
 *                     PackedCollection<?> data = target.get().evaluate();
 *                     for (int i = 0; i < data.getMemLength(); i++) {
 *                         data.setMem(i, data.toDouble(i) + 1.0);
 *                     }
 *                 };
 *             }
 *         };
 *     }
 * }
 *
 * // Usage:
 * OperationComputation<Void> increment = new IncrementOperation(dataProducer);
 * increment.get().run();  // data is incremented in-place
 * }</pre>
 *
 * <h3>Multi-Input Side Effects</h3>
 * <pre>{@code
 * public class CopyOperation extends OperationComputationAdapter<PackedCollection<?>> {
 *     private final Producer<PackedCollection<?>> source;
 *     private final Producer<PackedCollection<?>> destination;
 *
 *     public CopyOperation(Producer<PackedCollection<?>> source,
 *                         Producer<PackedCollection<?>> destination) {
 *         super(source, destination);  // Both are inputs
 *         this.source = source;
 *         this.destination = destination;
 *     }
 *
 *     @Override
 *     public Scope<Void> getScope(KernelStructureContext context) {
 *         return new Scope<>(context) {
 *             @Override
 *             public Runnable getRunnable() {
 *                 return () -> {
 *                     PackedCollection<?> src = source.get().evaluate();
 *                     PackedCollection<?> dst = destination.get().evaluate();
 *                     dst.setMem(src);  // Copy source to destination
 *                 };
 *             }
 *         };
 *     }
 * }
 * }</pre>
 *
 * <h3>With Dependent Computations</h3>
 * <pre>{@code
 * public class ComputeAndStoreOperation extends OperationComputationAdapter<PackedCollection<?>> {
 *     private final Computation<PackedCollection<?>> computation;
 *     private final Producer<PackedCollection<?>> destination;
 *
 *     public ComputeAndStoreOperation(Computation<PackedCollection<?>> computation,
 *                                    Producer<PackedCollection<?>> destination) {
 *         super(destination);  // Destination is input
 *         this.computation = computation;
 *         this.destination = destination;
 *     }
 *
 *     @Override
 *     protected List<Computation<?>> getDependentComputations() {
 *         return List.of(computation);  // computation is a dependency, not an input
 *     }
 *
 *     @Override
 *     public Scope<Void> getScope(KernelStructureContext context) {
 *         return new Scope<>(context) {
 *             @Override
 *             public Runnable getRunnable() {
 *                 return () -> {
 *                     PackedCollection<?> result = computation.get().evaluate();
 *                     PackedCollection<?> dst = destination.get().evaluate();
 *                     dst.setMem(result);  // Store computed result
 *                 };
 *             }
 *         };
 *     }
 * }
 * }</pre>
 *
 * <h2>Count Inference Strategy</h2>
 *
 * <p>The {@link #getCountLong()} method automatically infers the operation count:
 * <ol>
 *   <li><strong>If all inputs/dependencies have same count:</strong> Use that count</li>
 *   <li><strong>If inputs/dependencies have different counts:</strong> Sum them (treats as sequence)</li>
 *   <li><strong>If no dependent computations:</strong> Return 1 (single operation)</li>
 *   <li><strong>If dependent computations have same count:</strong> Use that count</li>
 *   <li><strong>Otherwise:</strong> Throw {@link UnsupportedOperationException}</li>
 * </ol>
 *
 * <p>Example:</p>
 * <pre>{@code
 * // Two inputs with count 100 each -> getCountLong() = 100
 * OperationComputation<Void> op = new MyOperation(input1, input2);
 *
 * // One input with count 100, one dependency with count 100 -> 100
 * // Inputs with different counts -> sum
 * }</pre>
 *
 * <h2>Compilation and Execution</h2>
 *
 * <p>The {@link #get()} method compiles the operation to a {@link Runnable}:</p>
 * <pre>{@code
 * OperationComputation<Void> operation = ...;
 * Runnable runnable = operation.get();  // Compiles
 *
 * // If compiled to AcceleratedOperation, loads kernel
 * if (runnable instanceof AcceleratedOperation<?>) {
 *     ((AcceleratedOperation<?>) runnable).load();  // Kernel loaded
 * }
 *
 * runnable.run();  // Executes operation
 * }</pre>
 *
 * <h2>Integration with Hardware Acceleration</h2>
 *
 * <p>Via {@link ComputerFeatures}, operations compile to hardware-accelerated runnables:</p>
 * <pre>{@code
 * // Compilation uses Hardware.getLocalHardware().getComputer()
 * Runnable runnable = compileRunnable(this);
 *
 * // May produce:
 * // - AcceleratedOperation (GPU kernel)
 * // - OperationList (sequence of operations)
 * // - Plain Runnable (CPU execution)
 * }</pre>
 *
 * <h2>Subclassing Guidelines</h2>
 *
 * <p>When extending {@link OperationComputationAdapter}:
 * <ul>
 *   <li><strong>Constructor:</strong> Call {@code super(inputs)} to register input producers</li>
 *   <li><strong>getDependentComputations():</strong> Override to declare additional dependencies</li>
 *   <li><strong>getScope():</strong> Implement to define the operation's behavior</li>
 *   <li><strong>init():</strong> Called automatically after inputs are set (optional override)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link OperationComputationAdapter} itself is thread-safe for compilation. However, the
 * compiled {@link Runnable} is not thread-safe - concurrent executions require external synchronization
 * or separate compiled instances.</p>
 *
 * @param <T> The type of data consumed by the operation (typically {@link MemoryData} or subtype)
 *
 * @see OperationComputation
 * @see ComputationBase
 * @see ComputerFeatures
 * @see AcceleratedOperation
 */
public abstract class OperationComputationAdapter<T>
		extends ComputationBase<T, Void, Runnable>
		implements OperationComputation<Void>, ComputerFeatures {

	/**
	 * Creates an operation computation with the specified input producers.
	 *
	 * <p>Registers the input producers and calls {@link #init()} for subclass initialization.
	 * Input producers are accessed during scope generation and evaluation.</p>
	 *
	 * @param inputArgs The input producers consumed by this operation
	 */
	@SafeVarargs
	public OperationComputationAdapter(Producer<T>... inputArgs) {
		this.setInputs(inputArgs);
		init();
	}

	/**
	 * A {@link List} of any {@link Computation}s that this operation depends on
	 * in addition to those which result from the {@link #getInputs() inputs}.
	 */
	protected List<Computation<?>> getDependentComputations() {
		return Collections.emptyList();
	}

	/**
	 * Returns the operation count inferred from inputs and dependencies.
	 *
	 * <p>Count inference strategy:</p>
	 * <ol>
	 *   <li>If all inputs/dependencies have the same count, returns that count</li>
	 *   <li>If inputs/dependencies have different counts, returns their sum (treats as sequence)</li>
	 *   <li>If no dependent computations exist, returns 1 (single operation)</li>
	 *   <li>If dependent computations exist and have the same count, returns that count</li>
	 *   <li>Otherwise, throws {@link UnsupportedOperationException}</li>
	 * </ol>
	 *
	 * @return The inferred operation count
	 * @throws UnsupportedOperationException if count cannot be inferred
	 */
	@Override
	public long getCountLong() {
		// Try to find a value suitable to the inputs and the dependent computations
		long p = Stream.of(getInputs(), getDependentComputations())
				.flatMap(List::stream)
				.mapToLong(Countable::countLong)
				.distinct().count();

		if (p == 1) {
			return Stream.of(getInputs(), getDependentComputations())
					.flatMap(List::stream)
					.mapToLong(Countable::countLong)
					.distinct().sum();
		}

		// Fallback to a value that is suitable for the dependent computations
		p = getDependentComputations().stream()
				.mapToLong(Countable::countLong).distinct().count();

		if (p == 0) {
			return 1;
		} else if (p == 1) {
			return getDependentComputations().stream()
					.mapToLong(Countable::countLong).distinct().sum();
		}

		// Otherwise, this will not succeed
		throw new UnsupportedOperationException();
	}

	/**
	 * Compiles this operation to a {@link Runnable} for execution.
	 *
	 * <p>Delegates to {@link ComputerFeatures#compileRunnable(Computation)} to compile
	 * the operation. If the result is an {@link AcceleratedOperation}, calls {@link AcceleratedOperation#load()}
	 * to prepare the compiled kernel for execution.</p>
	 *
	 * <p>The returned runnable may be:</p>
	 * <ul>
	 *   <li>{@link AcceleratedOperation} - Hardware-accelerated kernel</li>
	 *   <li>{@link OperationList} - Sequence of operations</li>
	 *   <li>Plain {@link Runnable} - CPU-based execution</li>
	 * </ul>
	 *
	 * @return A compiled runnable that executes this operation
	 */
	@Override
	public Runnable get() {
		Runnable r = compileRunnable(this);
		if (r instanceof AcceleratedOperation<?>) {
			((AcceleratedOperation<?>) r).load();
		}
		return r;
	}
}
