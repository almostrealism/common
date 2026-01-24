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
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.AdapterConfig.TargetLayer;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.layers.LowRankAdapterSupport;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Tests for {@link AdapterConfig} and {@link LowRankAdapterSupport} interface.
 */
public class AdapterConfigTests extends TestSuiteBase implements LayerFeatures, LowRankAdapterSupport {

	private static final double TOLERANCE = 1e-4;
	private static final Random random = new Random(42);

	// Test implementation of LowRankAdapterSupport
	private AdapterConfig testConfig;
	private final List<LoRALinear> testLoraLayers = new ArrayList<>();

	@Override
	public AdapterConfig getAdapterConfig() {
		return testConfig;
	}

	@Override
	public List<LoRALinear> getLoraLayers() {
		return testLoraLayers;
	}

	@Override
	public void addLoraLayer(LoRALinear layer) {
		testLoraLayers.add(layer);
	}

	/**
	 * Test default AdapterConfig values.
	 */
	@Test
	public void testDefaultConfig() {
		AdapterConfig config = new AdapterConfig();

		Assert.assertEquals("Default rank should be 8", 8, config.getRank());
		Assert.assertEquals("Default alpha should be 16.0", 16.0, config.getAlpha(), 0.001);
		Assert.assertTrue("Should target SELF_ATTENTION_QKV",
				config.isTargeted(TargetLayer.SELF_ATTENTION_QKV));
		Assert.assertTrue("Should target SELF_ATTENTION_OUT",
				config.isTargeted(TargetLayer.SELF_ATTENTION_OUT));
		Assert.assertFalse("Should not target FFN_GATE",
				config.isTargeted(TargetLayer.FFN_GATE));

		log("Default config test passed");
	}

	/**
	 * Test AdapterConfig builder pattern.
	 */
	@Test
	public void testBuilderPattern() {
		AdapterConfig config = new AdapterConfig()
				.rank(16)
				.alpha(32.0)
				.targets(TargetLayer.CROSS_ATTENTION_Q, TargetLayer.FFN_OUT);

		Assert.assertEquals(16, config.getRank());
		Assert.assertEquals(32.0, config.getAlpha(), 0.001);
		Assert.assertTrue(config.isTargeted(TargetLayer.CROSS_ATTENTION_Q));
		Assert.assertTrue(config.isTargeted(TargetLayer.FFN_OUT));
		Assert.assertFalse(config.isTargeted(TargetLayer.SELF_ATTENTION_QKV));

		log("Builder pattern test passed");
	}

	/**
	 * Test invalid rank validation.
	 */
	@Test(expected = IllegalArgumentException.class)
	public void testInvalidRank() {
		new AdapterConfig().rank(0);
	}

	/**
	 * Test invalid alpha validation.
	 */
	@Test(expected = IllegalArgumentException.class)
	public void testInvalidAlpha() {
		new AdapterConfig().alpha(-1.0);
	}

	/**
	 * Test forAudioDiffusion factory method.
	 */
	@Test
	public void testForAudioDiffusionFactory() {
		AdapterConfig config = AdapterConfig.forAudioDiffusion();

		Assert.assertEquals(8, config.getRank());
		Assert.assertEquals(16.0, config.getAlpha(), 0.001);

		Set<TargetLayer> targets = config.getTargets();
		Assert.assertTrue(targets.contains(TargetLayer.SELF_ATTENTION_QKV));
		Assert.assertTrue(targets.contains(TargetLayer.SELF_ATTENTION_OUT));
		Assert.assertTrue(targets.contains(TargetLayer.CROSS_ATTENTION_Q));
		Assert.assertTrue(targets.contains(TargetLayer.CROSS_ATTENTION_KV));
		Assert.assertTrue(targets.contains(TargetLayer.CROSS_ATTENTION_OUT));
		Assert.assertFalse(targets.contains(TargetLayer.FFN_GATE));
		Assert.assertFalse(targets.contains(TargetLayer.FFN_OUT));

		log("forAudioDiffusion factory test passed");
	}

	/**
	 * Test full() factory method.
	 */
	@Test
	public void testFullFactory() {
		AdapterConfig config = AdapterConfig.full();

		for (TargetLayer layer : TargetLayer.values()) {
			Assert.assertTrue("Should target " + layer, config.isTargeted(layer));
		}

		log("full factory test passed");
	}

	/**
	 * Test minimal() factory method.
	 */
	@Test
	public void testMinimalFactory() {
		AdapterConfig config = AdapterConfig.minimal();

		Assert.assertEquals(4, config.getRank());
		Assert.assertEquals(8.0, config.getAlpha(), 0.001);
		Assert.assertTrue(config.isTargeted(TargetLayer.SELF_ATTENTION_QKV));
		Assert.assertTrue(config.isTargeted(TargetLayer.SELF_ATTENTION_OUT));
		Assert.assertEquals(2, config.getTargets().size());

		log("minimal factory test passed");
	}

	/**
	 * Test loraOrDense creates LoRA layer when configured.
	 */
	@Test
	public void testLoraOrDenseCreatesLoraWhenTargeted() {
		testConfig = new AdapterConfig()
				.rank(8)
				.alpha(16.0)
				.targets(TargetLayer.SELF_ATTENTION_QKV);
		testLoraLayers.clear();

		int inputSize = 64;
		int outputSize = 32;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> random.nextGaussian() * 0.1);

