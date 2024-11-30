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

public class ForwardOnlyBlock implements Block {
	private Block block;

	public ForwardOnlyBlock(Block block) {
		this.block = block;
	}

	@Override
	public Supplier<Runnable> setup() {
		return new OperationList();
	}

	@Override
	public TraversalPolicy getInputShape() {
		return block.getInputShape();
	}

	@Override
	public TraversalPolicy getOutputShape() {
		return block.getOutputShape();
	}

	@Override
	public Cell<PackedCollection<?>> getForward() {
		return block.getForward();
	}

	@Override
	public Cell<PackedCollection<?>> getBackward() {
		return Cell.of((input, next) -> new OperationList());
	}
}
