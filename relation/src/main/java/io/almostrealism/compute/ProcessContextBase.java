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
 * Base implementation of {@link ProcessContext} providing default optimization behavior.
 *
 * <p>{@code ProcessContextBase} serves as the foundation for process context management,
 * maintaining the optimization strategy and tree depth during process optimization.
 * It provides a static default optimization strategy that can be configured globally
 * for all base contexts.</p>
 *
 * <h2>Default Optimization Strategy</h2>
 * <p>By default, {@code ProcessContextBase} uses {@link ParallelismTargetOptimization}
 * as its optimization strategy. This can be changed globally via
 * {@link #setDefaultOptimizationStrategy(ProcessOptimizationStrategy)} to affect all
 * subsequently created contexts.</p>
 *
 * <h2>Inheritance</h2>
 * <p>This class is designed for extension. {@link ParallelProcessContext} extends it
 * to add parallelism-specific context information such as count and aggregation data.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Use the default strategy
 * ProcessContext ctx = ProcessContext.base();
 *
 * // Configure a different default strategy globally
 * ProcessContextBase.setDefaultOptimizationStrategy(
 *     new CascadingOptimizationStrategy(
 *         new ParallelismDiversityOptimization(),
 *         new ParallelismTargetOptimization()
 *     )
 * );
 *
 * // All subsequent base contexts will use the new strategy
 * ProcessContext ctx2 = ProcessContext.base();
 * }</pre>
 *
 * @see ProcessContext
 * @see ParallelProcessContext
 * @see ProcessOptimizationStrategy
 *
 * @author Michael Murray
 */
public class ProcessContextBase implements ProcessContext {
	private static ProcessOptimizationStrategy defaultOptimizationStrategy;

	static {
		defaultOptimizationStrategy = new ParallelismTargetOptimization();
	}

	private ProcessOptimizationStrategy optimizationStrategy;
	private int depth;

	/**
	 * Constructs a new context with the specified depth.
	 *
	 * <p>The context is initialized with the current default optimization strategy.
	 * This constructor is protected to encourage use of factory methods like
	 * {@link ProcessContext#base()} or the static factory methods in
	 * {@link ParallelProcessContext}.</p>
	 *
	 * @param depth the depth in the process tree hierarchy, starting from 0
	 */
	protected ProcessContextBase(int depth) {
		this.optimizationStrategy = defaultOptimizationStrategy;
		this.depth = depth;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ProcessOptimizationStrategy getOptimizationStrategy() {
		return optimizationStrategy;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getDepth() { return depth; }

	/**
	 * Sets the default optimization strategy for all subsequently created contexts.
	 *
	 * <p>This method affects the global default strategy used when creating new
	 * {@code ProcessContextBase} instances. Existing context instances are not
	 * affected by this change.</p>
	 *
	 * <p>Common strategies include:</p>
	 * <ul>
	 *   <li>{@link ParallelismTargetOptimization} - Optimizes based on parallelism thresholds (default)</li>
	 *   <li>{@link CascadingOptimizationStrategy} - Chains multiple strategies together</li>
	 * </ul>
	 *
	 * @param strategy the new default optimization strategy; should not be {@code null}
	 * @see ProcessOptimizationStrategy
	 */
	public static void setDefaultOptimizationStrategy(ProcessOptimizationStrategy strategy) {
		defaultOptimizationStrategy = strategy;
	}
}
