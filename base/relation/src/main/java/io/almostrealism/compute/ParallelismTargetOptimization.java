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
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The default optimization strategy based on parallelism thresholds and scoring.
 *
 * <p>{@code ParallelismTargetOptimization} analyzes process trees and determines whether
 * to isolate child processes based on their parallelism counts, memory usage, and the
 * overall optimization score. This is the default strategy used by {@link ProcessContextBase}.</p>
 *
 * <h2>Parallelism Thresholds</h2>
 * <p>The strategy uses several configurable thresholds:</p>
 * <ul>
 *   <li>{@link #minCount} - Minimum parallelism count (256). Processes below this threshold
 *       may trigger warnings when isolation is needed but not possible.</li>
 *   <li>{@link #targetCount} - Target parallelism count (131072 = 2^17). When
 *       {@link #enableNarrowMax} is true, processes above this and with sufficient
 *       context parallelism skip isolation.</li>
 *   <li>{@link #maxCount} - Maximum parallelism count (1048576 = 2^20). Processes above
 *       this threshold are not isolated to avoid excessive fragmentation.</li>
 * </ul>
 *
 * <h2>Optimization Decision Logic</h2>
 * <p>The strategy decides whether to isolate children based on several conditions:</p>
 * <ol>
 *   <li><b>Single child or matching count</b> - If there's only one child and its
 *       parallelism equals the parent's, no isolation is needed.</li>
 *   <li><b>Contextual count</b> - When enabled, skips isolation if maximum child
 *       parallelism is less than or equal to the context's parallelism.</li>
 *   <li><b>Maximum count exceeded</b> - Skips isolation if max child parallelism
 *       exceeds {@link #maxCount}.</li>
 *   <li><b>Narrow max optimization</b> - When enabled, skips isolation if max child
 *       parallelism exceeds target and context has sufficient parallelism.</li>
 *   <li><b>Score comparison</b> - Skips isolation if it would result in a worse
 *       score than the current configuration.</li>
 *   <li><b>Score ratio check</b> - Skips isolation if current score is more than
 *       4x worse than alternative, unless explicit isolation targets are set.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <p>The behavior can be adjusted through static fields:</p>
 * <ul>
 *   <li>{@link #enableNarrowMax} - Enables target-based narrowing (default: true)</li>
 *   <li>{@link #enableContextualCount} - Enables context-aware count comparisons (default: false)</li>
 *   <li>{@link #minCount}, {@link #targetCount}, {@link #maxCount} - Parallelism thresholds</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Use with default settings
 * ProcessOptimizationStrategy strategy = new ParallelismTargetOptimization();
 *
 * // Adjust thresholds for specific workloads
 * ParallelismTargetOptimization.maxCount = 1 << 18;  // Lower max for memory-constrained systems
 * ParallelismTargetOptimization.enableContextualCount = true;  // Enable context awareness
 * }</pre>
 *
 * @see ProcessOptimizationStrategy
 * @see ParallelismSettings#score(long, long)
 * @see CascadingOptimizationStrategy
 *
 * @author Michael Murray
 */
public class ParallelismTargetOptimization implements ProcessOptimizationStrategy {

	/**
	 * Enables target-based narrowing optimization.
	 *
	 * <p>When {@code true} (the default), isolation is skipped if maximum child
	 * parallelism exceeds {@link #targetCount} and the context already has
	 * sufficient parallelism (at least {@link #minCount}).</p>
	 */
	public static boolean enableNarrowMax = true;

	/**
	 * Enables context-aware count comparisons.
	 *
	 * <p>When {@code true}, isolation is skipped if the maximum child parallelism
	 * is less than or equal to the context's parallelism. This helps avoid
	 * unnecessary isolation in deeply nested structures. Default is {@code false}.</p>
	 */
	public static boolean enableContextualCount = false;

	/**
	 * Minimum parallelism count threshold (256 = 2^8).
	 *
	 * <p>Processes with parallelism below this value may trigger warnings
	 * when isolation is needed but would create bottlenecks.</p>
	 */
	public static int minCount = 1 << 8;

	/**
	 * Target parallelism count (131072 = 2^17).
	 *
	 * <p>Used with {@link #enableNarrowMax} to skip isolation when child
	 * processes exceed this threshold and context has sufficient parallelism.</p>
	 */
	public static int targetCount = 1 << 17;

	/**
	 * Maximum parallelism count threshold (1048576 = 2^20).
	 *
	 * <p>Processes with parallelism above this value are not isolated,
	 * as excessive fragmentation would hurt performance more than
	 * the parallelism gains.</p>
	 */
	public static int maxCount = 1 << 20;

	/**
	 * Optimizes a process using parallelism threshold and score-based analysis.
	 *
	 * <p>This method implements the core optimization logic, analyzing child
	 * processes and deciding whether isolation would improve overall performance.
	 * The decision considers parallelism counts, memory usage, and optimization
	 * scores.</p>
	 *
	 * @param <P>            the type of child processes
	 * @param <T>            the result type of the process
	 * @param ctx            the process context with depth and strategy
	 * @param parent         the parent process being optimized
	 * @param children       the collection of child processes
	 * @param childProcessor a function to process children for analysis
	 * @return the optimized process with children isolated or not based on analysis
	 */
	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		ParallelProcessContext context = ParallelProcessContext.of(ctx);

		List<P> processed = childProcessor.apply(children).collect(Collectors.toList());
		long counts[] = processed.stream().mapToLong(ParallelProcess::parallelism).toArray();
		long cn = ParallelProcess.parallelism(parent); // Countable.countLong(parent);
		long p = counts.length;

		long tot = 0;
		long max = 0;
		for (long c : counts) {
			if (c > 0) {
				tot += c;
				max = Math.max(max, c);
			}
		}

		boolean isolate;

		if ((p <= 1 && tot == cn) || cn >= max) {
			isolate = false;
		} else if (enableContextualCount && max <= context.getCountLong()) {
			isolate = false;
		} else if (max > maxCount) {
			isolate = false;
		} else if (enableNarrowMax && max > targetCount && context.getCountLong() >= minCount) {
			isolate = false;
		} else {
			isolate = isIsolationScoreFavored(parent, cn, processed, counts);
		}

		// TODO  It is preferable to return null if no isolation,
		// TODO  so that this strategy can cascade to another if
		// TODO  it is not the last one in the chain
		return generate(parent, children, isolate);
	}

	/**
	 * Compares the {@link ParallelismSettings#score(long, long) score} of keeping the
	 * children inline with the parent against the best score among the children, the
	 * last test {@link #optimize} applies. The scores are only computed when every
	 * cheaper test has left isolation open, since each one reads the parallelism and
	 * output size of a process. The parallelism of each child has already been read by
	 * {@link #optimize}, so only the output size of each child is read here.
	 *
	 * @param parent      the parent process being optimized
	 * @param parallelism the parallelism of the parent
	 * @param children    the children, as presented for analysis
	 * @param counts      the parallelism of each child, in the order of {@code children}
	 * @return {@code true} if isolating the children scores better
	 */
	protected boolean isIsolationScoreFavored(Process<?, ?> parent, long parallelism,
											  List<? extends Process<?, ?>> children, long[] counts) {
		double currentScore = ParallelismSettings.score(parallelism, Process.outputSize(parent));

		double altScore = Integer.MIN_VALUE;
		for (int i = 0; i < counts.length; i++) {
			double score = ParallelismSettings.score(counts[i], Process.outputSize(children.get(i)));
			altScore = i == 0 ? score : Math.max(altScore, score);
		}

		double min = Math.min(currentScore, altScore);
		if (min < 0) {
			min = Math.abs(min);
			currentScore += min;
			altScore += min;
			currentScore++;
			altScore++;
		}

		if (altScore < currentScore) return false;
		return !(currentScore / altScore > 4 && ParallelProcess.explicitIsolationTargets.isEmpty());
	}
}
