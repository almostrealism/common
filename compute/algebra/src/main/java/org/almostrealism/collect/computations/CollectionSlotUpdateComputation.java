/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that replaces one slot per block of a base collection with new values,
 * selecting each block's slot position at runtime from a positions collection, and
 * passing every other element of the base through unchanged.
 *
 * <p>The base collection is treated as {@code blocks} contiguous blocks of
 * {@code blockSize} elements. For block {@code n}, the {@code slotSize} elements
 * starting at offset {@code positions[n]} (block-relative) are replaced with the
 * elements of {@code values[n * slotSize .. (n+1) * slotSize)}:</p>
 *
 * <pre>
 * out[n * blockSize + off] = values[n * slotSize + (off - positions[n])]
 *                                      if positions[n] &lt;= off &lt; positions[n] + slotSize
 *                          = base[n * blockSize + off]   otherwise
 * </pre>
 *
 * <p>This is the destination-indexed update that ring buffers (replace the head-aligned
 * frame, keep the rest of the ring) and cache-style structures (write one entry at a
 * runtime position per block) need. Expressing it as a <em>single</em> computation whose
 * inputs are plain collections matters as much as the semantics: building the same
 * result from per-slot {@code subset}/mask/{@code concat} compositions leaves a tree of
 * small computations that {@link io.almostrealism.compute.Process#optimize} may isolate,
 * and each isolated stage is then evaluated synchronously (dispatch plus completion
 * wait) on every evaluation. Here the entire selection is one expression over the raw
 * arguments, so there is nothing to isolate.</p>
 *
 * <p>The slot must fit within its block without wrapping: each {@code positions[n]} is
 * expected to satisfy {@code 0 <= positions[n] <= blockSize - slotSize}. Positions that
 * violate this read outside the values range for the wrapped elements rather than
 * wrapping around.</p>
 *
 * @see CollectionConcatenateComputation
 * @see DynamicIndexProjectionProducerComputation
 *
 * @author  Michael Murray
 */
public class CollectionSlotUpdateComputation extends TransitiveDeltaExpressionComputation
		implements CollectionFeatures {

	/** Number of blocks in the base collection. */
	private final int blocks;
	/** Elements per block of the base collection. */
	private final int blockSize;
	/** Elements replaced per block. */
	private final int slotSize;

	/**
	 * Constructs a slot update over the given base collection.
	 *
	 * @param shape     output shape; its total size must be {@code blocks * blockSize}
	 * @param blocks    number of blocks in the base collection
	 * @param blockSize elements per block
	 * @param slotSize  elements replaced per block
	 * @param base      the collection to update, total size {@code blocks * blockSize}
	 * @param values    replacement values, total size {@code blocks * slotSize}
	 * @param positions block-relative slot start positions, total size {@code blocks}
	 * @throws IllegalArgumentException if any argument's total size is inconsistent
	 *                                  with {@code blocks}/{@code blockSize}/{@code slotSize}
	 */
	public CollectionSlotUpdateComputation(TraversalPolicy shape,
										   int blocks, int blockSize, int slotSize,
										   Producer<PackedCollection> base,
										   Producer<PackedCollection> values,
										   Producer<PackedCollection> positions) {
		super("slotUpdate", shape, base, values, positions);
		this.blocks = blocks;
		this.blockSize = blockSize;
		this.slotSize = slotSize;

		if (shape.getTotalSizeLong() != (long) blocks * blockSize) {
			throw new IllegalArgumentException("Output shape " + shape
					+ " does not cover " + blocks + " blocks of " + blockSize);
		} else if (shape(base).getTotalSizeLong() != (long) blocks * blockSize) {
			throw new IllegalArgumentException("Base size " + shape(base).getTotalSizeLong()
					+ " does not cover " + blocks + " blocks of " + blockSize);
		} else if (shape(values).getTotalSizeLong() != (long) blocks * slotSize) {
			throw new IllegalArgumentException("Values size " + shape(values).getTotalSizeLong()
					+ " does not cover " + blocks + " slots of " + slotSize);
		} else if (shape(positions).getTotalSizeLong() != blocks) {
			throw new IllegalArgumentException("Positions size "
					+ shape(positions).getTotalSizeLong()
					+ " does not provide one slot position per block (" + blocks + ")");
		}

		init();
	}

	/**
	 * Gradients flow through the base (index 1) and the values (index 2); the slot
	 * positions (index 3) select where values land and are not differentiable.
	 *
	 * @param index the argument index to check (0 is the destination)
	 * @return whether gradients propagate through the argument
	 */
	@Override
	protected boolean isTransitiveArgumentIndex(int index) {
		return index == 1 || index == 2;
	}

	/**
	 * Generates the expression selecting, for each output position, either the
	 * replacement value (when the position falls inside the block's slot) or the
	 * base element.
	 *
	 * @param args traversable arguments where {@code args[1]} is the base,
	 *             {@code args[2]} the values, and {@code args[3]} the positions
	 * @return the selection expression
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		TraversalPolicy outputShape = getShape();

		return DefaultCollectionExpression.create(outputShape, idx -> {
			long totalSize = outputShape.getTotalSizeLong();
			Expression<?> batchIdx = idx.divide(totalSize);
			Expression<?> localIdx = idx.imod(totalSize);

			Expression<?> block = localIdx.divide((long) blockSize);
			Expression<?> offset = localIdx.imod((long) blockSize);

			Expression<Integer> position = args[3]
					.getValueAt(block.add(batchIdx.multiply((long) blocks)))
					.toInt();
			Expression<?> relative = offset.subtract(position);

			Expression<?> valueIdx = block.multiply((long) slotSize)
					.add(relative)
					.add(batchIdx.multiply((long) blocks * slotSize));

			Expression<Boolean> inSlot = relative.greaterThanOrEqual(0)
					.and(relative.lessThan(slotSize));
			return Conditional.of(inSlot,
					args[2].getValueAt(valueIdx),
					args[1].getValueAt(idx));
		});
	}

	/**
	 * Generates a new slot update with the given child processes. The block structure is
	 * positional, so the children must preserve the original argument shapes — a delta
	 * expansion that appends target dimensions is not yet representable.
	 *
	 * @param children the child processes (destination plus base, values, positions)
	 * @return a new {@link CollectionSlotUpdateComputation} over the children
	 * @throws UnsupportedOperationException if a child's shape differs from the
	 *                                       corresponding original argument shape
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		Producer<PackedCollection> base = (Producer) children.get(1);
		Producer<PackedCollection> values = (Producer) children.get(2);
		Producer<PackedCollection> positions = (Producer) children.get(3);

		if (shape(base).getTotalSizeLong() != (long) blocks * blockSize
				|| shape(values).getTotalSizeLong() != (long) blocks * slotSize) {
			throw new UnsupportedOperationException(
					"Slot update cannot be regenerated with reshaped arguments"
							+ " (delta expansion is not supported)");
		}

		return (CollectionProducerParallelProcess)
				new CollectionSlotUpdateComputation(getShape(), blocks, blockSize, slotSize,
						base, values, positions)
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) {
			return null;
		}

		return signature + "{blocks=" + blocks
				+ ",blockSize=" + blockSize + ",slotSize=" + slotSize + "}";
	}
}
