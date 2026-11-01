/*
 * Copyright 2026 Michael Murray
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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.Learning;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.layers.Tracking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link Block} that applies several child blocks to the same input in parallel and combines
 * their outputs. Every child receives the block's input independently; how the child outputs are
 * combined into the block's output, and how the output gradient is distributed back to each child,
 * are left to the subclass. This is the structural dual of {@link SequentialBlock}, which composes
 * blocks in series rather than in parallel.
 *
 * <p>The forward pass pushes the input to every child, captures each child's output via
 * {@link Cell.CaptureReceptor}, and pushes the {@link #combine(List) combined} result downstream.
 * The backward pass {@link #distribute(Producer, int) distributes} the output gradient to each
 * child's backward chain and accumulates every child's emitted input-gradient into a single shared
 * {@code inputShape}-sized buffer, then pushes the accumulated sum upstream and clears the buffer
 * for the next step. The children's gradients are accumulated as they are produced — rather than
 * captured and summed afterwards — because a child whose backward path ends in a
 * {@link BranchBlock} clears the gradient it just emitted, so a deferred read would see zero.</p>
 *
 * <p>The gradient buffer is allocated only when the backward cell is first built, so a block used
 * solely for inference holds no gradient memory. A parameter update set on this block reaches
 * every child that holds learnable parameters, so the weights inside each parallel path train.</p>
 *
 * <p>All children must accept this block's input shape, since the same input is pushed to each of
 * them and their input-gradients are summed into one input-sized buffer. Subclasses may impose
 * additional constraints on the children's output shapes (for example {@link AccumulationBlock}
 * requires them to share one output shape, while {@link ConcatenationBlock} does not).</p>
 *
 * @see Block
 * @see BranchBlock
 * @see SequentialBlock
 * @see AccumulationBlock
 * @see ConcatenationBlock
 * @author Michael Murray
 */
public abstract class ParallelBlock implements Block, Learning, Tracking {
	/** The input shape every child receives. */
	private final TraversalPolicy inputShape;

	/** This block's output shape, computed by the subclass from the children's output shapes. */
	private final TraversalPolicy outputShape;

	/** The child blocks applied in parallel and combined. */
	private final List<Block> children;

	/**
	 * Accumulated input-gradient sum from all child backward passes. Allocated only when the
	 * backward cell is built, so a block used solely for inference holds no gradient memory.
	 */
	private PackedCollection gradient;

	/** The lazily constructed forward cell returned by {@link #getForward()}. */
	private Cell<PackedCollection> forward;

	/** The lazily constructed backward cell returned by {@link #getBackward()}. */
	private Cell<PackedCollection> backward;

	/**
	 * Creates a block that combines the outputs of the given children applied to one input.
	 *
	 * @param inputShape  the input shape every child receives
	 * @param outputShape this block's output shape, determined by the subclass
	 * @param children    the child blocks to apply in parallel; at least one, all accepting
	 *                    {@code inputShape}
	 * @throws IllegalArgumentException if no children are given or a child does not accept
	 *                                  {@code inputShape}
	 */
	protected ParallelBlock(TraversalPolicy inputShape, TraversalPolicy outputShape, List<Block> children) {
		if (children.isEmpty()) {
			throw new IllegalArgumentException(getClass().getSimpleName() + " requires at least one block");
		}

		this.inputShape = inputShape;
		this.outputShape = outputShape;
		for (Block child : children) {
			if (child.getInputShape().getTotalSize() != inputShape.getTotalSize()) {
				throw new IllegalArgumentException(
						getClass().getSimpleName() + " requires every block to accept its input " + inputShape
								+ "; got a block expecting " + child.getInputShape());
			}
		}

		this.children = new ArrayList<>(children);
	}

	/**
	 * A stable label for the operations this block emits, used to name the forward, backward and
	 * gradient-accumulation operation lists.
	 *
	 * @return the operation label
	 */
	protected abstract String name();

