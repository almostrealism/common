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

package org.almostrealism.layers.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.stats.DistributionFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tests for layer operations including dense layers, RMSNorm, and attention mechanisms.
 */
public class LayersTests extends TestSuiteBase implements LayerFeatures, DistributionFeatures {
	/** Size for test collections. */
	private static final int SIZE = 768;

	/** CPU output buffer for comparison tests. */
	private float[] cpuOut;

	/** GPU output buffer for comparison tests. */
	private float[] gpuOut;

	/** Expected CPU sum for validation. */
	private final double cpuSum = -1.0;

	/** Expected GPU sum for validation. */
	private final double gpuSum = -1.0;

	/**
	 * Tests exponent operation with random input and weights.
	 */
	@Test(timeout = 30000)
	public void exponent() {
		PackedCollection in = new PackedCollection(SIZE).traverseEach();
		in.randFill();

		PackedCollection weights = new PackedCollection(SIZE).traverseEach();
		weights.randFill();

		PackedCollection cpuOut = new PackedCollection(SIZE);
		PackedCollection gpuOut = new PackedCollection(SIZE);

		verboseLog(() -> {
			OperationList cop = new OperationList();
			cop.setComputeRequirements(List.of(ComputeRequirement.CPU));
			cop.add(a(p(cpuOut), pow(p(in), p(weights))));

			OperationList gop = new OperationList();
			gop.setComputeRequirements(List.of(ComputeRequirement.GPU));
			gop.add(a(p(gpuOut), pow(p(in), p(weights))));

			cop.get().run();
			gop.get().run();

			float[] cpu = new float[SIZE];
			cpuOut.getMem(0, cpu, 0, SIZE);

			float[] gpu = new float[SIZE];
			gpuOut.getMem(0, gpu, 0, SIZE);

			for (int i = 0; i < SIZE; i++) {
				if (gpu[i] != cpu[i]) {
					// throw new RuntimeException("Mismatch at " + i + ": " + gpu[i] + " vs " + cpu[i]);
				}
			}
		});
	}

	/**
	 * Tests RMSNorm layer with CPU and GPU backends.
	 */
	@Test(timeout = 30000)
	public void rmsnorm() {
		PackedCollection in = new PackedCollection(shape(SIZE));
		in.randFill();

		PackedCollection weights = new PackedCollection(shape(SIZE));
		weights.randFill();

		SequentialBlock cpuModel = new SequentialBlock(shape(SIZE));
		cpuModel.add(rmsnorm(weights));
		cpuModel.getForward().setReceptor(out -> () -> () -> {
			PackedCollection o = out.get().evaluate();
			cpuOut = new float[SIZE];
			o.getMem(0, cpuOut, 0, SIZE);
		});

		SequentialBlock gpuModel = new SequentialBlock(shape(SIZE));
		gpuModel.add(rmsnorm(weights, ComputeRequirement.GPU));
		gpuModel.getForward().setReceptor(out -> () -> () -> {
			PackedCollection o = out.get().evaluate();
			gpuOut = new float[SIZE];
			o.getMem(0, gpuOut, 0, SIZE);
		});

		OperationList cop = ((OperationList) cpuModel.getForward().push(p(in)));
		cop = cop.flatten();
		cop = (OperationList) cop.optimize();
		cop.get().run();

		OperationList gop = ((OperationList) gpuModel.getForward().push(p(in)));
		gop = gop.flatten();
		gop = (OperationList) gop.optimize();
		gop.get().run();

		log("CPU vs GPU = " + cpuSum + " vs " + gpuSum);

		for (int i = 0; i < SIZE; i++) {
			assertEquals(gpuOut[i], cpuOut[i]);
		}

		for (int i = 0; i < SIZE; i++) {
			if (gpuOut[i] != cpuOut[i]) {
				// throw new RuntimeException("Mismatch at " + i + ": " + gpuOut[i] + " vs " + cpuOut[i]);
			}
		}
	}

