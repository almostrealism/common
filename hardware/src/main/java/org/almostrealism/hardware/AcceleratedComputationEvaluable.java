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
 * standard way to execute {@link ProducerComputation} instances (created via {@link Producer#get()}) on
 * hardware accelerators.</p>
 *
 * <h2>Evaluation Pattern</h2>
 *
 * <p>The typical usage pattern involves creating a {@link Producer}, getting its {@link Evaluable},
 * and invoking {@link #evaluate(Object...)}:</p>
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
	public static boolean enableRedundantCompilation = true;

	private IntFunction<Multiple<T>> destinationFactory;
	private Consumer<T> downstream;

	public AcceleratedComputationEvaluable(ComputeContext<MemoryData> context, Computation<T> c) {
		super(context, c, true);
	}

	@Override
	public ProducerComputation<T> getComputation() {
		return (ProducerComputation<T>) super.getComputation();
	}

	@Override
	public boolean isConstant() { return getComputation().isConstant(); }

	public IntFunction<Multiple<T>> getDestinationFactory() {
		return destinationFactory;
	}

	public void setDestinationFactory(IntFunction<Multiple<T>> destinationFactory) {
		this.destinationFactory = destinationFactory;
	}

	@Override
	public Multiple<T> createDestination(int size) {
		if (getDestinationFactory() == null) {
			return Evaluable.super.createDestination(size);
		}

		return getDestinationFactory().apply(size);
	}

	@Override
	public Evaluable<T> into(Object destination) {
		return new DestinationEvaluable(this, (MemoryBank) destination);
	}

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

	@Override
	public void setDownstream(Consumer<T> consumer) {
		if (downstream != null) {
			throw new UnsupportedOperationException();
		}

		this.downstream = consumer;
	}

	@Override
	public StreamingEvaluable<T> async(Executor executor) {
		return this;
	}

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
	 * As the result of an {@link AcceleratedComputationEvaluable} is not guaranteed to be
	 * of the correct type of {@link MemoryData}, depending on what optimizations
	 * are used during compilation, subclasses can override this method to ensure that the
	 * expected type is returned by the {@link #evaluate(Object...)} method.
	 */
	protected T postProcessOutput(MemoryData output, int offset) {
		return (T) output;
	}
}
