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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.layers.KernelLayer;

import java.util.ArrayList;
import java.util.List;

public class Model {
	private List<Block> blocks;
	private TraversalPolicy shape;

	public Model(TraversalPolicy shape) {
		this.shape = shape;
		this.blocks = new ArrayList<>();
	}

	public void addBlock(Block b) {
		blocks.add(b);
		shape = b.getOutputShape();
	}

	public KernelBlock addBlock(TraversableKernelExpression kernel, PackedCollection<?> weights) {
		KernelBlock b = new KernelBlock(shape, kernel, weights);
		addBlock(b);
		return b;
	}

	public KernelBlock addBlock(KernelLayer layer) {
		KernelBlock b = new KernelBlock(shape, layer.getKernel(), layer.getWeights());
		addBlock(b);
		return b;
	}
}
