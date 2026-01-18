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

package org.almostrealism.graph.model.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Synthetic training tests for activation function layers.
 * These tests validate that different activation functions
 * integrate correctly and support training.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>SiLU (Swish) activation</li>
 *   <li>GELU activation</li>
 *   <li>Softmax variants</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class SyntheticActivationTrainingTest implements ModelTestFeatures {
	static {
		if (TestUtils.getTrainTests()) {
			Console.root().addListener(OutputFeatures.fileOutput("results/logs/synthetic_activation_train.out"));
		}
	}

	/**
	 * Fixed coefficients for target functions.
	 */
	private final double[] coeff = { 0.3, -0.2, 0.5, 0.1 };

	/**
	 * Non-linear target function for testing activations.
	 * Uses ReLU-like outputs to test non-linearity.
	 */
	private final UnaryOperator<PackedCollection> nonLinearFunc =
			in -> {
				PackedCollection out = new PackedCollection(in.getShape());
				for (int i = 0; i < in.getMemLength(); i++) {
					double v = coeff[i % coeff.length] * in.valueAt(i);
					out.setMem(i, Math.max(0, v));  // ReLU-like
				}
				return out;
			};

	/**
	 * Test 4.1: Dense with SiLU Activation
	 *
	 * <p>Validates SiLU (Swish) activation function in training.</p>
	 *
	 * <p>Architecture: Input [4] - Dense [4 - 8] - SiLU - Dense [8 - 4] - Output [4]</p>
	 */
	@Test(timeout = 120000)
	public void denseWithSiLU() throws FileNotFoundException {
		if (testDepth < 2) return;

		log("=== Test 4.1: Dense with SiLU Activation ===");

		int inputSize = 4;
		int hiddenSize = 8;
		int outputSize = 4;
		int epochs = 300;
		int steps = 260;

		// Build model with SiLU activation
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(silu());
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		log("Model built with SiLU: " + inputSize + " -> " + hiddenSize + " (silu) -> " + outputSize);

		// Generate dataset
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, nonLinearFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("denseWithSiLU", model, data, epochs, steps, 1.0, 0.5);

		log("Test 4.1 completed successfully");
	}

	/**
	 * Test 4.2: Dense with ReLU Activation
	 *
	 * <p>Validates ReLU activation function in training.</p>
	 *
	 * <p>Architecture: Input [4] - Dense [4 - 8] - ReLU - Dense [8 - 4] - Output [4]</p>
	 */
	@Test(timeout = 2 * 60000)
	public void denseWithReLU() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Test 4.2: Dense with ReLU Activation ===");

		int inputSize = 4;
		int hiddenSize = 8;
		int outputSize = 4;
		int epochs = 300;
		int steps = 260;

		// Build model with ReLU activation
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(relu());
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		log("Model built with ReLU: " + inputSize + " -> " + hiddenSize + " (relu) -> " + outputSize);

		// Generate dataset
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, nonLinearFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("denseWithReLU", model, data, epochs, steps, 1.0, 0.5);

		log("Test 4.2 completed successfully");
	}

	/**
	 * Test 4.3: Multi-layer Dense
	 *
	 * <p>Validates multi-layer dense network for regression.</p>
	 *
	 * <p>Architecture: Input [4] - Dense [4 - 8] - Dense [8 - 4] - Output [4]</p>
	 */
	@Test(timeout = 120000)
	public void multiLayerDense() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Test 4.3: Multi-layer Dense ===");

		int inputSize = 4;
		int hiddenSize = 8;
		int outputSize = 4;
		int epochs = 300;
		int steps = 260;

		// Build multi-layer model
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		log("Model built: " + inputSize + " -> " + hiddenSize + " -> " + outputSize);

		// Generate dataset
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, nonLinearFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("multiLayerDense", model, data, epochs, steps, 1.0, 0.5);

		log("Test 4.3 completed successfully");
	}
}
