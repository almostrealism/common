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
 * flat concatenation of their outputs, in child order. Every child receives the block's input
 * independently, and the block's output is a single vector of shape {@code [Σ part sizes]}: the
 * first child's output, followed by the second child's, and so on.
 *
 * <p>Because the forward pass lays each child's output into its own contiguous segment of the
 * output, the backward pass gives each child the slice of the output gradient for its segment —
 * cut by flat offset and size, then reshaped to that child's output shape — and sums the children's
 * input-gradients to form the gradient delivered upstream. This makes a model that uses
 * {@code concat_blocks} trainable: the weights inside every concatenated part receive their own
 * gradient.</p>
 *
 * <p>All children must accept this block's input shape, since the same input is pushed to each of
 * them and their input-gradients are summed into one input-sized buffer. Unlike
 * {@link AccumulationBlock}, the children need not share an output shape — each contributes its own
 * segment, of whatever size, to the concatenation.</p>
 *
 * @see ParallelBlock
 * @see AccumulationBlock
 * @see org.almostrealism.layers.LayerRoutingFeatures#concatBlocks(TraversalPolicy, List, ComputeRequirement...)
 * @author Michael Murray
 */
public class ConcatenationBlock extends ParallelBlock {
	/**
	 * Creates a block that concatenates the outputs of the given children applied to one input.
	 *
	 * @param inputShape the input shape every child receives
	 * @param children   the child blocks to apply in parallel and concatenate; at least one, all
	 *                   accepting {@code inputShape}
	 * @throws IllegalArgumentException if no children are given or a child does not accept
	 *                                  {@code inputShape}
	 */
	public ConcatenationBlock(TraversalPolicy inputShape, List<Block> children) {
		super(inputShape, outputShapeOf(children), children);
	}

	/** {@inheritDoc} */
	@Override
	protected String name() {
		return "concat_blocks";
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Concatenates the children's outputs in order into one flat vector. Each output is first
	 * flattened to its total size, so children of differing ranks (for example a {@code [2, 3]}
	 * part followed by a {@code [4]} part) concatenate into the same contiguous segments that
	 * {@link #distribute(Producer, int)} slices the gradient back out of.</p>
	 */
	@Override
	protected Producer<PackedCollection> combine(List<Cell.CaptureReceptor<PackedCollection>> outputs) {
		List<Block> children = getChildren();
		CollectionProducer[] parts = new CollectionProducer[children.size()];
		for (int i = 0; i < children.size(); i++) {
			parts[i] = c(outputs.get(i).getReceipt())
					.reshape(shape(children.get(i).getOutputShape().getTotalSize()));
		}
		return concat(getOutputShape(), parts);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Each child receives the slice of the output gradient covering its segment of the
	 * concatenation: the {@code [offset, offset + part size)} range along the flat output axis,
	 * reshaped to that child's output shape. A child whose output does not depend on the input
	 * (its backward produces a zero input-gradient) therefore still receives its correct segment
	 * and contributes nothing to the summed input gradient.</p>
	 */
	@Override
	protected Producer<PackedCollection> distribute(Producer<PackedCollection> outputGradient, int childIndex) {
		List<Block> children = getChildren();
		int offset = 0;
		for (int i = 0; i < childIndex; i++) {
			offset += children.get(i).getOutputShape().getTotalSize();
		}

		TraversalPolicy childShape = children.get(childIndex).getOutputShape();
		return subset(shape(childShape.getTotalSize()), outputGradient, offset).reshape(childShape);
	}

	/**
	 * Computes the concatenated output shape: a flat vector whose size is the sum of the children's
	 * output sizes.
	 *
	 * @param children the child blocks
	 * @return the flat output shape {@code [Σ part sizes]}
	 */
	private static TraversalPolicy outputShapeOf(List<Block> children) {
		int totalSize = 0;
		for (Block child : children) {
			totalSize += child.getOutputShape().getTotalSize();
		}
		return new TraversalPolicy(totalSize);
	}
}
