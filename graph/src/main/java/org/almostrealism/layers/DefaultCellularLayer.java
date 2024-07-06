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

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.uml.Nameable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class DefaultCellularLayer implements CellularLayer, CodeFeatures, Learning, Nameable {
	public static boolean enableMemoryDataCopy = true;

	private TraversalPolicy inputShape;
	private TraversalPolicy outputShape;
	private Supplier<Runnable> setup;
	private Cell<PackedCollection<?>> forward;
	private Cell<PackedCollection<?>> backward;
	private List<PackedCollection<?>> weights;

	private Cell<PackedCollection<?>> entry;
	private Cell<PackedCollection<?>> exit;
	private Cell<PackedCollection<?>> fw;
	private Receptor<PackedCollection<?>> monitor;

	private String name;
	private List<ComputeRequirement> requirements;

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
								Cell<PackedCollection<?>> backward) {
		this(name, outputShape, forward, backward, Collections.emptyList(), new OperationList());
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

	public List<ComputeRequirement> getComputeRequirements() { return requirements; }

	public void setComputeRequirements(List<ComputeRequirement> requirements) { this.requirements = requirements; }

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
				OperationList op = new OperationList(getName() + " layer (Entry)");
				op.add(into(getName() + " layer (Input Record)", in, p(input), enableMemoryDataCopy));
				op.add(next.push(p(input)));
				return op;
			}
		});
		this.entry.setReceptor(forward);

//		this.exit = Cell.of((in, next) -> into(getName() + " layer " +
//				getInputShape() + "->" + getOutputShape(), in, p(output)));
		this.exit = Cell.of((in, next) -> output(in, p(output)));
		this.forward.setReceptor(exit);
	}

	private Supplier<Runnable> output(Producer<PackedCollection<?>> in, Producer<PackedCollection<?>> out) {
		Supplier<Runnable> o = into(getName() + " layer " +
				getInputShape() + "->" + getOutputShape(), in, out, false);
		if (getMonitor() == null) {
			return o;
		}

		OperationList op = new OperationList(getName() + " layer (Output and Monitor)");
		op.add(o);
		op.add(getMonitor().push(out));
		return op;
	}

	private <T extends MemoryData> Supplier<Runnable> into(String name,
														   Producer<T> in, Producer<T> out,
														   boolean copy) {
		TraversalPolicy shape = shape(in);

		OperationList op = new OperationList(name);
		op.setComputeRequirements(getComputeRequirements());

		if (!copy || shape.getCountLong() > 1) {
			if (shape.equalsIgnoreAxis(shape(out))) {
				op.add(a(name, traverse(shape.getTraversalAxis(), (Producer) out), in));
			} else {
				op.add(a(name, reshape(shape, out), in));
			}
		} else {
			if (!enableMemoryDataCopy)
				warn("Using MemoryDataCopy instead of Assignment for " + name);
			op.add(copy(name, in, out, shape.getTotalSize()));
		}

		return op;
	}

	public PackedCollection<?> getInput() { return input; }
	public PackedCollection<?> getOutput() { return output; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	public Receptor<PackedCollection<?>> getMonitor() {
		return monitor;
	}

	public void setMonitor(Receptor<PackedCollection<?>> monitor) {
		this.monitor = monitor;
	}

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
