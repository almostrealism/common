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

package org.almostrealism.optimize.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.TrainingResult;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for {@link ModelOptimizer} training functionality.
 *
 * <p>These tests use synthetic data to verify that training:
 * <ul>
 *   <li>Reduces loss over training epochs</li>
 *   <li>Updates LoRA weights (B matrix should become non-zero)</li>
 *   <li>Properly handles validation and early stopping</li>
 * </ul>
 */
public class ModelOptimizerTests extends TestSuiteBase implements LayerFeatures {

	private static final Random random = new Random(42);

	/**
	 * Test that training with LoRA reduces loss on a simple regression task.
	 *
	 * <p>This test creates a simple linear model with LoRA adapters, generates
	 * synthetic training data, and verifies that loss decreases after training.
	 */
	@Test
	public void testTrainingReducesLoss() {
		int batchSize = 1;
		int inputSize = 16;
		int outputSize = 8;
		int rank = 4;
		double alpha = 8.0;

		// Create base weights (random initialization)
		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		baseWeights.fill(pos -> random.nextGaussian() * 0.1);

		// Create a LoRA layer
		LoRALinear loraLayer = new LoRALinear(
				shape(batchSize, inputSize),
				baseWeights,
				null,  // No bias
				rank, alpha
		);

		// Build a simple model
		Model model = new Model(shape(batchSize, inputSize), 1e-3);
		model.add(loraLayer);

		// Compile with backprop enabled
		CompiledModel compiled = model.compile();

		// Generate synthetic training data - simple linear target
		List<ValueTarget<PackedCollection>> trainingSamples = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
			input.fill(pos -> random.nextGaussian());

			// Target is a simple transformation of input
			PackedCollection target = new PackedCollection(shape(batchSize, outputSize));
			for (int j = 0; j < outputSize; j++) {
				double val = 0;
				for (int k = 0; k < inputSize; k++) {
					val += input.toDouble(k) * ((k + j) % 3 == 0 ? 0.5 : -0.3);
				}
				target.setMem(j, val);
			}
			trainingSamples.add(ValueTarget.of(input, target));
		}

		Dataset<PackedCollection> trainData = Dataset.of(trainingSamples);

		// Get initial loss before training
		double initialLoss = computeAverageLoss(compiled, trainData);
		log("Initial loss: " + initialLoss);

