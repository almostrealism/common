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

/** The BackPropagationCell class. */
public class BackPropagationCell implements Cell<PackedCollection>, Learning, Nameable, CodeFeatures {
	private String name;

	private final BackPropagation propagation;
	private Supplier<PackedCollection> inputSupplier;

	private Receptor<PackedCollection> next;

	public BackPropagationCell(String name, BackPropagation propagation) {
		setName(name);
		this.propagation = propagation;

		if (propagation instanceof Nameable && ((Nameable) propagation).getName() == null) {
			((Nameable) propagation).setName(name);
		}
	}

	@Override
	public String getName() { return name; }

	@Override
	public void setName(String name) { this.name = name; }

	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		if (propagation instanceof Learning) {
			((Learning) propagation).setParameterUpdate(update);
		}
	}

	@Override
	public Supplier<Runnable> setup() { return new OperationList(); }

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> gradient) {
		return propagation.propagate(gradient, p(inputSupplier.get()), next);
	}

	/** Sets the forward input for backpropagation from a fixed collection. */
	public void setForwardInput(PackedCollection input) {
		this.inputSupplier = () -> input;
	}

	/** Sets the forward input supplier for lazy resolution during backpropagation. */
	public void setForwardInput(Supplier<PackedCollection> inputSupplier) {
		this.inputSupplier = inputSupplier;
	}

	@Override
	public void setReceptor(Receptor<PackedCollection> next) {
		if (this.next != null) {
			warn("Replacing receptor");
		}

		this.next = next;
	}
}
