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

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Named;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.Learning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class SequentialBlock implements Block, Learning, LayerFeatures {
	public static boolean enableWarnings = false;
	public static boolean enableComposites = true;

	private TraversalPolicy inputShape;

	private List<Block> blocks;

	private Cell<PackedCollection<?>> entry;
	private Receptor<PackedCollection<?>> push;
	private Receptor<PackedCollection<?>> downstream;

	private Cell<PackedCollection<?>> propagate;
	private Receptor<PackedCollection<?>> back;
	private Receptor<PackedCollection<?>> upstream;

	public SequentialBlock(TraversalPolicy inputShape) {
		this.inputShape = inputShape;
		this.blocks = new ArrayList<>();

		this.push = in -> {
			OperationList op = new OperationList();
			if (downstream != null) op.add(downstream.push(in));
			return op;
		};

		this.back = in -> {
			OperationList op = new OperationList();
			if (upstream != null) op.add(upstream.push(in));
			return op;
		};
	}

	@Override
	public void setLearningRate(Producer<PackedCollection<?>> learningRate) {
		blocks.forEach(b -> {
			if (b instanceof Learning)
				((Learning) b).setLearningRate(learningRate);
		});
	}

	public <T extends Block> void add(Function<TraversalPolicy, T> factory) {
		add(factory.apply(getOutputShape()));
	}

	public CellularLayer add(String name, Factor<PackedCollection<?>> operator,
							 ComputeRequirement... requirements) {
		return add(name, getOutputShape(), operator, requirements);
	}

	public CellularLayer add(String name, TraversalPolicy outputShape,
							 Factor<PackedCollection<?>> operator,
							 ComputeRequirement... requirements) {
		return add(layer(name, getOutputShape(), outputShape, operator, requirements));
	}

	public <T extends Block> T add(T block) {
		if (block.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		Block last = lastBlock();
		Receptor<PackedCollection<?>> prev;
		if (last != null) {
			last.getForward().setReceptor(block.getForward());
			prev = last.getBackward();
		} else {
			prev = back;
		}

		if (block.getBackward() == null) {
			if (enableWarnings)
				warn("No backward Cell for " + Named.nameOf(block));
		} else {
			block.getBackward().setReceptor(prev);
		}

		blocks.add(block);
		lastBlock().getForward().setReceptor(push);
		return block;
	}

	public SequentialBlock branch() {
		BranchBlock split = new BranchBlock(getOutputShape());
		SequentialBlock branch = split.append(new SequentialBlock(getOutputShape()));
		add(split);
		return branch;
	}

	public <T extends Block> T branch(Function<TraversalPolicy, T> factory) {
		return branch(factory.apply(getOutputShape()));
	}

	public <T extends Block> T branch(T branch) {
		if (branch.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		BranchBlock split = new BranchBlock(getOutputShape());
		split.append(branch);
		add(split);
		return branch;
	}


	public List<Block> split(TraversalPolicy subsetShape) {
		return split(subsetShape, -1);
	}

	public List<Block> split(TraversalPolicy subsetShape, int mainIndex) {
		TraversalPolicy superShape = getOutputShape();
		TraversalPolicy splitShape = padDimensions(subsetShape, 1, superShape.getDimensions());

		if (superShape.length(0) % splitShape.length(0) != 0) {
			throw new IllegalArgumentException("Split subset must evenly divide its input");
		} else if (superShape.getDimensions() != splitShape.getDimensions()) {
			throw new IllegalArgumentException("Split cannot change the total number of dimensions");
		}

		for (int i = 1; i < superShape.getDimensions(); i++) {
			if (splitShape.length(i) != superShape.length(i))
				throw new IllegalArgumentException("Split is only permitted along first dimension");
		}

		int count = superShape.length(0) / splitShape.length(0);
		List<Block> blocks = new ArrayList<>();

		BranchBlock split = new BranchBlock(superShape);
		Block main = null;

		for (int i = 0; i < count; i++) {
			int section = i;
			int[] pos = IntStream.range(0, superShape.getDimensions()).map(j -> j == 0 ? section : 0).toArray();

			SequentialBlock sub = new SequentialBlock(superShape);
			sub.add(subset(superShape, splitShape, pos));
			if (sub.getOutputShape().getDimensions() != subsetShape.getDimensions())
				sub.reshape(subsetShape);

			if (i == mainIndex) {
				main = sub;
			} else {
				split.append(sub);
			}

			blocks.add(sub);
		}

		add(split);

		if (main != null) {
			add(main);
		}

		return blocks;
	}

	// TODO  Should return 'this'?
	public void accum(Block value, ComputeRequirement... requirements) {
		if (value.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		if (enableComposites) {
			// Create a branch to direct the current output
			// to the other block
			value = branch(value);

			// Creat a Block which combines the residual and
			// the output of the other block
			add(accum(value.getOutputShape(), value, requirements));
		} else {
			add(accum(getOutputShape(), value.getForward(), requirements));
		}
	}

	// TODO  Should return 'this'?
	public void product(Block a, Block b, ComputeRequirement... requirements) {
		if (a.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();
		if (b.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		if (enableComposites) {
			// Create a branch to direct the current output
			// to Block 'b' along with the next block
			b = branch(b);

			// Create a block to apply Block 'a' and then
			// multiply its output with the output of Block 'b'
			SequentialBlock product = new SequentialBlock(getOutputShape());
			product.add(a);
			product.add(product(a.getOutputShape(), b, requirements));

			// Make the product Block the next block
			add(product);
		} else {
			add(product(a.getInputShape(), a.getOutputShape(), a.getForward(), b.getForward(), requirements));
		}
	}

	@Override
	public <T extends Block> Block andThen(T next) {
		add(next);
		return this;
	}

	@Override
	public Supplier<Runnable> setup() {
		return blocks.stream().map(Block::setup).collect(OperationList.collector());
	}

	@Override
	public TraversalPolicy getInputShape() {
		return inputShape;
	}

	@Override
	public TraversalPolicy getOutputShape() {
		return blocks.isEmpty() ? getInputShape() : lastBlock().getOutputShape();
	}

	public Block firstBlock() {
		return blocks.isEmpty() ? null : blocks.get(0);
	}

	public Block lastBlock() {
		return blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
	}

	public List<Block> getBlocks() {
		return Collections.unmodifiableList(blocks);
	}

	@Override
	public Cell<PackedCollection<?>> getForward() {
		if (entry == null) {
			entry = new Cell<>() {
				@Override
				public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
					return firstBlock().getForward().push(in);
				}

				@Override
				public void setReceptor(Receptor<PackedCollection<?>> r) {
					if (SequentialBlock.this.downstream != null) {
						warn("Replacing receptor");
					}

					SequentialBlock.this.downstream = r;
				}
			};
		}

		return entry;
	}

	@Override
	public Cell<PackedCollection<?>> getBackward() {
		if (propagate == null) {
			propagate = new Cell<>() {
				@Override
				public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
					return lastBlock().getBackward().push(in);
				}

				@Override
				public void setReceptor(Receptor<PackedCollection<?>> r) {
					if (SequentialBlock.this.upstream != null) {
						warn("Replacing receptor");
					}

					SequentialBlock.this.upstream = r;
				}
			};
		}

		return propagate;
	}
}
