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
 * The standard implementation of {@link CellularLayer} that manages forward and backward
 * cells, input/output tracking, and weight management.
 *
 * <p>{@code DefaultCellularLayer} wraps a forward cell and a backward cell, adding
 * optional input/output recording for debugging and gradient computation. When
 * tracking is enabled (via {@link #init(TraversalPolicy, boolean, boolean)}), the
 * layer captures its input and output in {@link PackedCollection} buffers so that
 * backpropagation can access the recorded forward-pass data.</p>
 *
 * <p>The layer manages a pipeline of internal cells:</p>
 * <ul>
 *   <li><b>entry</b> - Optionally records the input and forwards it</li>
 *   <li><b>forward</b> - The user-supplied transformation cell</li>
 *   <li><b>exit</b> - Records the output</li>
 * </ul>
 *
 * @see CellularLayer
 * @see LayerFeatures
 * @author Michael Murray
 */
public class DefaultCellularLayer implements CellularLayer, CodeFeatures, Learning, Nameable {
	/**
	 * Flag to enable memory-copy-based input/output recording.
	 * When false, assignment-based recording is used instead.
	 */
	public static boolean enableMemoryDataCopy = true;

	/** The expected input shape, set during {@link #init(TraversalPolicy, boolean, boolean)}. */
	private TraversalPolicy inputShape;

	/** The shape of output data produced by this layer. */
	private final TraversalPolicy outputShape;

	/** The setup operation for this layer, run before forward passes. */
	private final Supplier<Runnable> setup;

	/** The forward-pass transformation cell. */
	private Cell<PackedCollection> forward;

	/** The backward-pass gradient cell. */
	private Cell<PackedCollection> backward;

	/** The learnable parameters for this layer. */
	private List<PackedCollection> weights;

	/** The entry cell that optionally records input and forwards it. */
	private Cell<PackedCollection> entry;

	/** The exit cell that records the forward cell's output. */
	private Cell<PackedCollection> exit;

	/** The combined forward cell exposed to external callers. */
	private Cell<PackedCollection> fw;

	/** An optional monitoring receptor for NaN/zero detection on output. */
	private Receptor<PackedCollection> monitor;

	/** The human-readable name for this layer, used in logging and diagnostics. */
	private String name;

	/** Optional compute requirements to apply to operations in this layer. */
	private List<ComputeRequirement> requirements;

	/** The recorded forward-pass input (when input tracking is enabled). */
	private PackedCollection input;

	/** The recorded forward-pass output. */
	private PackedCollection output;

	/**
	 * Creates a layer with a no-op setup operation and no learnable weights.
	 *
	 * <p>The output shape is inferred from the forward cell's shape.</p>
	 *
	 * @param name     a human-readable label used in logging and diagnostics
	 * @param forward  the forward-pass transformation cell
	 * @param backward the backward-pass gradient cell
	 */
	public DefaultCellularLayer(String name,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward) {
		this(name, forward, backward, new OperationList());
	}

	/**
	 * Creates a layer with a custom setup operation and no learnable weights.
	 *
	 * <p>The output shape is inferred from the forward cell's shape.</p>
	 *
	 * @param name     a human-readable label used in logging and diagnostics
	 * @param forward  the forward-pass transformation cell
	 * @param backward the backward-pass gradient cell
	 * @param setup    the setup operation to run before the first forward pass
	 */
	public DefaultCellularLayer(String name,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward,
								Supplier<Runnable> setup) {
		this(name, forward, backward, Collections.emptyList(), setup);
	}

	/**
	 * Creates a layer with learnable weights, inferring the output shape from the forward cell.
	 *
	 * @param name     a human-readable label used in logging and diagnostics
	 * @param forward  the forward-pass transformation cell
	 * @param backward the backward-pass gradient cell
	 * @param weights  the learnable parameter collections for this layer
	 * @param setup    the setup operation to run before the first forward pass
	 * @throws IllegalArgumentException if the output shape cannot be inferred from the forward cell
	 */
	public DefaultCellularLayer(String name,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward,
								List<PackedCollection> weights,
								Supplier<Runnable> setup) {
		this(name, Component.shape(forward).orElseThrow(IllegalArgumentException::new), forward, backward, weights, setup);
	}

	/**
	 * Creates a layer with an explicit output shape, no weights, and a no-op setup.
	 *
	 * @param name        a human-readable label used in logging and diagnostics
	 * @param outputShape the shape produced by the forward cell
	 * @param forward     the forward-pass transformation cell
	 * @param backward    the backward-pass gradient cell
	 */
	public DefaultCellularLayer(String name,
								TraversalPolicy outputShape,
								Cell<PackedCollection> forward,
								Cell<PackedCollection> backward) {
		this(name, outputShape, forward, backward, Collections.emptyList(), new OperationList());
	}

	/**
	 * Full constructor that sets all fields directly.
	 *
	 * @param name        a human-readable label used in logging and diagnostics
	 * @param outputShape the shape produced by the forward cell
	 * @param forward     the forward-pass transformation cell
	 * @param backward    the backward-pass gradient cell
	 * @param weights     the learnable parameter collections for this layer
	 * @param setup       the setup operation to run before the first forward pass
	 */
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
	 * Initializes tracking buffers and wires up the entry/exit pipeline.
	 *
	 * <p>When {@code outputTracking} is true, a {@link PackedCollection} is allocated for
	 * the output and the forward cell is wrapped so that every push copies its result into
	 * that buffer. When {@code inputTracking} is additionally true, a second buffer is
	 * allocated for the input and the entry cell copies each incoming value before forwarding
	 * it. If neither is requested the method returns immediately without allocating buffers.</p>
	 *
	 * @param inputShape    the expected shape of data arriving at this layer's forward cell
	 * @param inputTracking whether to capture the forward-pass input in a buffer
	 * @param outputTracking whether to capture the forward-pass output in a buffer
	 * @throws UnsupportedOperationException if {@code inputTracking} is true but
	 *                                        {@code outputTracking} is false
	 */
	public void init(TraversalPolicy inputShape, boolean inputTracking, boolean outputTracking) {
		this.inputShape = inputShape;

		if (!outputTracking) {
			if (inputTracking) throw new UnsupportedOperationException();
			return;
		}

		this.input = inputTracking ? new PackedCollection(inputShape) : null;
		this.output = outputTracking ? new PackedCollection(outputShape) : null;

		this.entry = Cell.of((in, next) -> {
			if (this.input == null) {
				return next.push(in);
			} else {
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

	/**
	 * Builds the output recording operation, optionally appending a monitor push.
	 *
	 * @param in  the producer whose value is to be recorded
	 * @param out the destination buffer producer
	 * @return an operation that copies {@code in} into {@code out} and, if a monitor is
	 *         set, additionally pushes {@code out} to {@link #getMonitor()}
	 */
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
	 * Returns the recorded forward-pass input, or {@code null} if input tracking is disabled.
	 *
	 * @return the input buffer populated during the last forward pass, or {@code null}
	 */
	public PackedCollection getInput() { return input; }

	/**
	 * Returns the recorded forward-pass output, or {@code null} if output tracking is disabled.
	 *
	 * @return the output buffer populated during the last forward pass, or {@code null}
	 */
	public PackedCollection getOutput() { return output; }

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() { return setup; }

	/**
	 * Returns the optional monitoring receptor for this layer's output.
	 *
	 * @return the monitor receptor, or {@code null} if none has been set
	 */
	public Receptor<PackedCollection> getMonitor() {
		return monitor;
	}

	/**
	 * Sets an optional monitoring receptor that receives the recorded output on every
	 * forward pass, useful for NaN/zero detection and diagnostics.
	 *
	 * @param monitor the receptor to notify after each output recording, or {@code null}
	 *                to disable monitoring
	 */
	public void setMonitor(Receptor<PackedCollection> monitor) {
		this.monitor = monitor;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>When output tracking is disabled, returns the raw forward cell directly.
	 * When output tracking is enabled, returns a composite cell that first pushes
	 * through the entry/forward/exit pipeline and then forwards the recorded output
	 * to any downstream receptor.</p>
	 */
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

	/**
	 * Replaces the backward-pass gradient cell.
	 *
	 * <p>This is used when the backward cell must be constructed separately from the
	 * forward cell and assigned after initial construction.</p>
	 *
	 * @param backward the new backward cell to use for gradient propagation
	 */
	public void setBackward(Cell<PackedCollection> backward) {
		this.backward = backward;
	}

	/** {@inheritDoc} */
	@Override
	public Cell<PackedCollection> getBackward() {
		return backward;
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getInputShape() { return inputShape; }

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getOutputShape() { return outputShape; }

	/** {@inheritDoc} */
	@Override
	public List<PackedCollection> getWeights() { return weights; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates the update to the forward cell if it implements {@link Learning}, and
	 * likewise to the backward cell.</p>
	 */
	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		if (forward instanceof Learning) ((Learning) forward).setParameterUpdate(update);
		if (backward instanceof Learning) ((Learning) backward).setParameterUpdate(update);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Releases all resources held by this layer, including the forward and backward cells,
	 * the input/output tracking buffers, the setup operation, and all weight collections.
	 * After this call the layer is no longer usable.</p>
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * @return a human-readable string of the form {@code "name inputShape->outputShape"}
	 */
	@Override
	public String describe() {
		return getName() + " " + getInputShape() + "->" + getOutputShape();
	}
}
