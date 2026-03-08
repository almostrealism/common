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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Defines a strategy for optimizing {@link Process} trees to improve execution efficiency.
 *
 * <p>Process optimization strategies analyze process trees and determine whether child
 * processes should be isolated (executed independently) or kept inline. The decision
 * typically involves balancing parallelism gains against memory overhead and execution
 * fragmentation.</p>
 *
 * <h2>Isolation</h2>
 * <p>When a process is "isolated", it is wrapped in a way that forces it to execute
 * independently from its parent. This can improve performance when:</p>
 * <ul>
 *   <li>Child processes have high parallelism that can be exploited</li>
 *   <li>Memory pressure requires breaking up large computations</li>
 *   <li>Tree depth would otherwise cause stack overflow issues</li>
 * </ul>
 *
 * <p>Isolation may hurt performance when:</p>
 * <ul>
 *   <li>The overhead of separate execution exceeds parallelism gains</li>
 *   <li>Child processes have low parallelism that would create bottlenecks</li>
 *   <li>Excessive fragmentation leads to many small kernel invocations</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link ParallelismTargetOptimization} - Default strategy based on parallelism thresholds</li>
 *   <li>{@link CascadingOptimizationStrategy} - Chains multiple strategies, using the first that applies</li>
 * </ul>
 *
 * <h2>Listener Support</h2>
 * <p>Strategies can notify listeners when optimizing processes, enabling monitoring
 * and debugging of the optimization process.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a custom optimization strategy
 * ProcessOptimizationStrategy strategy = new ProcessOptimizationStrategy() {
 *     @Override
 *     public <P extends Process<?, ?>, T> Process<P, T> optimize(
 *             ProcessContext ctx,
 *             Process<P, T> parent,
 *             Collection<P> children,
 *             Function<Collection<P>, Stream<P>> childProcessor) {
 *         // Custom optimization logic
 *         boolean shouldIsolate = // determine based on context and children
 *         return generate(parent, children, shouldIsolate);
 *     }
 * };
 *
 * // Set as the default strategy
 * ProcessContextBase.setDefaultOptimizationStrategy(strategy);
 * }</pre>
 *
 * @see ParallelismTargetOptimization
 * @see CascadingOptimizationStrategy
 * @see Process#optimize(ProcessContext)
 * @see ParallelProcess
 *
 * @author Michael Murray
 */
public interface ProcessOptimizationStrategy {
	/**
	 * Listeners that are notified when processes are being optimized.
	 *
	 * <p>Add consumers to this list to monitor optimization decisions.
	 * Each listener receives the parent process being optimized.</p>
	 */
	List<Consumer<Process<?, ?>>> listeners = new ArrayList<>();

	/**
	 * Generates a new process with the given children, optionally isolating them.
	 *
	 * <p>This helper method creates the result of an optimization decision. When
	 * {@code isolateChildren} is {@code true}, each child process is wrapped via
	 * {@link Process#isolate(Process)} to force independent execution.</p>
	 *
	 * @param <P>             the type of child processes
	 * @param <T>             the result type of the process
	 * @param parent          the parent process being optimized
	 * @param children        the collection of child processes
	 * @param isolateChildren {@code true} to isolate children, {@code false} to keep them inline
	 * @return a new process with the specified children, isolated or not as requested
	 */
	default <P extends Process<?, ?>, T> Process<P, T> generate(
											Process<P, T> parent,
										  	Collection<P> children,
											boolean isolateChildren) {
		if (isolateChildren) {
			return parent.generate(children.stream()
					.map(c -> (P) parent.isolate((Process) c))
					.collect(Collectors.toList()));
		} else {
			return parent.generate(children.stream()
					.map(c -> (P) c)
					.collect(Collectors.toList()));
		}
	}

	/**
	 * Optimizes a process and its children according to this strategy.
	 *
	 * <p>This is the core method that implementations override to provide custom
	 * optimization logic. The method analyzes the process tree structure and decides
	 * whether to isolate child processes for independent execution.</p>
	 *
	 * <p>Implementations should:</p>
	 * <ol>
	 *   <li>Notify {@link #listeners} at the start of optimization</li>
	 *   <li>Analyze child parallelism, memory usage, and tree structure</li>
	 *   <li>Decide whether isolation would improve performance</li>
	 *   <li>Return an optimized process via {@link #generate(Process, Collection, boolean)}</li>
	 * </ol>
	 *
	 * <p>Cascading strategies should return {@code null} if they do not apply to the
	 * given process, allowing the next strategy in the chain to be tried.</p>
	 *
	 * @param <P>            the type of child processes
	 * @param <T>            the result type of the process
	 * @param ctx            the process context with depth and strategy information
	 * @param parent         the parent process being optimized
	 * @param children       the collection of child processes (already recursively optimized)
	 * @param childProcessor a function that processes and streams children for analysis
	 * @return an optimized process, or {@code null} if this strategy does not apply
	 * @see #generate(Process, Collection, boolean)
	 * @see CascadingOptimizationStrategy
	 */
	<P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
														Process<P, T> parent,
														Collection<P> children,
														Function<Collection<P>, Stream<P>> childProcessor);
}
