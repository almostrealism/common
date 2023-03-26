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

package org.almostrealism.layers;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;

import java.util.function.Supplier;

public class KernelLayer implements Layer {
	private Supplier<Runnable> setup;
	private TraversableKernelExpression kernel;
	private PackedCollection<?>	weights;

	public KernelLayer(Supplier<Runnable> setup, TraversableKernelExpression kernel, PackedCollection<?> weights) {
		this.setup = setup;
		this.kernel = kernel;
		this.weights = weights;
	}

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public PackedCollection<?> getWeights() { return weights; }

	public TraversableKernelExpression getKernel() { return kernel; }
}
