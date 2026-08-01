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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A {@link Block} adapter that exposes only the forward pass of an existing block.
 *
 * <p>The backward cell is replaced with a no-op so that gradients are not propagated
 * through this block. This is useful when a block must appear in a {@link SequentialBlock}
 * pipeline but should not participate in backpropagation (e.g., frozen inference layers).</p>
 *
 * @see Block
 * @see SequentialBlock
 * @author Michael Murray
 */
public class ForwardOnlyBlock implements Block {
	/** The wrapped block whose forward cell is exposed. */
	private final Block block;

	/**
	 * Creates a forward-only wrapper around the given block.
	 *
	 * @param block the block whose forward cell will be exposed; its backward cell is ignored
	 */
	public ForwardOnlyBlock(Block block) {
		this.block = block;
	}

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() {
		return new OperationList();
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getInputShape() {
		return block.getInputShape();
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getOutputShape() {
		return block.getOutputShape();
	}

	/** {@inheritDoc} */
	@Override
	public Cell<PackedCollection> getForward() {
		return block.getForward();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return a no-op cell that discards all gradient pushes without propagating them
	 */
	@Override
	public Cell<PackedCollection> getBackward() {
		return Cell.of((input, next) -> new OperationList());
	}
}
