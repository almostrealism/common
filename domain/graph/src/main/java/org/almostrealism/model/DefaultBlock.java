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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A simple {@link Block} implementation that wraps pre-constructed forward and backward cells.
 *
 * <p>{@code DefaultBlock} exposes the wrapped cells through a thin entry-cell shim that
 * intercepts {@link Cell#setReceptor(Receptor)} calls so the downstream receptor can be
 * swapped without modifying the inner forward cell's wiring.</p>
 *
 * <p>If the forward cell is {@code null}, the entry cell acts as a pass-through directly
 * to the downstream receptor.</p>
 *
 * @see Block
 * @see SequentialBlock
 * @author Michael Murray
 */
public class DefaultBlock implements Block {
	/** The expected input shape for this block. */
	private final TraversalPolicy inputShape;

	/** The shape produced by this block's forward cell. */
	private final TraversalPolicy outputShape;

	/** The setup operation run once before the first forward pass. */
	private final Supplier<Runnable> setup;

	/** The user-supplied forward transformation cell. */
	private final Cell<PackedCollection> forward;

	/** The user-supplied backward gradient cell. */
	private final Cell<PackedCollection> backward;

	/** The lazily constructed entry-cell shim returned by {@link #getForward()}. */
	private Cell<PackedCollection> entry;

	/** Receptor that forwards pushes to the optional downstream receptor. */
	private final Receptor<PackedCollection> push;

	/** The downstream receptor attached via the entry cell's {@code setReceptor} method. */
	private Receptor<PackedCollection> downstream;

	/**
	 * Creates a block with a no-op setup operation.
	 *
	 * @param inputShape  the expected input shape
	 * @param outputShape the shape produced by the forward cell
	 * @param forward     the forward transformation cell, or {@code null} for pass-through
	 * @param backward    the backward gradient cell
	 */
	public DefaultBlock(TraversalPolicy inputShape, TraversalPolicy outputShape,
						Cell<PackedCollection> forward, Cell<PackedCollection> backward) {
		this(inputShape, outputShape, forward, backward, new OperationList());
	}

	/**
	 * Creates a block with a custom setup operation.
	 *
	 * @param inputShape  the expected input shape
	 * @param outputShape the shape produced by the forward cell
	 * @param forward     the forward transformation cell, or {@code null} for pass-through
	 * @param backward    the backward gradient cell
	 * @param setup       the setup operation to run before the first forward pass
	 */
	public DefaultBlock(TraversalPolicy inputShape, TraversalPolicy outputShape,
						Cell<PackedCollection> forward, Cell<PackedCollection> backward,
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

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() {
		return setup;
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getInputShape() {
		return inputShape;
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getOutputShape() {
		return outputShape;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a lazily constructed entry cell. If a forward cell was provided it delegates
	 * push operations to it; otherwise it passes directly to the downstream receptor.</p>
	 */
	@Override
	public Cell<PackedCollection> getForward() {
		if (entry == null) {
			entry = new Cell<>() {
				@Override
				public Supplier<Runnable> setup() {
					return forward == null ? new OperationList() : forward.setup();
				}

				@Override
				public Supplier<Runnable> push(Producer<PackedCollection> in) {
					return forward == null ? push.push(in) : forward.push(in);
				}

				@Override
				public void setReceptor(Receptor<PackedCollection> r) {
					if (cellWarnings && DefaultBlock.this.downstream != null) {
						warn("Replacing receptor");
					}

					DefaultBlock.this.downstream = r;
				}

				@Override
				public Receptor<PackedCollection> getReceptor() {
					return DefaultBlock.this.downstream;
				}
			};
		}

		return entry;
	}

	/** {@inheritDoc} */
	@Override
	public Cell<PackedCollection> getBackward() {
		return backward;
	}
}
