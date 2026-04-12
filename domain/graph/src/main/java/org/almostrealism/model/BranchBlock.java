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
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.DefaultGradientPropagation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link Block} that distributes a single forward input to multiple parallel child
 * {@link CellularPropagation} branches and aggregates their backward gradients.
 *
 * <p>During the forward pass every {@link #append(CellularPropagation) appended} child
 * receives the same input producer. Any downstream receptor set on the entry cell also
 * receives the input directly.</p>
 *
 * <p>During the backward pass each child pushes its gradient into a shared accumulator
 * buffer ({@link #gradient}). Once all gradients have been summed the backward cell
 * pushes the accumulated gradient upstream and then zeroes the buffer for the next step.</p>
 *
 * @see Block
 * @see SequentialBlock
 * @author Michael Murray
 */
public class BranchBlock implements Block {
	/** The common input/output shape for this branch point. */
	private final TraversalPolicy shape;

	/** The lazily constructed entry cell returned by {@link #getForward()}. */
	private Cell<PackedCollection> entry;

	/** Receptor that fans out each push to all children and the optional downstream. */
	private final Receptor<PackedCollection> push;

	/** The optional downstream receptor attached to the entry cell. */
	private Receptor<PackedCollection> downstream;

	/** The lazily constructed backward cell returned by {@link #getBackward()}. */
	private Cell<PackedCollection> backwards;

	/** The child propagation units whose forward cells receive input and backward cells provide gradients. */
	private List<CellularPropagation<PackedCollection>> children;

	/** Accumulated gradient sum from all child backward passes. */
	private final PackedCollection gradient;

	/** Receptor that adds each child's gradient into {@link #gradient}. */
	private final Receptor<PackedCollection> aggregator;

	/**
	 * Creates a new branch block for data of the given shape.
	 *
	 * @param shape the input (and output) shape for this branch point
	 */
	public BranchBlock(TraversalPolicy shape) {
		this.shape = shape;

		this.push = in -> {
			OperationList op = new OperationList();
			children.stream().map(CellularPropagation::getForward).forEach(r -> op.add(r.push(in)));
			if (downstream != null) op.add(downstream.push(in));
			return op;
		};

		this.children = new ArrayList<>();
		this.gradient = new PackedCollection(shape);

		if (DefaultGradientPropagation.enableDiagnosticGrad) {
			this.aggregator = (input) -> {
				OperationList op = new OperationList("BranchBlock Aggregate");
				op.add(a("aggregate",
						p(gradient.each()), add(p(gradient.each()), input)));
				op.add(() -> () -> {
					gradient.print();
				});
				return op;
			};
		} else {
			this.aggregator = (input) ->
					a("aggregate",
							p(gradient.each()), add(p(gradient.each()), input));
		}
	}

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() {
		return new OperationList("BranchBlock Setup");
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getInputShape() {
		return shape;
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getOutputShape() {
		return shape;
	}

	/**
	 * Returns an unmodifiable view of the child propagation units appended to this block.
	 *
	 * @return an unmodifiable list of child {@link CellularPropagation} instances
	 */
	public List<CellularPropagation<PackedCollection>> getChildren() {
		return Collections.unmodifiableList(children);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a lazily constructed entry cell that fans out each push to all child forward
	 * cells and any downstream receptor. The receptor set on the returned cell becomes
	 * the optional downstream pass-through.</p>
	 */
	@Override
	public Cell<PackedCollection> getForward() {
		if (entry == null) {
			entry = new Cell<>() {
				@Override
				public Supplier<Runnable> push(Producer<PackedCollection> in) {
					return push.push(in);
				}

				@Override
				public void setReceptor(Receptor<PackedCollection> r) {
					if (cellWarnings && BranchBlock.this.downstream != null) {
						warn("Replacing receptor");
					}

					BranchBlock.this.downstream = r;
				}

				@Override
				public Receptor<PackedCollection> getReceptor() {
					return BranchBlock.this.downstream;
				}
			};
		}

		return entry;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a lazily constructed backward cell. When pushed with a gradient, it accumulates
	 * the gradient into the shared buffer, then forwards the accumulated value upstream and
	 * clears the buffer.</p>
	 */
	@Override
	public Cell<PackedCollection> getBackward() {
		if (backwards == null) {
			backwards = Cell.of((input, next) -> {
				OperationList op = new OperationList("BranchBlock Backward");
				op.add(aggregator.push(input));
				op.add(next.push(p(gradient)));
				op.add(a("clearBranchGradient", p(gradient.each()), c(0.0)));
				return op;
			});
		}

		return backwards;
	}

	/**
	 * Appends a child propagation unit to this branch.
	 *
	 * <p>The child's backward cell is wired to push its gradient into the shared
	 * {@link #aggregator} for accumulation.</p>
	 *
	 * @param <T> the concrete type of the child propagation
	 * @param l   the child to append
	 * @return {@code l}, for fluent chaining
	 */
	public <T extends CellularPropagation<PackedCollection>> T append(T l) {
		children.add(l);
		l.getBackward().setReceptor(aggregator);
		return l;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Releases the backward cell and the gradient accumulation buffer.</p>
	 */
	@Override
	public void destroy() {
		Destroyable.destroy(backwards);
		Destroyable.destroy(gradient);
	}
}
