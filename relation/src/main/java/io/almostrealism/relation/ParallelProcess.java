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

package io.almostrealism.relation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface ParallelProcess<P extends Process<?, ?>, T> extends Process<P, T>, Countable {
	boolean enableExplicitIsolation = false;
	List<Predicate<Process>> explicitIsolationTargets = new ArrayList<>();

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

	@Override
	default ParallelProcess<P, T> optimize(ProcessContext ctx) {
		Collection<? extends Process> children = getChildren();
		if (children.isEmpty()) return this;

		ParallelProcessContext context = ParallelProcessContext.of(ctx, this);
		children = children.stream().map(process -> process.optimize(context)).collect(Collectors.toList());

		if (!explicitIsolationTargets.isEmpty()) {
			return generate((List) children.stream()
					.map(c -> explicitIsolationTargets.stream().map(p -> p.test(c))
							.reduce(false, (a, b) -> a | b) ? c.isolate() : c)
					.collect(Collectors.toList()));
		}

		int counts[] = children.stream().mapToInt(ParallelProcess::count).filter(v -> v != 0).distinct().toArray();
		long cn = getCount();
		long p = counts.length;
		long tot = IntStream.of(counts).sum();
		long max = IntStream.of(counts).max().orElse(0);

		if ((p <= 1 && tot == cn) || cn >= max) {
			return generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
		} else if (enableContextualCount && max <= context.getCount()) {
			return generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
		} else if (max > maxCount) {
			if (cn < minCount && context.getCount() < minCount) {
				System.out.println("WARN: Count " + max + " is too high to isolate, " +
						"but the resulting process will have a count of only " + cn +
						" (ctx " + context.getCount() + ")");
			}

			return generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
		} else if (enableNarrowMax && max > targetCount && context.getCount() >= minCount) {
			return generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
		}

		return generate(children.stream().map(c -> (P) c.isolate()).collect(Collectors.toList()));
	}

	default boolean isUniform() {
		long p = getChildren().stream().mapToInt(ParallelProcess::count).distinct().count();
		return p == 1;
	}

	static <T> int count(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).getCount();
		}

		return 1;
	}

	static <T> boolean isFixedCount(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).isFixedCount();
		}

		return true;
	}
}
