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
import org.almostrealism.io.SystemUtils;

import java.util.function.Supplier;
import java.util.stream.Stream;

public class DefaultGradientPropagation implements BackPropagation, Nameable, CodeFeatures {

	public static boolean verbose = false;
	public static boolean enableOptimizedDiagnostics = false;
	public static boolean enableDiagnosticGrad = SystemUtils.isEnabled("AR_DIAGNOSTIC_GRADIENT").orElse(false);
	public static boolean enableDiagnosticWeight = false;

	private final Factor<PackedCollection<?>> operator;
	private final Producer<PackedCollection<?>>[] weights;

	private String name;

	public DefaultGradientPropagation(Factor<PackedCollection<?>> operator,
									  Stream<Producer<PackedCollection<?>>> weights) {
		this.operator = operator;
		this.weights = weights.toArray(Producer[]::new);
	}

	public DefaultGradientPropagation(Factor<PackedCollection<?>> operator,
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
		if (weights.length > 0 && learningRate == null) {
			throw new IllegalArgumentException("Learning rate is required");
		}

		if (next == null && verbose) {
			log("Gradient will not be computed for " + getName() +
					" because there is no provided Receptor");
		}

		TraversalPolicy shape = shape(input);

		Supplier<CollectionProducer<PackedCollection<?>>> function = () -> (CollectionProducer<PackedCollection<?>>) operator.getResultant(input);
		PackedCollection<?> gradIn = new PackedCollection<>(shape(gradient));
		PackedCollection<?> gradOut = next == null ? null : new PackedCollection<>(shape);

		int inSize = shape.getTotalSize();
		int outSize = shape(gradient).getTotalSize();

		OperationList op = new OperationList("Gradient Propagation");

		if (next != null) {
			Producer<PackedCollection<?>> deltaOutDeltaIn = function.get().delta(input)
					.reshape(outSize, inSize)
					.traverse(1)
					.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize))
					.traverse(0)
					.enumerate(1, 1)
					.sum(1)
					.reshape(shape(inSize))
					.each();

			if (enableDiagnosticGrad) {
				PackedCollection<?> deltaOut = new PackedCollection<>(shape(outSize, inSize)).traverse(1);
				Producer<PackedCollection<?>> delta = function.get().delta(input).reshape(outSize, inSize).traverse(1);

				op.add(OperationWithInfo.of(new OperationMetadata(getName() + " delta", getName() + " (\u03B4Out/\u03B4In)"), () -> {
					Evaluable<PackedCollection<?>> d = delta.get();
					Evaluable<PackedCollection<?>> grad = enableOptimizedDiagnostics ?
							(Evaluable) Process.optimized(deltaOutDeltaIn).get() : deltaOutDeltaIn.get();
					Evaluable<PackedCollection<?>> inputGrad = gradient.get();

					return () -> {
						String name = getName() + " (" + outSize + "x" + inSize + ")";
						d.into(deltaOut).evaluate();
						inputGrad.into(gradIn).evaluate();
						grad.into(gradOut).evaluate();
						// deltaOut.print(r -> log(name + " delta:\n" + r));
					};
				}));
			} else {
				op.add(a(getName() + " (\u03B4Out/\u03B4In)", traverseEach(p(gradOut)), deltaOutDeltaIn));
			}
		}

		for (int i = 0; i < weights.length; i++) {
			int weightSize = shape(weights[i]).getTotalSize();
			Producer<PackedCollection<?>> weightFlat = reshape(shape(weightSize), weights[i]);

			Producer<PackedCollection<?>> deltaOutDeltaWeight = function.get().delta(weights[i])
					.reshape(outSize, weightSize)
					.traverse(1)
					.multiply(c(gradient).reshape(outSize).traverse(1).repeat(weightSize))
					.traverse(0)
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
