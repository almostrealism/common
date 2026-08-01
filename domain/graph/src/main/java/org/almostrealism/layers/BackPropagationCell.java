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

import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Nameable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A {@link Cell} that executes a {@link BackPropagation} strategy during the backward pass.
 *
 * <p>{@code BackPropagationCell} bridges the cell-based computation graph with the
 * gradient propagation model. It stores a reference to the forward-pass input
 * (set via {@link #setForwardInput(PackedCollection)}) and invokes the configured
 * {@link BackPropagation} strategy when data is pushed to it during backpropagation.</p>
 *
 * <p>This cell is created by {@link LayerFeatures} and connected as the backward
 * cell of each {@link DefaultCellularLayer}. The downstream receptor is the backward
 * cell of the preceding layer in the network.</p>
 *
 * @see BackPropagation
 * @see DefaultGradientPropagation
 * @see DefaultCellularLayer
 * @author Michael Murray
 */
public class BackPropagationCell implements Cell<PackedCollection>, Learning, Nameable, CodeFeatures {
	/** The human-readable name for this cell, used in diagnostics and logging. */
	private String name;

	/** The gradient propagation strategy to invoke during backpropagation. */
	private final BackPropagation propagation;

	/** The forward-pass input collection, set by the owning layer after construction. */
	private PackedCollection input;

	/** The upstream receptor receiving the propagated gradient. */
	private Receptor<PackedCollection> next;

	/**
	 * Creates a new BackPropagationCell with the given name and propagation strategy.
	 *
	 * @param name        a human-readable name for logging and diagnostics
	 * @param propagation the strategy used to compute and propagate gradients
	 */
	public BackPropagationCell(String name, BackPropagation propagation) {
		setName(name);
		this.propagation = propagation;

		if (propagation instanceof Nameable && ((Nameable) propagation).getName() == null) {
			((Nameable) propagation).setName(name);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getName() { return name; }

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setName(String name) { this.name = name; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates the parameter update to the propagation strategy if it supports learning.</p>
	 *
	 * @param update the parameter update strategy
	 */
	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		if (propagation instanceof Learning) {
			((Learning) propagation).setParameterUpdate(update);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return an empty setup operation
	 */
	@Override
	public Supplier<Runnable> setup() { return new OperationList(); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Invokes the backpropagation strategy with the given gradient and the stored
	 * forward-pass input, propagating the resulting gradient to the upstream receptor.</p>
	 *
	 * @param gradient the gradient of the loss with respect to this layer's output
	 * @return a supplier that performs gradient computation and parameter updates
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> gradient) {
		return propagation.propagate(gradient, p(input), next);
	}

	/**
	 * Sets the forward-pass input collected during the forward pass.
	 * This must be called before the first backward push.
	 *
	 * @param input the input data from the forward pass
	 */
	public void setForwardInput(PackedCollection input) {
		this.input = input;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @param next the upstream receptor to receive the propagated gradient
	 */
	@Override
	public void setReceptor(Receptor<PackedCollection> next) {
		if (this.next != null) {
			warn("Replacing receptor");
		}

		this.next = next;
	}
}
