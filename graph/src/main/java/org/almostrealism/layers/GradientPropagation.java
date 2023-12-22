/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import io.almostrealism.relation.Factor;

import java.util.function.Supplier;
import java.util.stream.Stream;

public class GradientPropagation implements Propagation, CodeFeatures {
	private final Factor<PackedCollection<?>> operator;
	private final Producer<PackedCollection<?>>[] weights;


	public GradientPropagation(Factor<PackedCollection<?>> operator,
							   Stream<Producer<PackedCollection<?>>> weights) {
		this.operator = operator;
		this.weights = weights.toArray(Producer[]::new);
	}

	public GradientPropagation(Factor<PackedCollection<?>> operator,
							   Producer<PackedCollection<?>>... weights) {
		this.operator = operator;
		this.weights = weights;
	}

	@Override
	public Supplier<Runnable> propagate(Producer<PackedCollection<?>> learningRate,
										Producer<PackedCollection<?>> gradient,
										Producer<PackedCollection<?>> input,
										Receptor<PackedCollection<?>> next) {
		TraversalPolicy shape = shape(input);

		CollectionProducer<PackedCollection<?>> function = (CollectionProducer<PackedCollection<?>>) operator.getResultant(input);
		PackedCollection<?> gradIn = new PackedCollection<>(shape(gradient));
		PackedCollection<?> gradOut = new PackedCollection<>(shape);

		int inSize = shape.getTotalSize();
		int outSize = shape(gradient).getTotalSize();
		int weightSize = shape(weights[0]).getTotalSize();
		Producer<PackedCollection<?>> weightFlat = reshape(shape(weightSize), weights[0]);

		Producer<PackedCollection<?>> deltaOutDeltaIn;
		Producer<PackedCollection<?>> deltaOutDeltaWeight;
		Supplier<Runnable> weightUpdateAssignment;

		deltaOutDeltaIn = function.delta(input)
				.reshape(outSize, inSize)
				.traverse(1)
				.multiply(c(gradient).reshape(outSize).traverse(1).expand(inSize))
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(inSize))
				.each();
		deltaOutDeltaWeight = function.delta(weights[0])
				.reshape(outSize, weightSize)
				.traverse(1)
				.multiply(c(gradient).reshape(outSize).traverse(1).expand(weightSize))
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(weightSize))
				.each();

		weightUpdateAssignment =
				a(each(weightFlat),
						subtract(each(weightFlat), multiply(learningRate, deltaOutDeltaWeight)));

		OperationList op = new OperationList("Gradient Propagation");

		op.add(() -> {
			Evaluable<PackedCollection<?>> grad = deltaOutDeltaIn.get();
			Evaluable<PackedCollection<?>> inputGrad = gradient.get();

			long start = System.currentTimeMillis();
			log("Compiling weight update...");
			Runnable wua = weightUpdateAssignment.get();
			log("Compiled weight update in " +
					(System.currentTimeMillis() - start) / 60000 + "m");

			return () -> {
				inputGrad.into(gradIn).evaluate();
				grad.into(gradOut).evaluate();
				wua.run();
			};
		});
		if (next != null) op.add(next.push(p(gradOut)));

		return op;
	}
}
