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

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.ParallelismSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public interface ParallelProcess<P extends Process<?, ?>, T> extends Process<P, T>, Countable {
	List<Predicate<Process>> isolationFlags = new ArrayList<>();

	boolean enableNarrowMax = true;
	boolean enableContextualCount = false;
	int minCount = 1 << 8;
	int targetCount = 1 << 17;
	int maxCount = 1 << 20;

	@Override
	default ParallelProcess<P, T> generate(List<P> children) {
		return (ParallelProcess<P, T>) Process.super.generate(children);
	}

	@Override
	default ParallelProcess<P, T> optimize() {
		return (ParallelProcess<P, T>) Process.super.optimize();
	}

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

	@Override
	default boolean isIsolationTarget(ProcessContext context) {
		if (Process.isExplicitIsolation()) {
			return Process.isolationPermitted(this);
		}

		return Process.super.isIsolationTarget(context);
	}

	default Stream<? extends Process> processChildren(Collection<? extends Process> children) {
		return children.stream();
	}

	default ParallelProcessContext createContext(ProcessContext ctx) {
		return ParallelProcessContext.of(ctx, this);
	}

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

		long counts[] = processChildren(children).mapToLong(ParallelProcess::parallelism).toArray();
		long cn = getCountLong();
		long p = counts.length;
		long tot = LongStream.of(counts).sum();
		long max = LongStream.of(counts).max().orElse(0);

		long memory[] = processChildren(children).mapToLong(Process::outputSize).filter(i -> i > 0).toArray();
		long mem = getOutputSize();
		long maxMem = LongStream.of(memory).max().orElse(0);

		double currentScore = ParallelismSettings.score(cn, mem);
		double altScore = ParallelismSettings
				.scores(processChildren(children))
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

		if (isolate && currentScore / altScore > 4 && explicitIsolationTargets.isEmpty()) {
			System.out.println("Isolation is " + (currentScore / altScore) + " times worse - skipping");
			isolate = false;
		}

		ParallelProcess<P, T> result;

		if (isolate) {
			result = generate(children.stream().map(c -> (P) isolate((Process) c)).collect(Collectors.toList()));
		} else {
			result = generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
		}

		return result;
	}

	default long getParallelism() {
		return getCountLong();
	}

	default boolean isUniform() {
		long p = getChildren().stream().mapToLong(ParallelProcess::parallelism).distinct().count();
		return p == 1;
	}

	static <T> long parallelism(T c) {
		if (c instanceof ParallelProcess) {
			return ((ParallelProcess) c).getParallelism();
		}

		return 1;
	}
}
