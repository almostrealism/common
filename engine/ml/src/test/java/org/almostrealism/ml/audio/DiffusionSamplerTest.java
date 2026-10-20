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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Random;

/**
 * Tests for {@link DiffusionSampler}, in particular that both entry points into the sampling loop
 * build a schedule consistent with the latent's actual sequence length.
 */
public class DiffusionSamplerTest extends TestSuiteBase {

	/** Batch dimension of the sampled latent. */
	private static final int BATCH = 1;
	/** Channel count of the sampled latent. */
	private static final int CHANNELS = 2;
	/** Sequence length (final axis) of the sampled latent. */
	private static final int SEQ_LEN = 32;

	/**
	 * {@link DiffusionSampler#sampleFrom} must build its initial schedule using the latent's actual
	 * sequence length rather than the two-argument {@link SamplingStrategy#getTimesteps(int, int)}
	 * overload, which a length-dependent {@link DistributionShift} (via
	 * {@link PingPongSamplingStrategy}) rejects for a sequence length of zero. Before this was fixed,
	 * img2img sampling with any length-dependent schedule threw before the sampling loop started.
	 */
	@Test(timeout = 60000)
	public void sampleFromSupportsLengthDependentSchedule() {
		TraversalPolicy latentShape = shape(BATCH, CHANNELS, SEQ_LEN);
		DiffusionSampler sampler = new DiffusionSampler(
				new ZeroDiffusionModel(latentShape), new PingPongSamplingStrategy(new LogSNRShift()),
				1000, latentShape)
				.setNumInferenceSteps(4)
				.setVerbose(false);

		PackedCollection startLatent = new PackedCollection(latentShape).randnFill();
		PackedCollection output = sampler.sampleFrom(startLatent, 0.5, 42L, null, null);

		assertEquals(latentShape.getTotalSize(), output.getShape().getTotalSize());
	}

	/**
	 * {@link DiffusionSampler#sample} already passed the latent's sequence length into the schedule
	 * before this fix; this is a regression guard confirming a length-dependent schedule keeps working
	 * from pure noise as well as from an existing latent.
	 */
	@Test(timeout = 60000)
	public void sampleSupportsLengthDependentSchedule() {
		TraversalPolicy latentShape = shape(BATCH, CHANNELS, SEQ_LEN);
		DiffusionSampler sampler = new DiffusionSampler(
				new ZeroDiffusionModel(latentShape), new PingPongSamplingStrategy(new LogSNRShift()),
				1000, latentShape)
				.setNumInferenceSteps(4)
				.setVerbose(false);

		PackedCollection output = sampler.sample(42L, null, null);

		assertEquals(latentShape.getTotalSize(), output.getShape().getTotalSize());
	}

	/**
	 * With guidance enabled the sampler runs the model twice per step, once with the sampling
	 * conditioning and once with the negative conditioning, and feeds the guided prediction into
	 * the sampling step. A model predicting one under conditioning and zero without it, guided
	 * with scale two and no projection, predicts two; a single ping-pong step from noise at
	 * {@code t = 1} to {@code t = 0} then yields {@code noise - 2}.
	 */
	@Test(timeout = 60000)
	public void guidanceCombinesConditionalAndNegativePredictions() {
		TraversalPolicy latentShape = shape(BATCH, CHANNELS, SEQ_LEN);
		ConditioningSensitiveModel model = new ConditioningSensitiveModel(latentShape);
		PingPongSamplingStrategy strategy = new PingPongSamplingStrategy(new LogSNRShift());
		PackedCollection negative = new PackedCollection(shape(1, 1));

		DiffusionSampler sampler = new DiffusionSampler(model, strategy, 1000, latentShape)
				.setNumInferenceSteps(1)
				.setVerbose(false)
				.setGuidance(new ClassifierFreeGuidance(2.0, 0.0), null, negative);

		PackedCollection positive = new PackedCollection(shape(1, 1)).fill(1.0);
		PackedCollection output = sampler.sample(42L, null, positive);
		PackedCollection noise = strategy.sampleInitialNoise(latentShape.extent(), new Random(42L));

		assertEquals(2, model.calls);
		assertEquals(1, model.conditionedCalls);
		for (int i = 0; i < latentShape.getTotalSize(); i++) {
			assertEquals(noise.toDouble(i) - 2.0, output.toDouble(i), 1e-5);
		}
	}

