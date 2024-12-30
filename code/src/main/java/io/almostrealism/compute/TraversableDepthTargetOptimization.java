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

import io.almostrealism.collect.TraversableExpression;

import java.util.Collection;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Stream;

public class TraversableDepthTargetOptimization implements ProcessOptimizationStrategy {
	private final int limit;

	public TraversableDepthTargetOptimization() { this(8); }

	public TraversableDepthTargetOptimization(int depthLimit) {
		this.limit = depthLimit;
	}

	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		int maxDepth = childProcessor.apply(children)
				.mapToInt(t -> t.treeDepth(p -> p instanceof TraversableExpression))
				.max().orElse(0) + 1;

		if (maxDepth > limit) {
			return generate(parent, children, true);
		}

		return null;
	}
}
