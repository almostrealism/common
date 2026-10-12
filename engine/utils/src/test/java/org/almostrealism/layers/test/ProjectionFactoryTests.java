/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.AdapterConfig.TargetLayer;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DenseProjectionFactory;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.layers.LoRAProjectionFactory;
import org.almostrealism.layers.LowRankAdapterSupport;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for {@link ProjectionFactory}, {@link DenseProjectionFactory} and
 * {@link LoRAProjectionFactory}, and the {@link LowRankAdapterSupport} defaults
 * that build a {@link LoRAProjectionFactory}.
 */
public class ProjectionFactoryTests extends TestSuiteBase implements LayerFeatures {

	/** Tolerance for floating-point comparisons. */
	private static final double TOLERANCE = 1e-4;
	/** Random generator for deterministic test data. */
	private static final Random random = new Random(42);

	/**
	 * Builds a small random weight matrix.
	 *
	 * @param outputSize Number of output nodes (first dimension)
	 * @param inputSize Number of inputs (second dimension)
	 * @return A randomly initialized weight matrix of shape {@code [outputSize, inputSize]}
	 */
	private PackedCollection randomWeights(int outputSize, int inputSize) {
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		randn(weights.getShape(), random).multiply(0.1).into(weights.traverseEach()).evaluate();
		return weights;
	}

	/**
	 * A {@link LowRankAdapterSupport} implementor whose {@code dense(...)} override is
	 * observable: it counts calls so a test can prove an untargeted projection went
	 * through the implementor's own {@code dense(...)} rather than a factory-inherited one.
	 */
	private static class RecordingSupport implements LowRankAdapterSupport {
		/** Adapter configuration under test. */
		private final AdapterConfig config;
		/** Adapters registered by this implementor. */
		private final List<LoRALinear> loraLayers = new ArrayList<>();
		/** Number of times the overriding {@code dense(...)} was called. */
		private int denseCalls = 0;

		/**
		 * Creates a recording implementor.
		 *
		 * @param config Adapter configuration, may be {@code null}
		 */
		RecordingSupport(AdapterConfig config) {
			this.config = config;
		}

		@Override
		public AdapterConfig getAdapterConfig() {
			return config;
		}

		@Override
		public List<LoRALinear> getLoraLayers() {
			return loraLayers;
		}

		@Override
		public void addLoraLayer(LoRALinear layer) {
			loraLayers.add(layer);
		}

		@Override
		public CellularLayer dense(TraversalPolicy inputShape,
								   PackedCollection weights,
								   PackedCollection biases,
								   boolean init,
								   ComputeRequirement... requirements) {
			denseCalls++;
			return LowRankAdapterSupport.super.dense(inputShape, weights, biases, init, requirements);
		}
	}

	/**
	 * A {@link RecordingSupport} that overrides {@link #getProjectionFactory()} to return a
	 * plain dense factory &mdash; the {@code DiffusionTransformer} situation &mdash; to prove
	 * {@code loraOrDense} stays independent of that override.
	 */
	private static class DenseOverridingSupport extends RecordingSupport {
		/**
		 * Creates a dense-overriding implementor.
		 *
		 * @param config Adapter configuration, may be {@code null}
		 */
		DenseOverridingSupport(AdapterConfig config) {
			super(config);
		}

		@Override
		public ProjectionFactory getProjectionFactory() {
			return ProjectionFactory.dense();
		}
	}

	/**
	 * The statics return the named concrete types, not throwaway lambdas.
	 */
	@Test(timeout = 60000)
	public void concreteFactoryTypes() {
		Assert.assertTrue("dense() should be a DenseProjectionFactory",
				ProjectionFactory.dense() instanceof DenseProjectionFactory);

		List<LoRALinear> loraLayers = new ArrayList<>();
		Assert.assertTrue("lora(config, list) should be a LoRAProjectionFactory",
				ProjectionFactory.lora(new AdapterConfig(), loraLayers) instanceof LoRAProjectionFactory);

		RecordingSupport support = new RecordingSupport(new AdapterConfig());
		Assert.assertTrue("getProjectionFactory() should be a LoRAProjectionFactory",
				support.getProjectionFactory() instanceof LoRAProjectionFactory);

		log("concrete factory types - passed");
	}

