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

package org.almostrealism.layers.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tests for the {@link LoRALinear} layer implementation.
 */
public class LoRALinearTests extends TestSuiteBase implements LayerFeatures {

	private static final double TOLERANCE = 1e-4;
	private static final Random random = new Random(42);

	/**
	 * Test that LoRA layer with zero-initialized B matrix produces same output as base layer.
	 * Since B is initialized to zeros, the LoRA contribution should be zero initially.
	 */
	@Test
	public void testZeroInitializationPreservesBaseOutput() {
		int batchSize = 2;
		int inputSize = 64;
		int outputSize = 32;
		int rank = 8;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		baseWeights.fill(pos -> random.nextGaussian() * 0.1);

		PackedCollection baseBias = new PackedCollection(shape(outputSize));
		baseBias.fill(pos -> random.nextGaussian() * 0.01);

		LoRALinear loraLayer = new LoRALinear(
				shape(batchSize, inputSize),
				baseWeights, baseBias,
				rank, 16.0
		);

		CellularLayer baseLayer = dense(shape(batchSize, inputSize), baseWeights, baseBias, false);

		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.fill(pos -> random.nextGaussian());

		Model loraModel = new Model(shape(batchSize, inputSize));
		loraModel.add(loraLayer);
		CompiledModel loraCompiled = loraModel.compile();

		Model baseModel = new Model(shape(batchSize, inputSize));
		baseModel.add(baseLayer);
		CompiledModel baseCompiled = baseModel.compile();

		PackedCollection loraOutput = loraCompiled.forward(input);
		PackedCollection baseOutput = baseCompiled.forward(input);

		for (int i = 0; i < loraOutput.getShape().getTotalSize(); i++) {
			double loraVal = loraOutput.toDouble(i);
			double baseVal = baseOutput.toDouble(i);
			Assert.assertEquals("Output mismatch at index " + i, baseVal, loraVal, TOLERANCE);
		}

		log("Zero initialization test passed - LoRA output matches base output");
	}

	/**
	 * Test that LoRA layer produces different output after modifying B matrix.
	 */
	@Test
	public void testNonZeroBProducesDifferentOutput() {
		int batchSize = 2;
		int inputSize = 64;
		int outputSize = 32;
		int rank = 8;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		baseWeights.fill(pos -> random.nextGaussian() * 0.1);

		LoRALinear loraLayer = new LoRALinear(
				shape(batchSize, inputSize),
				baseWeights, null,
				rank, 16.0
		);

		loraLayer.getLoraB().fill(pos -> random.nextGaussian() * 0.1);

		CellularLayer baseLayer = dense(shape(batchSize, inputSize), baseWeights, null, false);

		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.fill(pos -> random.nextGaussian());

		Model loraModel = new Model(shape(batchSize, inputSize));
		loraModel.add(loraLayer);
		CompiledModel loraCompiled = loraModel.compile();

		Model baseModel = new Model(shape(batchSize, inputSize));
		baseModel.add(baseLayer);
		CompiledModel baseCompiled = baseModel.compile();

		PackedCollection loraOutput = loraCompiled.forward(input);
		PackedCollection baseOutput = baseCompiled.forward(input);

		boolean anyDifferent = false;
		for (int i = 0; i < loraOutput.getShape().getTotalSize(); i++) {
			double loraVal = loraOutput.toDouble(i);
			double baseVal = baseOutput.toDouble(i);
			if (Math.abs(loraVal - baseVal) > TOLERANCE) {
				anyDifferent = true;
				break;
			}
		}

		Assert.assertTrue("LoRA with non-zero B should produce different output", anyDifferent);
		log("Non-zero B test passed - LoRA produces different output from base");
	}

