/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.cycle.Setup;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.MemoryDataCopy;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.KernelLayer;
import org.almostrealism.layers.Learning;
import org.almostrealism.layers.PropagationCell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class Model implements Setup, CodeFeatures {
	private List<Block> blocks;
	private TraversalPolicy shape;

	private PackedCollection<?> learningRate;

	public Model() {
		this(null);
	}

	public Model(TraversalPolicy shape) {
		this(shape, 1e-4);
	}

	public Model(TraversalPolicy shape, double learningRate) {
		this.shape = shape;
		this.blocks = new ArrayList<>();
		this.learningRate = new PackedCollection<>(1);
		setLearningRate(learningRate);
	}

	public void setLearningRate(double rate) {
		learningRate.setMem(0, rate);
	}

	public double getLearningRate() {
		return learningRate.toDouble(0);
	}

	public List<Block> getBlocks() {
		return Collections.unmodifiableList(blocks);
	}

	public void addBlock(Block b) {
		if (shape == null) {
			if (b.getInputShape() == null) {
				throw new IllegalArgumentException("Cannot infer input shape");
			}
		} else if (b.getInputShape() != null && !shape.equals(b.getInputShape())) {
			if (blocks.isEmpty()) {
				throw new IllegalArgumentException("Block input shape does not match initial shape for model");
			} else {
				throw new IllegalArgumentException("Block input shape does not match output shape of last block");
			}
		}

		if (!blocks.isEmpty()) {
			blocks.get(blocks.size() - 1).forward().setReceptor(b.forward());
			b.backward().setReceptor(blocks.get(blocks.size() - 1).backward());
		}

		blocks.add(b);
		shape = b.getOutputShape();
	}

	public Block addBlock(Function<TraversalPolicy, Block> block) {
		Block b = block.apply(shape);
		addBlock(b);
		return b;
	}

	public CellularBlock addLayer(CellularLayer layer) {
		if (layer instanceof Learning) ((Learning) layer).setLearningRate(p(learningRate));

		CellularBlock b = new CellularBlock(shape,
									layer.getOutputShape(), layer.getForward(),
									layer.getBackward(), layer.setup());
		addBlock(b);
		return b;
	}

	public CellularBlock addLayer(Function<TraversalPolicy, CellularLayer> layer) {
		return addLayer(layer.apply(shape));
	}

	public Block lastBlock() {
		return blocks.get(blocks.size() - 1);
	}

	public TraversalPolicy getShape() { return shape; }

	@Override
	public Supplier<Runnable> setup() {
		return blocks.stream().map(Block::setup).collect(OperationList.collector());
	}

	public Cell<PackedCollection<?>> forward() { return blocks.get(0).forward(); }
	public PackedCollection<?> forward(PackedCollection<?> input) {
		PackedCollection<?> output = new PackedCollection<>(lastBlock().getOutputShape());
		lastBlock().forward().setReceptor(out ->
				new MemoryDataCopy("Model Output", () -> out.get().evaluate(), () -> output, output.getMemLength()));
		forward().push(p(input)).get().run();
		return output;
	}

	public Cell<PackedCollection<?>> backward() { return lastBlock().backward(); }
	public void backward(PackedCollection<?> gradient) { backward().push(p(gradient)).get().run(); }
}
