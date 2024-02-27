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

import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.OperationWithInfo;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Nameable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import io.almostrealism.relation.Factor;

import java.util.function.Supplier;
import java.util.stream.Stream;

public class GradientPropagation implements Propagation, Nameable, CodeFeatures {

	public static boolean enableDiagnosticGrad = false;
	public static boolean enableDiagnosticWeight = false;

	private final Factor<PackedCollection<?>> operator;
	private final Producer<PackedCollection<?>>[] weights;

	private String name;

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
	public String getName() { return name; }

	@Override
	public void setName(String name) { this.name = name; }

	@Override
	public Supplier<Runnable> propagate(Producer<PackedCollection<?>> learningRate,
										Producer<PackedCollection<?>> gradient,
										Producer<PackedCollection<?>> input,
										Receptor<PackedCollection<?>> next) {
		TraversalPolicy shape = shape(input);

		Supplier<CollectionProducer<PackedCollection<?>>> function = () -> (CollectionProducer<PackedCollection<?>>) operator.getResultant(input);
		PackedCollection<?> gradIn = new PackedCollection<>(shape(gradient));
		PackedCollection<?> gradOut = new PackedCollection<>(shape);

		int inSize = shape.getTotalSize();
		int outSize = shape(gradient).getTotalSize();

		OperationList op = new OperationList("Gradient Propagation");

		Producer<PackedCollection<?>> deltaOutDeltaIn = function.get().delta(input)
				.reshape(outSize, inSize)
				.traverse(1)
				.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize))
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(inSize))
				.each();

		if (enableDiagnosticGrad) {
			op.add(OperationWithInfo.of(new OperationMetadata(getName() + " delta", getName() + " (\u03B4Out/\u03B4In)"), () -> {
				Evaluable<PackedCollection<?>> grad = deltaOutDeltaIn.get();
				Evaluable<PackedCollection<?>> inputGrad = gradient.get();

				return () -> {
					inputGrad.into(gradIn).evaluate();
					grad.into(gradOut).evaluate();
				};
			}));
		} else {
			op.add(a(getName() + " (\u03B4Out/\u03B4In)", traverseEach(p(gradOut)), deltaOutDeltaIn));
		}

		for (int i = 0; i < weights.length; i++) {
			int weightSize = shape(weights[i]).getTotalSize();
			Producer<PackedCollection<?>> weightFlat = reshape(shape(weightSize), weights[i]);

			Producer<PackedCollection<?>> deltaOutDeltaWeight = function.get().delta(weights[i])
					.reshape(outSize, weightSize)
					.traverse(1)
					.multiply(c(gradient).reshape(outSize).traverse(1).repeat(weightSize))
					.enumerate(1, 1)
					.sum(1)
					.reshape(shape(weightSize))
					.each();

			Supplier<Runnable> weightUpdateAssignment =
					a(getName() + " (\u0394 weights)", each(weightFlat),
							subtract(each(weightFlat), multiply(learningRate, deltaOutDeltaWeight)));

			if (enableDiagnosticWeight) {
				op.add(OperationWithInfo.of(new OperationMetadata(getName() + " weights " + i, getName() + " (\u0394 weights)"),() -> {
					Runnable wua = weightUpdateAssignment.get();

					return () -> {
						wua.run();
					};
				}));
			} else {
				op.add(weightUpdateAssignment);
			}
		}

		if (next != null) op.add(next.push(p(gradOut)));
		return op;
	}
}
