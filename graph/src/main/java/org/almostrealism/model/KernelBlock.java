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

package org.almostrealism.model;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.KernelExpression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class KernelBlock implements Block, CodeFeatures {
	private final TraversalPolicy inputShape;
	private final TraversalPolicy outputShape;
	private final KernelExpression kernel;
	private final PackedCollection<?> weights;
	private final Supplier<Runnable> setup;

	public KernelBlock(TraversalPolicy shape, TraversableKernelExpression kernel, PackedCollection<?> weights) {
		this(shape, kernel, weights, new OperationList());
	}

	public KernelBlock(TraversalPolicy inputShape, TraversableKernelExpression kernel,
					   PackedCollection<?> weights, Supplier<Runnable> setup) {
		this(inputShape, kernel.getShape(), kernel, weights, setup);
	}

	public KernelBlock(TraversalPolicy inputShape, TraversalPolicy outputShape, KernelExpression kernel,
					   PackedCollection<?> weights, Supplier<Runnable> setup) {
		this.inputShape = inputShape;
		this.outputShape = outputShape;
		this.kernel = kernel;
		this.weights = weights;
		this.setup = setup;
	}

	@Override
	public TraversalPolicy getInputShape() { return inputShape; }

	@Override
	public TraversalPolicy getOutputShape() { return outputShape; }

	@Override
	public PackedCollection<?> getWeights() { return weights; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public Supplier<Runnable> forward(Producer<PackedCollection<?>> input, Producer<PackedCollection<?>> output) {
		CollectionProducerComputation<PackedCollection<?>> computation = kernel(outputShape, kernel, p(weights), input);

		return () -> {
			KernelizedEvaluable<PackedCollection<?>> k = computation.get();
			Evaluable<PackedCollection<?>> out = output.get();
			return () -> k.kernelEvaluate(out.evaluate().traverseEach());
		};
	}
}