		// This should create a LoRA layer
		CellularLayer layer = loraOrDense(
				shape(2, inputSize),
				weights,
				TargetLayer.SELF_ATTENTION_QKV
		);

		Assert.assertTrue("Should be LoRALinear", layer instanceof LoRALinear);
		Assert.assertEquals("Should track 1 LoRA layer", 1, testLoraLayers.size());

		log("loraOrDense creates LoRA when targeted - passed");
	}

	/**
	 * Test loraOrDense creates dense layer when not configured.
	 */
	@Test
	public void testLoraOrDenseCreatesDenseWhenNotTargeted() {
		testConfig = new AdapterConfig()
				.targets(TargetLayer.SELF_ATTENTION_QKV);  // Only QKV targeted
		testLoraLayers.clear();

		int inputSize = 64;
		int outputSize = 32;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> random.nextGaussian() * 0.1);

		// This should create a dense layer (not LoRA)
		CellularLayer layer = loraOrDense(
				shape(2, inputSize),
				weights,
				TargetLayer.SELF_ATTENTION_OUT  // Not targeted
		);

		Assert.assertFalse("Should not be LoRALinear", layer instanceof LoRALinear);
		Assert.assertEquals("Should track 0 LoRA layers", 0, testLoraLayers.size());

		log("loraOrDense creates dense when not targeted - passed");
	}

	/**
	 * Test loraOrDense with null config creates dense layer.
	 */
	@Test
	public void testLoraOrDenseWithNullConfig() {
		testConfig = null;
		testLoraLayers.clear();

		int inputSize = 64;
		int outputSize = 32;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> random.nextGaussian() * 0.1);

		CellularLayer layer = loraOrDense(
				shape(2, inputSize),
				weights,
				TargetLayer.SELF_ATTENTION_QKV
		);

		Assert.assertFalse("Should not be LoRALinear when config is null", layer instanceof LoRALinear);

		log("loraOrDense with null config - passed");
	}

	/**
	 * Test getTrainableParameters returns LoRA weights.
	 */
	@Test
	public void testGetTrainableParameters() {
		testConfig = AdapterConfig.full();
		testLoraLayers.clear();

		int inputSize = 64;
		int outputSize = 32;

		PackedCollection weights1 = new PackedCollection(shape(outputSize, inputSize));
		weights1.fill(pos -> random.nextGaussian() * 0.1);
		PackedCollection weights2 = new PackedCollection(shape(outputSize, inputSize));
		weights2.fill(pos -> random.nextGaussian() * 0.1);

		loraOrDense(shape(2, inputSize), weights1, TargetLayer.SELF_ATTENTION_QKV);
		loraOrDense(shape(2, inputSize), weights2, TargetLayer.SELF_ATTENTION_OUT);

		List<PackedCollection> trainable = getTrainableParameters();

		// Each LoRA layer has 2 trainable matrices (A and B)
		Assert.assertEquals("Should have 4 trainable parameters", 4, trainable.size());

		log("getTrainableParameters test passed");
	}

	/**
	 * Test getTrainableParameterCount.
	 */
	@Test
	public void testGetTrainableParameterCount() {
		testConfig = new AdapterConfig().rank(4);
		testLoraLayers.clear();

		int inputSize = 64;
		int outputSize = 32;
		int rank = 4;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> random.nextGaussian() * 0.1);

		loraOrDense(shape(2, inputSize), weights, TargetLayer.SELF_ATTENTION_QKV);

		long expectedCount = (rank * inputSize) + (outputSize * rank);
		long actualCount = getTrainableParameterCount();

		Assert.assertEquals("Trainable parameter count should match",
				expectedCount, actualCount);

		log("getTrainableParameterCount test passed: " + actualCount + " parameters");
	}

	/**
	 * Test that LoRA layer output matches base layer when B is zero.
	 */
	@Test
	public void testLoraLayerOutputMatchesBaseInitially() {
		testConfig = AdapterConfig.minimal();
		testLoraLayers.clear();

		int batchSize = 2;
		int inputSize = 64;
		int outputSize = 32;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		weights.fill(pos -> random.nextGaussian() * 0.1);

		// Create LoRA layer
		CellularLayer loraLayer = loraOrDense(
				shape(batchSize, inputSize),
				weights,
				TargetLayer.SELF_ATTENTION_QKV
		);

		// Create equivalent dense layer
		CellularLayer denseLayer = dense(shape(batchSize, inputSize), weights, null, false);

		// Create input
		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.fill(pos -> random.nextGaussian());

		// Compile and run both models
		Model loraModel = new Model(shape(batchSize, inputSize));
		loraModel.add(loraLayer);
		CompiledModel loraCompiled = loraModel.compile();

		Model denseModel = new Model(shape(batchSize, inputSize));
		denseModel.add(denseLayer);
		CompiledModel denseCompiled = denseModel.compile();

		PackedCollection loraOutput = loraCompiled.forward(input);
		PackedCollection denseOutput = denseCompiled.forward(input);

		// Outputs should match initially
		for (int i = 0; i < loraOutput.getShape().getTotalSize(); i++) {
			Assert.assertEquals("Output should match at index " + i,
					denseOutput.toDouble(i), loraOutput.toDouble(i), TOLERANCE);
		}

		log("LoRA output matches base initially - passed");
	}
}
