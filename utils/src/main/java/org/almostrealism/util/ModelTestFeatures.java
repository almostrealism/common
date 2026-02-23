/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.util;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.io.CSVReceptor;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.almostrealism.optimize.TrainingResult;

/**
 * Machine learning model testing interface providing utilities for dataset generation,
 * model training, and optimization testing.
 *
 * <p>This interface extends {@link TestFeatures} to add ML-specific testing capabilities:</p>
 * <ul>
 *   <li><b>Dataset Generation</b> - Create random datasets with specified input/output shapes</li>
 *   <li><b>Model Training</b> - Train models with automatic profiling and loss tracking</li>
 *   <li><b>Optimization</b> - Configure and run model optimization with target loss values</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MyModelTest implements ModelTestFeatures {
 *     @Test
 *     public void testModelTraining() throws Exception {
 *         // Create model
 *         Model model = new Model(shape(inputDim));
 *         model.add(dense(outputDim));
 *
 *         // Train with automatic profiling
 *         train("myModel", model, 100);  // 100 epochs
 *     }
 * }
 * }</pre>
 *
 * <p>Training results are saved to CSV files in the {@code results/} directory,
 * and logs are written to {@code results/logs/}.</p>
 *
 * @author Michael Murray
 * @see TestFeatures for the base testing interface
 * @see ModelOptimizer for the underlying optimization logic
 */
public interface ModelTestFeatures extends TestFeatures {

	/**
	 * Default dataset size for generated training data.
	 */
	int datasetSize = 500;

	/**
	 * Generates a random dataset with the specified input and output shapes.
	 * Each data point contains randomly generated values between 0 and 1.
	 *
	 * @param inShape  the shape of input tensors
	 * @param outShape the shape of output (target) tensors
	 * @return a list of input-output value pairs for training
	 */
	default List<ValueTarget<PackedCollection>> generateDataset(TraversalPolicy inShape, TraversalPolicy outShape) {
		List<ValueTarget<PackedCollection>> data = new ArrayList<>();

		log("Generating data...");
		for (int i = 0; i < datasetSize; i++) {
			PackedCollection input = new PackedCollection(inShape).fill(Math::random);
			data.add(ValueTarget.of(input, new PackedCollection(outShape).fill(Math::random)));
		}

		return data;
	}


	/**
	 * Trains a model with automatic dataset generation and profiling.
	 * Generates random training data based on the model's input and output shapes,
	 * compiles the model with profiling, and runs training for the specified epochs.
	 *
	 * <p>Training results are saved to {@code results/{name}.csv}.</p>
	 *
	 * @param name   the name for the training run (used for profiling and output files)
	 * @param model  the model to train
	 * @param epochs the number of training epochs
	 * @throws FileNotFoundException if the results directory cannot be written to
	 */
	default void train(String name, Model model, int epochs) throws FileNotFoundException {
		OperationProfileNode profile = new OperationProfileNode(name);
		CompiledModel compiled = model.compile(profile);
		log("Model compiled");

		Hardware.getLocalHardware().assignProfile(profile);

		try {
			ModelOptimizer optimizer = new ModelOptimizer(compiled,
					() -> Dataset.of(generateDataset(model.getInputShape(), model.getOutputShape())));
			train(name, optimizer, epochs, datasetSize, 0.001);
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}
	}

	/**
	 * Trains using a pre-configured {@link ModelOptimizer} with specified parameters.
	 * Results are logged every 25 steps and written to a CSV file.
	 *
	 * @param name       the name for the training run (used for output files)
	 * @param optimizer  the configured model optimizer
	 * @param epochs     the maximum number of training epochs
	 * @param steps      the number of steps for CSV recording
	 * @param lossTarget the target loss value to achieve (training stops when reached)
	 * @throws FileNotFoundException if the results directory cannot be written to
	 */
	default void train(String name, ModelOptimizer optimizer,
					  int epochs, int steps, double lossTarget) throws FileNotFoundException {
		try (CSVReceptor<Double> receptor =
					 new CSVReceptor<>(new FileOutputStream("results/" + name + ".csv"), steps)) {
			optimizer.setReceptor(receptor);
			optimizer.setLogFrequency(25);

			optimizer.setLossTarget(lossTarget);
			optimizer.optimize(epochs);
			log("Completed " + optimizer.getTotalIterations() + " epochs");
		}
	}

