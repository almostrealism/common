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

package io.almostrealism.compute;

import io.almostrealism.relation.Node;
import io.almostrealism.relation.Tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The fundamental abstraction for composable, optimizable computational work.
 *
 * <p>A {@link Process} represents a unit of work that can be composed with other processes
 * to form a computational tree. Each process is a {@link Supplier} that produces a result,
 * and may depend on other processes forming a hierarchical {@link Tree} structure that can
 * be optimized and orchestrated for efficient execution.</p>
 *
 * <h2>Core Concepts</h2>
 *
 * <h3>Process Trees</h3>
 * <p>Processes form trees where each process may have child processes. The children
 * represent dependencies that must complete before the parent can produce its result.
 * These trees can be restructured through optimization to improve execution efficiency.</p>
 *
 * <h3>Optimization</h3>
 * <p>The {@link #optimize(ProcessContext)} method transforms a process tree to improve
 * execution characteristics. Optimization strategies analyze parallelism, memory usage,
 * and tree structure to determine optimal restructuring. See {@link ProcessOptimizationStrategy}.</p>
 *
 * <h3>Isolation</h3>
 * <p>Process isolation wraps a process to force independent execution. When a process
 * is isolated, it executes separately from its parent rather than being inlined.
 * Isolation decisions are controlled by:</p>
 * <ul>
 *   <li>{@link #explicitIsolationTargets} - Predicates that explicitly mark processes for isolation</li>
 *   <li>{@link #isIsolationTarget(ProcessContext)} - Per-process isolation determination</li>
 *   <li>Optimization strategies that analyze tree structure</li>
 * </ul>
 *
 * <h2>Key Methods</h2>
 * <ul>
 *   <li>{@link #get()} - Produces the result of this process (from {@link Supplier})</li>
 *   <li>{@link #getChildren()} - Returns child processes (from {@link Tree})</li>
 *   <li>{@link #optimize(ProcessContext)} - Optimizes this process and its children</li>
 *   <li>{@link #isolate()} - Creates an isolated version of this process</li>
 *   <li>{@link #getOutputSize()} - Returns memory footprint for optimization decisions</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link ParallelProcess} - Extends Process with parallelism support</li>
 *   <li>{@code Operator} - Process that produces an {@code Evaluable}</li>
 *   <li>{@code OperationComputation} - Process that produces a {@code Runnable}</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Optimize a process tree
 * Process<?, ?> optimized = process.optimize();
 *
 * // Execute the process
 * Object result = optimized.get();
 *
 * // Check output size for memory considerations
 * long size = process.getOutputSize();
 *
 * // Create an isolated process
 * Process<?, ?> isolated = process.isolate();
 * }</pre>
 *
 * @param <P> the type of child processes in this process tree
 * @param <T> the type of result produced by this process; may be a direct value
 *            or a mechanism (like {@code Evaluable} or {@code Runnable}) for
 *            producing the ultimate result
 *
 * @see ParallelProcess
 * @see ProcessContext
 * @see ProcessOptimizationStrategy
 * @see Tree
 *
 * @author Michael Murray
 */
public interface Process<P extends Process<?, ?>, T> extends Node, Supplier<T>, Tree<P> {
	/**
	 * Predicates that explicitly mark processes for isolation.
	 *
	 * <p>When this list is non-empty, only processes matching at least one predicate
	 * will be isolated. This provides fine-grained control over isolation during
	 * debugging or performance tuning.</p>
	 *
	 * @see #isolationPermitted(Supplier)
	 * @see #isExplicitIsolation()
	 */
	List<Predicate<Process>> explicitIsolationTargets = new ArrayList<>();

	/**
	 * {@inheritDoc}
	 *
	 * <p>Creates a new process with the specified children, preserving this
	 * process's behavior but with different dependencies.</p>
	 *
	 * @param children the new child processes
	 * @return a new process with the specified children
	 */
	@Override
	default Process<P, T> generate(List<P> children) {
		return (Process<P, T>) Tree.super.generate(children);
	}

	/**
	 * Optimizes this process using the default base context.
	 *
	 * <p>This is a convenience method equivalent to calling
	 * {@code optimize(ProcessContext.base())}.</p>
	 *
	 * @return the optimized process
	 * @see #optimize(ProcessContext)
	 * @see ProcessContext#base()
	 */
	default Process<P, T> optimize() { return optimize(ProcessContext.base()); }

	/**
	 * Optimizes this process and its children using the given context.
	 *
	 * <p>Optimization may restructure the process tree to improve execution
	 * efficiency. The default implementation returns {@code this} unchanged;
	 * subclasses like {@link ParallelProcess} override this to perform actual
	 * optimization using the context's {@link ProcessOptimizationStrategy}.</p>
	 *
	 * @param context the process context providing optimization strategy and state
	 * @return the optimized process, which may be this process or a restructured version
	 * @see ProcessOptimizationStrategy
	 */
	default Process<P, T> optimize(ProcessContext context) {
		return this;
	}

	/**
	 * Creates an isolated version of this process.
	 *
	 * <p>An isolated process executes independently from its parent rather than
	 * being inlined. The default implementation wraps this process using
	 * {@link #of(Supplier)}.</p>
	 *
	 * @return an isolated wrapper around this process
	 * @see #isolate(Process)
	 */
	default Process<P, T> isolate() {
		return Process.of(this);
	}

	/**
	 * Isolates the given process if isolation is permitted.
	 *
	 * <p>This helper method checks {@link #isolationPermitted(Supplier)} before
	 * isolating, respecting explicit isolation targets.</p>
	 *
	 * @param process the process to potentially isolate
	 * @return the isolated process if permitted, or the original process
	 */
	default Process<P, T> isolate(Process<P, T> process) {
		return Process.isolationPermitted(process) ? process.isolate() : process;
	}

	/**
	 * Determines whether this process should be isolated in the given context.
	 *
	 * <p>The default implementation returns {@code false}. Subclasses can override
	 * this to implement context-sensitive isolation logic.</p>
	 *
	 * @param context the process context
	 * @return {@code true} if this process should be isolated, {@code false} otherwise
	 */
	default boolean isIsolationTarget(ProcessContext context) {
		return false;
	}

	/**
	 * Returns the output size (memory footprint) of this process.
	 *
	 * <p>The output size is used by optimization strategies to evaluate the
	 * memory cost of different process configurations. Larger output sizes
	 * may cause more aggressive isolation to reduce memory pressure.</p>
	 *
	 * <p>The default implementation returns 0. Subclasses should override
	 * this to provide accurate size information.</p>
	 *
	 * @return the output size in elements, or 0 if unknown
	 * @see ParallelismSettings#memoryCost(long)
	 */
	default long getOutputSize() {
		return 0;
	}

	/**
	 * Extracts the output size from an arbitrary object.
	 *
	 * <p>This utility method safely extracts output size from any object:</p>
	 * <ul>
	 *   <li>If the object is a {@code Process}, returns its output size</li>
	 *   <li>Otherwise, returns 0</li>
	 * </ul>
	 *
	 * @param <T> the type of the object
	 * @param c   the object to check
	 * @return the output size, or 0 if not a process
	 */
	static <T> long outputSize(T c) {
		if (c instanceof Process) {
			return ((Process<?, T>) c).getOutputSize();
		}

		return 0;
	}

	/**
	 * Creates a process wrapper around a supplier.
	 *
	 * <p>This factory method creates an anonymous process that delegates to the
	 * given supplier. If the supplier is itself a process, its children and
	 * output size are preserved.</p>
	 *
	 * @param <P>      the type of child processes
	 * @param <T>      the result type
	 * @param supplier the supplier to wrap
	 * @return a process that delegates to the supplier
	 */
	static <P extends Process<?, ?>, T> Process<P, T> of(Supplier<T> supplier) {
		return new Process<>() {
			@Override
			public Collection<P> getChildren() {
				return supplier instanceof Process ?
						((Process<P, T>) supplier).getChildren() : Collections.emptyList();
			}

			@Override
			public T get() {
				return supplier.get();
			}

			@Override
			public long getOutputSize() {
				return supplier instanceof Process ?
						((Process<P, T>) supplier).getOutputSize() :
						Process.super.getOutputSize();
			}
		};
	}

	/**
	 * Returns an optimized version of a supplier if it is a process.
	 *
	 * <p>If the supplier is a {@code Process}, calls its {@link #optimize()} method.
	 * Otherwise, returns the supplier unchanged.</p>
	 *
	 * @param <T>     the result type
	 * @param <P>     the supplier type
	 * @param process the supplier to potentially optimize
	 * @return the optimized process, or the original supplier if not a process
	 */
	static <T, P extends Supplier<T>> Supplier<T> optimized(P process) {
		if (process instanceof Process) {
			return ((Process<?, T>) process).optimize();
		} else {
			return process;
		}
	}

	/**
	 * Returns an isolated version of a supplier if permitted.
	 *
	 * <p>If the supplier is not a process, wraps it using {@link #of(Supplier)}.
	 * If it is a process and isolation is permitted, calls {@link #isolate()}.
	 * Otherwise, returns the supplier unchanged.</p>
	 *
	 * @param <T>     the result type
	 * @param <P>     the supplier type
	 * @param process the supplier to potentially isolate
	 * @return the isolated or wrapped process
	 * @see #isolationPermitted(Supplier)
	 */
	static <T, P extends Supplier<T>> Supplier<T> isolated(P process) {
		if (!(process instanceof Process)) {
			return Process.of(process);
		}

		if (isolationPermitted(process)) {
			return ((Process<?, T>) process).isolate();
		}

		return process;
	}

	/**
	 * Checks whether isolation is permitted for the given supplier.
	 *
	 * <p>When explicit isolation targets are configured, only processes matching
	 * at least one predicate in {@link #explicitIsolationTargets} are permitted
	 * to be isolated. When no explicit targets are configured, isolation is
	 * always permitted.</p>
	 *
	 * @param <T>     the result type
	 * @param <P>     the supplier type
	 * @param process the supplier to check
	 * @return {@code true} if isolation is permitted, {@code false} otherwise
	 * @see #explicitIsolationTargets
	 * @see #isExplicitIsolation()
	 */
	static <T, P extends Supplier<T>> boolean isolationPermitted(P process) {
		return !isExplicitIsolation() ||
				explicitIsolationTargets.stream().anyMatch(p -> p.test((Process) process));
	}

	/**
	 * Checks whether explicit isolation targeting is enabled.
	 *
	 * <p>Returns {@code true} if {@link #explicitIsolationTargets} contains any
	 * predicates, indicating that isolation should be restricted to matching
	 * processes only.</p>
	 *
	 * @return {@code true} if explicit isolation is enabled, {@code false} otherwise
	 */
	static boolean isExplicitIsolation() {
		return !explicitIsolationTargets.isEmpty();
	}
}
