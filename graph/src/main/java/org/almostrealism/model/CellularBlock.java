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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;

import java.util.function.Supplier;

public class CellularBlock implements Block {
	private final TraversalPolicy inputShape;
	private final TraversalPolicy outputShape;

	private final Cell<PackedCollection<?>> forward;
	private final Cell<PackedCollection<?>> backward;
	private final Supplier<Runnable> setup;

	public CellularBlock(TraversalPolicy inputShape, TraversalPolicy outputShape,
						 Cell<PackedCollection<?>> forward, Cell<PackedCollection<?>> backward,
						 Supplier<Runnable> setup) {
		this.inputShape = inputShape;
		this.outputShape = outputShape;
		this.forward = forward;
		this.backward = backward;
		this.setup = setup;
	}

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public TraversalPolicy getInputShape() { return inputShape; }

	@Override
	public TraversalPolicy getOutputShape() { return outputShape; }

	@Override
	public Cell<PackedCollection<?>> forward() { return forward; }

	@Override
	public Cell<PackedCollection<?>> backward() { return backward; }
}
