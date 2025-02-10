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

import java.util.function.Consumer;
import java.util.function.Supplier;

public class MonitorReceptor implements Receptor<PackedCollection<?>>, ConsoleFeatures {
	private String name;
	private TraversalPolicy inputShape;
	private TraversalPolicy outputShape;
	private Consumer<PackedCollection<?>> op;
	private PackedCollection<?> data[];

	public MonitorReceptor(Consumer<PackedCollection<?>> op) {
		this("monitor", null, null, op);
	}

	public MonitorReceptor(String name, Consumer<PackedCollection<?>> op) {
		this(name, null, null, op);
	}

	public MonitorReceptor(String name, TraversalPolicy inputShape, TraversalPolicy outputShape, PackedCollection<?>... data) {
		this(name, inputShape, outputShape, null, data);
	}

	public MonitorReceptor(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
							Consumer<PackedCollection<?>> op, PackedCollection<?>... data) {
		this.name = name;
		this.inputShape = inputShape;
		this.outputShape = outputShape;
		this.op = op;
		this.data = data;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
		return () -> () -> {
			PackedCollection<?> out = in.get().evaluate();

			if (op != null) {
				op.accept(out);
			}

			boolean isNaN = out.doubleStream().anyMatch(Double::isNaN);
			boolean isZero = out.doubleStream().allMatch(d -> d == 0.0);
			if (isNaN) {
				warn("Identified NaN from " + name +
						" layer (" + inputShape + " -> " + outputShape + ")");
				return;
			} else if (isZero) {
				warn("Identified Zero from " + name +
						" layer (" + inputShape + " -> " + outputShape + ")");
				return;
			}

			boolean isLarge = out.doubleStream().map(Math::abs).sum() > 1e6 ||
					out.doubleStream().map(Math::abs).max().getAsDouble() > 1e9;
			if (isLarge) {
				warn("Identified large output from " + name +
						" layer (" + inputShape + " -> " + outputShape + ")");
				return;
			}

			if (name != null && name.equals("softmax2d")) {
				double total = out.doubleStream().sum();
				if (total < 0.9) {
					warn("Softmax layer (" + inputShape + " -> " + outputShape + ") sum is " + total);
				}
			}
		};
	}
}
