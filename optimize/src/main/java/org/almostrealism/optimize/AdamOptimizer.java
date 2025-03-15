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

package org.almostrealism.optimize;

import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.ParameterUpdate;

import java.util.function.Supplier;

public class AdamOptimizer implements ParameterUpdate<PackedCollection<?>>, CodeFeatures {
	private Producer<PackedCollection<?>> learningRate;
	private Producer<PackedCollection<?>> beta1;
	private Producer<PackedCollection<?>> beta2;

	public AdamOptimizer(double learningRate, double beta1, double beta2) {
		this(CollectionFeatures.getInstance().c(learningRate),
				CollectionFeatures.getInstance().c(beta1),
				CollectionFeatures.getInstance().c(beta2));
	}

	public AdamOptimizer(Producer<PackedCollection<?>> learningRate,
						 Producer<PackedCollection<?>> beta1,
						 Producer<PackedCollection<?>> beta2) {
		this.learningRate = learningRate;
		this.beta1 = beta1;
		this.beta2 = beta2;
	}

	@Override
	public Supplier<Runnable> apply(String name,
									Producer<PackedCollection<?>> weights,
									Producer<PackedCollection<?>> gradient) {
		TraversalPolicy shape = shape(weights);

		PackedCollection<?> c = new PackedCollection<>(1);
		PackedCollection<?> m = new PackedCollection<>(shape.traverseEach());
		PackedCollection<?> v = new PackedCollection<>(shape.traverseEach());
		double eps = 1e-7; // Hardware.getLocalHardware().epsilon();

		OperationList ops = new OperationList();
		ops.add(a("increment", cp(c), cp(c).add(1)));
		ops.add(a(name + " (\u0394 momentum)", cp(m),
				c(beta1).multiply(cp(m)).add(c(1.0).subtract(c(beta1)).multiply(c(gradient)))));
		ops.add(a(name + " (\u0394 velocity)", cp(v),
				c(beta2).multiply(cp(v)).add(c(1.0).subtract(c(beta2)).multiply(c(gradient).sq()))));

		CollectionProducer<PackedCollection<?>> mt = cp(m).divide(c(1.0).subtract(c(beta1).pow(cp(c))));
		CollectionProducer<PackedCollection<?>> vt = cp(v).divide(c(1.0).subtract(c(beta2).pow(cp(c))));
		ops.add(a(name + " (\u0394 weights)", c(weights).each(),
				c(weights).each().subtract(c(learningRate).multiply(mt).divide(vt.sqrt().add(eps)))));
		return ops;
	}
}
