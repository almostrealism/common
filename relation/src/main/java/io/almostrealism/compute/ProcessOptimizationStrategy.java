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

public interface ProcessOptimizationStrategy {
	List<Consumer<Process<?, ?>>> listeners = new ArrayList<>();

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

	<P extends Process<?, ?>, T> Process<P, T> optimize(ProcessContext ctx,
														Process<P, T> parent,
														Collection<P> children,
														Function<Collection<P>, Stream<P>> childProcessor);
}
