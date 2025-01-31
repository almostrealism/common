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
import io.almostrealism.relation.ParallelismSettings;

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public class ParallelismTargetOptimization implements ProcessOptimizationStrategy {

	public static boolean enableNarrowMax = true;
	public static boolean enableContextualCount = false;
	public static int minCount = 1 << 8;
	public static int targetCount = 1 << 17;
	public static int maxCount = 1 << 20;

	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		ParallelProcessContext context = ParallelProcessContext.of(ctx);

		long counts[] = childProcessor.apply(children).mapToLong(ParallelProcess::parallelism).toArray();
		long cn = Countable.countLong(parent);
		long p = counts.length;
		long tot = LongStream.of(counts).sum();
		long max = LongStream.of(counts).max().orElse(0);

		long memory[] = childProcessor.apply(children).mapToLong(Process::outputSize).filter(i -> i > 0).toArray();
		long mem = Process.outputSize(parent);
		long maxMem = LongStream.of(memory).max().orElse(0);

		double currentScore = ParallelismSettings.score(cn, mem);
		double altScore = ParallelismSettings
				.scores(childProcessor.apply(children))
				.max().orElse(Integer.MIN_VALUE);

		double min = Math.min(currentScore, altScore);
		if (min < 0) {
			min = Math.abs(min);
			currentScore += min;
			altScore += min;
			currentScore++;
			altScore++;
		}

		boolean isolate = true;

		if ((p <= 1 && tot == cn) || cn >= max) {
			isolate = false;
		} else if (enableContextualCount && max <= context.getCountLong()) {
			isolate = false;
		} else if (max > maxCount) {
			if (cn < minCount && context.getCountLong() < minCount) {
				System.out.println("WARN: Count " + max + " is too high to isolate, " +
						"but the resulting process will have a count of only " + cn +
						" (ctx " + context.getCountLong() + ")");
			}

			isolate = false;
		} else if (enableNarrowMax && max > targetCount && context.getCountLong() >= minCount) {
			isolate = false;
		} else if (altScore < currentScore) {
			System.out.println("Skipping isolation to avoid score " +
					altScore + " (" + currentScore + " current)");
			isolate = false;
		}

		if (isolate && currentScore / altScore > 4 && ParallelProcess.explicitIsolationTargets.isEmpty()) {
			System.out.println("Isolation is " + (currentScore / altScore) + " times worse - skipping");
			isolate = false;
		}

		// TODO  It is preferable to return null if no isolation,
		// TODO  so that this strategy can cascade to another if
		// TODO  it is not the last one in the chain
		return generate(parent, children, isolate);
	}
}
