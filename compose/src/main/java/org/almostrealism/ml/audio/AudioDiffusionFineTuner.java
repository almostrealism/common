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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.optimize.FineTuneConfig;
import org.almostrealism.optimize.FineTuningResult;
import org.almostrealism.optimize.LossProvider;
import org.almostrealism.optimize.MeanSquaredError;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Fine-tuner specifically designed for diffusion model training.
 *
 * <p>Unlike standard fine-tuning, diffusion training involves:
 * <ol>
 *   <li>Sampling a random timestep t</li>
 *   <li>Adding noise to the clean latent: x_t = sqrt(alpha_t) * x_0 + sqrt(1-alpha_t) * noise</li>
 *   <li>Model predicts the noise: noise_pred = model(x_t, t)</li>
 *   <li>Loss = MSE(noise_pred, noise)</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create fine-tuner
 * AudioDiffusionFineTuner fineTuner = new AudioDiffusionFineTuner(
 *     compiledModel, config, latentShape, scheduler
 * );
 *
 * // Configure for aggressive overfitting
 * fineTuner.setAggressiveMode(true);
 *
 * // Run training
 * FineTuningResult result = fineTuner.fineTune(latentDataset);
 * }</pre>
 *
 * @see DiffusionNoiseScheduler
 * @see AudioLatentDataset
 * @author Michael Murray
 */
public class AudioDiffusionFineTuner implements ConsoleFeatures, CodeFeatures {

	private final CompiledModel model;
	private final FineTuneConfig config;
	private final DiffusionNoiseScheduler scheduler;
	private final TraversalPolicy latentShape;
	private final Evaluable<PackedCollection> lossGradient;
	private final BiFunction<PackedCollection, PackedCollection, Double> lossFunction;
	private final Random random;

	private boolean aggressiveMode = false;
	private int repeatFactor = 1;
	private Consumer<TrainingProgress> progressCallback;

	/**
	 * Creates a diffusion fine-tuner.
	 *
	 * @param model Compiled diffusion model with LoRA adapters
	 * @param config Fine-tuning configuration
	 * @param latentShape Shape of latent tensors (batch, channels, length)
	 * @param scheduler Diffusion noise scheduler
	 */
	public AudioDiffusionFineTuner(CompiledModel model, FineTuneConfig config,
								   TraversalPolicy latentShape, DiffusionNoiseScheduler scheduler) {
		this.model = model;
		this.config = config;
		this.latentShape = latentShape;
		this.scheduler = scheduler;
		this.random = new Random(42);

		// Compile loss gradient for noise prediction
		LossProvider lossProvider = new MeanSquaredError(latentShape.traverseEach());
		Producer<PackedCollection> gradProducer = lossProvider.gradient(
				cv(latentShape.traverseEach(), 0),
				cv(latentShape.traverseEach(), 1)
		);
		this.lossGradient = gradProducer.get();
		this.lossFunction = lossProvider::loss;
	}

