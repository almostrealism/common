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

package io.almostrealism.compute;

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A process optimization strategy that prevents aggregation process trees from becoming too deep.
 * <p>
 * This strategy addresses performance concerns that arise when process trees beneath aggregation
 * points grow excessively deep. Deep trees can lead to stack overflow issues, increased latency,
 * and reduced parallelization efficiency.
 * </p>
 * <p>
 * The optimization is applied opportunistically when the following conditions are met:
 * <ul>
 *   <li>The aggregation count exceeds {@value #AGGREGATION_THRESHOLD}</li>
 *   <li>The tree depth exceeds the configured limit (default: 12)</li>
 *   <li>All child processes have parallelism >= {@value #PARALLELISM_THRESHOLD}</li>
 * </ul>
 * </p>
 * <p>
 * When triggered, this strategy isolates children of the process to flatten the tree structure,
 * but only when doing so will not create a parallelization bottleneck (hence the parallelism check).
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Use default depth limit of 12
 * ProcessOptimizationStrategy strategy = new AggregationDepthTargetOptimization();
 *
 * // Or specify a custom depth limit
 * ProcessOptimizationStrategy strategy = new AggregationDepthTargetOptimization(8);
 * }</pre>
 *
 * @see ProcessOptimizationStrategy
 * @see ParallelProcessContext
 * @see Process
 */
public class AggregationDepthTargetOptimization implements ProcessOptimizationStrategy {
	/**
	 * The minimum aggregation count required before this optimization is considered.
	 * When the aggregation count is below this threshold, the optimization is skipped
	 * to avoid unnecessary overhead for small aggregations.
	 */
	public static final long AGGREGATION_THRESHOLD = 64;

	/**
	 * The minimum parallelism required for each child process.
	 * Children with parallelism below this threshold would create bottlenecks
	 * if isolated, so the optimization is not applied in such cases.
	 */
	public static final long PARALLELISM_THRESHOLD = 128;

	/** The maximum allowed tree depth before optimization is triggered. */
	private final int limit;

	/**
	 * Constructs an aggregation depth optimization with the default depth limit of 12.
	 */
	public AggregationDepthTargetOptimization() { this(12); }

	/**
	 * Constructs an aggregation depth optimization with a custom depth limit.
	 *
	 * @param depthLimit the maximum tree depth before optimization is triggered
	 */
	public AggregationDepthTargetOptimization(int depthLimit) {
		this.limit = depthLimit;
	}

	/**
	 * Attempts to optimize a process by flattening deep aggregation trees.
	 * <p>
	 * This method checks whether the optimization conditions are met and, if so,
	 * generates an isolated version of the process to reduce tree depth. The
	 * conditions are:
	 * <ol>
	 *   <li>The aggregation count must exceed {@value #AGGREGATION_THRESHOLD}</li>
	 *   <li>The current depth must exceed the configured limit</li>
	 *   <li>All child processes must have parallelism >= {@value #PARALLELISM_THRESHOLD}</li>
	 * </ol>
	 * </p>
	 *
	 * @param <P>            the type of child processes
	 * @param <T>            the result type of the process
	 * @param ctx            the process context containing depth and aggregation information
	 * @param parent         the parent process being optimized
	 * @param children       the collection of child processes
	 * @param childProcessor a function to process and stream the children
	 * @return an optimized process if conditions are met, or null if no optimization is applied
	 */
	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		ParallelProcessContext pctx = ParallelProcessContext.of(ctx);

		long c = childProcessor.apply(children).mapToLong(ParallelProcess::parallelism)
				.filter(p -> p < PARALLELISM_THRESHOLD).count();

		if (c == 0 && pctx.getAggregationCount() > AGGREGATION_THRESHOLD && pctx.getDepth() > limit) {
			return generate(parent, children, true);
		}

		return null;
	}
}
