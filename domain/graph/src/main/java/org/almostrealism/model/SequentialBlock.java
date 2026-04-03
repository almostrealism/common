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
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.layers.Learning;
import org.almostrealism.layers.ParameterUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A {@link Block} that chains multiple child {@link Block} instances in sequence, wiring
 * each block's forward output to the next block's forward input and each block's backward
 * input to the previous block's backward cell.
 *
 * <p>Blocks are appended via {@link #add(Block)} (and its overloads). The sequential block
 * exposes a single forward entry cell and a single backward propagation cell; both delegate
 * to the first and last child blocks respectively.</p>
 *
 * <p>Convenience methods support common composition patterns:</p>
 * <ul>
 *   <li>{@link #branch(Block)} — adds a parallel branch that shares the current output</li>
 *   <li>{@link #split(int)} — splits the output along an axis into equally sized sub-blocks</li>
 *   <li>{@link #accum(Block, ComputeRequirement...)} — adds a residual accumulation step</li>
 *   <li>{@link #product(Block, Block, ComputeRequirement...)} — element-wise product of two paths</li>
 * </ul>
 *
 * @see Block
 * @see BranchBlock
 * @see DefaultBlock
 * @author Michael Murray
 */
public class SequentialBlock implements Block, Learning, LayerRoutingFeatures {
	/** When {@code true}, logs a warning when a block with no backward cell is added. */
	public static boolean enableWarnings = false;

	/**
	 * When {@code true} (default), uses composite {@link BranchBlock}-based implementations
	 * for {@link #accum} and {@link #product}; when {@code false} uses simpler cell-level paths.
	 */
	public static boolean enableComposites = true;

	/** The input shape of the first block in the chain (or the block itself if empty). */
	private final TraversalPolicy inputShape;

	/** The ordered list of child blocks. */
	private List<Block> blocks;

	/** The lazily constructed entry cell returned by {@link #getForward()}. */
	private Cell<PackedCollection> entry;

	/** Receptor that forwards the last block's output to the optional downstream receptor. */
	private final Receptor<PackedCollection> push;

	/** The optional downstream receptor set via the entry cell's {@code setReceptor} method. */
	private Receptor<PackedCollection> downstream;

	/** The lazily constructed backward cell returned by {@link #getBackward()}. */
	private Cell<PackedCollection> propagate;

	/** Receptor that forwards the first block's backward output to the optional upstream receptor. */
	private final Receptor<PackedCollection> back;

	/** The optional upstream receptor set via the propagate cell's {@code setReceptor} method. */
	private Receptor<PackedCollection> upstream;

	/** Unused learning-rate producer retained for future use. */
	private Producer<PackedCollection> learningRate;

	/** The parameter update strategy propagated to all child blocks that implement {@link Learning}. */
	private ParameterUpdate<PackedCollection> parameterUpdate;

	/**
	 * Creates an empty sequential block with the given input shape.
	 *
	 * @param inputShape the shape of data arriving at the first block's forward cell
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>Stores the update and immediately propagates it to all currently added child blocks
	 * that implement {@link Learning}.</p>
	 */
	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		this.parameterUpdate = update;

		blocks.forEach(b -> {
			if (b instanceof Learning)
				((Learning) b).setParameterUpdate(update);
		});
	}

	/**
	 * Appends the block produced by {@code factory} for the current output shape.
	 *
	 * @param <T>     the concrete block type
	 * @param factory a function that creates the block given the current output shape
	 */
	public <T extends Block> void add(Function<TraversalPolicy, T> factory) {
		add(factory.apply(getOutputShape()));
	}

	/**
	 * Creates and appends a new layer with the given name and operator, keeping the current output shape.
	 *
	 * @param name         a human-readable label for the layer
	 * @param operator     the differentiable forward operator
	 * @param requirements optional compute requirements
	 * @return the newly created and appended {@link CellularLayer}
	 */
	public CellularLayer add(String name, Factor<PackedCollection> operator,
							 ComputeRequirement... requirements) {
		return add(name, getOutputShape(), operator, requirements);
	}

	/**
	 * Creates and appends a new layer with an explicit output shape.
	 *
	 * @param name         a human-readable label for the layer
	 * @param outputShape  the shape produced by the new layer
	 * @param operator     the differentiable forward operator
	 * @param requirements optional compute requirements
	 * @return the newly created and appended {@link CellularLayer}
	 */
	public CellularLayer add(String name, TraversalPolicy outputShape,
							 Factor<PackedCollection> operator,
							 ComputeRequirement... requirements) {
		return add(layer(name, getOutputShape(), outputShape, operator, requirements));
	}

	/**
	 * Appends a pre-built block, wiring its forward and backward cells into the chain.
	 *
	 * <p>The previous block's forward cell is wired to the new block's forward cell. The new
	 * block's backward cell is wired to the previous block's backward receptor. If a
	 * {@link ParameterUpdate} has been set it is applied to the new block immediately.</p>
	 *
	 * @param <T>   the concrete block type
	 * @param block the block to append
	 * @return {@code block}, for fluent chaining
	 * @throws IllegalArgumentException if the block's input shape does not match the current output shape
	 */
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

	/**
	 * Appends a parallel branch created by the given factory for the current output shape.
	 *
	 * @param <T>     the concrete block type
	 * @param factory a function that creates the branch given the current output shape
	 * @return the newly created branch block
	 */
	public <T extends Block> T branch(Function<TraversalPolicy, T> factory) {
		return branch(factory.apply(getOutputShape()));
	}

	/**
	 * Wraps the current tail in a {@link BranchBlock} and appends the given block as a parallel branch.
	 *
	 * <p>The forward output is forwarded to both the branch and the next block in the sequence.</p>
	 *
	 * @param <T>    the concrete block type
	 * @param branch the block to run in parallel with the current sequence tail
	 * @return {@code branch}
	 * @throws IllegalArgumentException if the branch's input shape does not match the current output shape
	 */
	public <T extends Block> T branch(T branch) {
		if (branch.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		BranchBlock split = new BranchBlock(getOutputShape());
		split.append(branch);
		add(split);
		return branch;
	}

	/**
	 * Splits the current output evenly along axis 0 into {@code count} sub-blocks.
	 *
	 * @param count the number of equal sub-blocks to create
	 * @return an ordered list of the created sub-blocks
	 */
	public List<Block> split(int count) { return split(count, 0); }

	/**
	 * Splits the current output evenly along the specified axis into {@code count} sub-blocks.
	 *
	 * @param count the number of equal sub-blocks to create
	 * @param axis  the axis along which to split
	 * @return an ordered list of the created sub-blocks
	 */
	public List<Block> split(int count, int axis) {
		return split(count, axis, -1);
	}

	/**
	 * Splits the current output evenly along an axis, optionally keeping one sub-block in the main path.
	 *
	 * @param count     the number of equal sub-blocks to create
	 * @param axis      the axis along which to split
	 * @param mainIndex the zero-based index of the sub-block that remains in the main sequential path,
	 *                  or {@code -1} if all sub-blocks should be branches
	 * @return an ordered list of the created sub-blocks
	 * @throws IllegalArgumentException if {@code count} is zero or does not evenly divide the axis length
	 */
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

	/**
	 * Splits the current output into sub-blocks of the given shape with no main-path block.
	 *
	 * @param subsetShape the shape of each sub-block
	 * @return an ordered list of the created sub-blocks
	 */
	public List<Block> split(TraversalPolicy subsetShape) {
		return split(subsetShape, -1);
	}

	/**
	 * Splits the current output into sub-blocks of the given shape, optionally keeping one in the main path.
	 *
	 * @param subsetShape the shape of each sub-block; must evenly divide the current output shape
	 * @param mainIndex   the zero-based index of the sub-block that remains in the main path,
	 *                    or {@code -1} if all sub-blocks should be branches
	 * @return an ordered list of the created sub-blocks
	 * @throws IllegalArgumentException if the subset shape does not evenly divide the output shape or
	 *                                  if the split would require changing dimension count
	 */
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


	/**
	 * Adds a residual accumulation step that sums the current output with the output of {@code value}.
	 *
	 * @param value        the block whose output is added to the main path output
	 * @param requirements optional compute requirements for the accumulation operation
	 */
	public void accum(Block value, ComputeRequirement... requirements) {
		accum(value, true, requirements);
	}

	/**
	 * Adds a residual accumulation step, optionally wrapping {@code value} in a branch first.
	 *
	 * @param value        the block whose output is added to the main path output
	 * @param branch       when {@code true}, the current output is branched to {@code value} as well
	 * @param requirements optional compute requirements for the accumulation operation
	 * @throws IllegalArgumentException if {@code branch} is {@code true} and the input shapes do not match
	 */
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

	/**
	 * Applies two factory-produced blocks to the same input and accumulates their outputs element-wise.
	 *
	 * @param a            factory that produces the first block
	 * @param b            factory that produces the second block
	 * @param requirements optional compute requirements
	 */
	public void accum(Function<TraversalPolicy, ? extends Block> a,
					  Function<TraversalPolicy, ? extends Block> b,
					  ComputeRequirement... requirements) {
		accum(a.apply(getOutputShape()), b.apply(getOutputShape()), requirements);
	}

	/**
	 * Applies a factory-produced block and a pre-built block to the same input and accumulates their outputs.
	 *
	 * @param a            factory that produces the first block
	 * @param b            the second pre-built block
	 * @param requirements optional compute requirements
	 */
	public void accum(Function<TraversalPolicy, ? extends Block> a, Block b,
					  ComputeRequirement... requirements) {
		accum(a.apply(getOutputShape()), b, requirements);
	}

	/**
	 * Applies two blocks to the same input and accumulates their outputs element-wise.
	 * Both {@code a} and {@code b} receive the current sequential output as input.
	 * The combined output replaces the current tail.
	 *
	 * @param a first block, transforms current input to some shape
	 * @param b second block, transforms same current input to the same output shape as {@code a}
	 * @param requirements optional compute requirements
	 */
	public void accum(Block a, Block b, ComputeRequirement... requirements) {
		if (a.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();
		if (b.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		if (enableComposites) {
			// Branch b off the current output so it receives the same input as a
			b = branch(b);

			// Build a sub-block: apply a, then element-wise add b's output
			SequentialBlock sum = new SequentialBlock(getOutputShape());
			sum.add(a);
			sum.add(accum(a.getOutputShape(), b, requirements));

			add(sum);
		} else {
			add(accum(getOutputShape(), b.getForward(), requirements));
		}
	}

	/**
	 * Adds an element-wise product step using blocks produced by the given factories.
	 *
	 * @param a            factory that produces the first operand block
	 * @param b            factory that produces the second operand block
	 * @param requirements optional compute requirements for the product operation
	 */
	public void product(Function<TraversalPolicy, ? extends Block> a,
						Function<TraversalPolicy, ? extends Block> b,
						ComputeRequirement... requirements) {
		product(a.apply(getOutputShape()), b.apply(getOutputShape()), requirements);
	}

	/**
	 * Adds an element-wise product step between a factory-produced block and a pre-built block.
	 *
	 * @param a            factory that produces the first operand block for the current output shape
	 * @param b            the pre-built second operand block
	 * @param requirements optional compute requirements for the product operation
	 */
	public void product(Function<TraversalPolicy, ? extends Block> a, Block b,
						ComputeRequirement... requirements) {
		product(a.apply(getOutputShape()), b, requirements);
	}

	/**
	 * Adds an element-wise product of blocks {@code a} and {@code b}.
	 *
	 * <p>Block {@code b} is branched from the current output. The current output is then
	 * processed by {@code a}, and its result is multiplied element-wise with the output of
	 * {@code b}.</p>
	 *
	 * @param a            the first operand block applied to the main path
	 * @param b            the second operand block running in parallel
	 * @param requirements optional compute requirements for the product operation
	 * @throws IllegalArgumentException if either block's input shape does not match the current output
	 */
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

	/** {@inheritDoc} */
	@Override
	public <T extends Block> SequentialBlock andThen(T next) {
		add(next);
		return this;
	}

	/**
	 * Applies the currently configured {@link ParameterUpdate} to the given component if it
	 * implements {@link Learning}.
	 *
	 * @param block the component to receive the parameter update strategy
	 */
	protected void applyParameterUpdate(Component block) {
		if (block instanceof Learning) {
			((Learning) block).setParameterUpdate(parameterUpdate);
		}
	}

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() {
		return blocks.stream().map(Block::setup).collect(OperationList.collector());
	}

	/** {@inheritDoc} */
	@Override
	public TraversalPolicy getInputShape() {
		return inputShape;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the output shape of the last appended block, or {@link #getInputShape()} if empty
	 */
	@Override
	public TraversalPolicy getOutputShape() {
		return blocks.isEmpty() ? getInputShape() : lastBlock().getOutputShape();
	}

	/**
	 * Returns the first block in the chain, or {@code null} if the chain is empty.
	 *
	 * @return the first {@link Block}, or {@code null}
	 */
	public Block firstBlock() {
		return blocks.isEmpty() ? null : blocks.get(0);
	}

	/**
	 * Returns the last block in the chain, or {@code null} if the chain is empty.
	 *
	 * @return the last {@link Block}, or {@code null}
	 */
	public Block lastBlock() {
		return blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
	}

	/**
	 * Returns an unmodifiable view of the child blocks in this sequential chain.
	 *
	 * @return an unmodifiable list of {@link Block} instances
	 */
	public List<Block> getBlocks() {
		return Collections.unmodifiableList(blocks);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a lazily constructed entry cell that delegates pushes to the first child block
	 * and exposes receptor set/get operations on the last child block.</p>
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a lazily constructed backward cell that delegates pushes to the last child block
	 * and exposes receptor set operations on the first child block.</p>
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>Destroys all child blocks and nulls the internal list.</p>
	 */
	@Override
	public void destroy() {
		if (blocks != null) {
			blocks.forEach(Block::destroy);
			blocks = null;
		}
	}
}
