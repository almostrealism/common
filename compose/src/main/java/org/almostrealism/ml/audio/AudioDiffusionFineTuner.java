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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.ml.DiffusionTrainingDataset;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.optimize.FineTuneConfig;
import org.almostrealism.optimize.FineTuningResult;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.optimize.ModelOptimizer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fine-tuner for diffusion models. This class is a thin wrapper that:
 * <ol>
 *   <li>Creates a {@link DiffusionTrainingDataset} from the source latents</li>
 *   <li>Configures a {@link ModelOptimizer}</li>
 *   <li>Delegates all training to {@link ModelOptimizer}</li>
 * </ol>
 *
 * <p>This class does NOT own the training loop. {@link ModelOptimizer} owns the training loop.
 *
 * @see DiffusionTrainingDataset
 * @see ModelOptimizer
 * @author Michael Murray
 */
public class AudioDiffusionFineTuner implements ConsoleFeatures {

	private final CompiledModel model;
	private final FineTuneConfig config;
	private final DiffusionNoiseScheduler scheduler;
	private final TraversalPolicy latentShape;

	private int repeatFactor = 1;

	/**
	 * Creates a diffusion fine-tuner.
	 *
	 * @param model       Compiled diffusion model with LoRA adapters
	 * @param config      Fine-tuning configuration
	 * @param latentShape Shape of latent tensors (batch, channels, length)
	 * @param scheduler   Diffusion noise scheduler
	 */
	public AudioDiffusionFineTuner(CompiledModel model, FineTuneConfig config,
								   TraversalPolicy latentShape, DiffusionNoiseScheduler scheduler) {
		this.model = model;
		this.config = config;
		this.latentShape = latentShape;
		this.scheduler = scheduler;
	}

	/**
	 * Enables aggressive mode for intentional overfitting.
	 * Sets repeat factor to 10.
	 *
	 * @param aggressive Whether to enable aggressive mode
	 * @return This fine-tuner for chaining
	 */
	public AudioDiffusionFineTuner setAggressiveMode(boolean aggressive) {
		if (aggressive) {
			this.repeatFactor = 10;
		}
		return this;
	}

	/**
	 * Sets the repeat factor for each sample.
	 *
	 * @param factor Number of times to repeat each sample per epoch
	 * @return This fine-tuner for chaining
	 */
	public AudioDiffusionFineTuner setRepeatFactor(int factor) {
		this.repeatFactor = factor;
		return this;
	}

	/**
	 * Runs diffusion fine-tuning on the latent dataset.
	 * Delegates entirely to {@link ModelOptimizer}.
	 *
	 * @param dataset Dataset of clean latents
	 * @return Fine-tuning result with loss history
	 */
	public FineTuningResult fineTune(AudioLatentDataset dataset) {
		Instant startTime = Instant.now();

		log("Starting diffusion fine-tuning");
		log("  Samples: " + dataset.size());
		log("  Epochs: " + config.getEpochs());
		log("  Repeat factor: " + repeatFactor);
		log("  Latent shape: " + latentShape);

		// Convert audio dataset to list of latents for generic diffusion training
		List<PackedCollection> latents = new ArrayList<>(dataset.size());
		for (int i = 0; i < dataset.size(); i++) {
			latents.add(dataset.getLatent(i));
		}

		// Create diffusion training dataset (generic, domain-agnostic)
		DiffusionTrainingDataset diffusionDataset = new DiffusionTrainingDataset(
				latents, scheduler, repeatFactor);

		// Create and configure ModelOptimizer - IT OWNS THE TRAINING LOOP
		ModelOptimizer optimizer = new ModelOptimizer(model, () -> {
			diffusionDataset.shuffle();
			return diffusionDataset;
		});
		optimizer.setLossFunction(new MeanSquaredError(latentShape.traverseEach()));
		optimizer.setLogFrequency(config.getLogEveryNSteps());
		optimizer.setLogConsumer(this::log);

		// Delegate to ModelOptimizer - NO LOOP HERE
		int totalIterations = config.getEpochs() * diffusionDataset.size();
		optimizer.optimize(config.getEpochs());

		Duration trainingTime = Duration.between(startTime, Instant.now());
		log("Fine-tuning completed");
		log("Total iterations: " + optimizer.getTotalIterations());
		log("Final loss: " + optimizer.getLoss());

		// Convert to FineTuningResult for API compatibility
		ArrayList<Double> lossHistory = new ArrayList<>();
		lossHistory.add(optimizer.getLoss());

		return new FineTuningResult(
				lossHistory,
				new ArrayList<>(),
				optimizer.getTotalIterations(),
				config.getEpochs() - 1,
				optimizer.getLoss(),
				trainingTime,
				false
		);
	}
}
