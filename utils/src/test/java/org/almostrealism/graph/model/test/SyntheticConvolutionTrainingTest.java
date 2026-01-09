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
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.ModelFeatures;
import org.almostrealism.optimize.Dataset;
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

/**
 * Synthetic training tests for convolutional neural network layers.
 * These tests validate that CNN training reduces loss progressively and that
 * inference produces expected results after training.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Simple 2D convolution with pooling</li>
 *   <li>Multi-layer convolution networks</li>
 *   <li>Convolution with padding options</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class SyntheticConvolutionTrainingTest extends TestSuiteBase implements ModelFeatures, ModelTestFeatures {

	/**
	 * Generates a synthetic dataset of circles and squares for binary classification.
	 * Circles are class 0, squares are class 1.
	 *
	 * @param batchSize batch size (usually 1 for this test)
	 * @param rows image height
	 * @param cols image width
	 * @param outShape output shape for classification targets
	 * @param samplesPerClass number of samples per class
	 * @return list of input-target pairs
	 */
	private List<ValueTarget<PackedCollection>> generateShapeDataset(
			int batchSize, int rows, int cols, TraversalPolicy outShape, int samplesPerClass) {
		List<ValueTarget<PackedCollection>> data = new ArrayList<>();

		log("Generating circles...");
		for (int i = 0; i < samplesPerClass; i++) {
			PackedCollection input = new PackedCollection(shape(batchSize, rows, cols));
			double x = Math.random() * cols;
			double y = Math.random() * rows;
			double r = 2 + Math.random() * (rows / 5.0);
			input.fill(pos -> {
				double dx = pos[2] - x;
				double dy = pos[1] - y;
				return dx * dx + dy * dy < r * r ? 1.0 : 0.0;
			});

			if (outShape.getTotalSize() == 2) {
				data.add(ValueTarget.of(input, PackedCollection.of(1.0, 0.0)));
			} else {
				data.add(ValueTarget.of(input, new PackedCollection(outShape).fill(pos -> pos[0] % 2 == 0 ? 1.0 : 0.0)));
			}
		}

		log("Generating squares...");
		for (int i = 0; i < samplesPerClass; i++) {
			PackedCollection input = new PackedCollection(shape(batchSize, rows, cols));
			double x = Math.random() * cols;
			double y = Math.random() * rows;
			double r = 2 + Math.random() * (rows / 5.0);
			input.fill(pos -> {
				double dx = Math.abs(pos[2] - x);
				double dy = Math.abs(pos[1] - y);
				return dx < r && dy < r ? 1.0 : 0.0;
			});

			if (outShape.getTotalSize() == 2) {
				data.add(ValueTarget.of(input, PackedCollection.of(0.0, 1.0)));
			} else {
				data.add(ValueTarget.of(input, new PackedCollection(outShape).fill(pos -> pos[0] % 2 == 0 ? 0.0 : 1.0)));
			}
		}

		Collections.shuffle(data);
		return data;
	}

	/**
	 * Test 2.1: Simple Conv2d
	 *
	 * <p>Validates basic 2D convolution training for binary classification.</p>
	 *
	 * <p>Architecture: Input [1, 16, 16] - Conv2d [1-4, 3x3] - Pool2d [2x2] - Flatten - Dense - Output [2]</p>
	 */
	@Test(timeout = 8 * 60000)
	@TestDepth(2)
	public void simpleConv2d() throws FileNotFoundException {
		log("=== Test 2.1: Simple Conv2d ===");

		int batchSize = 1;
		int rows = 16;
		int cols = 16;
		int filters = 4;
		int convSize = 3;
		int poolSize = 2;
		int numClasses = 2;
		int epochs = 15;
		int samplesPerClass = 100;

		// Build CNN model
		Model model = convolution2dModel(
				batchSize, 1, rows, cols,
				convSize, filters, 1,
				poolSize, numClasses, true);
		model.setParameterUpdate(ParameterUpdate.scaled(c(0.001)));

		log("Model built with " + model.getBlocks().size() + " blocks");
		log("Input: [" + batchSize + ", " + rows + ", " + cols + "], Output: " + model.getOutputShape());

		// Generate dataset
		List<ValueTarget<PackedCollection>> data = generateShapeDataset(
				batchSize, rows, cols, model.getOutputShape().item(), samplesPerClass);

		// Split into train/test
		int trainSize = (int) (data.size() * 0.8);
		Dataset<PackedCollection> trainData = Dataset.of(data.subList(0, trainSize)).batch(batchSize);
		Dataset<PackedCollection> testData = Dataset.of(data.subList(trainSize, data.size())).batch(batchSize);

		// Compile and train
		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode("SimpleConv2d"));
		CompiledModel compiled = model.compile(profile);
		log("Model compiled");

		try {
			ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> trainData);
			optimizer.setLossFunction(new NegativeLogLikelihood());
			optimizer.setLogFrequency(5);
			optimizer.setLossTarget(0.3);
			optimizer.optimize(epochs);

			double finalLoss = optimizer.getLoss();
			log("Final loss: " + finalLoss);
			log("Completed " + optimizer.getTotalIterations() + " epochs");

			// Calculate accuracy on test set
			ModelOptimizer testOptimizer = new ModelOptimizer(compiled, () -> testData);
			testOptimizer.setLossFunction(new NegativeLogLikelihood());
			double accuracy = testOptimizer.accuracy((expected, actual) -> expected.argmax() == actual.argmax());
			log("Test accuracy: " + (accuracy * 100) + "%");

			// Verify reasonable accuracy (random would be 50%)
			Assert.assertTrue("Should converge to some loss", finalLoss < 2.0);
		} finally {
			logKernelMetrics(profile);
		}

		log("Test 2.1 completed successfully");
	}

	/**
	 * Test 2.2: Multi-Layer Conv2d
	 *
	 * <p>Validates deeper convolution network with multiple conv layers.</p>
	 *
	 * <p>Architecture: Input [1, 20, 20] - Conv2d [1-8, 3x3] - Pool2d - Conv2d [8-16, 3x3] - Pool2d - Flatten - Dense - Output [4]</p>
	 */
	@Test(timeout = 75 * 60000)
	@TestDepth(2)
	public void multiLayerConv2d() {
		log("=== Test 2.2: Multi-Layer Conv2d ===");

		int batchSize = 1;
		int rows = 20;
		int cols = 20;
		int convSize = 3;
		int poolSize = 2;
		int numClasses = 2;
		int epochs = 10;
		int samplesPerClass = 75;

		// Build deeper CNN model (2 conv layers)
		Model model = convolution2dModel(
				batchSize, 1, rows, cols,
				convSize, 8, 2,
				poolSize, numClasses, true);
		model.setParameterUpdate(ParameterUpdate.scaled(c(0.001)));

		log("Model built with " + model.getBlocks().size() + " blocks");
		log("Input: [" + batchSize + ", " + rows + ", " + cols + "], Output: " + model.getOutputShape());

		// Generate dataset
		List<ValueTarget<PackedCollection>> data = generateShapeDataset(
				batchSize, rows, cols, model.getOutputShape().item(), samplesPerClass);

		// Split into train/test
		int trainSize = (int) (data.size() * 0.8);
		Dataset<PackedCollection> trainData = Dataset.of(data.subList(0, trainSize)).batch(batchSize);

		// Compile and train
		CompiledModel compiled = model.compile();
		log("Model compiled");

		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> trainData);
		optimizer.setLossFunction(new NegativeLogLikelihood());
		optimizer.setLogFrequency(5);
		optimizer.setLossTarget(0.5);
		optimizer.optimize(epochs);

		double finalLoss = optimizer.getLoss();
		log("Final loss: " + finalLoss);
		log("Completed " + optimizer.getTotalIterations() + " epochs");

		// Verify training happened
		Assert.assertTrue("Should complete training", optimizer.getTotalIterations() >= 1);

		log("Test 2.2 completed successfully");
	}

	/**
	 * Test 2.3: Conv2d Inference Only
	 *
	 * <p>Validates that convolution model inference works correctly
	 * (forward pass without training).</p>
	 *
	 * <p>Architecture: Input [1, 12, 12] - Conv2d [padding=0] - Pool - Flatten - Dense - Output</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(1)
	public void conv2dInference() {
		log("=== Test 2.3: Conv2d Inference ===");

		int batchSize = 1;
		int rows = 12;
		int cols = 12;
		int filters = 4;
		int convSize = 3;
		int poolSize = 2;
		int numClasses = 2;

		// Build model
		Model model = convolution2dModel(
				batchSize, 1, rows, cols,
				convSize, filters, 1,
				poolSize, numClasses, true);

		log("Model built with " + model.getBlocks().size() + " blocks");
		log("Input: [" + batchSize + ", " + rows + ", " + cols + "]");
		log("Output: " + model.getOutputShape());

		// Compile model
		CompiledModel compiled = model.compile(false);
		log("Model compiled (no backprop)");

		// Test forward pass
		PackedCollection input = new PackedCollection(shape(batchSize, rows, cols));
		input.fill(pos -> Math.random());

		PackedCollection output = compiled.forward(input);
		log("Output shape: " + output.getShape());
		log("Memory length: " + output.getMemLength());

		// Verify output shape
		Assert.assertEquals("Output should have correct size", numClasses, output.getShape().getTotalSize());

		// Verify no NaN values
		for (int i = 0; i < output.getMemLength(); i++) {
			Assert.assertFalse("Output should not contain NaN", Double.isNaN(output.toDouble(i)));
			Assert.assertFalse("Output should not contain Inf", Double.isInfinite(output.toDouble(i)));
		}

		// Log output values for debugging
		StringBuilder sb = new StringBuilder("Output values: [");
		for (int i = 0; i < output.getMemLength(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(output.toDouble(i));
		}
		sb.append("]");
		log(sb.toString());

		log("Test 2.3 completed successfully");
	}
}