	/**
	 * Trains a model with a custom dataset supplier and retry logic.
	 * Attempts training up to 6 times, retrying if training completes too quickly
	 * (fewer than 5 epochs), which may indicate poor initialization.
	 *
	 * <p>Training continues until the loss target is reached or the minimum loss
	 * is achieved after a quick initial convergence.</p>
	 *
	 * @param name       the name for the training run (used for output files)
	 * @param model      the model to train
	 * @param data       a supplier providing fresh datasets for each training attempt
	 * @param epochs     the maximum number of training epochs per attempt
	 * @param steps      the number of steps for CSV recording
	 * @param lossTarget the primary target loss value
	 * @param minLoss    the fallback minimum loss value if initial convergence is quick
	 * @throws FileNotFoundException if the results directory cannot be written to
	 * @throws RuntimeException      if training fails to converge after all retry attempts
	 * @deprecated TODO: Merge with the simpler train method above
	 */
	default void train(String name, Model model, Supplier<Dataset<?>> data, int epochs, int steps,
								double lossTarget, double minLoss) throws FileNotFoundException {
		i: for (int i = 0; i < 6; i++) {
			ModelOptimizer optimizer = new ModelOptimizer(model.compile(), data);

			try (CSVReceptor<Double> receptor = new CSVReceptor<>(new FileOutputStream("results/" + name + ".csv"), steps)) {
				optimizer.setReceptor(receptor);
				optimizer.setLogFrequency(2);

				optimizer.setLossTarget(lossTarget);
				optimizer.optimize(epochs);
				log("Completed " + optimizer.getTotalIterations() + " epochs");

				if (optimizer.getTotalIterations() < 5) {
					optimizer.setLossTarget(minLoss);
					optimizer.optimize(epochs);
					log("Completed " + optimizer.getTotalIterations() + " epochs");
				}

				if (optimizer.getTotalIterations() < 5) {
					continue i;
				}

				if (optimizer.getLoss() <= 0.0 || optimizer.getLoss() > optimizer.getLossTarget())
					throw new RuntimeException();

				return;
			}
		}

		throw new RuntimeException();
	}

	/**
	 * Trains a model using patience-based early stopping on training loss,
	 * with retry logic for poor initialization.
	 * <p>
	 * This method configures the {@link ModelOptimizer} with training patience
	 * so that training stops when loss plateaus. If training stops without making
	 * meaningful progress (loss did not decline), the model is recompiled with
	 * fresh random weights and training is retried up to {@code maxRetries} times.
	 * </p>
	 * <p>
	 * This handles the common case where random weight initialization leads to a
	 * loss landscape region where gradients are too small to make progress. A fresh
	 * initialization typically resolves this.
	 * </p>
	 *
	 * @param name       the name for the training run (used for output files)
	 * @param model      the model to train
	 * @param data       a supplier providing fresh datasets for each training attempt
	 * @param epochs     the maximum number of training epochs
	 * @param steps      the number of steps for CSV recording
	 * @param lossTarget the target loss value to achieve (training stops when reached)
	 * @param patience   number of epochs without training loss improvement before stopping
	 * @return the {@link TrainingResult} containing loss history and metrics
	 * @throws FileNotFoundException if the results directory cannot be written to
	 */
	default TrainingResult trainWithPatience(String name, Model model, Supplier<Dataset<?>> data,
											 int epochs, int steps, double lossTarget,
											 int patience) throws FileNotFoundException {
		int maxRetries = 6;

		for (int attempt = 0; attempt < maxRetries; attempt++) {
			ModelOptimizer optimizer = new ModelOptimizer(model.compile(), data);

			try (CSVReceptor<Double> receptor =
						 new CSVReceptor<>(new FileOutputStream("results/" + name + ".csv"), steps)) {
				optimizer.setReceptor(receptor);
				optimizer.setLogFrequency(25);
				optimizer.setLossTarget(lossTarget);
				optimizer.setTrainingPatience(patience);

				TrainingResult result = optimizer.optimize(epochs);
				log("Attempt " + (attempt + 1) + ": completed " + result.getEpochsCompleted() +
						" epochs" + (result.isEarlyStopped() ? " (early stopped)" : ""));

				// Check if training made meaningful progress
				List<Double> history = result.getTrainLossHistory();
				if (history.size() >= 2) {
					double initialLoss = history.get(0);
					double finalLoss = result.getFinalTrainLoss();

					// Training made progress if loss declined
					if (finalLoss < initialLoss) {
						return result;
					}
				}

				// Training reached the loss target (even in 1 epoch) — success
				if (!history.isEmpty() && result.getFinalTrainLoss() <= lossTarget) {
					return result;
				}

				// Training made no progress — retry with fresh initialization
				if (attempt < maxRetries - 1) {
					log("Training made no progress, retrying with fresh initialization " +
							"(attempt " + (attempt + 2) + "/" + maxRetries + ")");
				}
			}
		}

		// All retries exhausted — return the last result and let
		// assertTrainingConvergence report the specific failure
		ModelOptimizer optimizer = new ModelOptimizer(model.compile(), data);
		try (CSVReceptor<Double> receptor =
					 new CSVReceptor<>(new FileOutputStream("results/" + name + ".csv"), steps)) {
			optimizer.setReceptor(receptor);
			optimizer.setLogFrequency(25);
			optimizer.setLossTarget(lossTarget);
			optimizer.setTrainingPatience(patience);
			return optimizer.optimize(epochs);
		}
	}

