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

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.DiffusionNoiseScheduler;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Dataset adapter that transforms clean tensors into diffusion training samples.
 *
 * <p>This class wraps a list of clean samples and generates proper training
 * samples for diffusion models by:
 * <ol>
 *   <li>Sampling a random timestep t</li>
 *   <li>Sampling noise from N(0, 1)</li>
 *   <li>Creating noisy sample: x_t = sqrt(alpha_t) * x_0 + sqrt(1-alpha_t) * noise</li>
 *   <li>Returning (noisy_sample, timestep) as input and noise as target</li>
 * </ol>
 *
 * <p>This is a generic diffusion training dataset that works with any tensor data
 * (images, audio latents, video frames, etc.). It is domain-agnostic.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * List<PackedCollection> cleanSamples = loadSamples();
 * DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(1000);
 *
 * // Create diffusion training dataset
 * DiffusionTrainingDataset dataset = new DiffusionTrainingDataset(
 *     cleanSamples, scheduler, repeatFactor
 * );
 *
 * // Use with ModelOptimizer
 * ModelOptimizer optimizer = new ModelOptimizer(model, () -> dataset);
 * optimizer.optimize(epochs * dataset.size());
 * }</pre>
 *
 * @see DiffusionNoiseScheduler
 * @see org.almostrealism.optimize.ModelOptimizer
 * @author Michael Murray
 */
public class DiffusionTrainingDataset implements Dataset<PackedCollection> {

	/** The clean (noise-free) input samples that will be corrupted for training. */
	private final List<PackedCollection> samples;

	/** The noise scheduler used to sample timesteps and add noise to clean samples. */
	private final DiffusionNoiseScheduler scheduler;

	/** Number of times each sample is repeated within a single epoch. */
	private final int repeatFactor;

	/** Random number generator for deterministic and non-deterministic shuffling. */
	private final Random shuffleRandom;

	/** Optional extra arguments appended after the timestep for each training sample. */
	private PackedCollection[] extraArguments;

	/**
	 * Creates a diffusion training dataset.
	 *
	 * @param samples      List of clean samples (tensors)
	 * @param scheduler    Noise scheduler for timestep sampling and noise addition
	 * @param repeatFactor Number of times to repeat each sample per epoch
	 */
	public DiffusionTrainingDataset(List<PackedCollection> samples,
									DiffusionNoiseScheduler scheduler,
									int repeatFactor) {
		this.samples = new ArrayList<>(samples);  // Copy to allow shuffling
		this.scheduler = scheduler;
		this.repeatFactor = Math.max(1, repeatFactor);
		this.shuffleRandom = new Random();
	}

	/**
	 * Creates a diffusion training dataset with no repetition.
	 *
	 * @param samples   List of clean samples
	 * @param scheduler Noise scheduler
	 */
	public DiffusionTrainingDataset(List<PackedCollection> samples,
									DiffusionNoiseScheduler scheduler) {
		this(samples, scheduler, 1);
	}

	/**
	 * Returns the effective size of this dataset (sample count * repeat factor).
	 *
	 * @return Effective dataset size
	 */
	public int size() {
		return samples.size() * repeatFactor;
	}

	/**
	 * Returns the number of unique samples (before repetition).
	 *
	 * @return Number of unique samples
	 */
	public int uniqueSize() {
		return samples.size();
	}

	/**
	 * Sets additional static arguments to include with each training sample.
	 *
	 * <p>These arguments are appended after the timestep when constructing
	 * the {@link ValueTarget} for each sample. This is useful for models that
	 * require additional inputs beyond the noisy sample and timestep, such as
	 * global conditioning vectors.</p>
	 *
	 * @param extraArguments Additional arguments for each sample
	 */
	public void setExtraArguments(PackedCollection... extraArguments) {
		this.extraArguments = extraArguments;
	}

	/**
	 * Shuffles the samples.
	 */
	public void shuffle() {
		Collections.shuffle(samples, shuffleRandom);
	}

	/**
	 * Shuffles the samples with a specific random seed.
	 *
	 * @param seed Random seed for reproducible shuffling
	 */
	public void shuffle(long seed) {
		Collections.shuffle(samples, new Random(seed));
	}

	@Override
	public Iterator<ValueTarget<PackedCollection>> iterator() {
		return new DiffusionIterator();
	}

	/**
	 * Iterator that generates diffusion training samples on-the-fly.
	 */
	private class DiffusionIterator implements Iterator<ValueTarget<PackedCollection>> {
		/** Index into the samples list for the current iteration position. */
		private int sampleIndex = 0;

		/** Current repetition count for the sample at {@link #sampleIndex}. */
		private int repeatIndex = 0;

		@Override
		public boolean hasNext() {
			return sampleIndex < samples.size();
		}

		@Override
		public ValueTarget<PackedCollection> next() {
			PackedCollection cleanSample = samples.get(sampleIndex);

			// Sample random timestep
			int t = scheduler.sampleTimestep();

			// Sample noise
			PackedCollection noise = scheduler.sampleNoiseLike(cleanSample).evaluate();

			// Create noisy sample: x_t = sqrt(alpha_t) * x_0 + sqrt(1-alpha_t) * noise
			PackedCollection noisySample = scheduler.addNoise(cleanSample, noise, t).evaluate();

			// Create timestep tensor (normalized to [0, 1])
			PackedCollection timestep = createTimestepTensor(t);

			// Advance indices
			repeatIndex++;
			if (repeatIndex >= repeatFactor) {
				repeatIndex = 0;
				sampleIndex++;
			}

			// Build arguments array: [timestep, extraArguments...]
			PackedCollection[] arguments;
			if (extraArguments != null && extraArguments.length > 0) {
				arguments = new PackedCollection[1 + extraArguments.length];
				arguments[0] = timestep;
				// Copy references manually (not GPU data, just array of references)
				for (int j = 0; j < extraArguments.length; j++) {
					arguments[1 + j] = extraArguments[j];
				}
			} else {
				arguments = new PackedCollection[]{timestep};
			}

			// Return with all arguments (for multi-input model)
			// Input: noisy sample, Arguments: [timestep, extras...], Target: noise
			return ValueTarget.<PackedCollection>of(noisySample, noise)
					.withArguments(arguments);
		}

		/**
		 * Creates a single-element tensor holding the normalized timestep value {@code t / numSteps}.
		 *
		 * @param t The integer diffusion timestep
		 * @return A one-element {@link PackedCollection} containing the normalized timestep
		 */
		private PackedCollection createTimestepTensor(int t) {
			double normalizedT = (double) t / scheduler.getNumSteps();
			PackedCollection timestep = new PackedCollection(1);
			timestep.setMem(0, normalizedT);
			return timestep;
		}
	}
}
