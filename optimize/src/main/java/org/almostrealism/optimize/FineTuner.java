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

package org.almostrealism.optimize;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.LoRAFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * High-level fine-tuning orchestrator for LoRA-enabled models.
 *
 * <p>FineTuner provides a convenient wrapper around {@link ModelOptimizer}
 * specifically designed for fine-tuning with LoRA adapters. It handles:
 * <ul>
 *   <li>Training and validation loss tracking</li>
 *   <li>Early stopping based on validation loss</li>
 *   <li>Progress callbacks for UI integration</li>
 *   <li>Epoch-based training with proper statistics</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create model with LoRA adapters
 * LoRADiffusionTransformer model = LoRADiffusionTransformer.create(...);
 *
 * // Configure fine-tuning
 * FineTuneConfig config = FineTuneConfig.forAudioDiffusion()
 *     .epochs(10)
 *     .learningRate(1e-4);
 *
 * // Create fine-tuner
 * FineTuner fineTuner = new FineTuner(compiledModel, config, outputShape);
 *
 * // Run fine-tuning
 * FineTuningResult result = fineTuner.fineTune(trainData, validationData);
 *
 * if (result.converged()) {
 *     System.out.println("Training converged at epoch " + result.getBestEpoch());
 * }
 * }</pre>
 *
 * @see FineTuneConfig
 * @see FineTuningResult
 * @see LoRAFeatures
 * @author Michael Murray
 */
public class FineTuner implements ConsoleFeatures, CodeFeatures {

	private final CompiledModel model;
	private final FineTuneConfig config;
	private final LossProvider lossProvider;
	private final Evaluable<PackedCollection> lossGradient;
	private final BiFunction<PackedCollection, PackedCollection, Double> lossFunction;

	private Consumer<FineTuningProgress> progressCallback;

	/**
	 * Creates a fine-tuner for the given model.
	 *
	 * @param model Compiled model with LoRA adapters
	 * @param config Fine-tuning configuration
	 * @param outputShape Shape of model output (for loss computation)
	 */
	public FineTuner(CompiledModel model, FineTuneConfig config, TraversalPolicy outputShape) {
		this(model, config, new MeanSquaredError(outputShape.traverseEach()), outputShape);
	}

	/**
	 * Creates a fine-tuner with a custom loss provider.
	 *
	 * @param model Compiled model with LoRA adapters
	 * @param config Fine-tuning configuration
	 * @param lossProvider Loss function provider
	 */
	public FineTuner(CompiledModel model, FineTuneConfig config, LossProvider lossProvider) {
		this(model, config, lossProvider, model.getOutputShape());
	}

	/**
	 * Creates a fine-tuner with a custom loss provider and explicit output shape.
	 *
	 * @param model Compiled model with LoRA adapters
	 * @param config Fine-tuning configuration
	 * @param lossProvider Loss function provider
	 * @param outputShape Shape of model output (for gradient computation)
	 */
	public FineTuner(CompiledModel model, FineTuneConfig config, LossProvider lossProvider, TraversalPolicy outputShape) {
		this.model = model;
		this.config = config;
		this.lossProvider = lossProvider;

		// Compile gradient function with placeholder producers for output and target
		TraversalPolicy gradShape = outputShape.traverseEach();
		Producer<PackedCollection> gradProducer = lossProvider.gradient(cv(gradShape, 0), cv(gradShape, 1));
		this.lossGradient = gradProducer.get();
		this.lossFunction = lossProvider::loss;
	}

	/**
	 * Sets a callback to receive progress updates during training.
	 *
	 * @param callback Progress callback
	 * @return This fine-tuner for chaining
	 */
	public FineTuner onProgress(Consumer<FineTuningProgress> callback) {
		this.progressCallback = callback;
		return this;
	}

