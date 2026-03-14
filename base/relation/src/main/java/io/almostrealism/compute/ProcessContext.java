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

package io.almostrealism.compute;

/**
 * Provides contextual information during {@link Process} optimization and execution.
 *
 * <p>{@code ProcessContext} encapsulates the state needed to make optimization decisions
 * when traversing a process tree. It provides access to the current optimization strategy
 * and tracks the depth within the process hierarchy, enabling strategies to make
 * context-aware decisions about process isolation and parallelization.</p>
 *
 * <h2>Context Hierarchy</h2>
 * <p>As processes are optimized recursively, the context depth increases. This allows
 * optimization strategies to behave differently at various levels of the process tree.
 * For example, aggressive isolation might be applied at shallow depths while more
 * conservative strategies are used deeper in the tree to avoid excessive fragmentation.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a base context
 * ProcessContext ctx = ProcessContext.base();
 *
 * // Optimize a process using the context
 * Process<?, ?> optimized = process.optimize(ctx);
 *
 * // Access optimization strategy
 * ProcessOptimizationStrategy strategy = ctx.getOptimizationStrategy();
 * }</pre>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link ProcessContextBase} - Basic implementation with configurable strategy</li>
 *   <li>{@link ParallelProcessContext} - Extended context for parallel processes with
 *       parallelism count and aggregation information</li>
 * </ul>
 *
 * @see ProcessContextBase
 * @see ParallelProcessContext
 * @see Process#optimize(ProcessContext)
 * @see ProcessOptimizationStrategy
 *
 * @author Michael Murray
 */
public interface ProcessContext {
	/**
	 * Returns the optimization strategy to use when optimizing processes in this context.
	 *
	 * <p>The optimization strategy determines how process trees are restructured
	 * to improve execution efficiency. Different strategies may prioritize
	 * parallelism, memory usage, or other performance characteristics.</p>
	 *
	 * @return the current optimization strategy, never {@code null}
	 * @see ProcessOptimizationStrategy
	 * @see ParallelismTargetOptimization
	 * @see CascadingOptimizationStrategy
	 */
	ProcessOptimizationStrategy getOptimizationStrategy();

	/**
	 * Returns the current depth in the process tree hierarchy.
	 *
	 * <p>The depth starts at 0 for the root context and increases by 1 for each
	 * level of nesting. Optimization strategies can use this information to
	 * make depth-aware decisions, such as avoiding excessive isolation at
	 * deep levels to prevent tree fragmentation.</p>
	 *
	 * @return the depth in the process tree, starting from 0
	 */
	int getDepth();

	/**
	 * Creates a base {@link ProcessContext} with default settings.
	 *
	 * <p>The returned context has depth 0 and uses the default optimization
	 * strategy configured in {@link ProcessContextBase}. This is the standard
	 * entry point for beginning process optimization.</p>
	 *
	 * @return a new base context with depth 0 and the default optimization strategy
	 * @see ProcessContextBase#setDefaultOptimizationStrategy(ProcessOptimizationStrategy)
	 */
	static ProcessContext base() {
		return new ProcessContextBase(0);
	}
}
