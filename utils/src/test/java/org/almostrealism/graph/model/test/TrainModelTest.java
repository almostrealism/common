/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.graph.model.test;

import io.almostrealism.code.OperationProfileNode;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.IndexProjectionProducerComputation;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.ModelFeatures;
import org.almostrealism.ui.OperationProfileUI;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.stream.IntStream;

public class TrainModelTest implements ModelFeatures, TestFeatures, KernelAssertions {
	private int convSize = 3;
	private int poolSize = 2;
	private int w = 10;
	private int h = 10;
	private TraversalPolicy inputShape = shape(h, w);

	static {
		if (TestUtils.getTrainTests()) {
			NativeCompiler.enableLargeInstructionSetMonitoring = true;
			MetalProgram.enableLargeProgramMonitoring = true;
			MetalMemoryProvider.enableLargeAllocationLogging = true;
			MetalMemoryProvider.largeAllocationSize = 4 * 1024 * 1024;

			Console.root().addListener(OutputFeatures.fileOutput("results/logs/train.out"));
		}
	}

	@Test
	public void dense() {
		if (testProfileIs(TestUtils.PIPELINE)) return;
		if (skipLongTests) return;
		if (skipKnownIssues) return;

		int size = 30;
		int nodes = 10;

		Model model = new Model(shape(size));
		CellularLayer dense = dense(size, nodes);
		CellularLayer softmax = softmax(nodes);
		model.addLayer(dense);
		model.addLayer(softmax);

		Tensor<Double> t = tensor(shape(size));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> biases = dense.getWeights().get(1);
		IntStream.range(0, nodes).forEach(i -> biases.setMem(i, Math.random()));

		model.compile().forward(input);

		PackedCollection<?> weights = dense.getWeights().get(0);
		PackedCollection<?> output =  ((DefaultCellularLayer) dense).getOutput();

		for (int i = 0; i < nodes; i++) {
			double expected = 0;

			for (int x = 0; x < size; x++) {
				expected += weights.valueAt(x, i) * input.valueAt(x);
			}

			double actual = output.valueAt(i);
			Assert.assertNotEquals(expected, actual, 0.0001);

			expected += biases.valueAt(i);
			System.out.println("TrainModelTest: [" + i + "] " + expected + " vs " + actual);
			Assert.assertEquals(expected, actual, 0.0001);
		}

		input = output;
		output = ((DefaultCellularLayer) softmax).getOutput();

		double expValues[] = new double[nodes];

		for (int i = 0; i < nodes; i++) {
			expValues[i] = Math.exp(input.toDouble(i));
		}

		double sum = 0;

		for (int i = 0; i < nodes; i++) {
			sum += expValues[i];
		}

		for (int i = 0; i < nodes; i++) {
			double expected = expValues[i] / sum;
			double actual = output.toDouble(i);

			System.out.println("TrainModelTest: [" + i + "] " + expected + " vs " + actual);
			Assert.assertEquals(expected, actual, 0.0001);
		}
	}


