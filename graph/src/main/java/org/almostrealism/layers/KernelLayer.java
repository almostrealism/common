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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.OperationList;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class KernelLayer implements CellularLayer, Learning {
	private TraversalPolicy outputShape;
	private KernelLayerCell forward;
	private PropagationCell backward;
	private List<PackedCollection<?>> weights;

	private Supplier<Runnable> setup;

	public KernelLayer(TraversalPolicy inputShape, TraversableKernelExpression forward, Propagation backward) {
		this(inputShape, forward, backward, Collections.emptyList());
	}

	public KernelLayer(TraversalPolicy inputShape, TraversableKernelExpression forward, Propagation backward,
					   List<PackedCollection<?>> weights) {
		this(inputShape, forward, backward, weights, new OperationList());
	}

	public KernelLayer(TraversalPolicy inputShape, TraversableKernelExpression forward, Propagation backward,
					   List<PackedCollection<?>> weights, Supplier<Runnable> setup) {
		this.outputShape = forward.getShape();
		this.forward = new KernelLayerCell(inputShape, forward, weights);
		this.backward = new PropagationCell(backward);
		this.backward.setForwardInput(this.forward.getInput());
		this.weights = weights;
		this.setup = setup;
	}

	public TraversalPolicy getOutputShape() { return outputShape; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public List<PackedCollection<?>> getWeights() { return weights; }

	@Override
	public KernelLayerCell getForward() { return forward; }

	@Override
	public PropagationCell getBackward() { return backward; }

	@Override
	public void setLearningRate(Producer<PackedCollection<?>> learningRate) {
		backward.setLearningRate(learningRate);
	}
}
