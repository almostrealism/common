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

package org.almostrealism.hardware.computations;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArgumentList;
import io.almostrealism.streams.StreamingEvaluable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Wrapper {@link Evaluable} that provides hardware-accelerated execution with optional short-circuit evaluation.
 *
 * <p>{@link HardwareEvaluable} serves as the primary execution wrapper for compiled hardware operations.
 * It manages:</p>
 * <ul>
 *   <li><strong>Kernel execution:</strong> Context-specific compiled operations</li>
 *   <li><strong>Short-circuit evaluation:</strong> Alternative CPU-based evaluation path</li>
 *   <li><strong>Destination routing:</strong> Direct-to-memory evaluation via {@link DestinationEvaluable}</li>
 *   <li><strong>Streaming support:</strong> Async evaluation with downstream consumers</li>
 * </ul>
 *
 * <h2>Dual Execution Paths</h2>
 *
 * <p>{@link HardwareEvaluable} supports two evaluation strategies:</p>
 *
 * <pre>{@code
 * // 1. Kernel execution (GPU/native code)
 * Evaluable<Matrix> kernel = compiledOperation;
 *
 * // 2. Short-circuit (pure CPU evaluation)
 * Evaluable<Matrix> shortCircuit = cpuFallback;
 *
 * HardwareEvaluable<Matrix> evaluable =
 *     new HardwareEvaluable<>(() -> kernel, destination, shortCircuit, true);
 *
 * // Uses short-circuit if available, otherwise kernel
 * Matrix result = evaluable.evaluate();
 * }</pre>
 *
 * <h2>Context-Specific Kernels</h2>
 *
 * <p>Kernels are wrapped in {@link ContextSpecific} for thread-local compilation caching:</p>
 *
 * <pre>{@code
 * // Different threads may have different compiled versions
 * ContextSpecific<Evaluable<T>> kernel = evaluable.getKernel();
 *
 * // Thread A: Gets OpenCL kernel
 * Evaluable<T> kernelA = kernel.getValue();
 *
 * // Thread B: Gets JNI kernel
 * Evaluable<T> kernelB = kernel.getValue();
 * }</pre>
 *
 * <h2>Destination-Based Evaluation</h2>
 *
 * <p>When used with {@link MemoryBank}, routes output directly to pre-allocated memory:</p>
 *
 * <pre>{@code
 * MemoryBank destination = new MemoryBank(1024);
 *
 * // Create evaluable with destination
 * Evaluable<Matrix> withDest = evaluable.withDestination(destination);
 *
 * // Evaluate directly into destination
 * withDest.evaluate();  // Writes to destination
 *
 * // Or use into() method
 * evaluable.into(destination).evaluate();
 * }</pre>
 *
 * <h2>Destination Processor</h2>
 *
 * <p>Optional transformation of destination before evaluation:</p>
 *
 * <pre>{@code
 * evaluable.setDestinationProcessor(bank -> {
 *     // Transform destination (e.g., select subset)
 *     return bank.range(0, 512);
 * });
 *
 * // Processor is applied before writing
 * evaluable.withDestination(fullBank).evaluate();
 * }</pre>
 *
 * <h2>Streaming Evaluation</h2>
 *
 * <p>Supports async execution with downstream consumers:</p>
 *
 * <pre>{@code
 * HardwareEvaluable<Matrix> evaluable = ...;
 *
 * // Set downstream consumer
 * evaluable.setDownstream(result -> {
 *     System.out.println("Result: " + result);
 * });
 *
 * // Async request
 * evaluable.request(args);  // Calls downstream when done
 *
 * // Or create async variant
 * StreamingEvaluable<Matrix> async = evaluable.async(executor);
 * }</pre>
 *
 * <h2>Short-Circuit Use Cases</h2>
 *
 * <p>Short-circuit evaluation is useful for:</p>
 * <ul>
 *   <li><strong>Small data:</strong> CPU evaluation faster than kernel launch overhead</li>
 *   <li><strong>Debugging:</strong> CPU-based evaluation easier to inspect</li>
 *   <li><strong>Fallback:</strong> When hardware acceleration unavailable</li>
 * </ul>
 *
 * <h2>Resource Management</h2>
 *
 * <p>Destroying releases the context-specific kernel cache:</p>
 *
 * <pre>{@code
 * evaluable.destroy();
 * // Releases all thread-local compiled kernels
 * }</pre>
 *
 * <h2>ArgumentList Integration</h2>
 *
 * <p>Implements {@link ArgumentList} to expose operation structure:</p>
 *
 * <pre>{@code
 * int argCount = evaluable.getArgsCount();
 * Collection<Argument<? extends T>> children = evaluable.getChildren();
 * }</pre>
 *
 * @param <T> The type of value produced by evaluation
 * @see DestinationEvaluable
 * @see ContextSpecific
 * @see StreamingEvaluable
 * @author Michael Murray
 */
