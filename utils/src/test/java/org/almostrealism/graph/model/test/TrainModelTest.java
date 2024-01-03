/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.OperationProfile;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class TrainModelTest implements TestFeatures, KernelAssertions {
	private int convSize = 3;
	private int poolSize = 2;
	private int w = 10;
	private int h = 10;
	private TraversalPolicy inputShape = shape(h, w);

	public CellularLayer convolution2d(TraversalPolicy inputShape, int size, int filterCount, ComputeRequirement... requirements) {
		if (skipLongTests && !LayerFeatures.enableLegacyConvLayer && inputShape.getTotalSize() > 16)
			throw new UnsupportedOperationException();

		return TestFeatures.super.convolution2d(inputShape, size, filterCount, requirements);
	}

	@Test
	public void dense() {
		if (skipLongTests) return;

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
		if (skipLongTests) return;

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
		if (skipLongTests) return;

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
		if (skipLongTests) return;

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
					System.out.println("TrainModelTest: [" + p + ", " + q + ", " + r + "] " + expected + " vs " + actual);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void trainSmallest() {
		if (skipLongTests) return;

		NativeCompiler.enableInstructionSetMonitoring = true;
		MetalProgram.enableProgramMonitoring = true;

		int dim = 3;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 2, 2, 10));
	}

	@Test
	public void trainVerySmall() {
		if (skipLongTests) return;

		NativeCompiler.enableInstructionSetMonitoring = true;
		MetalProgram.enableProgramMonitoring = true;

		int dim = 4;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 3, 2, 10));
	}


	@Test
	public void trainSmall() {
		if (skipLongTests) return;

		NativeCompiler.enableLargeInstructionSetMonitoring = true;
		MetalProgram.enableLargeProgramMonitoring = true;

		int dim = 36;
		int filters = 8;
		Tensor<Double> t = tensor(shape(dim, dim));
		PackedCollection<?> input = t.pack();
		train(input, model(dim, dim, 3, filters, 10));
	}

	@Test
	public void trainLarge() {
		if (skipLongTests) return;

		Tensor<Double> t = tensor(shape(60, 60));
		PackedCollection<?> input = t.pack();
		train(input, model(60, 60, 3, 8, 10));
	}

	@Test
	public void trainProgressive() {
		if (skipLongTests) return;

		double size = 10;

		while (size < 60) {
			int s = (int) size;

			Tensor<Double> t = tensor(shape(s, s));
			PackedCollection<?> input = t.pack();
			train(input, model(s, s, 3, 8, 10));

			size = size * 1.2;
		}
	}

	protected void train(PackedCollection<?> input, Model model) {
		HardwareOperator.profile = new OperationProfile("HardwareOperator");
		OperationProfile profile = new OperationProfile("Model");
		CompiledModel compiled = model.compile(profile);
		log("Model compiled");

		// Scope.console.flag();

		int count = 100 * 1000;

		for (int i = 0; i < count; i++) {
			input.fill(pos -> 0.5 + 0.5 * Math.random());

			compiled.forward(input);

			if (i % 1000 == 0) {
				log("Input Size = " + input.getShape() +
						"\t | epoch = " + i / 1000);
			}

			compiled.backward(rand(model.lastBlock().getOutputShape()).get().evaluate());

			if (i % 1000 == 0) {
				log("\t\tbackprop\t\t" +
						" | epoch = " + i / 1000);
			}
		}

		profile.print();
		HardwareOperator.profile.print();
		AcceleratedOperation.printTimes();
	}

	protected Model model(int r, int c, int convSize, int convFilters, int denseSize) {
		Model model = new Model(shape(r, c));
		model.addLayer(convolution2d(convSize, convFilters));
		model.addLayer(pool2d(2));
		model.addBlock(flatten());
		model.addLayer(dense(denseSize));
		model.addLayer(softmax());
		log("Created model");
		return model;
	}
}
