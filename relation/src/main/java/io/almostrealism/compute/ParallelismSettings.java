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

import java.util.stream.DoubleStream;
import java.util.stream.Stream;

/**
 * Provides scoring functions for evaluating process parallelism and memory trade-offs.
 *
 * <p>{@code ParallelismSettings} contains utility methods used by optimization strategies
 * to evaluate whether isolating a process would be beneficial. The scoring functions
 * balance the value of parallelism against the cost of memory usage.</p>
 *
 * <h2>Scoring Model</h2>
 * <p>The scoring model uses two components:</p>
 * <ul>
 *   <li><b>Parallelism Value</b> - A logarithmic function that values higher parallelism
 *       with diminishing returns. Doubling parallelism adds a constant value increment.</li>
 *   <li><b>Memory Cost</b> - A super-linear function that penalizes large memory footprints
 *       increasingly as they grow, reflecting cache pressure and memory bandwidth limits.</li>
 * </ul>
 *
 * <p>The overall score is {@code parallelismValue - memoryCost}, where higher scores
 * indicate more favorable configurations. Processes with high parallelism and low memory
 * usage score well; those with low parallelism or high memory usage score poorly.</p>
 *
 * <h2>Usage in Optimization</h2>
 * <p>Optimization strategies like {@link ParallelismTargetOptimization} use these scores
 * to compare the current process configuration against alternative configurations (such
 * as isolating children). If isolation would result in worse scores, it may be skipped.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Calculate score for a single process
 * long parallelism = process.getParallelism();
 * long outputSize = process.getOutputSize();
 * double score = ParallelismSettings.score(parallelism, outputSize);
 *
 * // Calculate scores for multiple processes
 * DoubleStream scores = ParallelismSettings.scores(children.stream());
 * double maxScore = scores.max().orElse(0);
 * }</pre>
 *
 * @see ParallelismTargetOptimization
 * @see ParallelProcess
 * @see Process#getOutputSize()
 *
 * @author Michael Murray
 */
public class ParallelismSettings {
	/**
	 * Calculates the value of a given parallelism count.
	 *
	 * <p>The function uses a logarithmic scale where each doubling of parallelism
	 * adds approximately 4096 to the value. This reflects the diminishing returns
	 * of increasing parallelism - going from 2 to 4 threads is as valuable as
	 * going from 1024 to 2048.</p>
	 *
	 * <p>Formula: {@code 1 + 4096 * log2(count)}</p>
	 *
	 * @param count the parallelism count (number of parallel work items)
	 * @return the computed value representing the benefit of this parallelism level
	 */
	public static double parallelismValue(long count) {
		return 1 + (4096 * Math.log(count) / Math.log(2));
	}

	/**
	 * Calculates the memory cost for a given output size.
	 *
	 * <p>The function uses a super-linear scale where larger memory footprints
	 * are penalized increasingly. The exponent of 1.5 means that doubling
	 * memory usage more than doubles the cost.</p>
	 *
	 * <p>This reflects real-world performance characteristics where larger
	 * memory footprints cause cache misses, increased memory bandwidth pressure,
	 * and potential memory allocation overhead.</p>
	 *
	 * <p>Formula: {@code size^1.5 / 4096}</p>
	 *
	 * @param size the output size (memory footprint) in elements
	 * @return the computed cost representing the performance penalty of this memory usage
	 */
	public static double memoryCost(long size) {
		return Math.pow(size, 1.5) / 4096;
	}

	/**
	 * Calculates the overall score for a process with given parallelism and memory usage.
	 *
	 * <p>The score is the parallelism value minus the memory cost. Higher scores
	 * indicate more favorable configurations:</p>
	 * <ul>
	 *   <li>Positive scores: Parallelism benefits outweigh memory costs</li>
	 *   <li>Near-zero scores: Parallelism and memory costs are balanced</li>
	 *   <li>Negative scores: Memory costs dominate, suggesting potential issues</li>
	 * </ul>
	 *
	 * @param parallelism the parallelism count
	 * @param size        the output size (memory footprint)
	 * @return the overall score for this configuration
	 */
	public static double score(long parallelism, long size) {
		return parallelismValue(parallelism) - memoryCost(size);
	}

	/**
	 * Calculates scores for a stream of processes.
	 *
	 * <p>This utility method extracts parallelism and output size from each
	 * process and computes their scores. It's commonly used to find the
	 * maximum or average score among child processes during optimization.</p>
	 *
	 * @param <T>       the type of elements in the stream
	 * @param processes a stream of processes (or objects that may be processes)
	 * @return a stream of scores for each input element
	 */
	public static <T> DoubleStream scores(Stream<T> processes) {
		return processes.mapToDouble(c -> score(ParallelProcess.parallelism(c), Process.outputSize(c)));
	}
}
