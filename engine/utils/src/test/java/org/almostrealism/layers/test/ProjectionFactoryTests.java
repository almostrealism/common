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

		ProjectionFactory factory = ProjectionFactory.dense();

		try (PackedCollection input = new PackedCollection(shape(batchSize, inputSize))) {
			input.randnFill(random);

			PackedCollection unbiasedWeights = randomWeights(outputSize, inputSize);
			assertSameOutput(input,
					dense(shape(batchSize, inputSize), unbiasedWeights, null, false),
					factory.create(shape(batchSize, inputSize), unbiasedWeights, null, TargetLayer.SELF_ATTENTION_QKV));

			PackedCollection biasedWeights = randomWeights(outputSize, inputSize);
			PackedCollection bias = new PackedCollection(shape(outputSize));
			randn(bias.getShape(), random).multiply(0.1).into(bias.traverseEach()).evaluate();
			assertSameOutput(input,
					dense(shape(batchSize, inputSize), biasedWeights, bias, false),
					factory.create(shape(batchSize, inputSize), biasedWeights, bias, TargetLayer.SELF_ATTENTION_QKV));
		}

		log("dense matches LayerFeatures.dense - passed");
	}

	/**
	 * Compiles two single-layer models and asserts their forward outputs match element-wise.
	 *
	 * <p>Both models and their compiled forms are destroyed before this method returns,
	 * including when an assertion fails. Destroying a model releases its layers' weights,
	 * so the caller must not reuse the weights passed to {@code expected} or {@code actual}
	 * after this call.</p>
	 *
	 * @param input Input to feed both models; not destroyed by this method
	 * @param expected Reference layer, owned by this method from this call on
	 * @param actual Layer under test, owned by this method from this call on
	 */
	private void assertSameOutput(PackedCollection input, CellularLayer expected, CellularLayer actual) {
		try (Model expectedModel = new Model(input.getShape());
			 Model actualModel = new Model(input.getShape())) {
			expectedModel.add(expected);
			actualModel.add(actual);

			try (CompiledModel expectedCompiled = expectedModel.compile();
				 CompiledModel actualCompiled = actualModel.compile()) {
				double[] expectedOutput = expectedCompiled.forward(input).toArray();
				double[] actualOutput = actualCompiled.forward(input).toArray();

				Assert.assertEquals(expectedOutput.length, actualOutput.length);
				for (int i = 0; i < expectedOutput.length; i++) {
					Assert.assertEquals("Output should match at index " + i,
							expectedOutput[i], actualOutput[i], TOLERANCE);
				}
			}
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

		List<CellularLayer> untargetedLayers = new ArrayList<>();
		try (PackedCollection weights = randomWeights(32, 64)) {
			CellularLayer targeted = factory.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
			Assert.assertTrue("Targeted projection should be LoRALinear", targeted instanceof LoRALinear);
			Assert.assertEquals("List should track the adapter", 1, loraLayers.size());
			Assert.assertSame(targeted, loraLayers.get(0));

			CellularLayer untargeted = factory.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_OUT);
			untargetedLayers.add(untargeted);
			Assert.assertFalse("Untargeted projection should not be LoRALinear", untargeted instanceof LoRALinear);
			Assert.assertEquals("List should be untouched for untargeted", 1, loraLayers.size());
		} finally {
			loraLayers.forEach(LoRALinear::destroy);
			untargetedLayers.forEach(CellularLayer::destroy);
		}

		log("lora wraps targeted and tracks - passed");
	}

	/**
	 * {@code lora(null, list)} adapts nothing; {@code lora(config, null)} adapts without
	 * tracking (the null-list contract).
	 */
	@Test(timeout = 60000)
	public void loraNullContracts() {
		List<CellularLayer> created = new ArrayList<>();
		try (PackedCollection weights = randomWeights(32, 64)) {
			List<LoRALinear> loraLayers = new ArrayList<>();
			ProjectionFactory nullConfig = ProjectionFactory.lora(null, loraLayers);
			CellularLayer layer = nullConfig.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
			created.add(layer);
			Assert.assertFalse("Null config should never adapt", layer instanceof LoRALinear);
			Assert.assertEquals("Null config should track nothing", 0, loraLayers.size());

			ProjectionFactory nullList = ProjectionFactory.lora(
					new AdapterConfig().targets(TargetLayer.SELF_ATTENTION_QKV), null);
			CellularLayer adapted = nullList.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
			created.add(adapted);
			Assert.assertTrue("Null list should still adapt targeted layers", adapted instanceof LoRALinear);
		} finally {
			created.forEach(CellularLayer::destroy);
		}

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
		CellularLayer layer = null;
		try (PackedCollection weights = randomWeights(32, 64)) {
			layer = nullConfig.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);
			Assert.assertFalse("Null config delegates every projection to the fallback",
					layer instanceof LoRALinear);
			Assert.assertEquals("Null config creates no adapters", 0, created.size());
		} finally {
			if (layer != null) layer.destroy();
		}

		log("constructor contract - passed");
	}

	/**
	 * A {@link LoRAProjectionFactory} hands each untargeted projection, with its exact
	 * arguments, to the supplied fallback and returns the fallback's layer unchanged; a
	 * targeted projection never reaches the fallback and carries the configured rank,
	 * alpha and base weights.
	 */
	@Test(timeout = 60000)
	public void loraRoutesUntargetedToFallback() {
		List<LoRALinear> created = new ArrayList<>();
		List<CellularLayer> denseLayers = new ArrayList<>();
		try (PackedCollection weights = randomWeights(32, 64);
			 PackedCollection bias = new PackedCollection(shape(32))) {
			TraversalPolicy inputShape = shape(2, 64);
			CellularLayer fallbackLayer = dense(inputShape, weights, bias, false);
			denseLayers.add(fallbackLayer);

			List<TargetLayer> fallbackTargets = new ArrayList<>();
			ProjectionFactory fallback = (shape, w, b, target) -> {
				Assert.assertSame("Fallback should receive the input shape", inputShape, shape);
				Assert.assertSame("Fallback should receive the weights", weights, w);
				Assert.assertSame("Fallback should receive the bias", bias, b);
				fallbackTargets.add(target);
				return fallbackLayer;
			};

			AdapterConfig config = new AdapterConfig().rank(4).alpha(8.0)
					.targets(TargetLayer.SELF_ATTENTION_QKV);
			LoRAProjectionFactory factory = new LoRAProjectionFactory(config, created::add, fallback);

			CellularLayer untargeted = factory.create(inputShape, weights, bias, TargetLayer.FFN_GATE);
			Assert.assertSame("Untargeted projection should be the fallback's layer", fallbackLayer, untargeted);
			Assert.assertEquals(List.of(TargetLayer.FFN_GATE), fallbackTargets);
			Assert.assertTrue("Untargeted projection should create no adapter", created.isEmpty());

			CellularLayer targeted = factory.create(inputShape, weights, bias, TargetLayer.SELF_ATTENTION_QKV);
			Assert.assertTrue("Targeted projection should be LoRALinear", targeted instanceof LoRALinear);
			Assert.assertEquals("Targeted projection should not reach the fallback", 1, fallbackTargets.size());

			LoRALinear adapter = (LoRALinear) targeted;
			Assert.assertEquals(4, adapter.getRank());
			Assert.assertEquals(8.0, adapter.getAlpha(), 0.0);
			Assert.assertSame(weights, adapter.getBaseWeights());
			Assert.assertSame(bias, adapter.getBaseBias());
			Assert.assertEquals(List.of(adapter), created);
		} finally {
			created.forEach(LoRALinear::destroy);
			denseLayers.forEach(CellularLayer::destroy);
		}

		log("lora routes untargeted to fallback - passed");
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

		try (PackedCollection weights = randomWeights(32, 64)) {
			CellularLayer layer = factory.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_QKV);

			Assert.assertTrue(layer instanceof LoRALinear);
			Assert.assertEquals("Adapter should be registered via addLoraLayer",
					1, support.getLoraLayers().size());
			Assert.assertSame(layer, support.getLoraLayers().get(0));
		} finally {
			support.getLoraLayers().forEach(LoRALinear::destroy);
		}

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

		try (PackedCollection weights = randomWeights(32, 64)) {
			CellularLayer layer = support.loraOrDense(shape(2, 64), weights, TargetLayer.SELF_ATTENTION_QKV);

			Assert.assertTrue("loraOrDense must adapt regardless of getProjectionFactory() override",
					layer instanceof LoRALinear);
			Assert.assertEquals("Adapter should be registered", 1, support.getLoraLayers().size());
		} finally {
			support.getLoraLayers().forEach(LoRALinear::destroy);
		}

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

		List<CellularLayer> denseLayers = new ArrayList<>();
		try (PackedCollection weights = randomWeights(32, 64)) {
			denseLayers.add(support.getProjectionFactory()
					.create(shape(2, 64), weights, null, TargetLayer.SELF_ATTENTION_OUT));
			Assert.assertEquals("getProjectionFactory fallback should use the implementor's dense()",
					1, support.denseCalls);

			denseLayers.add(support.loraOrDense(shape(2, 64), weights, TargetLayer.SELF_ATTENTION_OUT));
			Assert.assertEquals("loraOrDense fallback should use the implementor's dense()",
					2, support.denseCalls);

			Assert.assertTrue("No adapters should be created for untargeted projections",
					support.getLoraLayers().isEmpty());
		} finally {
			denseLayers.forEach(CellularLayer::destroy);
		}

		log("untargeted projections use implementor dense - passed");
	}
}
