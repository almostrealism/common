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
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.MemoryDataCopy;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class DefaultCellularLayer implements CellularLayer, Learning {
	private TraversalPolicy outputShape;
	private Supplier<Runnable> setup;
	private Cell<PackedCollection<?>> forward;
	private Cell<PackedCollection<?>> backward;
	private List<PackedCollection<?>> weights;

	private PackedCollection<?> input;

	public DefaultCellularLayer(Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward) {
		this(forward, backward, new OperationList());
	}

	public DefaultCellularLayer(Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward,
								Supplier<Runnable> setup) {
		this(forward, backward, Collections.emptyList(), setup);
	}

	public DefaultCellularLayer(Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward,
								List<PackedCollection<?>> weights,
								Supplier<Runnable> setup) {
		this(Component.shape(forward).orElseThrow(IllegalArgumentException::new), forward, backward, weights, setup);
	}

	public DefaultCellularLayer(TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward,
								List<PackedCollection<?>> weights,
								Supplier<Runnable> setup) {
		this.outputShape = outputShape;
		this.setup = setup;
		this.forward = forward;
		this.backward = backward;
		this.weights = weights;
	}

	public void init(TraversalPolicy inputShape) {
		this.input = new PackedCollection<>(inputShape);
	}

	public PackedCollection<?> getInput() { return input; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public Cell<PackedCollection<?>> getForward() {
		return Cell.branch(forward, Cell.of((in, next) ->
			new MemoryDataCopy(in.get()::evaluate, () -> input, input.getMemLength())
		));
	}

	@Override
	public Cell<PackedCollection<?>> getBackward() { return backward; }

	@Override
	public TraversalPolicy getOutputShape() { return outputShape; }

	@Override
	public List<PackedCollection<?>> getWeights() { return weights; }

	@Override
	public void setLearningRate(Producer<PackedCollection<?>> learningRate) {
		if (forward instanceof Learning) ((Learning) forward).setLearningRate(learningRate);
		if (backward instanceof Learning) ((Learning) backward).setLearningRate(learningRate);
	}
}
