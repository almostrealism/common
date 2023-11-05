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

public class GradientPropagation implements Propagation, CodeFeatures {
	private final Factor<PackedCollection<?>> operator;
	private final Producer<PackedCollection<?>>[] weights;

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
		PackedCollection<?> deltaOut = new PackedCollection<>(shape);
		PackedCollection<?> gradOut = new PackedCollection<>(shape);
		PackedCollection<?> wOut = new PackedCollection<>(shape);

		Producer<PackedCollection<?>> deltaOutDeltaIn = function.delta(input);
		Producer<PackedCollection<?>> deltaOutDeltaWeight = function.delta(weights[0]).traverse(2);
		Producer<PackedCollection<?>> weightUpdate = c(weights[0]).subtract(multiply(learningRate, gradient).multiply(deltaOutDeltaWeight));
		Supplier<Runnable> weightUpdateAssignment = a(traverseEach(weights[0]),
				c(weights[0]).each().subtract(multiply(learningRate, gradient).multiply(deltaOutDeltaWeight)));

		OperationList op = new OperationList("Gradient Propagation");

		op.add(() -> {
			Evaluable<PackedCollection<?>> grad = deltaOutDeltaIn.get();
			Evaluable<PackedCollection<?>> dOut = deltaOutDeltaWeight.get();
			Evaluable<PackedCollection<?>> wUp = weightUpdate.get();
			Evaluable<PackedCollection<?>> inputGrad = gradient.get();
			Evaluable<PackedCollection<?>> w = weights[0].get();
			Runnable wua = weightUpdateAssignment.get();

			return () -> {
				inputGrad.into(gradIn).evaluate();
				grad.into(gradOut).evaluate();
				dOut.into(deltaOut).evaluate();
				w.into(wOut).evaluate();

//				System.out.println("GradientPropagation: Input Gradient = " + gradIn.toDouble(0));

//				for (int i = 0; i < shape.getTotalSize(); i++) {
//					System.out.println("\tw" + i + " -> dO/dW = " +
//							deltaOut.toDouble(i) + ", original w" + i + " = " + wOut.toDouble(i));
//				}

				wua.run();
				w.into(wOut).evaluate();

//				for (int i = 0; i < shape.getTotalSize(); i++) {
//					System.out.println("\tw" + i + " = " + wOut.toDouble(i));
//				}
			};
		});
		if (next != null) op.add(next.push(p(gradOut)));

		return op;
	}
}
