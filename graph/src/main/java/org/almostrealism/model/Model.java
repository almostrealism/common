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

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.cycle.Setup;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.layers.Learning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Model implements Setup, CodeFeatures {
	private SequentialBlock blocks;
	private List<Block> inputs;

	private PackedCollection<?> learningRate;

	public Model() {
		this(null);
	}

	public Model(TraversalPolicy shape) {
		this(shape, 1e-5);
	}

	public Model(TraversalPolicy shape, double learningRate) {
		this.blocks = new SequentialBlock(shape);
		this.inputs = new ArrayList<>();
		this.learningRate = new PackedCollection<>(1);
		setLearningRate(learningRate);
	}

	public void setLearningRate(double rate) {
		learningRate.setMem(0, rate);
		blocks.setLearningRate(p(learningRate));
	}

	public double getLearningRate() {
		return learningRate.toDouble(0);
	}

	public List<Block> getBlocks() {
		return blocks.getBlocks();
	}

	public Model add(Block b) {
		blocks.add(b);
		return this;
	}

	public Model add(Function<TraversalPolicy, ? extends Block> block) {
		add(block.apply(blocks.getOutputShape()));
		return this;
	}

	public Model addInput(Block b) {
		if (b instanceof Learning) {
			((Learning) b).setLearningRate(p(learningRate));
		}

		inputs.add(b);
		return this;
	}

	public SequentialBlock sequential() {
		SequentialBlock seq = new SequentialBlock(blocks.getOutputShape());
		add(seq);
		return seq;
	}

	public List<Block> getInputs() {
		return Collections.unmodifiableList(inputs);
	}

	public Block firstBlock() { return blocks.firstBlock(); }

	public Block lastBlock() { return blocks.lastBlock(); }

	public TraversalPolicy getInputShape() { return firstBlock().getInputShape(); }

	public TraversalPolicy getOutputShape() { return lastBlock().getOutputShape(); }

	@Override
	public Supplier<Runnable> setup() {
		return blocks.setup();
	}

	public List<Cell<PackedCollection<?>>> forward() {
		return Stream.concat(Stream.of(
							blocks.getForward()),
							inputs.stream().map(CellularPropagation::getForward))
				.collect(Collectors.toUnmodifiableList());
	}

	public Cell<PackedCollection<?>> backward() { return blocks.getBackward(); }

	public CompiledModel compile() {
		return CompiledModel.compile(this);
	}

	public CompiledModel compile(OperationProfile profile) {
		return CompiledModel.compile(this, profile);
	}

	public CompiledModel compile(boolean backprop) {
		return CompiledModel.compile(this, backprop, false, null);
	}

	public CompiledModel compile(boolean backprop, boolean returnGradient) {
		return CompiledModel.compile(this, backprop, returnGradient, null);
	}

	public CompiledModel compile(boolean backprop, OperationProfile profile) {
		return CompiledModel.compile(this, backprop, false, profile);
	}
}
