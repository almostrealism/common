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

public interface ModelTestFeatures extends TestFeatures {
	int datasetSize = 500;

	default List<ValueTarget<PackedCollection<?>>> generateDataset(TraversalPolicy inShape, TraversalPolicy outShape) {
		List<ValueTarget<PackedCollection<?>>> data = new ArrayList<>();

		log("Generating data...");
		for (int i = 0; i < datasetSize; i++) {
			PackedCollection<?> input = new PackedCollection<>(inShape).fill(Math::random);
			data.add(ValueTarget.of(input, new PackedCollection<>(outShape).fill(Math::random)));
		}

		return data;
	}


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

	// TODO  Merge with above method
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
