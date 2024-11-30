/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class DefaultBlock implements Block {
	private TraversalPolicy inputShape;
	private TraversalPolicy outputShape;

	private Supplier<Runnable> setup;
	private Cell<PackedCollection<?>> forward;
	private Cell<PackedCollection<?>> backward;

	private Cell<PackedCollection<?>> entry;
	private Receptor<PackedCollection<?>> push;
	private Receptor<PackedCollection<?>> downstream;

	public DefaultBlock(TraversalPolicy inputShape, TraversalPolicy outputShape,
						Cell<PackedCollection<?>> forward, Cell<PackedCollection<?>> backward) {
		this(inputShape, outputShape, forward, backward, new OperationList());
	}

	public DefaultBlock(TraversalPolicy inputShape, TraversalPolicy outputShape,
						Cell<PackedCollection<?>> forward, Cell<PackedCollection<?>> backward,
						Supplier<Runnable> setup) {
		this.inputShape = inputShape;
		this.outputShape = outputShape;
		this.setup = setup;
		this.forward = forward;
		this.backward = backward;

		this.push = in -> {
			OperationList op = new OperationList();
			if (downstream != null) op.add(downstream.push(in));
			return op;
		};

		if (this.forward != null) {
			this.forward.setReceptor(push);
		}
	}

	@Override
	public Supplier<Runnable> setup() {
		return setup;
	}

	@Override
	public TraversalPolicy getInputShape() {
		return inputShape;
	}

	@Override
	public TraversalPolicy getOutputShape() {
		return outputShape;
	}

	@Override
	public Cell<PackedCollection<?>> getForward() {
		if (entry == null) {
			entry = new Cell<>() {
				@Override
				public Supplier<Runnable> setup() {
					return forward == null ? new OperationList() : forward.setup();
				}

				@Override
				public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
					return forward == null ? push.push(in) : forward.push(in);
				}

				@Override
				public void setReceptor(Receptor<PackedCollection<?>> r) {
					if (cellWarnings && DefaultBlock.this.downstream != null) {
						warn("Replacing receptor");
					}

					DefaultBlock.this.downstream = r;
				}
			};
		}

		return entry;
	}

	@Override
	public Cell<PackedCollection<?>> getBackward() {
		return backward;
	}
}
