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

package org.almostrealism.ml.audio;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;

/**
 * Strategy interface for diffusion sampling algorithms.
 *
 * <p>Different sampling strategies implement the reverse diffusion process
 * in different ways, trading off quality, speed, and stochasticity.
 *
 * <p><b>GPU-First Design:</b> Methods return {@link CollectionProducer} instead of
 * {@link PackedCollection} to allow composition into GPU computation graphs. The caller
 * decides when to materialize results with {@code .evaluate()}. This enables:
 * <ul>
 *   <li>Multiple operations to be fused into a single GPU kernel</li>
 *   <li>Better optimization opportunities</li>
 *   <li>Reduced CPU-GPU synchronization overhead</li>
 * </ul>
 *
 * <h2>Available Strategies</h2>
 * <ul>
 *   <li>{@link DDIMSamplingStrategy} - Deterministic/accelerated DDIM sampling</li>
 *   <li>{@link PingPongSamplingStrategy} - Ping-pong (rectified flow) sampling</li>
 * </ul>
 *
 * @see DiffusionSampler
 * @author Michael Murray
 */
public interface SamplingStrategy {

	/**
	 * Returns the timestep schedule for this strategy.
	 *
	 * @param numSteps Total number of diffusion steps
	 * @param numInferenceSteps Number of inference steps (may be fewer than numSteps)
	 * @return Array of timestep values for each inference step
	 */
	double[] getTimesteps(int numSteps, int numInferenceSteps);

	/**
	 * Performs one sampling step.
	 *
	 * <p>Returns a {@link CollectionProducer} for GPU-accelerated computation.
	 * The caller should call {@code .evaluate()} when materialization is needed.</p>
	 *
	 * @param x Current noisy sample
	 * @param modelOutput Model's prediction (noise or velocity depending on parameterization)
	 * @param t Current timestep value
	 * @param tPrev Next timestep value (lower noise level)
	 * @param noise Pre-sampled noise for stochastic sampling (may be null for deterministic)
	 * @return Producer for the updated sample at timestep tPrev
	 */
	CollectionProducer step(PackedCollection x, PackedCollection modelOutput,
							   double t, double tPrev, PackedCollection noise);

	/**
	 * Samples initial noise for starting the diffusion process.
	 *
	 * <p>Note: Random number generation is inherently CPU-bound, so this
	 * returns {@link PackedCollection} directly.</p>
	 *
	 * @param shape Shape of the latent tensor
	 * @param random Random number generator
	 * @return Initial noisy sample
	 */
	default PackedCollection sampleInitialNoise(int[] shape, Random random) {
		return new PackedCollection(shape).randnFill(random);
	}

	/**
	 * Adds noise to a clean sample for img2img-style generation.
	 *
	 * <p>Returns a {@link CollectionProducer} for GPU-accelerated computation.</p>
	 *
	 * @param cleanSample The clean sample to add noise to
	 * @param t Target timestep (noise level)
	 * @param noise Pre-sampled noise tensor
	 * @return Producer for the noisy sample at timestep t
	 */
	CollectionProducer addNoise(PackedCollection cleanSample, double t, PackedCollection noise);
}
