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

/**
 * The standard trainable layer implementation that wraps a forward computation cell
 * with entry and exit cells for input/output tracking.
 *
 * <p>DefaultCellularLayer introduces an <strong>entry/exit cell architecture</strong> around
 * the core forward cell. The entry cell optionally copies input data into a dedicated buffer
 * (input tracking), and the exit cell copies the forward cell's output into an output buffer.
 * These copies serve two purposes:</p>
 * <ul>
 *   <li><strong>Input tracking</strong>: Preserves the original input for backpropagation.
 *       The {@link BackPropagationCell} needs access to the forward pass input to compute
 *       gradients. Without tracking, the input may be overwritten by subsequent operations.</li>
 *   <li><strong>Output tracking</strong>: Captures the layer's output in a stable buffer so
 *       downstream consumers can read it after the forward pass completes.</li>
 * </ul>
 *
 * <h2>Training vs Inference</h2>
 * <p>Input tracking is only required during training. For inference-only execution,
 * {@link #setInputTracking(boolean)} can disable it, structurally rebuilding the entry cell
 * as a simple pass-through. This eliminates the input copy overhead (~18% of forward pass
 * time in profiled models). The rebuild happens before graph optimization, so the optimizer
 * sees a clean structure with no dead branches.</p>
 *
 * <h2>Comparison with DefaultBlock</h2>
 * <p>{@link org.almostrealism.model.DefaultBlock} is a lightweight alternative with no
 * input/output buffers and no tracking overhead. Use DefaultBlock for pure transformations
 * that don't need weight updates or gradient computation. Use DefaultCellularLayer for
 * trainable layers with weights.</p>
 *
 * @see org.almostrealism.model.DefaultBlock
 * @see CellularLayer
 * @see BackPropagationCell
 * @see org.almostrealism.model.CompiledModel
 * @author Michael Murray
 */
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
	 * Initializes this layer with the given input shape and tracking configuration.
	 * This creates the entry cell, exit cell, input buffer (if tracking), and output buffer,
	 * and wires them to the forward cell. Must be called before the layer can participate
	 * in a computation graph.
	 *
	 * @param inputShape the shape of input data this layer will receive
	 * @param inputTracking whether to copy input into a dedicated buffer for backpropagation
	 * @param outputTracking whether to copy output into a dedicated buffer
	 * @throws UnsupportedOperationException if inputTracking is true but outputTracking is false
	 */
	public void init(TraversalPolicy inputShape, boolean inputTracking, boolean outputTracking) {
		this.inputShape = inputShape;

		if (!outputTracking) {
			if (inputTracking) throw new UnsupportedOperationException();
			return;
		}

		this.inputTrackingEnabled = inputTracking;
		this.output = new PackedCollection(outputShape);

		if (inputTracking) {
			this.input = new PackedCollection(inputShape);
		}

		buildEntryCell();

		this.exit = Cell.of((in, next) -> output(in, p(output)));
		this.forward.setReceptor(exit);
	}

	/**
	 * Reconfigures whether this layer tracks (copies) its input during the forward pass.
	 * When {@code inputTracking} is {@code true}, the entry cell copies input data into
	 * a dedicated buffer before forwarding, enabling backpropagation to access the original
	 * input. When {@code false}, the entry cell passes input through without copying,
	 * eliminating overhead for inference-only execution.
	 *
	 * <p>This method performs a <strong>structural rebuild</strong> of the entry cell.
	 * The cached composite forward cell ({@code fw}) is intentionally preserved because
	 * its lambda reads {@code this.entry} at runtime, so it automatically picks up the
	 * rebuilt entry cell. Invalidating {@code fw} would destroy the inter-block receptor
	 * chain wired during {@link org.almostrealism.model.SequentialBlock#add}.</p>
	 *
	 * <p>Must be called before the computation graph is optimized (i.e., before
	 * {@code Process.optimize()} or {@code OperationList.flatten().optimize()}).
	 * Calling it after optimization has undefined behavior.</p>
	 *
	 * @param inputTracking whether to enable input tracking for this layer
	 * @throws IllegalStateException if the layer has not been initialized via {@link #init}
	 */
	public void setInputTracking(boolean inputTracking) {
		if (this.output == null) {
			throw new IllegalStateException("Layer has not been initialized");
		}

		if (this.inputTrackingEnabled == inputTracking) {
			return;
		}

		this.inputTrackingEnabled = inputTracking;

		if (inputTracking && this.input == null) {
			this.input = new PackedCollection(this.inputShape);
		} else if (!inputTracking && this.input != null) {
			this.input.destroy();
			this.input = null;
		}

		buildEntryCell();
	}

	private void buildEntryCell() {
		if (inputTrackingEnabled) {
			this.entry = Cell.of((in, next) -> {
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
			});
		} else {
			this.entry = Cell.of((in, next) -> next.push(in));
		}

		this.entry.setReceptor(forward);
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
	 * Returns the input tracking buffer, or {@code null} if input tracking is disabled.
	 * During the forward pass with tracking enabled, the entry cell copies input data
	 * into this buffer. The {@link BackPropagationCell} reads from this buffer to
	 * compute gradients.
	 *
	 * @return the input tracking buffer, or null if tracking is disabled
	 */
	public PackedCollection getInput() { return input; }

	/**
	 * Returns the output buffer where the exit cell stores the forward pass result.
	 *
	 * @return the output buffer, or null if the layer has not been initialized with output tracking
	 */
	public PackedCollection getOutput() { return output; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	public Receptor<PackedCollection> getMonitor() {
		return monitor;
	}

	public void setMonitor(Receptor<PackedCollection> monitor) {
		this.monitor = monitor;
	}

	@Override
	public Cell<PackedCollection> getForward() {
		if (this.output == null) {
			return this.forward;
		} else if (fw == null) {
			fw = Cell.of((in, next) -> {
				OperationList op = new OperationList(getName() + " Layer (Forward)");
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