	/**
	 * A {@code dense()} projection matches {@link LayerFeatures#dense} for the same
	 * weights, with and without bias.
	 */
	@Test(timeout = 120000)
	public void denseMatchesLayerFeaturesDense() {
		int batchSize = 2;
		int inputSize = 64;
		int outputSize = 32;

		PackedCollection weights = randomWeights(outputSize, inputSize);
		PackedCollection bias = new PackedCollection(shape(outputSize));
		randn(bias.getShape(), random).multiply(0.1).into(bias.traverseEach()).evaluate();

		PackedCollection input = new PackedCollection(shape(batchSize, inputSize));
		input.randnFill(random);

		ProjectionFactory factory = ProjectionFactory.dense();

		assertSameOutput(input,
				dense(shape(batchSize, inputSize), weights, null, false),
				factory.create(shape(batchSize, inputSize), weights, null, TargetLayer.SELF_ATTENTION_QKV));

		assertSameOutput(input,
				dense(shape(batchSize, inputSize), weights, bias, false),
				factory.create(shape(batchSize, inputSize), weights, bias, TargetLayer.SELF_ATTENTION_QKV));

		log("dense matches LayerFeatures.dense - passed");
	}

	/**
	 * Compiles two single-layer models and asserts their forward outputs match element-wise.
	 *
	 * @param input Input to feed both models
	 * @param expected Reference layer
	 * @param actual Layer under test
	 */
	private void assertSameOutput(PackedCollection input, CellularLayer expected, CellularLayer actual) {
		Model expectedModel = new Model(input.getShape());
		expectedModel.add(expected);
		CompiledModel expectedCompiled = expectedModel.compile();

		Model actualModel = new Model(input.getShape());
		actualModel.add(actual);
		CompiledModel actualCompiled = actualModel.compile();

		PackedCollection expectedOutput = expectedCompiled.forward(input);
		PackedCollection actualOutput = actualCompiled.forward(input);

		Assert.assertEquals(expectedOutput.getShape().getTotalSize(), actualOutput.getShape().getTotalSize());
		for (int i = 0; i < expectedOutput.getShape().getTotalSize(); i++) {
			Assert.assertEquals("Output should match at index " + i,
					expectedOutput.toDouble(i), actualOutput.toDouble(i), TOLERANCE);
		}
	}

	/**
	 * {@code lora(config, list)} wraps a targeted layer and tracks it; an untargeted layer
	 * is dense and the list is untouched.
	 */
	@Test(timeout = 60000)
	public void loraWrapsTargetedAndTracks() {
		AdapterConfig config = new AdapterConfig().targets(TargetLayer.SELF_ATTENTION_QKV);
		List<LoRALinear> loraLayers = new ArrayList<>();
		ProjectionFactory factory = ProjectionFactory.lora(config, loraLayers);

		PackedCollection weights = randomWeights(32, 64);

		CellularLayer targeted = factory.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
		Assert.assertTrue("Targeted projection should be LoRALinear", targeted instanceof LoRALinear);
		Assert.assertEquals("List should track the adapter", 1, loraLayers.size());
		Assert.assertSame(targeted, loraLayers.get(0));

		CellularLayer untargeted = factory.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_OUT);
		Assert.assertFalse("Untargeted projection should not be LoRALinear", untargeted instanceof LoRALinear);
		Assert.assertEquals("List should be untouched for untargeted", 1, loraLayers.size());

