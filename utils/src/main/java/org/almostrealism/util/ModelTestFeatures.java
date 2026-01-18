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
}
