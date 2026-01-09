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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.NegativeLogLikelihood;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Synthetic training tests for dense (fully-connected) neural network layers.
 * These tests validate that training reduces loss progressively and that
 * inference produces expected results after training.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Simple dense regression with MSE loss</li>
 *   <li>Dense networks with ReLU activation</li>
 *   <li>Dense classification with softmax/log-softmax</li>
 *   <li>Batched training with batch size greater than 1</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class SyntheticDenseTrainingTest extends TestSuiteBase implements ModelTestFeatures {

	/**
	 * Fixed coefficients for target functions.
	 */
	private final double[] coeff = { 0.3, -0.2, 0.5, 0.1, -0.4 };

	/**
	 * Simple linear function: output[i] = coeff[i] * input[i]
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
	 * Weighted sum function: output = sum(coeff[i] * input[i])
	 */
	private final UnaryOperator<PackedCollection> weightedSumFunc =
			in -> {
				double sum = 0.0;
				for (int i = 0; i < in.getMemLength(); i++) {
					int coeffIdx = i % coeff.length;
					sum += coeff[coeffIdx] * in.valueAt(i);
				}
				return PackedCollection.of(sum);
			};

	/**
	 * Test 1.1: Simple Dense Regression
	 *
	 * <p>Validates basic dense layer training with MSE loss.</p>
	 *
	 * <p>Architecture: Input [5] - Dense [5 - 5] - Output [5]</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(1)
	public void simpleDenseRegression() throws FileNotFoundException {
		log("=== Test 1.1: Simple Dense Regression ===");

		int size = 5;
		int epochs = 300;
		int steps = 260;

		// Build model using SequentialBlock pattern (like GradientDescentTests)
		SequentialBlock block = new SequentialBlock(shape(size));
		block.add(dense(size, size));

		Model model = new Model(shape(size), 1e-5);
		model.add(block);

		log("Model built: " + size + " -> " + size);

		// Generate dataset using same pattern as GradientDescentTests
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(size)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, linearFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train using existing infrastructure
		train("simpleDenseRegression", model, data, epochs, steps, 0.6, 0.2);

		log("Test 1.1 completed successfully");
	}

	/**
	 * Test 1.2: Dense with ReLU Activation
	 *
	 * <p>Validates dense layers with multiple layers for non-linear regression.</p>
	 *
	 * <p>Architecture: Input [3] - Dense [3 - 5] - Dense [5 - 1] - Output [1]</p>
	 */
	@Test(timeout = 4 * 60000)
	@TestDepth(1)
	public void denseWithMultipleLayers() throws FileNotFoundException {
		log("=== Test 1.2: Dense with Multiple Layers ===");

		int inputSize = 3;
		int hiddenSize = 5;
		int outputSize = 1;
		int epochs = 400;
		int steps = 200;

		// Build model with two dense layers
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		log("Model built: " + inputSize + " -> " + hiddenSize + " -> " + outputSize);

		// Generate dataset
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> input.fill(pos -> 5 + 4 * Math.random()))
				.map(input -> ValueTarget.of(input, weightedSumFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("denseMultipleLayers", model, data, epochs, steps, 1.0, 0.1);

		log("Test 1.2 completed successfully");
	}

	/**
	 * Test 1.3: Dense Classification
	 *
	 * <p>Validates dense network for classification with softmax output.</p>
	 *
	 * <p>Architecture: Input [4] - Dense [4 - 2] - Softmax - Output [2]</p>
	 */
	@Test(timeout = 12 * 60000)
	@TestDepth(1)
	public void denseClassification() throws FileNotFoundException {
		log("=== Test 1.3: Dense Classification ===");

		int inputSize = 4;
		int numClasses = 2;
		int epochs = 200;
		int steps = 300;

		// Build classification model with softmax
		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, numClasses));
		block.add(softmax());

		Model model = new Model(shape(inputSize), 1e-3);
		model.add(block);

		log("Classification model built: " + inputSize + " -> " + numClasses);

		// Generate binary classification dataset
		// Class 0: inputs with sum < threshold
		// Class 1: inputs with sum >= threshold
		final double threshold = 2.0;
		Supplier<Dataset<?>> data = () -> {
			List<ValueTarget<PackedCollection>> samples = new ArrayList<>();

			for (int i = 0; i < steps; i++) {
				PackedCollection input = new PackedCollection(shape(inputSize))
						.fill(Math::random);

				double sum = 0.0;
				for (int j = 0; j < inputSize; j++) {
					sum += input.valueAt(j);
				}

				PackedCollection target;
				if (sum < threshold) {
					target = PackedCollection.of(1.0, 0.0);
				} else {
					target = PackedCollection.of(0.0, 1.0);
				}

				samples.add(ValueTarget.of(input, target));
			}

			Collections.shuffle(samples);
			return Dataset.of(samples);
		};

		// Compile and train
		CompiledModel compiled = model.compile();
		log("Model compiled");

		ModelOptimizer optimizer = new ModelOptimizer(compiled, data);
		optimizer.setLossFunction(new MeanSquaredError(model.getOutputShape().traverseEach()));
		optimizer.setLogFrequency(40);
		optimizer.setLossTarget(0.1);
		optimizer.optimize(epochs);

		double finalLoss = optimizer.getLoss();
		log("Final loss: " + finalLoss);
		log("Completed " + optimizer.getTotalIterations() + " epochs");

		// Calculate classification accuracy
		double accuracy = optimizer.accuracy((expected, actual) -> expected.argmax() == actual.argmax());
		log("Accuracy: " + (accuracy * 100) + "%");

		// Verify reasonable accuracy (random would be 50% for binary)
		Assert.assertTrue("Accuracy should be better than random", accuracy > 0.55);
		log("Test 1.3 completed successfully");
	}

	/**
	 * Test 1.4: Dense Batched Training
	 *
	 * <p>Validates batched training with batch size greater than 1.</p>
	 *
	 * <p>Architecture: Input [bs=10, 3] - Dense [3 - 3] - Output [bs=10, 3]</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(1)
	public void denseBatched() throws FileNotFoundException {
		log("=== Test 1.4: Dense Batched Training ===");

		int batchSize = 10;
		int size = 3;
		int epochs = 300;
		int steps = 260;

		// Fixed coefficients for the target function
		final double[] batchCoeff = {0.24, -0.1, 0.36};

		// Build batched model (following DenseLayerTests pattern)
		SequentialBlock block = new SequentialBlock(shape(batchSize, size));
		block.add(dense(size, size));

		Model model = new Model(shape(batchSize, size), 1e-5);
		model.add(block);

		log("Batched model built: [" + batchSize + ", " + size + "] -> [" + batchSize + ", " + size + "]");

		// Function to compute expected output for batched input
		UnaryOperator<PackedCollection> batchFunc = input -> {
			TraversalPolicy inShape = padDimensions(input.getShape(), 2);
			input = input.reshape(inShape);

			PackedCollection result = new PackedCollection(inShape);
			for (int n = 0; n < inShape.length(0); n++) {
				result.range(shape(size), n * size).setMem(
						batchCoeff[0] * input.valueAt(n, 0),
						batchCoeff[1] * input.valueAt(n, 1),
						batchCoeff[2] * input.valueAt(n, 2));
			}
			return result;
		};

		// Generate batched dataset
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(batchSize, size)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, batchFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("denseBatched", model, data, epochs, steps, 0.6, 0.2);

		log("Test 1.4 completed successfully");
	}
}
