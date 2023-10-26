package org.almostrealism.model;

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SequentialBlock implements Block, LayerFeatures {
	private TraversalPolicy inputShape;

	private List<Block> blocks;

	private Cell<PackedCollection<?>> entry;
	private Receptor<PackedCollection<?>> push;
	private Receptor<PackedCollection<?>> downstream;

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
	}

	public <T extends Block> T add(T block) {
		if (block.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();

		Block last = lastBlock();
		if (last != null) last.getForward().setReceptor(block.getForward());

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

	public CellularLayer product(Block value) {
		if (value.getInputShape().getTotalSize() != getOutputShape().getTotalSize())
			throw new IllegalArgumentException();
		return add(product(value.getOutputShape(), value.getForward()));
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

	public Block lastBlock() {
		return blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
	}

	@Override
	public Cell<PackedCollection<?>> getForward() {
		if (entry == null) {
			entry = new Cell<>() {
				@Override
				public Supplier<Runnable> setup() {
					return new OperationList();
				}

				@Override
				public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
					return blocks.get(0).getForward().push(in);
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
		throw new UnsupportedOperationException();
	}

	@Override
	public <T extends Receptor<PackedCollection<?>>> T append(T r) {
		receptors.add(r);
		return r;
	}
}