	/**
	 * Test weight merging produces identical output to LoRA layer.
	 */
	@Test
	public void testWeightMergingCorrectness() {
		int batchSize = 2;
		int inputSize = 64;
		int outputSize = 32;
		int rank = 8;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		baseWeights.fill(pos -> random.nextGaussian() * 0.1);

		PackedCollection baseBias = new PackedCollection(shape(outputSize));
		baseBias.fill(pos -> random.nextGaussian() * 0.01);

		LoRALinear loraLayer = new LoRALinear(
				shape(batchSize, inputSize),
				baseWeights, baseBias,
				rank, 16.0
		);

		loraLayer.getLoraA().fill(pos -> random.nextGaussian() * 0.1);
		loraLayer.getLoraB().fill(pos -> random.nextGaussian() * 0.1);

		PackedCollection mergedWeights = loraLayer.mergeWeights();

		CellularLayer mergedLayer = dense(shape(batchSize, inputSize), mergedWeights, baseBias, false);

		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.fill(pos -> random.nextGaussian());

		Model loraModel = new Model(shape(batchSize, inputSize));
		loraModel.add(loraLayer);
		CompiledModel loraCompiled = loraModel.compile();

		Model mergedModel = new Model(shape(batchSize, inputSize));
		mergedModel.add(mergedLayer);
		CompiledModel mergedCompiled = mergedModel.compile();

		PackedCollection loraOutput = loraCompiled.forward(input);
		PackedCollection mergedOutput = mergedCompiled.forward(input);

		for (int i = 0; i < loraOutput.getShape().getTotalSize(); i++) {
			double loraVal = loraOutput.toDouble(i);
			double mergedVal = mergedOutput.toDouble(i);
			Assert.assertEquals("Merged output mismatch at index " + i, loraVal, mergedVal, TOLERANCE);
		}

		log("Weight merging test passed - merged layer produces identical output");
	}

	/**
	 * Test that only LoRA weights are exposed via getWeights().
	 */
	@Test
	public void testOnlyLoraWeightsExposed() {
		int inputSize = 64;
		int outputSize = 32;
		int rank = 8;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection baseBias = new PackedCollection(shape(outputSize));

		LoRALinear loraLayer = new LoRALinear(
				shape(1, inputSize),
				baseWeights, baseBias,
				rank, 16.0
		);

		List<PackedCollection> trainableWeights = loraLayer.getWeights();

		Assert.assertEquals("Should have exactly 2 weight sets (A and B)", 2, trainableWeights.size());

		Assert.assertEquals("LoRA A shape mismatch",
				inputSize * rank, trainableWeights.get(0).getShape().getTotalSize());
		Assert.assertEquals("LoRA B shape mismatch",
				rank * outputSize, trainableWeights.get(1).getShape().getTotalSize());

		log("Weight exposure test passed - only LoRA A and B are exposed");
	}

