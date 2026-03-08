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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A composite optimization strategy that chains multiple strategies together.
 *
 * <p>{@code CascadingOptimizationStrategy} allows multiple {@link ProcessOptimizationStrategy}
 * implementations to be tried in sequence until one successfully handles the process.
 * This enables sophisticated optimization logic by combining simpler, focused strategies.</p>
 *
 * <h2>Cascading Behavior</h2>
 * <p>When {@link #optimize(ProcessContext, Process, Collection, Function)} is called, each
 * strategy in the chain is tried in order. The first strategy to return a non-{@code null}
 * result is used; subsequent strategies are not called. If all strategies return {@code null},
 * the cascading strategy itself returns {@code null}.</p>
 *
 * <p>This pattern allows strategies to be composed:</p>
 * <ul>
 *   <li>Specialized strategies handle specific cases early in the chain</li>
 *   <li>General-purpose strategies serve as fallbacks at the end</li>
 *   <li>Each strategy can focus on a single concern</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a cascading strategy with multiple layers
 * ProcessOptimizationStrategy strategy = new CascadingOptimizationStrategy(
 *     new AggregationDepthTargetOptimization(8),     // Handle deep trees first
 *     new ParallelismDiversityOptimization(),        // Handle diverse parallelism
 *     new ParallelismTargetOptimization()            // General fallback
 * );
 *
 * // Set as the default optimization strategy
 * ProcessContextBase.setDefaultOptimizationStrategy(strategy);
 * }</pre>
 *
 * <h2>Strategy Requirements</h2>
 * <p>For cascading to work correctly, component strategies should:</p>
 * <ul>
 *   <li>Return {@code null} when they don't apply (not a default or identity result)</li>
 *   <li>Be ordered from most specific to most general</li>
 *   <li>Have minimal overlap to avoid redundant processing</li>
 * </ul>
 *
 * @see ProcessOptimizationStrategy
 * @see ParallelismTargetOptimization
 *
 * @author Michael Murray
 */
public class CascadingOptimizationStrategy implements ProcessOptimizationStrategy {
	private List<ProcessOptimizationStrategy> strategies;

	/**
	 * Constructs a cascading strategy with the given strategies in order.
	 *
	 * <p>Strategies are tried in the order provided. Place more specific
	 * strategies first and more general fallback strategies last.</p>
	 *
	 * @param strategies the strategies to cascade, in priority order
	 */
	public CascadingOptimizationStrategy(ProcessOptimizationStrategy... strategies) {
		this.strategies = Arrays.asList(strategies);
	}

	/**
	 * Optimizes a process by trying each strategy in the chain until one succeeds.
	 *
	 * <p>Each strategy is invoked in order. The first to return a non-{@code null}
	 * result wins and that result is returned. If all strategies return {@code null},
	 * this method also returns {@code null}.</p>
	 *
	 * @param <P>            the type of child processes
	 * @param <T>            the result type of the process
	 * @param ctx            the process context
	 * @param parent         the parent process being optimized
	 * @param children       the collection of child processes
	 * @param childProcessor a function to process children for analysis
	 * @return the optimized process from the first successful strategy, or {@code null}
	 */
	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		for (ProcessOptimizationStrategy strategy : strategies) {
			Process<P, T> result = strategy.optimize(ctx, parent, children, childProcessor);
			if (result != null) return result;
		}

		return null;
	}
}
