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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;

import java.util.List;

/**
 * A {@link ParallelBlock} that applies several child blocks to the same input and produces the
 * element-wise sum of their outputs. Every child receives the block's input independently, and the
 * block's output is {@code sum(child_i(input))}.
 *
 * <p>Because the forward pass is an element-wise sum, the backward pass is gradient pass-through to
 * every child: each child receives the full output gradient and propagates it through its own
 * backward chain, and the child input-gradients are summed to form the gradient delivered
 * upstream.</p>
 *
 * <p>All children must accept this block's input shape and all must share an output shape, which is
 * also this block's output shape.</p>
 *
 * @see ParallelBlock
 * @see ConcatenationBlock
 * @see org.almostrealism.layers.LayerRoutingFeatures#accumBlocks(TraversalPolicy, List, ComputeRequirement...)
 * @author Michael Murray
 */
public class AccumulationBlock extends ParallelBlock {
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
		super(inputShape, outputShapeOf(children), children);

		TraversalPolicy outputShape = getOutputShape();
		for (Block child : children) {
			if (child.getOutputShape().getTotalSize() != outputShape.getTotalSize()) {
				throw new IllegalArgumentException(
						"AccumulationBlock requires all blocks to share an output shape; got "
								+ outputShape + " and " + child.getOutputShape());
			}
		}
	}

	/** {@inheritDoc} */
	@Override
	protected String name() {
		return "accum_blocks";
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Sums the children's outputs element-wise, each reshaped to the shared output shape.</p>
	 */
	@Override
	protected Producer<PackedCollection> combine(List<Cell.CaptureReceptor<PackedCollection>> outputs) {
		CollectionProducer sum = c(outputs.get(0).getReceipt()).reshape(getOutputShape());
		for (int i = 1; i < outputs.size(); i++) {
			sum = sum.add(c(outputs.get(i).getReceipt()).reshape(getOutputShape()));
		}
		return sum;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The gradient of an element-wise sum is delivered in full to every summed path, so every
	 * child receives the whole output gradient.</p>
	 */
	@Override
	protected Producer<PackedCollection> distribute(Producer<PackedCollection> outputGradient, int childIndex) {
		return outputGradient;
	}

	/**
	 * Computes the shared output shape of the children: that of the first child. Checks for an
	 * empty child list here, before the shape of the first child is read.
	 *
	 * @param children the child blocks
	 * @return the output shape of the first child
	 * @throws IllegalArgumentException if no children are given
	 */
	private static TraversalPolicy outputShapeOf(List<Block> children) {
		if (children.isEmpty()) {
			throw new IllegalArgumentException("AccumulationBlock requires at least one block");
		}

		return children.get(0).getOutputShape();
	}
}
