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
 * Synthetic training tests for normalization layers.
 * These tests validate that models with normalization layers
 * train correctly and produce expected results.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Layer normalization (norm)</li>
 *   <li>RMS normalization (rmsnorm)</li>
 *   <li>Group normalization with multiple groups</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class SyntheticNormTrainingTest implements ModelTestFeatures {
	static {
		if (TestUtils.getTrainTests()) {
			Console.root().addListener(OutputFeatures.fileOutput("results/logs/synthetic_norm_train.out"));
		}
	}

	/**
	 * Fixed coefficients for target functions.
	 */
	private final double[] coeff = { 0.24, -0.1, 0.36 };

	/**
	 * Simple element-wise linear function: output[i] = coeff[i] * input[i]
	 */
	private final UnaryOperator<PackedCollection> linearFunc =
			in -> {
				PackedCollection out = new PackedCollection(in.getShape());
				for (int i = 0; i < in.getMemLength(); i++) {
					int coeffIdx = i % coeff.length;
					out.setMem(i, coeff[coeffIdx] * in.valueAt(i));
				}
				return out;
			};

	/**
	 * Test 3.1: Dense with Layer Normalization
	 *
	 * <p>Validates that layer normalization integrates correctly with dense layers.</p>
	 *
	 * <p>Architecture: Input [6] - Dense [6 - 6] - Norm - Dense [6 - 3] - Output [3]</p>
	 */
	@Test(timeout = 3 * 60000)
	public void denseWithNorm() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Test 3.1: Dense with Layer Normalization ===");

		int inputSize = 6;
		int hiddenSize = 6;
		int outputSize = 3;
		int epochs = 300;
		int steps = 260;

		// Build model with normalization layer
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(norm(1));  // Layer normalization with 1 group
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		log("Model built with normalization: " + inputSize + " -> " + hiddenSize + " (norm) -> " + outputSize);

		// Generate dataset with varied input magnitudes to test normalization
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> input.fill(pos -> (1 + 10 * Math.random()) * (Math.random() - 0.5)))
				.map(input -> ValueTarget.of(input, linearFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train using existing infrastructure
		train("denseWithNorm", model, data, epochs, steps, 1.0, 0.5);

		log("Test 3.1 completed successfully");
	}

	/**
	 * Test 3.2: Dense Multi-layer with Layer Norm (same dimensions)
	 *
	 * <p>Validates layer normalization in deeper network with consistent dimensions.</p>
	 *
	 * <p>Architecture: Input [6] - Dense [6 - 6] - Norm - Dense [6 - 6] - Norm - Dense [6 - 3] - Output [3]</p>
	 */
	@Test(timeout = 5 * 60000)
	public void denseMultiLayerWithNorm() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Test 3.2: Dense Multi-layer with Norm ===");

		int inputSize = 6;
		int hiddenSize = 6;  // Same as input for norm compatibility
		int outputSize = 3;
		int epochs = 300;
		int steps = 260;

		// Build model with multiple normalization layers
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(norm(1));  // First layer normalization
		block.add(dense(hiddenSize, hiddenSize));
		block.add(norm(1));  // Second layer normalization
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		log("Model built with multiple norms: " + inputSize + " -> " + hiddenSize + " (norm) -> " + hiddenSize + " (norm) -> " + outputSize);

		// Generate dataset with varied input magnitudes
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> input.fill(pos -> (1 + 10 * Math.random()) * (Math.random() - 0.5)))
				.map(input -> ValueTarget.of(input, linearFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("denseMultiLayerWithNorm", model, data, epochs, steps, 1.0, 0.5);

		log("Test 3.2 completed successfully");
	}

	/**
	 * Test 3.3: Group Normalization
	 *
	 * <p>Validates group normalization with multiple groups.</p>
	 *
	 * <p>Architecture: Input [12] - Dense [12 - 12] - Norm [groups=3] - Dense [12 - 6] - Output [6]</p>
	 */
	@Test(timeout = 10 * 60000)
	public void groupNorm() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Test 3.3: Group Normalization ===");

		int inputSize = 12;
		int hiddenSize = 12;
		int outputSize = 6;
		int groups = 3;  // 12 / 3 = 4 elements per group
		int epochs = 300;
		int steps = 260;

		// Build model with group normalization
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(norm(groups));  // Group normalization with 3 groups
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		log("Model built with GroupNorm (" + groups + " groups): " + inputSize + " -> " + hiddenSize + " -> " + outputSize);

		// Extended coefficients for larger output
		final double[] extCoeff = { 0.24, -0.1, 0.36, 0.2, -0.3, 0.15 };

		// Generate dataset
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> {
					PackedCollection out = new PackedCollection(shape(outputSize));
					for (int j = 0; j < outputSize; j++) {
						out.setMem(j, extCoeff[j] * input.valueAt(j % inputSize));
					}
					return ValueTarget.of(input, out);
				})
				.collect(Collectors.toList()));

		// Train
		train("groupNorm", model, data, epochs, steps, 1.0, 0.5);

		log("Test 3.3 completed successfully");
	}
}