	/**
	 * Combines the captured child outputs into this block's forward output. Called once per forward
	 * push, after every child's output has been captured.
	 *
	 * @param outputs the capture receptors holding each child's forward output, in child order
	 * @return a producer of this block's output, with total size equal to {@link #getOutputShape()}
	 */
	protected abstract Producer<PackedCollection> combine(List<Cell.CaptureReceptor<PackedCollection>> outputs);

	/**
	 * Distributes this block's output gradient to the child at the given index. Called once per
	 * child per backward push to determine the gradient pushed into that child's backward chain.
	 *
	 * @param outputGradient the gradient of this block's output
	 * @param childIndex     the index of the child receiving the gradient, in child order
	 * @return the gradient delivered to that child, matching its output shape
	 */
	protected abstract Producer<PackedCollection> distribute(Producer<PackedCollection> outputGradient, int childIndex);

	/**
	 * Returns an unmodifiable view of the child blocks combined by this block.
	 *
	 * @return an unmodifiable list of child {@link Block} instances
	 */
	public List<Block> getChildren() {
		return Collections.unmodifiableList(children);
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
	 * <p>Runs every child's setup, since the children are driven directly by this block rather
	 * than appended to a {@link SequentialBlock} that would collect them.</p>
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList(name() + " setup");
		children.forEach(child -> setup.add(child.setup()));
		return setup;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Pushes the input to every child, captures each child's output, and pushes their
	 * {@link #combine(List) combined} result downstream.</p>
	 */
	@Override
	public Cell<PackedCollection> getForward() {
		if (forward == null) {
			forward = Cell.of((input, next) -> {
				List<Cell.CaptureReceptor<PackedCollection>> outputs = new ArrayList<>();
				for (Block child : children) {
					Cell.CaptureReceptor<PackedCollection> receptor = new Cell.CaptureReceptor<>();
					child.getForward().setReceptor(receptor);
					outputs.add(receptor);
				}

				OperationList ops = new OperationList(name());
				for (Block child : children) {
					ops.add(child.getForward().push(input));
				}

				if (next != null) {
					ops.add(next.push(combine(outputs)));
				}
				return ops;
			});
		}

		return forward;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Pushes the {@link #distribute(Producer, int) distributed} output gradient into every
	 * child's backward chain and accumulates each child's emitted input-gradient into the shared
	 * buffer, then pushes the accumulated sum upstream and clears the buffer for the next step.
	 * Accumulation happens as each child emits, because a child ending in a {@link BranchBlock}
	 * clears the gradient it emitted immediately afterward.</p>
	 */
	@Override
	public Cell<PackedCollection> getBackward() {
		if (backward == null) {
			gradient = new PackedCollection(inputShape);
			Receptor<PackedCollection> aggregator = input ->
					a(name() + " aggregate", p(gradient.each()), add(p(gradient.each()), input));

			backward = Cell.of((gradientValue, next) -> {
				for (Block child : children) {
					child.getBackward().setReceptor(aggregator);
				}

				OperationList ops = new OperationList(name() + "-backward");
				for (int i = 0; i < children.size(); i++) {
					ops.add(children.get(i).getBackward().push(distribute(gradientValue, i)));
				}

				if (next != null) {
					ops.add(next.push(p(gradient)));
				}
				ops.add(a(name() + "-backward clear", p(gradient.each()), c(0.0)));
				return ops;
			});
		}

		return backward;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Propagates the update to every child that holds learnable parameters, so the weights
	 * inside each parallel path train.</p>
	 */
	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		children.forEach(child -> {
			if (child instanceof Learning) {
				((Learning) child).setParameterUpdate(update);
			}
		});
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Propagates the setting to every child that implements {@link Tracking}.</p>
	 */
	@Override
	public void setInputTracking(boolean inputTracking) {
		Tracking.propagate(children, inputTracking);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Propagates the requirements to every child, so a requirement applied to this block
	 * reaches the layers inside each parallel path.</p>
	 */
	@Override
	public void setComputeRequirements(List<ComputeRequirement> requirements) {
		children.forEach(child -> child.setComputeRequirements(requirements));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Destroys every child block and the gradient accumulation buffer.</p>
	 */
	@Override
	public void destroy() {
		children.forEach(child -> child.destroy());
		Destroyable.destroy(gradient);
	}
}
