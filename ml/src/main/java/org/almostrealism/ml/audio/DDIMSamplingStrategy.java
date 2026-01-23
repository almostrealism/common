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

/**
 * DDIM (Denoising Diffusion Implicit Models) sampling strategy.
 *
 * <p>DDIM provides deterministic or semi-stochastic sampling that can be
 * faster than DDPM while maintaining quality.
 *
 * <p><b>GPU-First Design:</b> Methods return {@link CollectionProducer} to allow
 * composition into GPU computation graphs. See {@link SamplingStrategy} for details.</p>
 *
 * @see SamplingStrategy
 * @see DiffusionNoiseScheduler
 * @author Michael Murray
 */
public class DDIMSamplingStrategy implements SamplingStrategy {

	private final DiffusionNoiseScheduler scheduler;
	private final double eta;

	/**
	 * Creates a DDIM strategy with deterministic sampling (eta = 0).
	 *
	 * @param scheduler Noise scheduler for alpha values
	 */
	public DDIMSamplingStrategy(DiffusionNoiseScheduler scheduler) {
		this(scheduler, 0.0);
	}

	/**
	 * Creates a DDIM strategy with configurable stochasticity.
	 *
	 * @param scheduler Noise scheduler for alpha values
	 * @param eta Stochasticity parameter (0 = deterministic, 1 = DDPM)
	 */
	public DDIMSamplingStrategy(DiffusionNoiseScheduler scheduler, double eta) {
		this.scheduler = scheduler;
		this.eta = eta;
	}

	@Override
	public double[] getTimesteps(int numSteps, int numInferenceSteps) {
		// Return timesteps as normalized values [0, 1]
		int[] intTimesteps = scheduler.getDDIMTimesteps(numInferenceSteps);
		double[] timesteps = new double[intTimesteps.length + 1];
		for (int i = 0; i < intTimesteps.length; i++) {
			timesteps[i] = (double) intTimesteps[i] / numSteps;
		}
		timesteps[intTimesteps.length] = -1.0 / numSteps; // Final step indicator
		return timesteps;
	}

	@Override
	public CollectionProducer step(PackedCollection x, PackedCollection modelOutput,
									  double t, double tPrev, PackedCollection noise) {
		// Convert normalized timesteps back to integer indices
		int tInt = (int) Math.round(t * scheduler.getNumSteps());
		int tPrevInt = tPrev >= 0 ? (int) Math.round(tPrev * scheduler.getNumSteps()) : -1;

		return scheduler.stepDDIM(x, modelOutput, tInt, tPrevInt, eta, noise);
	}

	@Override
	public CollectionProducer addNoise(PackedCollection cleanSample, double t, PackedCollection noise) {
		int tInt = (int) Math.round(t * scheduler.getNumSteps());
		return scheduler.addNoise(cleanSample, noise, tInt);
	}

	/**
	 * Returns the eta (stochasticity) parameter.
	 *
	 * @return Eta value (0 = deterministic, 1 = DDPM)
	 */
	public double getEta() {
		return eta;
	}
}
