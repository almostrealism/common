/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Nameable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** The DefaultCellularLayer class. */
public class DefaultCellularLayer implements CellularLayer, CodeFeatures, Learning, Nameable {
	public static boolean enableMemoryDataCopy = true;

	private TraversalPolicy inputShape;
	private final TraversalPolicy outputShape;
	private final Supplier<Runnable> setup;
	private Cell<PackedCollection> forward;
	private Cell<PackedCollection> backward;
	private List<PackedCollection> weights;

	private Cell<PackedCollection> entry;
	private Cell<PackedCollection> exit;
	private Cell<PackedCollection> fw;
	private Receptor<PackedCollection> monitor;

	private String name;
	private List<ComputeRequirement> requirements;

	private boolean inputTrackingEnabled;
	private boolean optimizeOnForward;
	private PackedCollection input;
	private PackedCollection output;

	public DefaultCellularLayer(String name,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward) {
		this(name, forward, backward, new OperationList());
	}

	public DefaultCellularLayer(String name,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward,
								Supplier<Runnable> setup) {
		this(name, forward, backward, Collections.emptyList(), setup);
	}

	public DefaultCellularLayer(String name,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward,
								List<PackedCollection> weights,
								Supplier<Runnable> setup) {
		this(name, Component.shape(forward).orElseThrow(IllegalArgumentException::new), forward, backward, weights, setup);
	}

	public DefaultCellularLayer(String name,
								TraversalPolicy outputShape,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward) {
		this(name, outputShape, forward, backward, Collections.emptyList(), new OperationList());
	}

	public DefaultCellularLayer(String name,
								TraversalPolicy outputShape,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward,
								List<PackedCollection> weights,
								Supplier<Runnable> setup) {
		setName(name);
		this.outputShape = outputShape;
		this.setup = setup;
		this.forward = forward;
		this.backward = backward;
		this.weights = weights;
	}

	@Override
	public void setName(String name) { this.name = name; }

	@Override
	public String getName() { return name; }

	public List<ComputeRequirement> getComputeRequirements() { return requirements; }

	public void setComputeRequirements(List<ComputeRequirement> requirements) { this.requirements = requirements; }

	/**
	 * Enables process optimization during forward pass execution.
	 * When true, {@link #getForward()} wraps the forward operation
	 * with an {@code optimize()} call to trigger process isolation
	 * for computations that require it (e.g., native loop generation).
	 *
	 * <p>This should be set for layers that contain computations with
	 * {@code isIsolationTarget() == true}, such as those using
	 * {@code LoopedWeightedSumComputation}. For layers compiled
	 * through {@code CompiledModel}, the top-level optimize() handles
	 * isolation, making this unnecessary.</p>
	 */
	public void setOptimizeOnForward(boolean optimize) { this.optimizeOnForward = optimize; }

	/** Performs the init operation. */
	public void init(TraversalPolicy inputShape, boolean inputTracking, boolean outputTracking) {
		this.inputShape = inputShape;

		if (!outputTracking) {
			if (inputTracking) throw new UnsupportedOperationException();
			return;
		}

		this.inputTrackingEnabled = inputTracking;
		this.output = outputTracking ? new PackedCollection(outputShape) : null;

		this.entry = Cell.of((in, next) -> {
			if (!inputTrackingEnabled) {
				return next.push(in);
			} else {
				if (this.input == null) {
					this.input = new PackedCollection(this.inputShape);
				}

				OperationList op = new OperationList(getName() + " layer (Entry)");
				op.add(into(getName() + " layer (Input Record)", in, p(input),
						enableMemoryDataCopy, getComputeRequirements()));
				if (HardwareFeatures.outputMonitoring) {
					op.add(new MonitorReceptor(getName() + " layer (Input Monitor)",
							getInputShape(), getOutputShape())
							.push(p(input)));
				}

				op.add(next.push(p(input)));
				return op;
			}
		});
		this.entry.setReceptor(forward);

		this.exit = Cell.of((in, next) -> output(in, p(output)));
		this.forward.setReceptor(exit);
	}

	private Supplier<Runnable> output(Producer<PackedCollection> in, Producer<PackedCollection> out) {
		Supplier<Runnable> o = into(getName() + " layer " +
				getInputShape() + "->" + getOutputShape(), in, out, enableMemoryDataCopy,
				getComputeRequirements());
		if (getMonitor() == null) {
			return o;
		}

		OperationList op = new OperationList(getName() + " layer (Output and Monitor)");
		op.add(o);
		op.add(getMonitor().push(out));
		return op;
	}

	/**
	 * Returns the input buffer for this layer, allocating it lazily
	 * if input tracking is enabled but the buffer has not yet been created.
	 */
	public PackedCollection getInput() {
		if (input == null && inputTrackingEnabled) {
			this.input = new PackedCollection(this.inputShape);
		}
		return input;
	}
	public PackedCollection getOutput() { return output; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	public Receptor<PackedCollection> getMonitor() {
		return monitor;
	}

	public void setMonitor(Receptor<PackedCollection> monitor) {
		this.monitor = monitor;
	}

	/**
	 * Disables input tracking for this layer by nulling the input buffer.
	 * When the input buffer is null, the entry cell skips the input copy
	 * operation, passing data directly to the forward cell. The exit copy
	 * to the output buffer is preserved to maintain expression tree
	 * isolation boundaries between layers.
	 *
	 * <p>This has the same effect as calling {@code init(inputShape, false, true)},
	 * which is the path used when {@code Layer.ioTracking} is disabled via
	 * the {@code AR_GRAPH_IO_TRACKING} system property.</p>
	 */
	@Override
	public void disableTracking() {
		this.inputTrackingEnabled = false;
		if (this.input != null) {
			this.input.destroy();
			this.input = null;
		}
	}

	@Override
	public Cell<PackedCollection> getForward() {
		if (this.output == null) {
			return this.forward;
		} else if (fw == null) {
			fw = Cell.of((in, next) -> {
				OperationList op = optimizeOnForward
						? new OperationList(getName() + " Layer (Forward)") {
							@Override
							public Runnable get() { return optimize().get(); }
						}
						: new OperationList(getName() + " Layer (Forward)");
				op.add(entry.push(in));
				if (next != null) op.add(next.push(p(output)));
				return op;
			});
		}

		return fw;
	}

	public void setBackward(Cell<PackedCollection> backward) {
		this.backward = backward;
	}

	@Override
	public Cell<PackedCollection> getBackward() {
		return backward;
	}

	@Override
	public TraversalPolicy getInputShape() { return inputShape; }

	@Override
	public TraversalPolicy getOutputShape() { return outputShape; }

	@Override
	public List<PackedCollection> getWeights() { return weights; }

	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		if (forward instanceof Learning) ((Learning) forward).setParameterUpdate(update);
		if (backward instanceof Learning) ((Learning) backward).setParameterUpdate(update);
	}

	@Override
	public void destroy() {
		Destroyable.destroy(forward);
		Destroyable.destroy(backward);
		Destroyable.destroy(input);
		Destroyable.destroy(output);
		Destroyable.destroy(setup);

		if (weights != null) {
			weights.forEach(PackedCollection::destroy);
			weights = null;
		}

		forward = null;
		backward = null;
		input = null;
		output = null;
		entry = null;
	}

	@Override
	public String describe() {
		return getName() + " " + getInputShape() + "->" + getOutputShape();
	}
}
