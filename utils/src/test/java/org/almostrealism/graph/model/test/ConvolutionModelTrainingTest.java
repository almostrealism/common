/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.graph.model.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.graph.io.CSVReceptor;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.model.Model;
import org.almostrealism.model.ModelFeatures;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class ConvolutionModelTrainingTest implements ModelFeatures, TestFeatures {
	static boolean large = false;
	static int rows, cols;

	static {
		if (large) {
			rows = 54;
			cols = 54;
		} else {
//			rows = 28;
//			cols = 28;
			rows = 18;
			cols = 18;
		}

		if (TestUtils.getTrainTests()) {
			NativeCompiler.enableLargeInstructionSetMonitoring = true;
			MetalProgram.enableLargeProgramMonitoring = true;
			MetalMemoryProvider.enableLargeAllocationLogging = true;
			MetalMemoryProvider.largeAllocationSize = 4 * 1024 * 1024;

			Console.root().addListener(OutputFeatures.fileOutput("results/logs/train.out"));
		}
	}

	@Test
	public void train() throws FileNotFoundException {
		if (!trainingTests) return;

		List<ValueTarget<PackedCollection<?>>> data = new ArrayList<>();

		Model model = convolution2dModel(rows, cols, 3, 8, large ? 3 : 2, 2);
		model.setLearningRate(1e-4);
		TraversalPolicy outShape = model.lastBlock().getOutputShape();

		log("Adding circles...");
		for (int i = 0; i < 250; i++) {
			PackedCollection<?> input = new PackedCollection<>(shape(rows, cols));
			double x = Math.random() * cols;
			double y = Math.random() * rows;
			double r = Math.random() * (rows / 4.0);
			input.fill(pos -> {
				double dx = pos[1] - x;
				double dy = pos[0] - y;
				return dx * dx + dy * dy < r * r ? 1.0 : 0.0;
			});

			if (outShape.getTotalSize() == 2) {
				data.add(ValueTarget.of(input, PackedCollection.of(1.0, 0.0)));
			} else {
				data.add(ValueTarget.of(input, new PackedCollection<>(outShape).fill(pos -> pos[0] % 2 == 0 ? 1.0 : 0.0)));
			}
		}

		log("Adding squares...");
		for (int i = 0; i < 250; i++) {
			PackedCollection<?> input = new PackedCollection<>(shape(rows, cols));
			double x = Math.random() * cols;
			double y = Math.random() * rows;
			double r = Math.random() * (rows / 4.0);
			input.fill(pos -> {
				double dx = Math.abs(pos[1] - x);
				double dy = Math.abs(pos[0] - y);
				return dx < r && dy < r ? 1.0 : 0.0;
			});

			if (outShape.getTotalSize() == 2) {
				data.add(ValueTarget.of(input, PackedCollection.of(0.0, 1.0)));
			} else {
				data.add(ValueTarget.of(input, new PackedCollection<>(outShape).fill(pos -> pos[0] % 2 == 0 ? 0.0 : 1.0)));
			}
		}

		optimize("convolution2d_" + rows * cols, model,
				() -> {
					Collections.shuffle(data);
					return Dataset.of(data);
				}, 2000, data.size(), 0.001);
	}

	public void optimize(String name, Model model, Supplier<Dataset<?>> data,
						 int epochs, int steps, double lossTarget) throws FileNotFoundException {
		ModelOptimizer optimizer = new ModelOptimizer(model.compile(), data);

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/" + name + ".csv"), steps)) {
			optimizer.setReceptor(receptor);
			optimizer.setLogFrequency(25);

			optimizer.setLossTarget(lossTarget);
			optimizer.optimize(epochs);
			log("Completed " + optimizer.getTotalIterations() + " epochs");
		}
	}
}
