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

import io.almostrealism.relation.Countable;

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * This optimization strategy identifies {@link Process} instances that have a much lower
 * parallelism than the average parallelism of their children and favors isolating those
 * children to run independently if it would result in significantly diverse parallelism.
 */
public class ParallelismDiversityOptimization implements ProcessOptimizationStrategy {
	/** The minimum number of distinct parallelism values required to trigger isolation. */
	private final int threshold;

	/**
	 * The ratio between the parent parallelism and the child average parallelism
	 * above which isolation is favoured.
	 */
	private final double factor;

	/**
	 * Constructs the optimization with default thresholds (diversity threshold 8,
	 * parallelism ratio 64.0).
	 */
	public ParallelismDiversityOptimization() { this(8, 64.0); }

	/**
	 * Constructs the optimization with the given thresholds.
	 *
	 * @param diversityThreshold the minimum number of distinct parallelism values that
	 *                           must be present among the children to trigger isolation
	 * @param parallelismRatio   the minimum ratio between child average parallelism and
	 *                           parent parallelism to trigger isolation
	 */
	public ParallelismDiversityOptimization(int diversityThreshold, double parallelismRatio) {
		this.threshold = diversityThreshold;
		this.factor = parallelismRatio;
	}

	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		long counts[] = childProcessor.apply(children).mapToLong(ParallelProcess::parallelism).toArray();
		long distinct = LongStream.of(counts).distinct().count();
		double average = LongStream.of(counts).average().orElse(0);
		long count = Countable.countLong(parent);

		if (distinct > threshold && average > count * factor) {
			return generate(parent, children, true);
		}

		return null;
	}
}