	/**
	 * {@link DiffusionSampler#runSamplingLoop} must never destroy the buffer {@link DiffusionModel#forward}
	 * returns, because {@link DiffusionModel#forward} is documented to hand back a buffer owned by the
	 * model — which a real implementation may reuse across every call. A model that always returns a
	 * freshly allocated buffer (like the {@link ZeroDiffusionModel} used by the schedule tests above)
	 * cannot expose a violation of that contract, since destroying one call's buffer does not affect
	 * the next. This test uses a model that reuses one buffer across every step, matching the reused-
	 * buffer implementations the sampler actually runs against, and confirms the model can still be
	 * called successfully after sampling completes.
	 */
	@Test(timeout = 60000)
	public void samplerNeverDestroysTheModelsOwnedOutputBuffer() {
		TraversalPolicy latentShape = shape(BATCH, CHANNELS, SEQ_LEN);
		ZeroDiffusionModel model = new ZeroDiffusionModel(latentShape);
		DiffusionSampler sampler = new DiffusionSampler(
				model, new PingPongSamplingStrategy(new LogSNRShift()),
				1000, latentShape)
				.setNumInferenceSteps(4)
				.setVerbose(false);

		sampler.sample(42L, null, null);

		PackedCollection output = model.forward(null, null, null, null);
		assertEquals(0.0, output.toDouble(0), 0.0);
	}

	/**
	 * A {@link DiffusionModel} stub predicting one everywhere when the global conditioning is
	 * non-zero and zero everywhere otherwise, counting how it was called.
	 */
	private static class ConditioningSensitiveModel implements DiffusionModel {
		/** The buffer returned for every forward pass, refilled rather than reallocated so
		 * this stub honors {@link DiffusionModel#forward}'s model-owned-buffer contract the
		 * same way a real implementation does. */
		private final PackedCollection output;

		/** Number of forward passes made. */
		private int calls;

		/** Number of forward passes made with non-zero global conditioning. */
		private int conditionedCalls;

		/**
		 * Creates the stub.
		 *
		 * @param shape shape of the prediction to return
		 */
		private ConditioningSensitiveModel(TraversalPolicy shape) {
			this.output = new PackedCollection(shape);
		}

		@Override
		public PackedCollection forward(PackedCollection x, PackedCollection t,
										 PackedCollection crossAttnCond, PackedCollection globalCond) {
			calls++;
			boolean conditioned = globalCond != null && globalCond.toDouble(0) != 0.0;
			if (conditioned) conditionedCalls++;
			return output.fill(conditioned ? 1.0 : 0.0);
		}
	}

	/**
	 * Minimal {@link DiffusionModel} stub that always predicts zero, so the sampling loop's numerics
	 * are irrelevant to these schedule-focused tests.
	 */
	private static class ZeroDiffusionModel implements DiffusionModel {
		/** The zero-filled buffer returned for every forward pass. Allocated once and never
		 * written to again, so it stays zero without needing a refill per call; reusing it
		 * (rather than allocating a fresh buffer per call) honors {@link DiffusionModel#forward}'s
		 * model-owned-buffer contract the same way a real implementation does. */
		private final PackedCollection output;

		/**
		 * Creates a stub that predicts zero tensors of the given shape.
		 *
		 * @param shape shape of the prediction to return
		 */
		private ZeroDiffusionModel(TraversalPolicy shape) {
			this.output = new PackedCollection(shape);
		}

		@Override
		public PackedCollection forward(PackedCollection x, PackedCollection t,
										 PackedCollection crossAttnCond, PackedCollection globalCond) {
			return output;
		}
	}
}
