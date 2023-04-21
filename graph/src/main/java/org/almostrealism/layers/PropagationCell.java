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

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class PropagationCell implements Cell<PackedCollection<?>>, Learning, CodeFeatures {
	private final Propagation propagation;
	private PackedCollection<?> input;

	private Producer<PackedCollection<?>> learningRate;

	private Receptor<PackedCollection<?>> next;

	public PropagationCell(Propagation propagation) {
		this.propagation = propagation;
	}

	@Override
	public Supplier<Runnable> setup() { return new OperationList(); }

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> gradient) {
		return propagation.propagate(learningRate, gradient, p(input), next);
	}

	public void setForwardInput(PackedCollection<?> input) {
		this.input = input;
	}

	public Producer<PackedCollection<?>> getLearningRate() {
		return learningRate;
	}

	@Override
	public void setLearningRate(Producer<PackedCollection<?>> learningRate) {
		this.learningRate = learningRate;
	}

	@Override
	public void setReceptor(Receptor<PackedCollection<?>> next) {
		this.next = next;
	}
}
