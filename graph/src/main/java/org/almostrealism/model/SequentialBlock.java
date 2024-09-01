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
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class SequentialBlock implements Block, Learning, LayerFeatures {
	public static boolean enableWarnings = false;

	private TraversalPolicy inputShape;

	private List<Block> blocks;

	private Cell<PackedCollection<?>> entry;
	private Receptor<PackedCollection<?>> push;
	private Receptor<PackedCollection<?>> downstream;

	private Cell<PackedCollection<?>> propagate;
	private Receptor<PackedCollection<?>> back;
	private Receptor<PackedCollection<?>> upstream;

	private List<Receptor<PackedCollection<?>>> receptors;

	public SequentialBlock(TraversalPolicy inputShape) {
		this.inputShape = inputShape;
		this.blocks = new ArrayList<>();
		this.receptors = new ArrayList<>();

		this.push = in -> {
			OperationList op = new OperationList();
			receptors.forEach(r -> op.add(r.push(in)));
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

	public <T extends Block> T add(Function<TraversalPolicy, T> factory) {
		return add(factory.apply(getOutputShape()));
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
				System.out.println("WARN: No backward Cell for " + Named.nameOf(block));
		} else {
			block.getBackward().setReceptor(prev);
		}

		blocks.add(block);
		lastBlock().getForward().setReceptor(push);
		return block;
	}

	public SequentialBlock branch() {
		Block split = new DefaultBlock(getOutputShape(), getOutputShape());
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

		Block split = new DefaultBlock(getOutputShape(), getOutputShape());
		split.append(branch);
		add(split);
		return branch;
	}

	public CellularLayer accum(Block value, ComputeRequirement... requirements) {
		if (value.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();
		return add(accum(getOutputShape(), value.getForward(), requirements));
	}

	public CellularLayer product(Block a, Block b, ComputeRequirement... requirements) {
		if (a.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();
		if (b.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();
		return add(product(a.getInputShape(), a.getOutputShape(), a.getForward(), b.getForward(), requirements));
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
					SequentialBlock.this.upstream = r;
				}
			};
		}

		return propagate;
	}

	@Override
	public <T extends Receptor<PackedCollection<?>>> T append(T r) {
		receptors.add(r);
		return r;
	}
}
