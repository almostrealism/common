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

public class CascadingOptimizationStrategy implements ProcessOptimizationStrategy {
	private List<ProcessOptimizationStrategy> strategies;

	public CascadingOptimizationStrategy(ProcessOptimizationStrategy... strategies) {
		this.strategies = Arrays.asList(strategies);
	}

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
