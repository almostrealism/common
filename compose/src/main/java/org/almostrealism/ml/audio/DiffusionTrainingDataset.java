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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;

import java.util.Iterator;

/**
 * Dataset adapter that transforms clean latents into diffusion training samples.
 *
 * <p>This class wraps an {@link AudioLatentDataset} and generates proper training
 * samples for diffusion models by:
 * <ol>
 *   <li>Sampling a random timestep t</li>
 *   <li>Sampling noise from N(0, 1)</li>
 *   <li>Creating noisy latent: x_t = sqrt(alpha_t) * x_0 + sqrt(1-alpha_t) * noise</li>
 *   <li>Returning (noisy_latent, timestep) as input and noise as target</li>
 * </ol>
 *
 * <p>This design follows the architecture specified in the design document,
 * allowing diffusion training to use {@link org.almostrealism.optimize.ModelOptimizer}
 * without any modifications to the training loop.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AudioLatentDataset cleanLatents = AudioLatentDataset.fromDirectory(...);
 * DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(1000);
 *
 * // Wrap for diffusion training
 * DiffusionTrainingDataset diffusionData = new DiffusionTrainingDataset(
 *     cleanLatents, scheduler, repeatFactor
 * );
 *
 * // Use with ModelOptimizer
 * ModelOptimizer optimizer = new ModelOptimizer(model, () -> diffusionData);
 * optimizer.optimize(epochs * diffusionData.size());
 * }</pre>
 *
 * @see AudioLatentDataset
 * @see DiffusionNoiseScheduler
 * @see org.almostrealism.optimize.ModelOptimizer
 * @author Michael Murray
 */
public class DiffusionTrainingDataset implements Dataset<PackedCollection> {

	private final AudioLatentDataset source;
	private final DiffusionNoiseScheduler scheduler;
	private final int repeatFactor;

	/**
	 * Creates a diffusion training dataset.
	 *
	 * @param source       The source dataset of clean latents
	 * @param scheduler    Noise scheduler for timestep sampling and noise addition
	 * @param repeatFactor Number of times to repeat each sample (for aggressive training)
	 */
	public DiffusionTrainingDataset(AudioLatentDataset source,
									DiffusionNoiseScheduler scheduler,
									int repeatFactor) {
		this.source = source;
		this.scheduler = scheduler;
		this.repeatFactor = Math.max(1, repeatFactor);
	}

	/**
	 * Creates a diffusion training dataset with no repetition.
	 *
	 * @param source    The source dataset of clean latents
	 * @param scheduler Noise scheduler
	 */
	public DiffusionTrainingDataset(AudioLatentDataset source,
									DiffusionNoiseScheduler scheduler) {
		this(source, scheduler, 1);
	}

	/**
	 * Returns the effective size of this dataset (source size * repeat factor).
	 *
	 * @return Effective dataset size
	 */
	public int size() {
		return source.size() * repeatFactor;
	}

	/**
	 * Shuffles the underlying source dataset.
	 */
	public void shuffle() {
		source.shuffle();
	}

	@Override
	public Iterator<ValueTarget<PackedCollection>> iterator() {
		return new DiffusionIterator();
	}

	/**
	 * Iterator that generates diffusion training samples on-the-fly.
	 */
	private class DiffusionIterator implements Iterator<ValueTarget<PackedCollection>> {
		private int sampleIndex = 0;
		private int repeatIndex = 0;

		@Override
		public boolean hasNext() {
			return sampleIndex < source.size();
		}

		@Override
		public ValueTarget<PackedCollection> next() {
			PackedCollection cleanLatent = source.getLatent(sampleIndex);

			// Sample random timestep
			int t = scheduler.sampleTimestep();

			// Sample noise
			PackedCollection noise = scheduler.sampleNoiseLike(cleanLatent);

			// Create noisy latent: x_t = sqrt(alpha_t) * x_0 + sqrt(1-alpha_t) * noise
			PackedCollection noisyLatent = scheduler.addNoise(cleanLatent, noise, t);

			// Create timestep tensor (normalized to [0, 1])
			PackedCollection timestep = createTimestepTensor(t);

			// Advance indices
			repeatIndex++;
			if (repeatIndex >= repeatFactor) {
				repeatIndex = 0;
				sampleIndex++;
			}

			// Return with timestep as argument (for multi-input model)
			// Input: noisy latent, Arguments: [timestep], Target: noise
			return ValueTarget.<PackedCollection>of(noisyLatent, noise)
					.withArguments(timestep);
		}

		private PackedCollection createTimestepTensor(int t) {
			double normalizedT = (double) t / scheduler.getNumSteps();
			PackedCollection timestep = new PackedCollection(1);
			timestep.setMem(0, normalizedT);
			return timestep;
		}
	}
}
