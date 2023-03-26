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
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.KernelLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class Model implements Setup, Receptor<PackedCollection<?>>, CodeFeatures {
	private List<Block> blocks;
	private List<PackedCollection<?>> inputs;
	private TraversalPolicy shape;

	public Model() {
		this(null);
	}

	public Model(TraversalPolicy shape) {
		this.shape = shape;
		this.blocks = new ArrayList<>();
		this.inputs = new ArrayList<>();
	}

	public void addBlock(Block b) {
		if (shape == null) {
			if (b.getInputShape() == null) {
				throw new IllegalArgumentException("Cannot infer input shape");
			}

			inputs.add(new PackedCollection<>(b.getInputShape()));
		} else if (b.getInputShape() != null && !shape.equals(b.getInputShape())) {
			if (blocks.isEmpty()) {
				throw new IllegalArgumentException("Block input shape does not match initial shape for model");
			} else {
				throw new IllegalArgumentException("Block input shape does not match output shape of last block");
			}
		} else if (blocks.isEmpty()) {
			inputs.add(new PackedCollection<>(shape));
		}

		blocks.add(b);
		shape = b.getOutputShape();
		inputs.add(new PackedCollection<>(shape));
	}

	public KernelBlock addBlock(TraversableKernelExpression kernel, PackedCollection<?> weights, Supplier<Runnable> setup) {
		KernelBlock b = new KernelBlock(shape, kernel, weights, setup);
		addBlock(b);
		return b;
	}

	public KernelBlock addBlock(KernelLayer layer) {
		KernelBlock b = new KernelBlock(shape, layer.getKernel(), layer.getWeights(), layer.setup());
		addBlock(b);
		return b;
	}

	public TraversalPolicy getShape() { return shape; }

	public List<PackedCollection<?>> getInputs() { return inputs; }

	@Override
	public Supplier<Runnable> setup() {
		return blocks.stream().map(Block::setup).collect(OperationList.collector());
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> input) {
		OperationList push = new OperationList();
		push.add(run(identity(input), inputs.get(0).traverseEach()));

		for (int i = 0; i < blocks.size(); i++) {
			PackedCollection<?> in = inputs.get(i);
			PackedCollection<?> out = inputs.get(i + 1);
			push.add(blocks.get(i).forward(p(in), p(out)));
		}

		return push;
	}

	public void forward(PackedCollection<?> input) {
		push(p(input)).get().run();
	}
}
