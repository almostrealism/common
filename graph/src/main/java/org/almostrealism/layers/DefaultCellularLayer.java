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

import io.almostrealism.relation.Nameable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class DefaultCellularLayer implements CellularLayer, CodeFeatures, Learning, Nameable {
	private TraversalPolicy inputShape;
	private TraversalPolicy outputShape;
	private Supplier<Runnable> setup;
	private Cell<PackedCollection<?>> forward;
	private Cell<PackedCollection<?>> backward;
	private List<PackedCollection<?>> weights;

	private Cell<PackedCollection<?>> entry;
	private Cell<PackedCollection<?>> exit;
	private Cell<PackedCollection<?>> fw;

	private String name;

	private List<Receptor<PackedCollection<?>>> receptors;

	private PackedCollection<?> input;
	private PackedCollection<?> output;

	public DefaultCellularLayer(String name,
								Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward) {
		this(name, forward, backward, new OperationList());
	}

	public DefaultCellularLayer(String name,
								Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward,
								Supplier<Runnable> setup) {
		this(name, forward, backward, Collections.emptyList(), setup);
	}

	public DefaultCellularLayer(String name,
								Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward,
								List<PackedCollection<?>> weights,
								Supplier<Runnable> setup) {
		this(name, Component.shape(forward).orElseThrow(IllegalArgumentException::new), forward, backward, weights, setup);
	}

	public DefaultCellularLayer(String name,
								TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward,
								Cell<PackedCollection<?>> backward,
								List<PackedCollection<?>> weights,
								Supplier<Runnable> setup) {
		setName(name);
		this.outputShape = outputShape;
		this.setup = setup;
		this.forward = forward;
		this.backward = backward;
		this.weights = weights;
		this.receptors = new ArrayList<>();
	}

	@Override
	public void setName(String name) { this.name = name; }

	@Override
	public String getName() { return name; }

	public void init(TraversalPolicy inputShape, boolean inputTracking, boolean outputTracking) {
		this.inputShape = inputShape;

		if (!outputTracking) {
			if (inputTracking) throw new UnsupportedOperationException();
			return;
		}

		this.input = inputTracking ? new PackedCollection<>(inputShape) : null;
		this.output = outputTracking ? new PackedCollection<>(outputShape) : null;

		this.entry = Cell.of((in, next) -> {
			if (this.input == null) {
				return next.push(in);
			} else {
				OperationList op = new OperationList(getName() + " Layer (Entry)");
				op.add(into("DefaultCellularLayer - " + getName() + " (Input Record)", in, p(input)));
				op.add(next.push(p(input)));
				return op;
			}
		});
		this.entry.setReceptor(forward);

		this.exit = Cell.of((in, next) -> into(getName() + " Layer (Evaluate)", in, p(output)));
		this.forward.setReceptor(exit);
	}

	private <T extends MemoryData> Supplier<Runnable> into(String name, Producer<T> in, Producer<T> out) {
		TraversalPolicy shape = shape(in);

		if (shape.getCount() > 1) {
			return a(name, reshape(shape, out), in);
		} else {
			return copy(name, in, out, shape.getTotalSize());
		}
	}

	public PackedCollection<?> getInput() { return input; }
	public PackedCollection<?> getOutput() { return output; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public Cell<PackedCollection<?>> getForward() {
		if (this.output == null) {
			return this.forward;
		} else if (fw == null) {
			fw = Cell.of((in, next) -> {
				OperationList op = new OperationList(getName() + " Layer (Forward)");
				op.add(entry.push(in));
				receptors.forEach(r -> op.add(r.push(p(output))));
				if (next != null) op.add(next.push(p(output)));
				return op;
			});
		}

		return fw;
	}

	@Override
	public Cell<PackedCollection<?>> getBackward() {
		return backward;

//		Cell<PackedCollection<?>> copyOutput = Cell.of((in, next) ->
//				new MemoryDataCopy(in.get()::evaluate, () -> output, output.getMemLength())
//		);
//
//		backward.setReceptor(copyOutput);
//
//		return new Cell<>() {
//			private Receptor<PackedCollection<?>> r;
//
//			@Override
//			public Supplier<Runnable> setup() {
//				return backward.setup();
//			}
//
//			@Override
//			public Supplier<Runnable> push(Producer<PackedCollection<?>> protein) {
//				OperationList op = new OperationList();
//				op.add(backward.push(protein));
//				if (r != null) op.add(r.push(p(output)));
//				return op;
//			}
//
//			@Override
//			public void setReceptor(Receptor<PackedCollection<?>> r) {
//				this.r = r;
//			}
//		};
	}

	@Override
	public TraversalPolicy getInputShape() { return inputShape; }

	@Override
	public TraversalPolicy getOutputShape() { return outputShape; }

	@Override
	public List<PackedCollection<?>> getWeights() { return weights; }

	@Override
	public void setLearningRate(Producer<PackedCollection<?>> learningRate) {
		if (forward instanceof Learning) ((Learning) forward).setLearningRate(learningRate);
		if (backward instanceof Learning) ((Learning) backward).setLearningRate(learningRate);
	}

	@Override
	public <T extends Receptor<PackedCollection<?>>> T append(T r) {
		if (this.output == null)
			throw new UnsupportedOperationException();

		receptors.add(r);
		return r;
	}
}