		// Create optimizer and run training
		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> trainData);
		optimizer.setLossFunction(new MeanSquaredError(shape(batchSize, outputSize).traverseEach()));
		optimizer.setLogFrequency(5);

		TrainingResult result = optimizer.optimize(5);

		// Get final loss after training
		double finalLoss = result.getFinalTrainLoss();
		log("Final loss: " + finalLoss);
		log("Epochs completed: " + result.getEpochsCompleted());
		log("Total steps: " + result.getTotalSteps());

		// Verify loss decreased
		Assert.assertTrue("Loss should decrease after training",
				finalLoss < initialLoss);

		// Verify we completed the expected number of epochs
		Assert.assertEquals("Should complete all epochs", 5, result.getEpochsCompleted());

		log("Training reduces loss test passed");
	}

	/**
	 * Test that LoRA B matrix is updated during training.
	 *
	 * <p>LoRA B matrix is initialized to zeros, so after training it should
	 * contain non-zero values if learning is occurring.
	 */
	@Test
	public void testLoRAWeightsAreUpdated() {
		int batchSize = 1;
		int inputSize = 16;
		int outputSize = 8;
		int rank = 4;
		double alpha = 8.0;

		// Create base weights
		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		baseWeights.fill(pos -> random.nextGaussian() * 0.1);

		// Create LoRA layer
		LoRALinear loraLayer = new LoRALinear(
				shape(batchSize, inputSize),
				baseWeights,
				null,
				rank, alpha
		);

		// Verify B matrix is initially zero
		PackedCollection loraBBefore = loraLayer.getLoraB();
		double sumBefore = 0;
		for (int i = 0; i < loraBBefore.getShape().getTotalSize(); i++) {
			sumBefore += Math.abs(loraBBefore.toDouble(i));
		}
		Assert.assertEquals("LoRA B should be initialized to zero", 0.0, sumBefore, 1e-10);

		// Build model
		Model model = new Model(shape(batchSize, inputSize), 1e-2);  // Higher LR to see updates
		model.add(loraLayer);
		CompiledModel compiled = model.compile();

		// Generate training data
		List<ValueTarget<PackedCollection>> trainingSamples = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
			input.fill(pos -> random.nextGaussian());

			PackedCollection target = new PackedCollection(shape(batchSize, outputSize));
			target.fill(pos -> random.nextGaussian() * 0.5);
			trainingSamples.add(ValueTarget.of(input, target));
		}

		// Run training
		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> Dataset.of(trainingSamples));
		optimizer.setLossFunction(new MeanSquaredError(shape(batchSize, outputSize).traverseEach()));
		optimizer.setLogFrequency(0);  // Disable logging for this test
		optimizer.optimize(3);

		// Check that B matrix has been updated
		PackedCollection loraBAfter = loraLayer.getLoraB();
		double sumAfter = 0;
		for (int i = 0; i < loraBAfter.getShape().getTotalSize(); i++) {
			sumAfter += Math.abs(loraBAfter.toDouble(i));
		}

		log("LoRA B sum before: " + sumBefore);
		log("LoRA B sum after: " + sumAfter);

		Assert.assertTrue("LoRA B should be updated during training",
				sumAfter > 1e-6);

		log("LoRA weights update test passed");
	}

	/**
	 * Test validation loss tracking during training.
	 *
	 * <p>This test verifies that validation loss is properly tracked
	 * and reported in the training result.
	 */
	@Test
	public void testValidationLossTracking() {
		int batchSize = 1;
		int inputSize = 8;
		int outputSize = 4;

		// Create a simple dense layer (no LoRA for this test)
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> random.nextGaussian() * 0.1);

		Model model = new Model(shape(batchSize, inputSize), 1e-3);
		model.add(dense(shape(batchSize, inputSize), weights, null, false));
		CompiledModel compiled = model.compile();

		// Generate training data
		List<ValueTarget<PackedCollection>> trainSamples = new ArrayList<>();
		List<ValueTarget<PackedCollection>> valSamples = new ArrayList<>();

		for (int i = 0; i < 15; i++) {
			PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
			input.fill(pos -> random.nextGaussian());

			PackedCollection target = new PackedCollection(shape(batchSize, outputSize));
			target.fill(pos -> random.nextGaussian() * 0.2);

			if (i < 10) {
				trainSamples.add(ValueTarget.of(input, target));
			} else {
				valSamples.add(ValueTarget.of(input, target));
			}
		}

		// Configure optimizer with validation
		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> Dataset.of(trainSamples));
		optimizer.setValidationDataset(() -> Dataset.of(valSamples));
		optimizer.setLossFunction(new MeanSquaredError(shape(batchSize, outputSize).traverseEach()));
		optimizer.setEarlyStoppingPatience(0);  // Disable early stopping
		optimizer.setLogFrequency(0);

		TrainingResult result = optimizer.optimize(5);

		log("Epochs completed: " + result.getEpochsCompleted());
		log("Validation loss history size: " + result.getValidationLossHistory().size());
		log("Best epoch: " + result.getBestEpoch());
		log("Best validation loss: " + result.getBestValidationLoss());

		// Verify validation loss was tracked
		Assert.assertEquals("Should complete 5 epochs", 5, result.getEpochsCompleted());
		Assert.assertEquals("Should have 5 validation loss entries",
				5, result.getValidationLossHistory().size());
		Assert.assertFalse("Final validation loss should not be NaN",
				Double.isNaN(result.getFinalValidationLoss()));
		Assert.assertTrue("Best validation loss should be finite",
				Double.isFinite(result.getBestValidationLoss()));

		log("Validation loss tracking test passed");
	}

	/**
	 * Test TrainingResult metrics calculation.
	 */
	@Test
	public void testTrainingResultMetrics() {
		int batchSize = 1;
		int inputSize = 8;
		int outputSize = 4;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> random.nextGaussian() * 0.1);

		Model model = new Model(shape(batchSize, inputSize), 1e-3);
		model.add(dense(shape(batchSize, inputSize), weights, null, false));
		CompiledModel compiled = model.compile();

		// Generate training data
		List<ValueTarget<PackedCollection>> samples = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
			input.fill(pos -> random.nextGaussian());

			PackedCollection target = new PackedCollection(shape(batchSize, outputSize));
			target.fill(pos -> random.nextGaussian() * 0.2);

			samples.add(ValueTarget.of(input, target));
		}

		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> Dataset.of(samples));
		optimizer.setLossFunction(new MeanSquaredError(shape(batchSize, outputSize).traverseEach()));
		optimizer.setEarlyStoppingPatience(0);  // Disable early stopping
		optimizer.setLogFrequency(0);

		TrainingResult result = optimizer.optimize(3);

		// Verify metrics
		Assert.assertEquals("Should complete 3 epochs", 3, result.getEpochsCompleted());
		Assert.assertEquals("Should have 3 loss history entries",
				3, result.getTrainLossHistory().size());
		Assert.assertFalse("Should not be early stopped", result.isEarlyStopped());
		Assert.assertTrue("Training time should be positive",
				result.getTrainingTime().toMillis() > 0);
		Assert.assertFalse("Final train loss should not be NaN",
				Double.isNaN(result.getFinalTrainLoss()));

		log("Result: " + result);
		log("Improvement ratio: " + result.getImprovementRatio());

		log("TrainingResult metrics test passed");
	}

	/**
	 * Computes average loss over a dataset without training.
	 */
	private double computeAverageLoss(CompiledModel model, Dataset<PackedCollection> data) {
		double totalLoss = 0;
		int count = 0;

		for (ValueTarget<?> target : data) {
			PackedCollection output = model.forward(target.getInput(), target.getArguments());
			PackedCollection expected = target.getExpectedOutput();

			// Compute MSE
			double loss = 0;
			for (int i = 0; i < expected.getShape().getTotalSize(); i++) {
				double diff = output.toDouble(i) - expected.toDouble(i);
				loss += diff * diff;
			}
			loss /= expected.getShape().getTotalSize();

			totalLoss += loss;
			count++;
		}

		return totalLoss / count;
	}
}