	/**
	 * Asserts that a training result shows convergence by analyzing the loss curve.
	 * <p>
	 * This method evaluates training quality by examining the loss trajectory rather
	 * than using hard thresholds. It accepts fast convergence (reaching the loss target
	 * in few epochs) as success, and only fails when training genuinely did not make
	 * progress.
	 * </p>
	 * <p>
	 * The analysis checks:
	 * </p>
	 * <ol>
	 *   <li>Whether the final loss is at or below the target (fast convergence = success)</li>
	 *   <li>Whether the loss curve shows a declining trend</li>
	 *   <li>Whether the magnitude of improvement is meaningful</li>
	 * </ol>
	 *
	 * @param result       the training result to validate
	 * @param minEpochs    advisory minimum epochs; used only when loss did NOT reach
	 *                     the target, to distinguish stalled training from a run that
	 *                     simply did not have enough time to converge
	 * @param maxFinalLoss maximum acceptable final training loss
	 */
	default void assertTrainingConvergence(TrainingResult result, int minEpochs, double maxFinalLoss) {
		List<Double> history = result.getTrainLossHistory();
		double finalLoss = result.getFinalTrainLoss();

		if (history.isEmpty()) {
			throw new AssertionError("Training produced no loss history");
		}

		double finalLoss = result.getFinalTrainLoss();
		double initialLoss = history.get(0);

		// Fast convergence: if training reached the loss target, that is success
		// regardless of how many epochs it took
		if (finalLoss <= maxFinalLoss && finalLoss < initialLoss) {
			log("Training converged in " + history.size() + " epochs (fast convergence), " +
					"loss " + String.format("%.6f", initialLoss) + " -> " +
					String.format("%.6f", finalLoss) +
					" (target: " + maxFinalLoss + ", improvement: " +
					String.format("%.2f", result.getImprovementRatio()) + "x)");
			return;
		}

		// Loss reached the target but didn't improve from initial — the model
		// was initialized with loss already below target. This is acceptable
		// as long as training didn't make things worse.
		if (finalLoss <= maxFinalLoss && finalLoss <= initialLoss) {
			log("Training loss within target from start: " + history.size() + " epochs, " +
					"loss " + String.format("%.6f", initialLoss) + " -> " +
					String.format("%.6f", finalLoss) + " (target: " + maxFinalLoss + ")");
			return;
		}

		// Training did not reach the loss target — analyze the loss curve
		// to determine whether meaningful progress was made
		if (finalLoss >= initialLoss) {
			throw new AssertionError("Training did not improve: initial loss " +
					String.format("%.6f", initialLoss) + ", final loss " +
					String.format("%.6f", finalLoss) +
					" (" + history.size() + " epochs completed)");
		}

		// Loss improved but didn't reach the target — check if the decline
		// is meaningful by examining the loss curve trend
		double improvementRatio = result.getImprovementRatio();
		double percentReduction = (initialLoss - finalLoss) / initialLoss * 100.0;

		if (finalLoss > maxFinalLoss) {
			// Loss is declining but still above target. If the trend is clearly
			// downward and the run was cut short, the issue is likely that the
			// model needs more epochs, not that training is failing.
			boolean trendIsDownward = isLossTrendDeclining(history);

			if (trendIsDownward && history.size() < minEpochs) {
				throw new AssertionError("Training is making progress (loss declining " +
						String.format("%.1f%%", percentReduction) + " over " + history.size() +
						" epochs) but was cut short before reaching target " + maxFinalLoss +
						" (final: " + String.format("%.6f", finalLoss) + ")");
			}

			throw new AssertionError("Final training loss " + String.format("%.6f", finalLoss) +
					" exceeds target " + maxFinalLoss +
					" despite " + String.format("%.1f%%", percentReduction) + " reduction from " +
					String.format("%.6f", initialLoss) + " over " + history.size() + " epochs");
		}

		log("Training converged: " + history.size() + " epochs, " +
				"loss " + String.format("%.6f", initialLoss) + " -> " +
				String.format("%.6f", finalLoss) +
				" (improvement: " + String.format("%.2f", improvementRatio) + "x)");
	}

	/**
	 * Determines whether the loss history shows a generally declining trend.
	 * <p>
	 * Compares the average loss in the first half of training to the average
	 * loss in the second half. A declining trend means the second half average
	 * is lower than the first half average.
	 * </p>
	 *
	 * @param history the loss history to analyze
	 * @return true if the loss trend is declining
	 */
	default boolean isLossTrendDeclining(List<Double> history) {
		if (history.size() < 2) return false;

		int midpoint = history.size() / 2;
		double firstHalfAvg = history.subList(0, midpoint).stream()
				.mapToDouble(Double::doubleValue).average().orElse(0);
		double secondHalfAvg = history.subList(midpoint, history.size()).stream()
				.mapToDouble(Double::doubleValue).average().orElse(0);
		return secondHalfAvg < firstHalfAvg;
	}
}
