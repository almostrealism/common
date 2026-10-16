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
import org.almostrealism.collect.CollectionProducer;
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
 * A {@link Block} that applies several child blocks to the same input and produces the
 * element-wise sum of their outputs. Every child receives the layer's input independently,
 * and the layer's output is {@code sum(child_i(input))}; this is the structural dual of
 * {@link SequentialBlock}, which composes blocks in series rather than in parallel.
 *
 * <p>Because the forward pass is an element-wise sum, the backward pass is gradient
 * pass-through to every child: each child receives the full output gradient and propagates it
 * through its own backward chain, and the child input-gradients are summed to form the
 * gradient delivered upstream. The children's gradients are accumulated into a shared buffer
 * as they are produced — rather than captured and summed afterwards — because a child whose
 * backward path ends in a {@link BranchBlock} clears the gradient it just emitted, so a
 * deferred read would see zero. A parameter update set on this block reaches every child that
 * holds learnable parameters, so the weights inside each summed path train.</p>
 *
 * <p>All children must accept this block's input shape, since the same input is pushed to each
 * of them and their input-gradients are summed into one input-sized buffer, and all must share an
 * output shape, which is also this block's output shape.</p>
 *
 * @see Block
 * @see BranchBlock
 * @see SequentialBlock
 * @see org.almostrealism.layers.LayerRoutingFeatures#accumBlocks(TraversalPolicy, List, ComputeRequirement...)
 * @author Michael Murray
 */
public class AccumulationBlock implements Block, Learning, Tracking {
	/** The input shape every child receives. */
	private final TraversalPolicy inputShape;

	/** The shared output shape of the children, which is this block's output shape. */
	private final TraversalPolicy outputShape;

	/** The child blocks applied in parallel and summed. */
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
	 * Creates a block that sums the outputs of the given children applied to one input.
	 *
	 * @param inputShape the input shape every child receives
	 * @param children   the child blocks to apply in parallel and sum; at least one, all accepting
	 *                   {@code inputShape} and sharing an output shape
	 * @throws IllegalArgumentException if no children are given, a child does not accept
	 *                                  {@code inputShape}, or the children do not share an
	 *                                  output shape
	 */
	public AccumulationBlock(TraversalPolicy inputShape, List<Block> children) {
		if (children.isEmpty()) {
			throw new IllegalArgumentException("AccumulationBlock requires at least one block");
		}

		this.inputShape = inputShape;
		this.outputShape = children.get(0).getOutputShape();
		for (Block child : children) {
			if (child.getInputShape().getTotalSize() != inputShape.getTotalSize()) {
				throw new IllegalArgumentException(
						"AccumulationBlock requires every block to accept its input " + inputShape
								+ "; got a block expecting " + child.getInputShape());
			}

			if (child.getOutputShape().getTotalSize() != outputShape.getTotalSize()) {
				throw new IllegalArgumentException(
						"AccumulationBlock requires all blocks to share an output shape; got "
								+ outputShape + " and " + child.getOutputShape());
			}
		}

		this.children = new ArrayList<>(children);
	}

	/**
	 * Returns an unmodifiable view of the child blocks summed by this block.
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
		OperationList setup = new OperationList("accum_blocks setup");
		children.forEach(child -> setup.add(child.setup()));
		return setup;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Pushes the input to every child, captures each child's output, and pushes their
	 * element-wise sum downstream. A forward output is recorded in its own stable buffer, so the
	 * captures may be summed after every child has run.</p>
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

				OperationList ops = new OperationList("accum_blocks");
				for (Block child : children) {
					ops.add(child.getForward().push(input));
				}

				if (next != null) {
					CollectionProducer sum = c(outputs.get(0).getReceipt()).reshape(outputShape);
					for (int i = 1; i < outputs.size(); i++) {
						sum = sum.add(c(outputs.get(i).getReceipt()).reshape(outputShape));
					}
					ops.add(next.push(sum));
				}
				return ops;
			});
		}

		return forward;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Pushes the output gradient into every child's backward chain and accumulates each
	 * child's emitted input-gradient into the shared buffer, then pushes the accumulated sum
	 * upstream and clears the buffer for the next step. The gradient of an element-wise sum is
	 * delivered in full to every summed path. Accumulation happens as each child emits, because a
	 * child ending in a {@link BranchBlock} clears the gradient it emitted immediately afterward.</p>
	 */
	@Override
	public Cell<PackedCollection> getBackward() {
		if (backward == null) {
			gradient = new PackedCollection(inputShape);
			Receptor<PackedCollection> aggregator = input ->
					a("accum_blocks aggregate", p(gradient.each()), add(p(gradient.each()), input));

			backward = Cell.of((gradientValue, next) -> {
				for (Block child : children) {
					child.getBackward().setReceptor(aggregator);
				}

				OperationList ops = new OperationList("accum_blocks-backward");
				for (Block child : children) {
					ops.add(child.getBackward().push(gradientValue));
				}

				if (next != null) {
					ops.add(next.push(p(gradient)));
				}
				ops.add(a("accum_blocks-backward clear", p(gradient.each()), c(0.0)));
				return ops;
			});
		}

		return backward;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Propagates the update to every child that holds learnable parameters, so the weights
	 * inside each summed path train.</p>
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
	 * reaches the layers inside each summed path.</p>
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
