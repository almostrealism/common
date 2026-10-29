/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.util;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.ParameterUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link ParameterUpdate} that leaves every weight unchanged and instead records the gradient it
 * receives, which makes the analytic gradient of a model available for comparison against finite
 * differences (see {@link TestFeatures#centralDifferences}).
 *
 * <p>When the model wires each weight's update, one collection of that weight's shape is added to the
 * recorded gradients, in the order the model wires its layers. Each backward pass then copies the
 * weight's gradient into that same collection, overwriting what the previous pass recorded, so the
 * recorder always holds the gradients of the most recent pass rather than a history.</p>
 *
 * <pre>{@code
 * GradientRecorder recorder = new GradientRecorder();
 * Model model = new Model(shape, recorder);
 * model.add(layer);
 * CompiledModel compiled = model.compile(true, true);
 * compiled.forward(input);
 * compiled.backward(outputGradient);
 * double[] gradient = recorder.gradientOfSize(weight.getShape().getTotalSize());
 * }</pre>
 *
 * @see ModelTestFeatures#gradientRecorder(List)
 */
public class GradientRecorder implements ParameterUpdate<PackedCollection>, CodeFeatures {

	/** The recorded gradients, one per weight, in the order the model wired their updates. */
	private final List<PackedCollection> gradients;

	/** Creates a recorder that holds the gradients it records itself. */
	public GradientRecorder() {
		this(new ArrayList<>());
	}

	/**
	 * Creates a recorder that adds the gradients it records to the given list. This is the recorder
	 * {@link ModelTestFeatures#gradientRecorder(List)} returns, for tests that read the gradients from
	 * that list in the order the model wired the weights; it is not public, so that a new caller holds
	 * no alias to the recorded gradients and asks {@link #gradientOfSize} for them instead.
	 *
	 * @param gradients the list receiving one gradient collection per weight
	 */
	GradientRecorder(List<PackedCollection> gradients) {
		this.gradients = gradients;
	}

	/**
	 * Adds a collection of the weight's shape to the recorded gradients and returns the operation that
	 * copies the weight's gradient into it, leaving the weight unchanged.
	 *
	 * @param name     a human-readable label used in profiling and operation metadata
	 * @param weights  the weight the gradient is of, which is not updated
	 * @param gradient the gradient of the loss with respect to {@code weights}
	 * @return the operation that records the gradient
	 */
	@Override
	public Supplier<Runnable> apply(String name, Producer<PackedCollection> weights,
									Producer<PackedCollection> gradient) {
		PackedCollection buffer = new PackedCollection(shape(weights));
		gradients.add(buffer);
		return a(name + " (recorded gradient)", p(buffer.each()),
				c(gradient).reshape(buffer.getShape()).each());
	}

	/**
	 * Returns the gradient most recently recorded for the one weight with the given number of values,
	 * flattened, so that a backward pass can be compared with finite differences weight by weight. A
	 * model whose weights all differ in size identifies each of them this way, whatever order the
	 * model wired them in.
	 *
	 * @param size the number of values of the weight
	 * @return the weight's gradient, flattened
	 * @throws AssertionError unless exactly one recorded gradient has {@code size} values
	 */
	public double[] gradientOfSize(int size) {
		List<PackedCollection> matches = gradients.stream()
				.filter(gradient -> gradient.getShape().getTotalSize() == size)
				.toList();

		if (matches.isEmpty()) {
			throw new AssertionError("no gradient was recorded for a weight of " + size + " values");
		} else if (matches.size() > 1) {
			throw new AssertionError(matches.size() + " gradients were recorded for weights of " + size
					+ " values, so the size does not identify the weight");
		}

		return matches.get(0).doubleStream().toArray();
	}
}
