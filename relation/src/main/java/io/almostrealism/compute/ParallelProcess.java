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

package io.almostrealism.compute;

import io.almostrealism.relation.Countable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link Process} that executes operations in parallel across multiple elements.
 *
 * <p>{@code ParallelProcess} extends the basic {@link Process} interface with support for
 * parallel execution. It implements {@link Countable} to express the degree of parallelism
 * available, which typically corresponds to the number of independent work items that
 * can be processed concurrently (e.g., elements in a vector, pixels in an image, or
 * batched operations in machine learning).</p>
 *
 * <h2>Parallelism Model</h2>
 * <p>The parallelism of a process represents the number of independent execution units.
 * For GPU kernels, this corresponds to the number of work items or threads. For CPU
 * execution, it represents the degree of SIMD or thread-based parallelism available.</p>
 *
 * <p>Key properties:</p>
 * <ul>
 *   <li>{@link #getParallelism()} - Returns the parallelism count (delegates to {@link Countable#getCountLong()})</li>
 *   <li>{@link #isUniform()} - Whether all children have the same parallelism</li>
 *   <li>{@link #isFixedCount()} - From {@link Countable}, whether parallelism is fixed or variable</li>
 * </ul>
 *
 * <h2>Optimization</h2>
 * <p>Parallel processes participate in tree optimization to maximize execution efficiency.
 * The {@link #optimize(ProcessContext)} method recursively optimizes child processes and
 * then applies the context's {@link ProcessOptimizationStrategy} to determine whether
 * children should be isolated for independent execution.</p>
 *
 * <p>The optimization process:</p>
 * <ol>
 *   <li>Creates a {@link ParallelProcessContext} capturing current parallelism state</li>
 *   <li>Recursively optimizes each child process</li>
 *   <li>Applies isolation based on explicit targets or strategy decisions</li>
 *   <li>Delegates to the optimization strategy for final tree restructuring</li>
 * </ol>
 *
 * <h2>Isolation Flags</h2>
 * <p>The static {@link #isolationFlags} list allows flagging specific process types
 * for isolation during debugging or performance tuning. When a child process matches
 * any predicate in this list, it will be logged and potentially isolated.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Check parallelism of a process
 * long parallelism = ParallelProcess.parallelism(someProcess);
 *
 * // Optimize a parallel process tree
 * ParallelProcess<?, ?> optimized = parallelProcess.optimize();
 *
 * // Check if children have uniform parallelism
 * if (parallelProcess.isUniform()) {
 *     // Can use optimized uniform execution path
 * }
 * }</pre>
 *
 * @param <P> the type of child processes in the tree
 * @param <T> the result type produced by this process
 *
 * @see Process
 * @see Countable
 * @see ParallelProcessContext
 * @see ProcessOptimizationStrategy
 *
 * @author Michael Murray
 */
public interface ParallelProcess<P extends Process<?, ?>, T> extends Process<P, T>, Countable {
	/**
	 * Predicates for flagging specific processes for isolation during debugging.
	 *
	 * <p>When a process matches any predicate in this list during optimization,
	 * it will be logged. This is useful for debugging optimization behavior
	 * and identifying which processes are being considered for isolation.</p>
	 */
	List<Predicate<Process>> isolationFlags = new ArrayList<>();

	/**
	 * {@inheritDoc}
	 *
	 * @return a new {@code ParallelProcess} with the specified children
	 */
	@Override
	default ParallelProcess<P, T> generate(List<P> children) {
		return (ParallelProcess<P, T>) Process.super.generate(children);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Uses the default base context for optimization.</p>
	 *
	 * @return the optimized parallel process
	 */
	@Override
	default ParallelProcess<P, T> optimize() {
		return (ParallelProcess<P, T>) Process.super.optimize();
	}

	/**
	 * Optimizes a child process within this parallel process's context.
	 *
	 * <p>This method handles the optimization of a single child process, including
	 * the decision of whether to isolate it. The isolation decision is based on:</p>
	 * <ul>
	 *   <li>Explicit isolation targets (if any are configured via {@link Process#explicitIsolationTargets})</li>
	 *   <li>The process's own {@link Process#isIsolationTarget(ProcessContext)} determination</li>
	 * </ul>
	 *
	 * @param ctx     the process context for optimization decisions
	 * @param process the child process to optimize
	 * @return the optimized process, possibly isolated
	 */
	default Process<P, T> optimize(ProcessContext ctx, Process<P, T> process) {
		process = process.optimize(ctx);

		boolean isolate;

		if (Process.isExplicitIsolation()) {
			isolate = Process.isolationPermitted(process);
		} else {
			isolate = process.isIsolationTarget(ctx);
		}

		return isolate ? isolate(process) : process;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>For parallel processes, this checks explicit isolation targets first,
	 * then falls back to the parent implementation.</p>
	 */
	@Override
	default boolean isIsolationTarget(ProcessContext context) {
		if (Process.isExplicitIsolation()) {
			return Process.isolationPermitted(this);
		}

		return Process.super.isIsolationTarget(context);
	}

	/**
	 * Processes the children collection into a stream for analysis.
	 *
	 * <p>Subclasses can override this to filter or transform children before
	 * they are analyzed by optimization strategies. The default implementation
	 * simply streams all children.</p>
	 *
	 * @param children the collection of child processes
	 * @return a stream of processes for analysis
	 */
	default Stream<? extends Process> processChildren(Collection<? extends Process> children) {
		return children.stream();
	}

	/**
	 * Creates a {@link ParallelProcessContext} for optimizing children of this process.
	 *
	 * <p>The created context captures this process's parallelism and aggregation
	 * information, which child optimizations can use for context-aware decisions.</p>
	 *
	 * @param ctx the parent process context
	 * @return a new parallel process context for child optimization
	 */
	default ParallelProcessContext createContext(ProcessContext ctx) {
		return ParallelProcessContext.of(ctx, this);
	}

	/**
	 * Optimizes this parallel process and its children using the given context.
	 *
	 * <p>The optimization process:</p>
	 * <ol>
	 *   <li>Returns immediately if there are no children</li>
	 *   <li>Creates a {@link ParallelProcessContext} capturing current state</li>
	 *   <li>Recursively optimizes each child process</li>
	 *   <li>Checks isolation flags for debugging output</li>
	 *   <li>Delegates to the context's optimization strategy</li>
	 * </ol>
	 *
	 * @param ctx the process context providing optimization strategy and depth
	 * @return the optimized parallel process
	 * @throws UnsupportedOperationException if no optimization strategy is available
	 */
	@Override
	default ParallelProcess<P, T> optimize(ProcessContext ctx) {
		Collection<P> children = getChildren();
		if (children.isEmpty()) return this;

		ParallelProcessContext context = createContext(ctx);
		children = children.stream().map(process -> (P) optimize(context, (Process) process)).collect(Collectors.toList());

		if (!isolationFlags.isEmpty()) {
			if (children.stream()
					.map(c ->
							isolationFlags.stream().map(p -> p.test(c))
									.reduce(false, (a, b) -> a | b))
					.anyMatch(v -> v)) {
				System.out.println("ParallelProcess: Flagged for isolation");
			}
		}

		ProcessOptimizationStrategy strategy = context.getOptimizationStrategy();

		if (strategy != null) {
			return (ParallelProcess)
					strategy.optimize(context, (Process) this, children,
							c -> processChildren(c).map(p -> (P) p));
		}

		throw new UnsupportedOperationException();

//		long counts[] = processChildren(children).mapToLong(ParallelProcess::parallelism).toArray();
//		long cn = getCountLong();
//		long p = counts.length;
//		long tot = LongStream.of(counts).sum();
//		long max = LongStream.of(counts).max().orElse(0);
//
//		long memory[] = processChildren(children).mapToLong(Process::outputSize).filter(i -> i > 0).toArray();
//		long mem = getOutputSize();
//		long maxMem = LongStream.of(memory).max().orElse(0);
//
//		double currentScore = ParallelismSettings.score(cn, mem);
//		double altScore = ParallelismSettings
//				.scores(processChildren(children))
//				.max().orElse(Integer.MIN_VALUE);
//
//		double min = Math.min(currentScore, altScore);
//		if (min < 0) {
//			min = Math.abs(min);
//			currentScore += min;
//			altScore += min;
//			currentScore++;
//			altScore++;
//		}
//
//		boolean isolate = true;
//
//		if ((p <= 1 && tot == cn) || cn >= max) {
//			isolate = false;
//		} else if (enableContextualCount && max <= context.getCountLong()) {
//			isolate = false;
//		} else if (max > maxCount) {
//			if (cn < minCount && context.getCountLong() < minCount) {
//				System.out.println("WARN: Count " + max + " is too high to isolate, " +
//						"but the resulting process will have a count of only " + cn +
//						" (ctx " + context.getCountLong() + ")");
//			}
//
//			isolate = false;
//		} else if (enableNarrowMax && max > targetCount && context.getCountLong() >= minCount) {
//			isolate = false;
//		} else if (altScore < currentScore) {
//			System.out.println("Skipping isolation to avoid score " +
//					altScore + " (" + currentScore + " current)");
//			isolate = false;
//		}
//
//		if (isolate && currentScore / altScore > 4 && explicitIsolationTargets.isEmpty()) {
//			System.out.println("Isolation is " + (currentScore / altScore) + " times worse - skipping");
//			isolate = false;
//		}
//
//		ParallelProcess<P, T> result;
//
//		if (isolate) {
//			result = generate(children.stream().map(c -> (P) isolate((Process) c)).collect(Collectors.toList()));
//		} else {
//			result = generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
//		}
//
//		return result;
	}

	/**
	 * Returns the parallelism count for this process.
	 *
	 * <p>This is the number of independent work items that can be processed
	 * concurrently. For most implementations, this delegates to
	 * {@link Countable#getCountLong()}.</p>
	 *
	 * @return the parallelism count
	 */
	default long getParallelism() {
		return getCountLong();
	}

	/**
	 * Checks whether all children have the same parallelism.
	 *
	 * <p>Uniform parallelism enables certain optimizations where all child
	 * processes can be executed with the same kernel configuration.</p>
	 *
	 * @return {@code true} if all children have identical parallelism counts
	 */
	default boolean isUniform() {
		long p = getChildren().stream().mapToLong(ParallelProcess::parallelism).distinct().count();
		return p == 1;
	}

	/**
	 * Returns the parallelism of an arbitrary object.
	 *
	 * <p>This utility method safely extracts parallelism from any object:</p>
	 * <ul>
	 *   <li>If the object is a {@code ParallelProcess}, returns its parallelism</li>
	 *   <li>Otherwise, returns 1 (sequential execution)</li>
	 * </ul>
	 *
	 * @param <T> the type of the object
	 * @param c   the object to check
	 * @return the parallelism count, or 1 if not a parallel process
	 */
	static <T> long parallelism(T c) {
		if (c instanceof ParallelProcess) {
			return ((ParallelProcess) c).getParallelism();
		}

		return 1;
	}
}
