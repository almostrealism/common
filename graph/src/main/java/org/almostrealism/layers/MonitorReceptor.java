/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.Supplier;

public class MonitorReceptor implements Receptor<PackedCollection<?>>, ConsoleFeatures {
	private String name;
	private TraversalPolicy inputShape;
	private TraversalPolicy outputShape;

	public MonitorReceptor(String name, TraversalPolicy inputShape, TraversalPolicy outputShape) {
		this.name = name;
		this.inputShape = inputShape;
		this.outputShape = outputShape;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
		return () -> () -> {
			PackedCollection<?> out = in.get().evaluate();
			boolean isNaN = out.doubleStream().anyMatch(Double::isNaN);
			boolean isZero = out.doubleStream().allMatch(d -> d == 0.0);
			if (isNaN) {
				warn("Identified NaN from " + name +
						" layer (" + inputShape + " -> " + outputShape + ")");
			} else if (isZero) {
				warn("Identified Zero from " + name +
						" layer (" + inputShape + " -> " + outputShape + ")");
			}
		};
	}
}