	/**
	 * Tests dense layer with model optimizer.
	 */
	@Test(timeout = 30000)
	@TestProperties(knownIssue = true)
	public void dense() {

		int size = 1800;
		int nodes = 10;
		int steps = 100;

		Model model = new Model(shape(size));
		model.add(dense(nodes));

		initKernelMetrics();
		OperationProfile profile = new OperationProfile("Model");

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(size)))
				.map(input -> {
					rand(input.getShape()).multiply(2.0).add(1.0).into(input.traverseEach()).evaluate();
					return input;
				})
				.map(input -> ValueTarget.of(input, input))
				.collect(Collectors.toList()));
		ModelOptimizer train = new ModelOptimizer(model, profile, data);
		log("Model compiled");

		try {
			train.optimize(1);
		} finally {
			logKernelMetrics(profile);
		}
	}

	/**
	 * Tests SiLU activation training with model optimizer.
	 */
	@Test(timeout = 30000)
	@TestDepth(3)
	public void siluTrain() throws IOException {
		int size = 21952;
		int steps = 1;

		Model model = new Model(shape(size));
		model.add(silu());

		initKernelMetrics();
		OperationProfileNode profile = new OperationProfileNode("Silu Model");

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(size)))
				.map(input -> {
					rand(input.getShape()).multiply(2.0).add(1.0).into(input.traverseEach()).evaluate();
					return input;
				})
				.map(input -> ValueTarget.of(input, input))
				.collect(Collectors.toList()));

		try {
			CompiledModel compiled = model.compile(true, true);
			log("Model compiled");

			ModelOptimizer train = new ModelOptimizer(compiled, data);
			profile(profile, () -> train.optimize(1))
					.save("results/siluTrain_" + size + ".xml");
		} finally {
			logKernelMetrics(profile);
		}
	}

	/**
	 * Tests SiLU transformation against expected sigmoid-based computation.
	 */
	@Test(timeout = 30000)
	public void siluTransformation() {
		int size = 100;
		
		// Create random input values in range [-2, 2] for numerical stability
		PackedCollection input = new PackedCollection(shape(size));
		rand(input.getShape()).add(-0.5).multiply(4.0).into(input.traverseEach()).evaluate();
		
		// Create SILU layer
		SequentialBlock model = new SequentialBlock(shape(size));
		model.add(silu());
		
		// Capture output
		PackedCollection actualOutput = new PackedCollection(shape(size));
		model.getForward().setReceptor(out -> () -> () -> {
			PackedCollection result = out.get().evaluate();
			actualOutput.setFrom(0, result, 0, size);
		});
		
		// Execute the model
		OperationList op = (OperationList) model.getForward().push(p(input));
		op.get().run();
		
		// Calculate expected output manually: silu(x) = x * sigmoid(x)
		double[] inputArray = input.toArray(0, size);
		double[] expectedOutput = new double[size];
		
		for (int i = 0; i < size; i++) {
			double x = inputArray[i];
			double sigmoid = 1.0 / (1.0 + Math.exp(-x));
			expectedOutput[i] = x * sigmoid;
		}
		
		// Compare results with tolerance for floating-point precision
		double[] actualArray = actualOutput.toArray(0, size);
		for (int i = 0; i < size; i++) {
			assertEquals(expectedOutput[i], actualArray[i]);
		}
	}

	/**
	 * The backward pass of the GELU layer delivers to its input the output gradient times the
	 * derivative of the tanh approximation of GELU, element by element:
	 * {@code d/dx[0.5 x (1 + tanh(u))] = 0.5 (1 + tanh(u)) + 0.5 x (1 - tanh(u)^2) du/dx} with
	 * {@code u = sqrt(2 / pi) (x + 0.044715 x^3)}. The hyperbolic tangent of that polynomial was
	 * differentiated wrongly before it was differentiated at the Producer level.
	 */
	@Test(timeout = 120000)
	public void geluInputGradientMatchesTheDerivative() {
		TraversalPolicy shape = shape(4, 6);
		Random random = new Random(11);
		PackedCollection input = randn(shape, 0.0, 1.5, random).evaluate();
		PackedCollection outputGradient = new PackedCollection(shape).randnFill(random);

		Model model = new Model(shape);
		model.add(gelu(shape));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] gradient = compiled.backward(outputGradient).doubleStream().toArray();
			double[] x = input.doubleStream().toArray();
			double[] g = outputGradient.doubleStream().toArray();

			double k = Math.sqrt(2.0 / Math.PI);
			for (int i = 0; i < x.length; i++) {
				double t = Math.tanh(k * (x[i] + 0.044715 * x[i] * x[i] * x[i]));
				double derivative = 0.5 * (1.0 + t)
						+ 0.5 * x[i] * (1.0 - t * t) * k * (1.0 + 3.0 * 0.044715 * x[i] * x[i]);
				assertEquals("input gradient " + i, g[i] * derivative, gradient[i], 1e-4);
			}
		}
	}
}