	/**
	 * Test LoRA parameters: rank and alpha.
	 */
	@Test
	public void testLoraParameters() {
		int inputSize = 64;
		int outputSize = 32;
		int rank = 4;
		double alpha = 8.0;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));

		LoRALinear loraLayer = new LoRALinear(
				shape(1, inputSize),
				baseWeights, null,
				rank, alpha
		);

		Assert.assertEquals("Rank mismatch", rank, loraLayer.getRank());
		Assert.assertEquals("Alpha mismatch", alpha, loraLayer.getAlpha(), 0.001);

		Assert.assertEquals("LoRA A should have [inputSize, rank] shape",
				inputSize * rank, loraLayer.getLoraA().getShape().getTotalSize());
		Assert.assertEquals("LoRA B should have [rank, outputSize] shape",
				rank * outputSize, loraLayer.getLoraB().getShape().getTotalSize());

		log("LoRA parameters test passed");
	}

	/**
	 * Test that LoRA can be used in a model that compiles successfully.
	 */
	@Test
	public void testModelCompilation() {
		int batchSize = 4;
		int inputSize = 32;
		int hiddenSize = 64;
		int outputSize = 16;

		PackedCollection weights1 = new PackedCollection(shape(hiddenSize, inputSize));
		weights1.fill(pos -> random.nextGaussian() * 0.1);

		PackedCollection weights2 = new PackedCollection(shape(outputSize, hiddenSize));
		weights2.fill(pos -> random.nextGaussian() * 0.1);

		LoRALinear lora1 = new LoRALinear(shape(batchSize, inputSize), weights1, null, 4, 8.0);
		LoRALinear lora2 = new LoRALinear(shape(batchSize, hiddenSize), weights2, null, 4, 8.0);

		Model model = new Model(shape(batchSize, inputSize));
		model.add(lora1);
		model.add(relu(shape(batchSize, hiddenSize)));
		model.add(lora2);

		CompiledModel compiled = model.compile();

		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.fill(pos -> random.nextGaussian());

		PackedCollection output = compiled.forward(input);

		Assert.assertEquals("Output batch size mismatch", batchSize, output.getShape().length(0));
		Assert.assertEquals("Output feature size mismatch", outputSize, output.getShape().length(1));

		log("Model compilation test passed - multi-layer LoRA model compiles and runs");
	}

	/**
	 * Test loading pre-existing LoRA weights (simulating checkpoint loading).
	 */
	@Test
	public void testLoadExistingLoraWeights() {
		int inputSize = 64;
		int outputSize = 32;
		int rank = 8;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		baseWeights.fill(pos -> random.nextGaussian() * 0.1);

		PackedCollection savedLoraA = new PackedCollection(shape(inputSize, rank));
		savedLoraA.fill(pos -> random.nextGaussian() * 0.1);

		PackedCollection savedLoraB = new PackedCollection(shape(rank, outputSize));
		savedLoraB.fill(pos -> random.nextGaussian() * 0.1);

		LoRALinear loadedLayer = new LoRALinear(
				shape(1, inputSize),
				baseWeights, null,
				rank, 16.0,
				new PackedCollection[]{savedLoraA, savedLoraB}
		);

		for (int i = 0; i < savedLoraA.getShape().getTotalSize(); i++) {
			Assert.assertEquals("LoRA A mismatch at " + i,
					savedLoraA.toDouble(i), loadedLayer.getLoraA().toDouble(i), 1e-10);
		}
		for (int i = 0; i < savedLoraB.getShape().getTotalSize(); i++) {
			Assert.assertEquals("LoRA B mismatch at " + i,
					savedLoraB.toDouble(i), loadedLayer.getLoraB().toDouble(i), 1e-10);
		}

		log("Pre-existing weights loading test passed");
	}

	/**
	 * Test the toMergedLayer() method creates a functional dense layer.
	 */
	@Test
	public void testToMergedLayer() {
		int batchSize = 2;
		int inputSize = 64;
		int outputSize = 32;
		int rank = 8;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));
		baseWeights.fill(pos -> random.nextGaussian() * 0.1);

		PackedCollection baseBias = new PackedCollection(shape(outputSize));
		baseBias.fill(pos -> random.nextGaussian() * 0.01);

		LoRALinear loraLayer = new LoRALinear(
				shape(batchSize, inputSize),
				baseWeights, baseBias,
				rank, 16.0
		);

		loraLayer.getLoraA().fill(pos -> random.nextGaussian() * 0.1);
		loraLayer.getLoraB().fill(pos -> random.nextGaussian() * 0.1);

		CellularLayer mergedLayer = loraLayer.toMergedLayer();

		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.fill(pos -> random.nextGaussian());

		Model loraModel = new Model(shape(batchSize, inputSize));
		loraModel.add(loraLayer);
		CompiledModel loraCompiled = loraModel.compile();

		Model mergedModel = new Model(shape(batchSize, inputSize));
		mergedModel.add(mergedLayer);
		CompiledModel mergedCompiled = mergedModel.compile();

		PackedCollection loraOutput = loraCompiled.forward(input);
		PackedCollection mergedOutput = mergedCompiled.forward(input);

		for (int i = 0; i < loraOutput.getShape().getTotalSize(); i++) {
			Assert.assertEquals("Merged layer output mismatch at index " + i,
					loraOutput.toDouble(i), mergedOutput.toDouble(i), TOLERANCE);
		}

		log("toMergedLayer() test passed");
	}

	/**
	 * Test shapes are correct throughout the layer.
	 */
	@Test
	public void testShapes() {
		int batchSize = 4;
		int inputSize = 128;
		int outputSize = 64;
		int rank = 16;

		PackedCollection baseWeights = new PackedCollection(shape(outputSize, inputSize));

		LoRALinear loraLayer = new LoRALinear(
				shape(batchSize, inputSize),
				baseWeights, null,
				rank, 32.0
		);

		Assert.assertEquals("Input shape length mismatch", 2, loraLayer.getInputShape().getDimensions());
		Assert.assertEquals("Input batch size mismatch", batchSize, loraLayer.getInputShape().length(0));
		Assert.assertEquals("Input feature size mismatch", inputSize, loraLayer.getInputShape().length(1));

		Assert.assertEquals("Output shape length mismatch", 2, loraLayer.getOutputShape().getDimensions());
		Assert.assertEquals("Output batch size mismatch", batchSize, loraLayer.getOutputShape().length(0));
		Assert.assertEquals("Output feature size mismatch", outputSize, loraLayer.getOutputShape().length(1));

		log("Shape test passed");
	}
}