	/**
	 * Runs fine-tuning on the provided datasets.
	 *
	 * @param trainData Training dataset
	 * @param validationData Validation dataset (may be null to skip validation)
	 * @return Fine-tuning result with loss history and metrics
	 */
	public FineTuningResult fineTune(Dataset<PackedCollection> trainData,
									 Dataset<PackedCollection> validationData) {
		List<Double> trainLossHistory = new ArrayList<>();
		List<Double> validationLossHistory = new ArrayList<>();
		Instant startTime = Instant.now();

		int totalSteps = 0;
		int bestEpoch = 0;
		double bestValidationLoss = Double.MAX_VALUE;
		int epochsWithoutImprovement = 0;
		boolean earlyStopped = false;

		log("Starting fine-tuning with config: " + config);
		log("Training samples: " + countSamples(trainData));
		if (validationData != null) {
			log("Validation samples: " + countSamples(validationData));
		}

		for (int epoch = 0; epoch < config.getEpochs(); epoch++) {
			// Training phase
			double epochTrainLoss = 0;
			int trainCount = 0;

			for (ValueTarget<?> target : trainData) {
				PackedCollection input = target.getInput();
				PackedCollection expected = target.getExpectedOutput();
				PackedCollection[] arguments = target.getArguments();

				// Forward pass
				PackedCollection output = model.forward(input, arguments);

				// Compute loss
				double stepLoss = lossFunction.apply(output, expected);
				if (Double.isNaN(stepLoss)) {
					warn("NaN loss at step " + totalSteps + ", skipping");
					continue;
				}

				epochTrainLoss += stepLoss;
				trainCount++;
				totalSteps++;

				// Backward pass
				PackedCollection grad = lossGradient.evaluate(output.each(), expected.each());
				model.backward(grad);

				// Logging
				if (totalSteps % config.getLogEveryNSteps() == 0) {
					log("Step " + totalSteps + " - loss: " + String.format("%.6f", stepLoss));
				}

				// Progress callback
				if (progressCallback != null) {
					progressCallback.accept(new FineTuningProgress(
							epoch, totalSteps, stepLoss, null
					));
				}
			}

			if (trainCount == 0) {
				warn("No valid training samples in epoch " + epoch);
				continue;
			}

			double avgTrainLoss = epochTrainLoss / trainCount;
			trainLossHistory.add(avgTrainLoss);

			// Validation phase
			Double avgValidationLoss = null;
			if (validationData != null) {
				avgValidationLoss = evaluate(validationData);
				validationLossHistory.add(avgValidationLoss);

				// Early stopping check
				if (avgValidationLoss < bestValidationLoss - config.getEarlyStoppingMinDelta()) {
					bestValidationLoss = avgValidationLoss;
					bestEpoch = epoch;
					epochsWithoutImprovement = 0;
				} else {
					epochsWithoutImprovement++;
				}

				if (config.getEarlyStoppingPatience() > 0 &&
						epochsWithoutImprovement >= config.getEarlyStoppingPatience()) {
					log("Early stopping at epoch " + epoch + " (no improvement for " +
							epochsWithoutImprovement + " epochs)");
					earlyStopped = true;
					break;
				}
			}

			// Epoch summary
			String summary = String.format("Epoch %d/%d - train_loss: %.6f",
					epoch + 1, config.getEpochs(), avgTrainLoss);
			if (avgValidationLoss != null) {
				summary += String.format(" - val_loss: %.6f", avgValidationLoss);
			}
			log(summary);

			// Progress callback for epoch
			if (progressCallback != null) {
				progressCallback.accept(new FineTuningProgress(
						epoch, totalSteps, avgTrainLoss, avgValidationLoss
				));
			}
		}

		Duration trainingTime = Duration.between(startTime, Instant.now());
		log("Fine-tuning completed in " + formatDuration(trainingTime));
		log("Total steps: " + totalSteps);
		log("Final train loss: " + String.format("%.6f", trainLossHistory.get(trainLossHistory.size() - 1)));
		if (!validationLossHistory.isEmpty()) {
			log("Best validation loss: " + String.format("%.6f", bestValidationLoss) +
					" at epoch " + (bestEpoch + 1));
		}

		return new FineTuningResult(
				trainLossHistory,
				validationLossHistory,
				totalSteps,
				bestEpoch,
				bestValidationLoss,
				trainingTime,
				earlyStopped
		);
	}

	/**
	 * Evaluates the model on a dataset without training.
	 *
	 * @param data Dataset to evaluate
	 * @return Average loss over the dataset
	 */
	public double evaluate(Dataset<PackedCollection> data) {
		double totalLoss = 0;
		int count = 0;

		for (ValueTarget<?> target : data) {
			PackedCollection input = target.getInput();
			PackedCollection expected = target.getExpectedOutput();
			PackedCollection[] arguments = target.getArguments();

			PackedCollection output = model.forward(input, arguments);
			double loss = lossFunction.apply(output, expected);

			if (!Double.isNaN(loss)) {
				totalLoss += loss;
				count++;
			}
		}

		return count > 0 ? totalLoss / count : Double.NaN;
	}

	private int countSamples(Dataset<?> data) {
		int count = 0;
		for (ValueTarget<?> ignored : data) {
			count++;
		}
		return count;
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
	 * Progress update during fine-tuning.
	 */
	public static class FineTuningProgress {
		private final int epoch;
		private final int step;
		private final double trainLoss;
		private final Double validationLoss;

		public FineTuningProgress(int epoch, int step, double trainLoss, Double validationLoss) {
			this.epoch = epoch;
			this.step = step;
			this.trainLoss = trainLoss;
			this.validationLoss = validationLoss;
		}

		public int getEpoch() { return epoch; }
		public int getStep() { return step; }
		public double getTrainLoss() { return trainLoss; }
		public Double getValidationLoss() { return validationLoss; }
	}
}