public class HardwareEvaluable<T> implements
		Evaluable<T>, StreamingEvaluable<T>, Destroyable, Runnable, ArgumentList<T> {
	/** Supplier that produces the underlying {@link Evaluable} for this computation. */
	private Supplier<Evaluable<T>> ev;
	/** Evaluable used to allocate and provide the output destination buffer. */
	private Evaluable<T> destination;
	/** If set, this evaluable is used instead of the main computation (short-circuit path). */
	private Evaluable<T> shortCircuit;

	/** True if this evaluable runs as a GPU/hardware kernel rather than sequential CPU logic. */
	private boolean isKernel;
	/** Context-specific wrapper that manages per-context lifecycle for the underlying evaluable. */
	private ContextSpecific<Evaluable<T>> kernel;

	/** Optional post-processor applied to the output destination before kernel dispatch. */
	private UnaryOperator<MemoryBank<?>> destinationProcessor;

	/** Optional executor for async dispatch of the underlying evaluable. */
	private Executor executor;
	/** Optional consumer called with the result of each evaluation. */
	private Consumer<T> downstream;

	/**
	 * Creates a hardware evaluable without an executor.
	 *
	 * @param ev          Supplier for the underlying evaluable
	 * @param destination Evaluable used to create the output destination
	 * @param shortCircuit If non-null, used instead of {@code ev} for evaluation
	 * @param kernel      True if this should execute as a hardware kernel
	 */
	public HardwareEvaluable(Supplier<Evaluable<T>> ev,
							 Evaluable<T> destination, Evaluable<T> shortCircuit,
							 boolean kernel) {
		this(ev, destination, shortCircuit, kernel, null);
	}

	/**
	 * Creates a hardware evaluable with an optional executor.
	 *
	 * @param ev          Supplier for the underlying evaluable
	 * @param destination Evaluable used to create the output destination
	 * @param shortCircuit If non-null, used instead of {@code ev} for evaluation
	 * @param kernel      True if this should execute as a hardware kernel
	 * @param executor    Optional executor for async dispatch
	 */
	public HardwareEvaluable(Supplier<Evaluable<T>> ev,
							 Evaluable<T> destination, Evaluable<T> shortCircuit,
							 boolean kernel, Executor executor) {
		this.ev = ev;
		this.destination = destination;
		this.shortCircuit = shortCircuit;
		this.isKernel = kernel;
		this.kernel = new DefaultContextSpecific<>(() -> ev.get(), Destroyable::destroy);
		this.executor = executor;
	}

	/**
	 * Replaces the underlying evaluable supplier with the given one.
	 *
	 * @param ev New evaluable supplier to use for future evaluations
	 */
	public void setEvaluable(Supplier<Evaluable<T>> ev) { this.ev = ev; }

	/**
	 * Returns the evaluable used to create output destinations.
	 *
	 * @return Destination evaluable, or null if not set
	 */
	public Evaluable<T> getDestination() { return destination; }

	/**
	 * Sets the evaluable used to create output destinations.
	 *
	 * @param destination Destination evaluable
	 */
	public void setDestination(Evaluable<T> destination) {
		this.destination = destination;
	}

	/**
	 * Returns the short-circuit evaluable used instead of the main computation.
	 *
	 * @return Short-circuit evaluable, or null if the main computation is used
	 */
	public Evaluable<T> getShortCircuit() { return shortCircuit; }

	/**
	 * Sets a short-circuit evaluable that replaces the main computation during evaluation.
	 *
	 * @param shortCircuit Evaluable to use instead of the main computation
	 */
	public void setShortCircuit(Evaluable<T> shortCircuit) {
		this.shortCircuit = shortCircuit;
	}

	@Override
	public boolean isConstant() {
		return getKernel().getValue().isConstant();
	}

	/**
	 * Returns true if this evaluable executes as a GPU/hardware kernel.
	 *
	 * @return True if kernel-based execution is used
	 */
	public boolean isKernel() { return isKernel; }

	/**
	 * Returns the destination processor that transforms the output memory bank before dispatch.
	 *
	 * @return Destination processor, or null if none is configured
	 */
	public UnaryOperator<MemoryBank<?>> getDestinationProcessor() {
		return destinationProcessor;
	}

	/**
	 * Sets a function to post-process the destination memory bank before kernel dispatch.
	 *
	 * @param destinationProcessor Function applied to the destination before dispatch
	 */
	public void setDestinationProcessor(UnaryOperator<MemoryBank<?>> destinationProcessor) {
		this.destinationProcessor = destinationProcessor;
	}

	@Override
	public Evaluable into(Object destination) {
		return withDestination((MemoryBank) destination);
	}

	/**
	 * Returns an evaluable that writes its results into the given destination memory bank.
	 *
	 * @param destination Memory bank to write results into
	 * @return Evaluable targeting the specified destination
	 */
	public Evaluable<T> withDestination(MemoryBank destination) {
		if (destinationProcessor != null) {
			destination = destinationProcessor.apply(destination);
		}

		Evaluable ev = getKernel().getValue();
		if (ev instanceof HardwareEvaluable<?>) {
			return ((HardwareEvaluable) ev).withDestination(destination);
		}

		return new DestinationEvaluable<>(ev, destination);
	}

	public ContextSpecific<Evaluable<T>> getKernel() { return kernel; }

	@Override
	public Multiple<T> createDestination(int size) {
		return destination == null ? getKernel().getValue().createDestination(size) : destination.createDestination(size);
	}

	@Override
	public void run() { evaluate(); }

	@Override
	public T evaluate(Object... args) {
		if (Arrays.stream(args).anyMatch(i -> i instanceof Object[])) {
			throw new IllegalArgumentException("Embedded array provided to evaluate");
		}

		return shortCircuit == null ? getKernel().getValue().evaluate(args) : shortCircuit.evaluate(args);
	}

	@Override
	public void request(Object[] args) {
		if (Arrays.stream(args).anyMatch(i -> i instanceof Object[])) {
			throw new IllegalArgumentException("Embedded array provided to request");
		}

		if (shortCircuit != null) {
			downstream.accept(shortCircuit.evaluate(args));
			return;
		}

		Evaluable<T> cev = getKernel().getValue();
		if (cev instanceof StreamingEvaluable<?>) {
			((StreamingEvaluable<T>) cev).setDownstream(downstream);
			((StreamingEvaluable<T>) cev).request(args);
		}

		throw new UnsupportedOperationException();
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
		return new HardwareEvaluable<>(ev, destination, shortCircuit, isKernel, executor);
	}

	@Override
	public int getArgsCount() {
		return ((ArgumentList) getKernel().getValue()).getArgsCount();
	}

	@Override
	public Collection<Argument<? extends T>> getChildren() {
		Evaluable ev = getKernel().getValue();

		if (ev instanceof ArgumentList) {
			return ((ArgumentList) ev).getChildren();
		}

		return Collections.emptyList();
	}

	@Override
	public void destroy() {
		Destroyable.destroy(kernel);
	}
}