		log("lora wraps targeted and tracks - passed");
	}

	/**
	 * {@code lora(null, list)} adapts nothing; {@code lora(config, null)} adapts without
	 * tracking (the null-list contract).
	 */
	@Test(timeout = 60000)
	public void loraNullContracts() {
		PackedCollection weights = randomWeights(32, 64);

		List<LoRALinear> loraLayers = new ArrayList<>();
		ProjectionFactory nullConfig = ProjectionFactory.lora(null, loraLayers);
		CellularLayer layer = nullConfig.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
		Assert.assertFalse("Null config should never adapt", layer instanceof LoRALinear);
		Assert.assertEquals("Null config should track nothing", 0, loraLayers.size());

		ProjectionFactory nullList = ProjectionFactory.lora(
				new AdapterConfig().targets(TargetLayer.SELF_ATTENTION_QKV), null);
		CellularLayer adapted = nullList.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
		Assert.assertTrue("Null list should still adapt targeted layers", adapted instanceof LoRALinear);

		log("lora null contracts - passed");
	}

	/**
	 * The {@link LoRAProjectionFactory} constructor rejects a null registry or fallback, but
	 * accepts a null config (which makes every projection untargeted).
	 */
	@Test(timeout = 60000)
	public void constructorContract() {
		ProjectionFactory fallback = ProjectionFactory.dense();

		try {
			new LoRAProjectionFactory(new AdapterConfig(), null, fallback);
			Assert.fail("Null registry should be rejected");
		} catch (NullPointerException expected) {
			// expected
		}

		try {
			new LoRAProjectionFactory(new AdapterConfig(), layer -> { }, null);
			Assert.fail("Null fallback should be rejected");
		} catch (NullPointerException expected) {
			// expected
		}

		List<LoRALinear> created = new ArrayList<>();
		LoRAProjectionFactory nullConfig = new LoRAProjectionFactory(null, created::add, fallback);
		PackedCollection weights = randomWeights(32, 64);
		CellularLayer layer = nullConfig.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
		Assert.assertFalse("Null config delegates every projection to the fallback",
				layer instanceof LoRALinear);
		Assert.assertEquals("Null config creates no adapters", 0, created.size());

		log("constructor contract - passed");
	}

	/**
	 * {@link LowRankAdapterSupport#getProjectionFactory()} registers adapters via
	 * {@code addLoraLayer}.
	 */
	@Test(timeout = 60000)
	public void getProjectionFactoryRegistersAdapters() {
		RecordingSupport support = new RecordingSupport(
				new AdapterConfig().targets(TargetLayer.SELF_ATTENTION_QKV));
		ProjectionFactory factory = support.getProjectionFactory();

		PackedCollection weights = randomWeights(32, 64);
		CellularLayer layer = factory.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);

		Assert.assertTrue(layer instanceof LoRALinear);
		Assert.assertEquals("Adapter should be registered via addLoraLayer",
				1, support.getLoraLayers().size());
		Assert.assertSame(layer, support.getLoraLayers().get(0));

		log("getProjectionFactory registers adapters - passed");
	}

	/**
	 * {@code loraOrDense} still creates and registers a {@link LoRALinear} for a targeted layer
	 * even when the implementor overrides {@code getProjectionFactory()} to return a plain dense
	 * factory (the {@code DiffusionTransformer} situation).
	 */
	@Test(timeout = 60000)
	public void loraOrDenseIndependentOfOverriddenFactory() {
		DenseOverridingSupport support = new DenseOverridingSupport(
				new AdapterConfig().targets(TargetLayer.SELF_ATTENTION_QKV));

		PackedCollection weights = randomWeights(32, 64);
		CellularLayer layer = support.loraOrDense(shape(2, 64), weights, TargetLayer.SELF_ATTENTION_QKV);

		Assert.assertTrue("loraOrDense must adapt regardless of getProjectionFactory() override",
				layer instanceof LoRALinear);
		Assert.assertEquals("Adapter should be registered", 1, support.getLoraLayers().size());

		log("loraOrDense independent of overridden factory - passed");
	}

	/**
	 * An untargeted projection goes through the implementor's own overriding {@code dense(...)},
	 * via both {@code getProjectionFactory().create(...)} and {@code loraOrDense(...)}.
	 */
	@Test(timeout = 60000)
	public void untargetedProjectionsUseImplementorDense() {
		RecordingSupport support = new RecordingSupport(
				new AdapterConfig().targets(TargetLayer.SELF_ATTENTION_QKV));
		PackedCollection weights = randomWeights(32, 64);

		support.getProjectionFactory().create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_OUT);
		Assert.assertEquals("getProjectionFactory fallback should use the implementor's dense()",
				1, support.denseCalls);

		support.loraOrDense(shape(2, 64), weights, TargetLayer.SELF_ATTENTION_OUT);
		Assert.assertEquals("loraOrDense fallback should use the implementor's dense()",
				2, support.denseCalls);

		Assert.assertTrue("No adapters should be created for untargeted projections",
				support.getLoraLayers().isEmpty());

		log("untargeted projections use implementor dense - passed");
	}
}
