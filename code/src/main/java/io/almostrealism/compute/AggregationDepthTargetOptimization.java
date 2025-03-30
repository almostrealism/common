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
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The {@link AggregationDepthTargetOptimization} is a strategy that attempts
 * to prevent a {@link Process} tree below an aggregation becoming too deep.
 * When {@link ParallelProcessContext#getAggregationCount()} is above
 * {@value #AGGREGATION_THRESHOLD}, it will opportunistically isolate the
 * children of any {@link Process} when the tree depth above it exceeds a
 * specified limit, so long as every child {@link Process} has sufficient
 * parallelism that this is unlikely to form a bottleneck.
 */
public class AggregationDepthTargetOptimization implements ProcessOptimizationStrategy {
	public static final long AGGREGATION_THRESHOLD = 64;
	public static final long PARALLELISM_THRESHOLD = 128;

	private final int limit;

	public AggregationDepthTargetOptimization() { this(12); }

	public AggregationDepthTargetOptimization(int depthLimit) {
		this.limit = depthLimit;
	}

	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
															   Process<P, T> parent,
															   Collection<P> children,
															   Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		ParallelProcessContext pctx = ParallelProcessContext.of(ctx);

		long c = childProcessor.apply(children).mapToLong(ParallelProcess::parallelism)
				.filter(p -> p < PARALLELISM_THRESHOLD).count();

		if (c == 0 && pctx.getAggregationCount() > AGGREGATION_THRESHOLD && pctx.getDepth() > limit) {
			return generate(parent, children, true);
		}

		return null;
	}
}