	/**
	 * Enables aggressive mode for intentional overfitting.
	 *
	 * <p>In aggressive mode:
	 * <ul>
	 *   <li>Each sample is repeated multiple times per epoch</li>
	 *   <li>Learning rate may be increased</li>
	 *   <li>No early stopping</li>
	 * </ul>
	 *
	 * @param aggressive Whether to enable aggressive mode
	 * @return This fine-tuner for chaining
	 */
	public AudioDiffusionFineTuner setAggressiveMode(boolean aggressive) {
		this.aggressiveMode = aggressive;
		if (aggressive) {
			this.repeatFactor = 10; // Repeat each sample 10 times per epoch
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
	 * Sets a callback for progress updates.
	 *
	 * @param callback Progress callback
	 * @return This fine-tuner for chaining
	 */
	public AudioDiffusionFineTuner onProgress(Consumer<TrainingProgress> callback) {
		this.progressCallback = callback;
		return this;
	}

	/**
	 * Runs diffusion fine-tuning on the latent dataset.
	 *
	 * @param dataset Dataset of clean latents
	 * @return Fine-tuning result with loss history
	 */
	public FineTuningResult fineTune(AudioLatentDataset dataset) {
		List<Double> trainLossHistory = new ArrayList<>();
		Instant startTime = Instant.now();

		int totalSteps = 0;
		int numSamples = dataset.size();

		log("Starting diffusion fine-tuning");
		log("  Samples: " + numSamples);
		log("  Epochs: " + config.getEpochs());
		log("  Repeat factor: " + repeatFactor);
		log("  Aggressive mode: " + aggressiveMode);
		log("  Latent shape: " + latentShape);

		for (int epoch = 0; epoch < config.getEpochs(); epoch++) {
			double epochLoss = 0;
			int epochSteps = 0;

			// Shuffle dataset at start of epoch
			dataset.shuffle();

			// Process each sample (repeated if in aggressive mode)
			for (int sampleIdx = 0; sampleIdx < numSamples; sampleIdx++) {
				PackedCollection cleanLatent = dataset.getLatent(sampleIdx);

				for (int repeat = 0; repeat < repeatFactor; repeat++) {
					// Sample random timestep
					int t = scheduler.sampleTimestep();

					// Sample noise
					PackedCollection noise = scheduler.sampleNoiseLike(cleanLatent);

					// Create noisy latent
					PackedCollection noisyLatent = scheduler.addNoise(cleanLatent, noise, t);

					// Create timestep tensor (normalized to [0, 1])
					PackedCollection timestep = createTimestepTensor(t);

					// Forward pass: model predicts noise
					PackedCollection predictedNoise = model.forward(noisyLatent, timestep);

					// Compute loss
					double loss = lossFunction.apply(predictedNoise, noise);
					if (Double.isNaN(loss)) {
						warn("NaN loss at step " + totalSteps + ", skipping");
						continue;
					}

					epochLoss += loss;
					epochSteps++;
					totalSteps++;

					// Backward pass
					PackedCollection grad = lossGradient.evaluate(
							predictedNoise.each(), noise.each());
					model.backward(grad);

					// Logging
					if (totalSteps % config.getLogEveryNSteps() == 0) {
						log(String.format("Step %d - loss: %.6f, timestep: %d",
								totalSteps, loss, t));
					}

					// Progress callback
					if (progressCallback != null) {
						progressCallback.accept(new TrainingProgress(
								epoch, totalSteps, loss, t
						));
					}
				}
			}

			double avgLoss = epochSteps > 0 ? epochLoss / epochSteps : Double.NaN;
			trainLossHistory.add(avgLoss);

			log(String.format("Epoch %d/%d - avg_loss: %.6f, steps: %d",
					epoch + 1, config.getEpochs(), avgLoss, epochSteps));
		}

		Duration trainingTime = Duration.between(startTime, Instant.now());
		log("Fine-tuning completed in " + formatDuration(trainingTime));
		log("Total steps: " + totalSteps);

		if (!trainLossHistory.isEmpty()) {
			log("Final loss: " + String.format("%.6f", trainLossHistory.get(trainLossHistory.size() - 1)));
		}

		return new FineTuningResult(
				trainLossHistory,
				new ArrayList<>(), // No validation
				totalSteps,
				config.getEpochs() - 1,
				trainLossHistory.isEmpty() ? Double.NaN : trainLossHistory.get(trainLossHistory.size() - 1),
				trainingTime,
				false
		);
	}

	/**
	 * Creates a timestep tensor for the model.
	 *
	 * @param t Timestep value
	 * @return Timestep tensor
	 */
	private PackedCollection createTimestepTensor(int t) {
		// Normalize timestep to [0, 1] range
		double normalizedT = (double) t / scheduler.getNumSteps();
		PackedCollection timestep = new PackedCollection(1);
		timestep.setMem(0, normalizedT);
		return timestep;
	}

	private String formatDuration(Duration duration) {
		long hours = duration.toHours();
		long minutes = duration.toMinutesPart();
		long seconds = duration.toSecondsPart();

		if (hours > 0) {
			return String.format("%dh %dm %ds", hours, minutes, seconds);
		} else if (minutes > 0) {
			return String.format("%dm %ds", minutes, seconds);
		} else {
			return String.format("%ds", seconds);
		}
	}

	/**
	 * Progress update during diffusion training.
	 */
	public static class TrainingProgress {
		private final int epoch;
		private final int step;
		private final double loss;
		private final int timestep;

		public TrainingProgress(int epoch, int step, double loss, int timestep) {
			this.epoch = epoch;
			this.step = step;
			this.loss = loss;
			this.timestep = timestep;
		}

		public int getEpoch() { return epoch; }
		public int getStep() { return step; }
		public double getLoss() { return loss; }
		public int getTimestep() { return timestep; }
	}
}
