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

package org.almostrealism.hardware;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.streams.StreamingEvaluable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.instructions.ComputableInstructionSetManager;
import org.almostrealism.hardware.instructions.ScopeInstructionsManager;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * {@link Evaluable} implementation that executes {@link ProducerComputation} instances on hardware accelerators.
 *
 * <p>{@link AcceleratedComputationEvaluable} extends {@link AcceleratedComputationOperation} to implement the
 * {@link Evaluable} interface, enabling direct evaluation of computations to produce results. This is the
 * standard way to execute {@link ProducerComputation} instances (created via Producer.get()) on
 * hardware accelerators.</p>
 *
 * <h2>Evaluation Pattern</h2>
 *
 * <p>The typical usage pattern involves creating a Producer, getting its {@link Evaluable},
 * and invoking evaluate():</p>
 * <pre>{@code
 * // Create producer
 * Producer<PackedCollection<?>> producer = c(2.0).multiply(c(3.0));
 *
 * // Get evaluable (returns AcceleratedComputationEvaluable)
 * Evaluable<PackedCollection<?>> evaluable = producer.get();
 *
 * // Evaluate to produce result
 * PackedCollection<?> result = evaluable.evaluate();  // Result: 6.0
 * }</pre>
 *
 * <h2>Destination Management</h2>
 *
 * <p>Results can be computed into existing memory via {@link #into(Object)}:</p>
 * <pre>{@code
 * // Allocate output buffer
 * PackedCollection<?> output = PackedCollection.create(1000);
 *
 * // Compute directly into output (zero-copy)
 * producer.get().into(output).evaluate();
 *
 * // output now contains the result
 * }</pre>
 *
 * <p>Custom destination factories can be provided via {@link #setDestinationFactory}:</p>
 * <pre>{@code
 * AcceleratedComputationEvaluable eval = ...;
 *
 * // Use custom memory allocation strategy
 * eval.setDestinationFactory(size -> new PackedCollection(size, () -> customMemory(size)));
 *
 * // Destinations created with custom factory
 * PackedCollection<?> result = eval.evaluate();
 * }</pre>
 *
 * <h2>Streaming Support</h2>
 *
 * <p>Implements {@link StreamingEvaluable} for integration with data pipelines:</p>
 * <pre>{@code
 * AcceleratedComputationEvaluable<PackedCollection<?>> transform = ...;
 *
 * // Set downstream consumer
 * transform.setDownstream(result -> {
 *     // Process each result
 *     System.out.println("Result: " + result);
 * });
 *
 * // Evaluate pushes to downstream
 * transform.evaluate();  // Triggers downstream consumer
 * }</pre>
 *
 * <h2>Constant Propagation</h2>
 *
 * <p>If the wrapped {@link ProducerComputation} is constant (produces the same value every time),
 * {@link #isConstant()} returns true, enabling optimizations:</p>
 * <pre>{@code
 * Producer<Scalar> constant = c(42.0);  // Constant value
 * Evaluable<Scalar> eval = constant.get();
 *
 * if (eval.isConstant()) {
 *     // Can cache result, skip compilation, etc.
 *     Scalar cached = eval.evaluate();
 * }
 * }</pre>
 *
 * <h2>Post-Compilation Hooks</h2>
 *
 * <p>After compilation, {@link #postCompile()} is called to perform additional setup.
 * Subclasses can override to customize behavior:</p>
 * <pre>{@code
 * @Override
 * public synchronized void postCompile() {
 *     super.postCompile();
 *
 *     // Custom post-compilation setup
 *     optimizeForHardware();
 * }
 * }</pre>
 *
 * <h2>Redundant Compilation</h2>
 *
 * <p>The static flag {@link #enableRedundantCompilation} controls whether multiple evaluables
 * with the same signature can compile independently. When true (default), each evaluable
 * compiles even if another with the same signature exists:</p>
 * <pre>{@code
 * // Disable redundant compilation to save resources
 * AcceleratedComputationEvaluable.enableRedundantCompilation = false;
 *
 * // First evaluable compiles
 * Evaluable eval1 = producer1.get();
 * eval1.evaluate();  // Compiles
 *
 * // Second evaluable with same signature reuses compilation
 * Evaluable eval2 = producer2.get();  // Same structure as producer1
 * eval2.evaluate();  // Reuses compiled kernel
 * }</pre>
 *
 * <h2>Integration with AcceleratedOperation</h2>
 *
 * <p>Inherits all {@link AcceleratedComputationOperation} functionality:</p>
 * <ul>
 *   <li>Automatic argument mapping and aggregation</li>
 *   <li>Instruction set caching via signatures</li>
 *   <li>Asynchronous execution support</li>
 *   <li>Profiling and timing metrics</li>
 * </ul>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Simple Evaluation</h3>
 * <pre>{@code
 * Producer<PackedCollection<?>> multiply = a.multiply(b);
 * PackedCollection<?> result = multiply.get().evaluate();
 * }</pre>
 *
 * <h3>In-Place Evaluation</h3>
 * <pre>{@code
 * PackedCollection<?> buffer = PackedCollection.create(1000);
 * transform.get().into(buffer).evaluate();
 * // buffer modified in-place
 * }</pre>
 *
 * <h3>Streaming Pipeline</h3>
 * <pre>{@code
 * AcceleratedComputationEvaluable<PackedCollection<?>> stage1 = ...;
 * AcceleratedComputationEvaluable<PackedCollection<?>> stage2 = ...;
 *
 * stage1.setDownstream(result -> stage2.evaluate(result));
 * stage1.evaluate();  // Triggers entire pipeline
 * }</pre>
 *
 * @param <T> The type of {@link MemoryData} produced by evaluation
 * @see AcceleratedComputationOperation
 * @see Evaluable
 * @see ProducerComputation
 * @see StreamingEvaluable
 */
public class AcceleratedComputationEvaluable<T extends MemoryData>
		extends AcceleratedComputationOperation<T>
		implements StreamingEvaluable<T>, Evaluable<T> {
	/** Controls whether multiple evaluables with the same signature can compile independently. */
	public static boolean enableRedundantCompilation = true;

	/** Custom factory for allocating output memory, or null for default allocation. */
	private IntFunction<Multiple<T>> destinationFactory;
	/** Consumer to receive evaluation results for streaming pipelines. */
	private Consumer<T> downstream;

	/**
	 * Creates an evaluable for the specified computation on the given context.
	 *
	 * <p>Initializes the accelerated operation with the computation and enables
	 * compilation. The evaluable will be ready to execute after compilation.</p>
	 *
	 * @param context The {@link ComputeContext} to execute on (OpenCL, Metal, JNI, etc.)
	 * @param c The {@link Computation} to evaluate
	 */
	public AcceleratedComputationEvaluable(ComputeContext<MemoryData> context, Computation<T> c) {
		super(context, c, true);
	}

	/**
	 * Returns the underlying {@link ProducerComputation} for this evaluable.
	 *
	 * <p>Casts the computation to {@link ProducerComputation} (which produces values)
	 * rather than the base {@link Computation} type.</p>
	 *
	 * @return The producer computation being evaluated
	 */
	@Override
	public ProducerComputation<T> getComputation() {
		return (ProducerComputation<T>) super.getComputation();
	}

	/**
	 * Returns whether this evaluable produces a constant value.
	 *
	 * <p>Constant evaluables always produce the same result regardless of input.
	 * This enables optimizations like result caching and compilation skipping.</p>
	 *
	 * @return true if the underlying computation is constant, false otherwise
	 */
	@Override
	public boolean isConstant() { return getComputation().isConstant(); }

	/**
	 * Returns the custom destination factory for creating output memory.
	 *
	 * <p>If set, this factory is used by {@link #createDestination(int)} to allocate
	 * output memory instead of the default allocation strategy.</p>
	 *
	 * @return The destination factory, or null if using default allocation
	 */
	public IntFunction<Multiple<T>> getDestinationFactory() {
		return destinationFactory;
	}

	/**
	 * Sets a custom destination factory for output memory allocation.
	 *
	 * <p>The factory receives the requested size and returns a {@link Multiple}
	 * containing the allocated memory. This allows custom memory management
	 * strategies (pooling, specialized allocators, etc.).</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * evaluable.setDestinationFactory(size ->
	 *     new PackedCollection(size, () -> customHeap.allocate(size))
	 * );
	 * }</pre>
	 *
	 * @param destinationFactory The factory to use for allocating output memory
	 */
	public void setDestinationFactory(IntFunction<Multiple<T>> destinationFactory) {
		this.destinationFactory = destinationFactory;
	}

	/**
	 * Creates a destination {@link Multiple} for storing evaluation results.
	 *
	 * <p>If a custom {@link #destinationFactory} is set, uses that factory to create
	 * the destination. Otherwise, delegates to {@link Evaluable#createDestination(int)}
	 * for default allocation behavior.</p>
	 *
	 * @param size The number of elements in the destination
	 * @return A {@link Multiple} containing the allocated destination memory
	 */
	@Override
	public Multiple<T> createDestination(int size) {
		if (getDestinationFactory() == null) {
			return Evaluable.super.createDestination(size);
		}

		return getDestinationFactory().apply(size);
	}

	/**
	 * Returns an {@link Evaluable} that writes results into the specified destination.
	 *
	 * <p>Creates a {@link DestinationEvaluable} wrapper that evaluates this computation
	 * directly into the provided {@link MemoryBank}, avoiding intermediate allocations.
	 * This enables zero-copy in-place evaluation.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * PackedCollection<?> buffer = PackedCollection.create(1000);
	 * producer.get().into(buffer).evaluate();
	 * // buffer now contains the result
	 * }</pre>
	 *
	 * @param destination The {@link MemoryBank} to write results into
	 * @return An evaluable that evaluates into the destination
	 */
	@Override
	public Evaluable<T> into(Object destination) {
		return new DestinationEvaluable(this, (MemoryBank) destination);
	}

	/**
	 * Performs post-compilation setup for this evaluable.
	 *
	 * <p>After the hardware kernel is compiled, this method identifies the output argument
	 * and registers its index and offset with the {@link ScopeInstructionsManager}. This
	 * allows {@link #evaluate(Object...)} to extract the result from the correct argument
	 * after kernel execution.</p>
	 *
	 * <p>The output variable is determined by analyzing the computation's variable bindings.
	 * The root delegate of the output variable is located in the argument list, and its
	 * index and offset are stored for later result extraction.</p>
	 *
	 * @throws IllegalArgumentException if no output variable is found or if the output
	 *         variable is not one of the kernel arguments
	 */
	@Override
	public synchronized void postCompile() {
		super.postCompile();

		ArrayVariable outputVariable = (ArrayVariable) getOutputVariable();

		// Capture the offset, but ultimately use the root delegate
		int offset = outputVariable.getOffset();
		outputVariable = (ArrayVariable) outputVariable.getRootDelegate();

		if (outputVariable == null) {
			throw new IllegalArgumentException("Cannot capture result, as there is no argument which serves as an output variable");
		}

		int outputArgIndex = getArgumentVariables().indexOf(outputVariable);

		if (outputArgIndex < 0) {
			throw new IllegalArgumentException("An output variable does not appear to be one of the arguments to the Evaluable");
		}

		ComputableInstructionSetManager<?> manager = getInstructionSetManager();

		if (manager instanceof ScopeInstructionsManager mgr) {
			mgr.setOutputArgumentIndex(getExecutionKey(), outputArgIndex);
			mgr.setOutputOffset(getExecutionKey(), offset);
		} else {
			warn("Compilation post processing on " + getName() +
					" with unexpected InstructionSetManager (" +
					manager.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * Ensures the kernel is compiled and ready for execution.
	 *
	 * <p>Checks whether the evaluable has been compiled by verifying that argument
	 * variables are available. If not compiled and either {@link #enableRedundantCompilation}
	 * is true or no instruction set manager exists, triggers compilation via {@link #load()}.</p>
	 *
	 * <p>Logs warnings when:</p>
	 * <ul>
	 *   <li>The evaluable was not compiled ahead of time (just-in-time compilation)</li>
	 *   <li>Instructions already exist but redundant compilation is occurring</li>
	 * </ul>
	 */
	protected void confirmLoad() {
		if (getArgumentVariables() == null &&
				(enableRedundantCompilation || getInstructionSetManager() == null)) {
			if (getInstructionSetManager() == null) {
				warn(getName() + " was not compiled ahead of time");
			} else {
				warn("Instructions already available for " + getName() + " - but it will be redundantly compiled");
			}

			load();
		}
	}

	/**
	 * Evaluates this computation synchronously with the specified arguments.
	 *
	 * <p>Executes the compiled hardware kernel, waits for completion, and extracts
	 * the result from the output argument. The kernel is compiled on-demand if not
	 * already compiled.</p>
	 *
	 * <p>Execution flow:</p>
	 * <ol>
	 *   <li>Ensures the kernel is compiled ({@link #confirmLoad()})</li>
	 *   <li>Retrieves output argument index and offset from instruction manager</li>
	 *   <li>Dispatches the kernel via {@link AcceleratedOperation#apply(MemoryBank, Object[])}</li>
	 *   <li>Waits for kernel completion</li>
	 *   <li>Extracts result from output argument via {@link #postProcessOutput}</li>
	 *   <li>Validates result (checks for NaN if monitoring enabled)</li>
	 * </ol>
	 *
	 * @param args The input arguments for the computation
	 * @return The evaluated result of type T
	 * @throws HardwareException if kernel execution fails
	 */
	@Override
	public T evaluate(Object... args) {
		confirmLoad();

		int outputArgIndex = getInstructionSetManager().getOutputArgumentIndex(getExecutionKey());
		int offset = getInstructionSetManager().getOutputOffset(getExecutionKey());

		try {
			AcceleratedProcessDetails process = apply(null, args);
			waitFor(process.getSemaphore());

			T result = postProcessOutput((MemoryData) process.getOriginalArguments()[outputArgIndex], offset);
			return validate(result);
		} catch (HardwareException e) {
			throw new HardwareException("Failed to evaluate " + getName(), e);
		}
	}

	/**
	 * Requests asynchronous evaluation that pushes results to the downstream consumer.
	 *
	 * <p>Dispatches the kernel without blocking, registering a callback that extracts the
	 * result and pushes it to {@link #downstream} upon completion. This enables non-blocking
	 * streaming pipelines.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * evaluable.setDownstream(result -> processResult(result));
	 * evaluable.request(args);  // Non-blocking, result sent to downstream
	 * }</pre>
	 *
	 * @param args The input arguments for the computation
	 * @throws NullPointerException if {@link #downstream} is not set
	 */
	@Override
	public void request(Object[] args) {
		confirmLoad();

		int outputArgIndex = getInstructionSetManager().getOutputArgumentIndex(getExecutionKey());
		int offset = getInstructionSetManager().getOutputOffset(getExecutionKey());

		AcceleratedProcessDetails process = apply(null, args);
		process.getSemaphore().onComplete(() -> {
			T result = postProcessOutput((MemoryData) process.getOriginalArguments()[outputArgIndex], offset);
			downstream.accept(validate(result));
		});
	}

	/**
	 * Sets the downstream consumer for streaming evaluation results.
	 *
	 * <p>Once set, {@link #request(Object[])} will push results to this consumer
	 * upon completion. The downstream can only be set once per evaluable instance.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * evaluable.setDownstream(result -> {
	 *     // Process result asynchronously
	 *     logger.info("Received: " + result);
	 * });
	 * }</pre>
	 *
	 * @param consumer The consumer to receive evaluation results
	 * @throws UnsupportedOperationException if downstream is already set
	 */
	@Override
	public void setDownstream(Consumer<T> consumer) {
		if (downstream != null) {
			throw new UnsupportedOperationException();
		}

		this.downstream = consumer;
	}

	/**
	 * Returns this evaluable configured for asynchronous execution.
	 *
	 * <p>Since {@link AcceleratedComputationEvaluable} already supports asynchronous
	 * execution via {@link #request(Object[])}, this method simply returns {@code this}.
	 * The provided executor is not used, as hardware kernel dispatch is already
	 * non-blocking.</p>
	 *
	 * @param executor The executor (ignored, hardware dispatch is inherently async)
	 * @return This evaluable instance
	 */
	@Override
	public StreamingEvaluable<T> async(Executor executor) {
		return this;
	}

	/**
	 * Validates the evaluation result and logs warnings for invalid values.
	 *
	 * <p>When {@code outputMonitoring} is enabled, checks the result for NaN values
	 * and logs a warning if any are found. This helps identify numerical stability
	 * issues in computations.</p>
	 *
	 * @param result The result to validate
	 * @return The validated result (unchanged)
	 */
	protected T validate(T result) {
		if (outputMonitoring) {
			int nanCount = result.count(Double::isNaN);

			if (nanCount > 0) {
				warn("Output of " + getName() + " contains " + nanCount + " NaN values");
			}
		}

		return result;
	}

	/**
	 * Post-processes the output memory to ensure correct type.
	 *
	 * <p>As the result of an {@link AcceleratedComputationEvaluable} is not guaranteed to be
	 * of the correct type of {@link MemoryData}, depending on what optimizations
	 * are used during compilation, subclasses can override this method to ensure that the
	 * expected type is returned by the {@link #evaluate(Object...)} method.</p>
	 *
	 * @param output the raw output memory data from kernel execution
	 * @param offset the offset within the output memory
	 * @return the processed output cast to type T
	 */
	protected T postProcessOutput(MemoryData output, int offset) {
		return (T) output;
	}
}
