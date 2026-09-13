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
	 * Minimal {@link DiffusionModel} stub that always predicts zero, so the sampling loop's numerics
	 * are irrelevant to these schedule-focused tests.
	 */
	private static class ZeroDiffusionModel implements DiffusionModel {
		/** Shape of the prediction returned for every forward pass. */
		private final TraversalPolicy shape;

		/**
		 * Creates a stub that predicts zero tensors of the given shape.
		 *
		 * @param shape shape of the prediction to return
		 */
		private ZeroDiffusionModel(TraversalPolicy shape) {
			this.shape = shape;
		}

		@Override
		public PackedCollection forward(PackedCollection x, PackedCollection t,
										 PackedCollection crossAttnCond, PackedCollection globalCond) {
			return new PackedCollection(shape);
		}
	}
}
