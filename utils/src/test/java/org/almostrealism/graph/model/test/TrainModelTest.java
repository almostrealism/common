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

import io.almostrealism.collect.WeightedSumExpression;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.compute.ParallelProcess;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.IndexProjectionProducerComputation;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.ModelFeatures;
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
			HardwareOperator.enableLargeInstructionSetMonitoring = true;
			MetalMemoryProvider.enableLargeAllocationLogging = true;

			Console.root().addListener(OutputFeatures.fileOutput("results/logs/train.out"));
		}
	}

	@Test
	public void dense() {
		if (testProfileIs(TestUtils.PIPELINE)) return;
		if (skipKnownIssues) return;

		int size = 30;
		int nodes = 10;

		Model model = new Model(shape(size));
		CellularLayer dense = dense(size, nodes).apply(shape(size));
		CellularLayer softmax = softmax(nodes);
		model.add(dense);
		model.add(softmax);

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
			if (verboseLogs)
				log("[" + i + "] " + expected + " vs " + actual);

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

			if (verboseLogs)
				log("[" + i + "] " + expected + " vs " + actual);
			Assert.assertEquals(expected, actual, 0.0001);
		}
	}

	@Test
	public void pool() {
		Block conv = convolution2d(inputShape, 8, convSize, false);
		TraversalPolicy inputShape = conv.getOutputShape();

		Model model = new Model(inputShape);
		CellularLayer pool = pool2d(inputShape, poolSize);

		model.add(pool);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		model.compile().forward(input);

		PackedCollection<?> output = ((DefaultCellularLayer) pool).getOutput();

		pool2d(inputShape.length(0), inputShape.length(1), 8, 2, input, output);
	}

	@Test
	public void convPool() {
		Model model = new Model(inputShape);
		CellularLayer conv = (CellularLayer) convolution2d(inputShape, 8, convSize, false);
		CellularLayer pool = pool2d(conv.getOutputShape(), poolSize);

		model.add(conv);
		model.add(pool);

		Tensor<Double> t = tensor(inputShape);
		PackedCollection<?> input = t.pack();

		PackedCollection<?> in = input;
		verboseLog(() -> model.compile().forward(in));

		PackedCollection<?> filter = conv.getWeights().get(0);

		PackedCollection<?> output = ((DefaultCellularLayer) conv).getOutput();
		TraversalPolicy outputShape = output.getShape();

		for (int n = 0; n < outputShape.length(0); n++) {
			for (int p = 0; p < outputShape.length(1); p++) {
				for (int q = 0; q < outputShape.length(2); q++) {
					for (int r = 0; r < outputShape.length(3); r++) {
						double expected = 0;

						for (int x = 0; x < convSize; x++) {
							for (int y = 0; y < convSize; y++) {
								expected += filter.valueAt(p, 0, x, y) * input.valueAt(q + x, r + y);
							}
						}

						double actual = output.valueAt(n, p, q, r);
						if (verboseLogs)
							log("[" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
						assertEquals(expected, actual);
					}
				}
			}
		}

		input = output;
		inputShape = input.getShape();

		output = ((DefaultCellularLayer) pool).getOutput();
		outputShape = output.getShape();

		for (int n = 0; n < outputShape.length(0); n++) {
			for (int p = 0; p < outputShape.length(1); p++) {
				for (int q = 0; q < outputShape.length(2); q++) {
					for (int r = 0; r < outputShape.length(3); r++) {
						int x0 = q * poolSize;
						int y0 = r * poolSize;

						double expected = input.valueAt(n, p, x0, y0);

						for (int x = 0; x < poolSize; x++) {
							for (int y = 0; y < poolSize; y++) {
								expected = Math.max(expected, input.valueAt(n, p, x0 + x, y0 + y));
							}
						}

						double actual = output.valueAt(n, p, q, r);

						if (verboseLogs)
							log("[" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);

						assertEquals(expected, actual);
					}
				}
			}
		}
	}

	protected Model model(int r, int c, int convSize, int convFilters, int convLayers, int denseSize) {
		Model model = convolution2dModel(r, c, convSize, convFilters, convLayers, denseSize);
		log("Created model (" + model.getBlocks().size() + " blocks)");
		return model;
	}

	@Test
	public void trainSmallest() throws IOException {
		if (testDepth < 1) return;

		int dim = 3;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 2, 2, 1, 10));
	}

	@Test
	public void trainVerySmall() throws IOException {
		if (testDepth < 2) return;

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
		if (testDepth < 3) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;
		if (!trainingTests &&
				!IndexProjectionProducerComputation.enableDelegatedIsolate)
			return;

		boolean weightedSum = WeightedSumExpression.enableCollectionExpression;

		try {
			WeightedSumExpression.enableCollectionExpression = false;

			int dim = 28;
			int filters = 8;
			Tensor<Double> t = tensor(shape(dim, dim));
			PackedCollection<?> input = t.pack();
			train(input, model(dim, dim, 3, filters, 2, 10));
		} finally {
			WeightedSumExpression.enableCollectionExpression = weightedSum;
		}
	}

	@Test
	public void trainMedium() throws IOException {
		if (skipLongTests || !trainingTests) return;

		int dim = 54;
		int filters = 8;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 3, filters, 3, 10));
	}

	@Test
	public void trainLarge() throws IOException {
		if (skipLongTests || !trainingTests) return;

		try {
			int dim = 72;
			int filters = 8;
			Tensor<Double> t = tensor(shape(dim, dim));
			PackedCollection<?> input = t.pack();
			train(input, model(dim, dim, 3, filters, 4, 10));
		} finally {
			ParallelProcess.isolationFlags.clear();
		}
	}

	@Test
	public void trainProgressive() throws IOException {
		if (skipLongTests || !trainingTests) return;

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

					if (first) {
						AcceleratedComputationOperation.printTimes();
					} else if (remaining > 900) {
						return;
					}
				}
			}
		} finally {
			logKernelMetrics(profile);
			profile.save("results/train.xml");
		}
	}
}
