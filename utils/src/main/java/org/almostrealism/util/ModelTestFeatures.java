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
import org.almostrealism.optimize.TrainingResult;
import org.almostrealism.optimize.ValueTarget;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
			train(name, optimizer, epochs, datasetSize);
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}
	}

	/**
	 * Trains using a pre-configured {@link ModelOptimizer} with patience-based
	 * early stopping and loss curve validation. Training stops when loss plateaus
	 * (no improvement for 20 consecutive epochs) or max epochs are reached, then
	 * the loss history is validated to ensure healthy training behavior.
	 *
	 * @param name      the name for the training run (used for output files)
	 * @param optimizer the configured model optimizer
	 * @param epochs    the maximum number of training epochs
	 * @param steps     the number of steps for CSV recording
	 * @throws FileNotFoundException if the results directory cannot be written to
	 */
	default void train(String name, ModelOptimizer optimizer,
					   int epochs, int steps) throws FileNotFoundException {
		try (CSVReceptor<Double> receptor =
					 new CSVReceptor<>(new FileOutputStream("results/" + name + ".csv"), steps)) {
			optimizer.setReceptor(receptor);
			optimizer.setLogFrequency(25);
			optimizer.setTrainingPatience(20);

			TrainingResult result = optimizer.optimize(epochs);
			log("Completed " + result.getEpochsCompleted() + " epochs");

			assertTrainingConvergence(result.getTrainLossHistory(), name);
		}
	}

	/**
	 * Trains using a pre-configured {@link ModelOptimizer} with a target loss value.
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
	 * Trains a model with a custom dataset supplier and validates training convergence.
	 * Uses patience-based early stopping for predictable test durations, then validates
	 * that the loss curve shows healthy training behavior.
	 *
	 * @param name   the name for the training run (used for output files)
	 * @param model  the model to train
	 * @param data   a supplier providing fresh datasets for each training attempt
	 * @param epochs the maximum number of training epochs
	 * @param steps  the number of steps for CSV recording
	 * @throws FileNotFoundException if the results directory cannot be written to
	 */
	default void train(String name, Model model, Supplier<Dataset<?>> data,
					   int epochs, int steps) throws FileNotFoundException {
		ModelOptimizer optimizer = new ModelOptimizer(model.compile(), data);
		train(name, optimizer, epochs, steps);
	}

	/**
	 * Trains a model with a custom dataset supplier.
	 *
	 * @deprecated Use {@link #train(String, Model, Supplier, int, int)} instead,
	 *             which uses patience-based stopping and loss curve validation.
	 */
	@Deprecated
	default void train(String name, Model model, Supplier<Dataset<?>> data, int epochs, int steps,
								double lossTarget, double minLoss) throws FileNotFoundException {
		train(name, model, data, epochs, steps);
	}

	/**
	 * Validates that a loss history represents healthy training convergence.
	 * <p>
	 * Checks that training loss:
	 * <ul>
	 *   <li>Decreased overall from start to finish</li>
	 *   <li>Achieved at least 10% reduction (meaningful progress)</li>
	 *   <li>Did not exhibit sustained rising trends</li>
	 *   <li>Made meaningful progress in the first half (progressive decline)</li>
	 * </ul>
	 *
	 * @param lossHistory per-epoch loss values from training
	 * @param testName    name of the test for diagnostic messages
	 * @throws RuntimeException if any convergence check fails
	 */
	default void assertTrainingConvergence(List<Double> lossHistory, String testName) {
		int size = lossHistory.size();
		if (size < 5) {
			throw new RuntimeException(testName +
					": insufficient training history (need >= 5 epochs, got " + size + ")");
		}

		double initialLoss = lossHistory.get(0);
		double finalLoss = lossHistory.get(size - 1);

		// Loss must decrease overall
		if (finalLoss >= initialLoss) {
			throw new RuntimeException(testName +
					": loss did not decrease (initial=" + String.format("%.6f", initialLoss) +
					", final=" + String.format("%.6f", finalLoss) + ")");
		}

		// Meaningful improvement: at least 10% reduction
		double reductionPct = (initialLoss - finalLoss) / initialLoss;
		if (reductionPct < 0.10) {
			throw new RuntimeException(testName +
					": insufficient loss reduction (" +
					String.format("%.1f%%", reductionPct * 100) +
					", initial=" + String.format("%.6f", initialLoss) +
					", final=" + String.format("%.6f", finalLoss) + ")");
		}

		// No sustained rising trend: consecutive epochs where loss increases
		// by more than 0.1% should be limited
		int maxConsecutiveRising = 0;
		int currentRising = 0;
		for (int i = 1; i < size; i++) {
			if (lossHistory.get(i) > lossHistory.get(i - 1) * 1.001) {
				currentRising++;
				maxConsecutiveRising = Math.max(maxConsecutiveRising, currentRising);
			} else {
				currentRising = 0;
			}
		}
		int allowedRising = Math.max(5, size / 4);
		if (maxConsecutiveRising > allowedRising) {
			throw new RuntimeException(testName +
					": sustained loss increase for " + maxConsecutiveRising +
					" consecutive epochs (max allowed: " + allowedRising + ")");
		}

		// Progressive decline: first half should see meaningful progress
		int mid = size / 2;
		double firstHalfDrop = lossHistory.get(0) - lossHistory.get(mid);
		double totalDrop = initialLoss - finalLoss;
		if (totalDrop > 0) {
			double firstHalfShare = firstHalfDrop / totalDrop;
			if (firstHalfShare < 0.20) {
				throw new RuntimeException(testName +
						": abnormal loss curve - only " +
						String.format("%.0f%%", firstHalfShare * 100) +
						" of progress occurred in first half of training");
			}
		}

		log(testName + ": convergence validated (" +
				String.format("%.6f", initialLoss) + " -> " +
				String.format("%.6f", finalLoss) + ", " +
				String.format("%.1f%%", reductionPct * 100) + " reduction, " +
				size + " epochs)");
	}
}
