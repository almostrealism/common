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

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.ModelFeatures;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.NegativeLogLikelihood;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.texture.GraphicsConverter;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class ConvolutionModelTrainingTest implements ModelFeatures, ModelTestFeatures {
	static boolean large = true;
	static int rows, cols;

	static {
		if (large) {
			rows = 54;
			cols = 54;
		} else {
			rows = 30;
			cols = 30;
		}

		if (TestUtils.getTrainTests()) {
			HardwareOperator.enableLargeInstructionSetMonitoring = true;
			MetalMemoryProvider.enableLargeAllocationLogging = true;

			Console.root().addListener(OutputFeatures.fileOutput("results/logs/train.out"));
		}
	}

	public List<ValueTarget<PackedCollection<?>>> generateDataset(TraversalPolicy outShape) {
		List<ValueTarget<PackedCollection<?>>> data = new ArrayList<>();


		log("Adding circles...");
		for (int i = 0; i < 500; i++) {
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
		for (int i = 0; i < 500; i++) {
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

		return data;
	}

	public List<ValueTarget<PackedCollection<?>>> loadDataset(File imagesDir, TraversalPolicy outShape) throws IOException {
		List<ValueTarget<PackedCollection<?>>> data = new ArrayList<>();

		for (File file : imagesDir.listFiles()) {
			if (file.getName().endsWith(".png")) {
				PackedCollection<?> input = GraphicsConverter.loadGrayscale(file);

				boolean circle = file.getName().contains("circle");

				if (outShape.getTotalSize() == 2) {
					data.add(ValueTarget.of(input,
							circle ? PackedCollection.of(1.0, 0.0) :
									PackedCollection.of(0.0, 1.0)));
				} else {
					int v = circle ? 0 : 1;

					data.add(ValueTarget.of(input,
							new PackedCollection<>(outShape).fill(pos -> pos[0] % 2 == v ? 1.0 : 0.0)));
				}
			}
		}
		
		return data;
	}

	@Test
	public void train() throws IOException {
		if (!trainingTests) return;

		int runs = 1; // 10;
		int epochs = 10;

		Model model = convolution2dModel(
				rows, cols, 3, 6, large ? 3 : 2,
				4, 4, true);
		model.setParameterUpdate(ParameterUpdate.scaled(c(0.001)));
		TraversalPolicy outShape = model.lastBlock().getOutputShape();

		List<ValueTarget<PackedCollection<?>>> data;

		File imagesDir = new File("generated_images");
		if (!imagesDir.exists()) {
			log("Generated images not available");
			data = generateDataset(outShape);
		} else {
			log("Loading generated images...");
			data = loadDataset(imagesDir, outShape);
		}

		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode("CNN " + cols + "x" + rows));
		CompiledModel compiled = model.compile(profile);

		StringBuilder results = new StringBuilder();
		append(results, IntStream.range(0, epochs).toArray());

		try {
			for (int i = 0; i < runs; i++) {
				if (i > 0) compiled.reset();

				Collections.shuffle(data);
				Dataset<PackedCollection<?>> all = Dataset.of(data);
				List<Dataset<PackedCollection<?>>> split = all.split(0.8);

				double accuracy[] =
						optimize("convolution2d_" + rows * cols, compiled,
								() -> split.get(0), () -> split.get(1),
								10, data.size(), 0.05);
				append(results, accuracy);
			}
		} finally {
			logKernelMetrics(profile);
			ScopeSettings.printStats();
			profile.save("results/logs/cnn_" + cols + "x" + rows +
					"_" + ScopeSettings.shortDesc() + ".xml");
		}

		System.out.println();
		System.out.println(results);
	}

	public double[] optimize(String name, CompiledModel model,
						 Supplier<Dataset<?>> trainData,
						 Supplier<Dataset<?>> testData,
						 int epochs, int steps, double lossTarget) throws IOException {
		double accuracy[] = new double[epochs];

		ModelOptimizer optimizer = new ModelOptimizer(model, trainData);
		optimizer.setLossFunction(new NegativeLogLikelihood());

		for (int i = 0; i < epochs; i++) {
			train(name, optimizer, 1, steps, lossTarget);
			accuracy[i] = validate(model, testData);
		}

		return accuracy;
	}

	public double validate(CompiledModel model, Supplier<Dataset<?>> data) {
		ModelOptimizer optimizer = new ModelOptimizer(model, data);
		double accuracy = optimizer.accuracy((expected, actual) -> expected.argmax() == actual.argmax());
		log("Accuracy: " + accuracy);
		return accuracy;
	}

	protected static void append(StringBuilder buf, int values[]) {
		for (int i = 0; i < values.length; i++) {
			buf.append(values[i]);
			buf.append(",");
		}
		buf.append("\n");
	}

	protected static void append(StringBuilder buf, double values[]) {
		for (int i = 0; i < values.length; i++) {
			buf.append(values[i]);
			buf.append(",");
		}
		buf.append("\n");
	}
}
