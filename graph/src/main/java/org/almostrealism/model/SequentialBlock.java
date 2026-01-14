/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Named;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.Component;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.Learning;
import org.almostrealism.layers.ParameterUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class SequentialBlock implements Block, Learning, LayerFeatures {
	public static boolean enableWarnings = false;
	public static boolean enableComposites = true;

	private final TraversalPolicy inputShape;

	private List<Block> blocks;

	private Cell<PackedCollection> entry;
	private final Receptor<PackedCollection> push;
	private Receptor<PackedCollection> downstream;

	private Cell<PackedCollection> propagate;
	private final Receptor<PackedCollection> back;
	private Receptor<PackedCollection> upstream;

	private Producer<PackedCollection> learningRate;
	private ParameterUpdate<PackedCollection> parameterUpdate;

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
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		this.parameterUpdate = update;

		blocks.forEach(b -> {
			if (b instanceof Learning)
				((Learning) b).setParameterUpdate(update);
		});
	}

	public <T extends Block> void add(Function<TraversalPolicy, T> factory) {
		add(factory.apply(getOutputShape()));
	}

	public CellularLayer add(String name, Factor<PackedCollection> operator,
							 ComputeRequirement... requirements) {
		return add(name, getOutputShape(), operator, requirements);
	}

	public CellularLayer add(String name, TraversalPolicy outputShape,
							 Factor<PackedCollection> operator,
							 ComputeRequirement... requirements) {
		return add(layer(name, getOutputShape(), outputShape, operator, requirements));
	}

	public <T extends Block> T add(T block) {
		if (block.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException("Cannot add a Block which expects " + block.getInputShape() +
					" input to a SequentialBlock that produces " + getOutputShape());

		Block last = lastBlock();
		Receptor<PackedCollection> prev;
		if (last != null) {
			// Preserve existing receptor (e.g., cache writes from andThen()) by chaining
			Receptor<PackedCollection> existing = last.getForward().getReceptor();
			if (existing != null) {
				last.getForward().setReceptor(Receptor.to(existing, block.getForward()));
			} else {
				last.getForward().setReceptor(block.getForward());
			}
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
		applyParameterUpdate(block);

		// Chain with existing receptor if one was set (e.g., via andThen() for cache writes)
		Cell<PackedCollection> forward = lastBlock().getForward();
		Receptor<PackedCollection> existing = forward.getReceptor();
		if (existing != null) {
			forward.setReceptor(Receptor.to(existing, push));
		} else {
			forward.setReceptor(push);
		}
		return block;
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

	public List<Block> split(int count) { return split(count, 0); }

	public List<Block> split(int count, int axis) {
		return split(count, axis, -1);
	}

	public List<Block> split(int count, int axis, int mainIndex) {
		if (count <= 0) {
			throw new IllegalArgumentException("Count must be greater than zero");
		}

		TraversalPolicy superShape = getOutputShape();
		long len = superShape.lengthLong(axis);
		if (len % count != 0) {
			throw new IllegalArgumentException("Count must evenly divide the length of the axis");
		}

		int splitLength = (int) (len / count);
		TraversalPolicy splitShape = superShape.replaceDimension(axis, splitLength);
		return split(splitShape, mainIndex);
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

		int axis;

		int[] axes = superShape.differingAxes(splitShape);
		if (axes.length > 1) {
			throw new IllegalArgumentException("Cannot split along multiple dimensions");
		} else if (axes.length == 1) {
			axis = axes[0];
		} else {
			warn("Unnecessary split");
			axis = -1;
		}

		int count = superShape.length(axis) / splitShape.length(axis);
		List<Block> blocks = new ArrayList<>();

		BranchBlock split = new BranchBlock(superShape);
		Block main = null;

		for (int i = 0; i < count; i++) {
			int section = i * splitShape.length(axis);
			int[] pos = IntStream.range(0, superShape.getDimensions()).map(j -> j == axis ? section : 0).toArray();

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


	public void accum(Block value, ComputeRequirement... requirements) {
		accum(value, true, requirements);
	}

	// TODO  Should return 'this'?
	public void accum(Block value, boolean branch, ComputeRequirement... requirements) {
		if (branch && value.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		if (enableComposites) {
			// Create a branch to direct the current output
			// to the other block
			if (branch)
				value = branch(value);

			// Creat a Block which combines the residual and
			// the output of the other block
			add(accum(value.getOutputShape(), value, requirements));
		} else {
			add(accum(getOutputShape(), value.getForward(), requirements));
		}
	}

	public void product(Function<TraversalPolicy, ? extends Block> a,
						Function<TraversalPolicy, ? extends Block> b,
						ComputeRequirement... requirements) {
		product(a.apply(getOutputShape()), b.apply(getOutputShape()), requirements);
	}

	public void product(Function<TraversalPolicy, ? extends Block> a, Block b,
						ComputeRequirement... requirements) {
		product(a.apply(getOutputShape()), b, requirements);
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
	public <T extends Block> SequentialBlock andThen(T next) {
		add(next);
		return this;
	}

	protected void applyParameterUpdate(Component block) {
		if (block instanceof Learning) {
			((Learning) block).setParameterUpdate(parameterUpdate);
		}
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
	public Cell<PackedCollection> getForward() {
		if (entry == null) {
			entry = new Cell<>() {
				@Override
				public Supplier<Runnable> push(Producer<PackedCollection> in) {
					Block first = firstBlock();

					if (first == null) {
						return SequentialBlock.this.downstream.push(in);
					} else {
						return first.getForward().push(in);
					}
				}

				@Override
				public void setReceptor(Receptor<PackedCollection> r) {
					if (cellWarnings && SequentialBlock.this.downstream != null) {
						warn("Replacing receptor");
					}

					Block last = lastBlock();
					if (last != null) {
						last.getForward().setReceptor(r);
					} else {
						SequentialBlock.this.downstream = r;
					}
				}

				@Override
				public Receptor<PackedCollection> getReceptor() {
					Block last = lastBlock();
					if (last != null) {
						return last.getForward().getReceptor();
					} else {
						return SequentialBlock.this.downstream;
					}
				}
			};
		}

		return entry;
	}

	@Override
	public Cell<PackedCollection> getBackward() {
		if (propagate == null) {
			propagate = new Cell<>() {
				@Override
				public Supplier<Runnable> push(Producer<PackedCollection> in) {
					Block last = lastBlock();

					if (last == null) {
						return SequentialBlock.this.upstream.push(in);
					} else {
						return last.getBackward().push(in);
					}
				}

				@Override
				public void setReceptor(Receptor<PackedCollection> r) {
					if (cellWarnings && SequentialBlock.this.upstream != null) {
						warn("Replacing receptor");
					}

					Block first = firstBlock();
					if (first != null) {
						first.getBackward().setReceptor(r);
					} else {
						SequentialBlock.this.upstream = r;
					}
				}
			};
		}

		return propagate;
	}

	@Override
	public void destroy() {
		if (blocks != null) {
			blocks.forEach(Block::destroy);
			blocks = null;
		}
	}
}