	@Test
	public void conv() {
		Model model = new Model(inputShape);
		CellularLayer conv = convolution2d(inputShape, convSize, 8);

		model.addLayer(conv);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.compile().forward(input);

		PackedCollection<?> filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();

		PackedCollection<?> output = ((DefaultCellularLayer) conv).getOutput();
		TraversalPolicy outputShape = output.getShape();

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					double expected = 0;

					for (int x = 0; x < convSize; x++) {
						for (int y = 0; y < convSize; y++) {
							expected += filter.toDouble(filterShape.index(r, x, y)) * input.toDouble(inputShape.index(p + x, q + y));
						}
					}

					double actual = output.toDouble(outputShape.index(p, q, r));
					System.out.println("TrainModelTest: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void pool() {
		CellularLayer conv = convolution2d(inputShape, convSize, 8);
		TraversalPolicy inputShape = conv.getOutputShape();

		Model model = new Model(inputShape);
		CellularLayer pool = pool2d(inputShape, poolSize);

		model.addLayer(pool);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.compile().forward(input);

		PackedCollection<?> output = ((DefaultCellularLayer) pool).getOutput();

		pool2d(inputShape.length(0), inputShape.length(1), 8, 2, input, output);
	}

	@Test
	public void convPool() {
		Model model = new Model(inputShape);
		CellularLayer conv = convolution2d(inputShape, convSize, 8);
		CellularLayer pool = pool2d(conv.getOutputShape(), poolSize);

		model.addLayer(conv);
		model.addLayer(pool);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		PackedCollection<?> in = input;
		HardwareOperator.verboseLog(() -> model.compile().forward(in));

		PackedCollection<?> filter = conv.getWeights().get(0);
		TraversalPolicy filterShape = filter.getShape();

		PackedCollection<?> output = ((DefaultCellularLayer) conv).getOutput();
		TraversalPolicy outputShape = output.getShape();

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					double expected = 0;

					for (int x = 0; x < convSize; x++) {
						for (int y = 0; y < convSize; y++) {
							expected += filter.toDouble(filterShape.index(r, x, y)) * input.toDouble(inputShape.index(p + x, q + y));
						}
					}

					double actual = output.toDouble(outputShape.index(p, q, r));
					System.out.println("TrainModelTest: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}

		input = output;
		inputShape = input.getShape();

		output = ((DefaultCellularLayer) pool).getOutput();
		outputShape = output.getShape();

		for (int p = 0; p < outputShape.length(0); p++) {
			for (int q = 0; q < outputShape.length(1); q++) {
				for (int r = 0; r < outputShape.length(2); r++) {
					int x0 = p * poolSize;
					int y0 = q * poolSize;

					double expected = input.toDouble(inputShape.index(x0, y0, r));

					for (int x = 0; x < poolSize; x++) {
						for (int y = 0; y < poolSize; y++) {
							expected = Math.max(expected, input.toDouble(inputShape.index(x0 + x, y0 + y, r)));
						}
					}

					double actual = output.toDouble(outputShape.index(p, q, r));
					log("[" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void displayProfile() throws IOException {
		if (!trainingTests) return;

		OperationProfileUI.display(OperationProfileNode.load("results/logs/train.xml"));

		try {
			Thread.sleep(24 * 60 * 60 * 1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}


	protected Model model(int r, int c, int convSize, int convFilters, int convLayers, int denseSize) {
		Model model = convolution2dModel(r, c, convSize, convFilters, convLayers, denseSize);
		log("Created model (" + model.getBlocks().size() + " blocks)");
		return model;
	}

	@Test
	public void trainSmallest() throws IOException {
		if (skipLongTests) return;

		int dim = 3;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 2, 2, 2, 10));
	}

	@Test
	public void trainVerySmall() throws IOException {
		if (skipLongTests) return;

		try {
			int dim = 8;
			Tensor<Double> t = tensor(shape(dim, dim));
			PackedCollection<?> input = t.pack();
			train(input, model(dim, dim, 3, 4, 1, 10), 2);
		} finally {
			ParallelProcess.explicitIsolationTargets.clear();
		}
	}


	@Test
	public void trainSmall() throws IOException {
		if (skipLongTests) return;
		if (!trainingTests &&
				!IndexProjectionProducerComputation.enableDelegatedIsolate)
			return;

		int dim = 28;
		int filters = 8;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 3, filters, 2, 10));
	}

	@Test
	public void trainMedium() throws IOException {
		if (skipLongTests) return;
		if (!trainingTests) return;

		int dim = 54;
		int filters = 8;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 3, filters, 3, 10));
	}

	@Test
	public void trainLarge() throws IOException {
		if (!trainingTests) return;

		try {
			int dim = 72;
			int filters = 8;
			Tensor<Double> t = tensor(shape(dim, dim));
			PackedCollection<?> input = t.pack();
			train(input, model(dim, dim, 3, filters, 3, 10));
		} finally {
			ParallelProcess.isolationFlags.clear();
		}
	}

	@Test
	public void trainProgressive() throws IOException {
		if (!trainingTests) return;

		double size = 10;

		while (size < 75) {
			int s = (int) size;

			Tensor<Double> t = tensor(shape(s, s));
			PackedCollection<?> input = t.pack();
			train(input, model(s, s, 3, 8, 2, 10));

			size = size * 1.2;
		}
	}

	protected void train(PackedCollection<?> input, Model model) throws IOException {
		train(input, model, trainingTests ? 80 : 2);
	}

	protected void train(PackedCollection<?> input, Model model, int epochCount) throws IOException {
		OperationProfileNode profile = new OperationProfileNode("Model");
		CompiledModel compiled = model.compile(profile);
		log("Model compiled");

		initKernelMetrics(profile);

		double epochMinutes = 0.0;
		int epochSize = 1000;

		try {
			int count = epochCount * epochSize;

			long start = 0;

			for (int i = 0; i < count; i++) {
				input.fill(pos -> 0.5 + 0.5 * Math.random());

				compiled.forward(input);

				if (i % 1000 == 0) {
					log("Input Size = " + input.getShape() +
							"\t | epoch = " + i / epochSize);
				}

				compiled.backward(rand(model.lastBlock().getOutputShape()).get().evaluate());

				if (i % 1000 == 0) {
					if (i > 0) {
						epochMinutes = (System.currentTimeMillis() - start) * epochSize / (60000.0 * i);
					} else {
						start = System.currentTimeMillis();
					}

					int remaining = 0;
					String remainingText = "";
					boolean first = false;

					if (epochMinutes > 0) {
						remaining = (int) (epochMinutes * (count - i) / epochSize);
						remainingText = remaining + " minutes remaining";
					} else {
						first = true;
					}

					log("\t\tbackprop\t\t\t" +
							" | epoch = " + i / epochSize + "\t|\t" + remainingText);

					if (first && Scope.timing.getTotal() > 180) {
						AcceleratedComputationOperation.printTimes();
					} else if (remaining > 900) {
						return;
					}
				}
			}
		} finally {
			logKernelMetrics(profile);
			profile.save("results/logs/train.xml");
		}
	}
}
