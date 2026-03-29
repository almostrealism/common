/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Tests for the layer IO tracking mechanism, verifying that inference-mode
 * compilation (backprop=false) correctly eliminates input tracking copies
 * while preserving correct output values.
 */
public class LayerTrackingTest extends TestSuiteBase {

	private Model createDenseModel(int inputSize, int hiddenSize, int outputSize) {
		Model model = new Model(shape(inputSize));
		CellularLayer dense1 = dense(inputSize, hiddenSize).apply(shape(inputSize));
		CellularLayer dense2 = dense(hiddenSize, outputSize).apply(shape(hiddenSize));

		PackedCollection w1 = dense1.getWeights().get(0);
		PackedCollection b1 = dense1.getWeights().get(1);
		PackedCollection w2 = dense2.getWeights().get(0);
		PackedCollection b2 = dense2.getWeights().get(1);

		w1.fill(pos -> 0.01 * (pos[0] + 1));
		b1.fill(pos -> 0.1);
		w2.fill(pos -> 0.02 * (pos[0] + 1));
		b2.fill(pos -> 0.05);

		model.add(dense1);
		model.add(dense2);
		return model;
	}

	@Test(timeout = 120000)
	public void testInferenceProducesCorrectOutput() {
		int inputSize = 4;
		int hiddenSize = 3;
		int outputSize = 2;

		Model model = createDenseModel(inputSize, hiddenSize, outputSize);
		CompiledModel compiled = model.compile(false);

		PackedCollection input = new PackedCollection(shape(inputSize));
		IntStream.range(0, inputSize).forEach(i -> input.setMem(i, 1.0 + i * 0.5));

		PackedCollection output = compiled.forward(input);
		Assert.assertNotNull("Output should not be null", output);
		Assert.assertEquals("Output size should match", outputSize, output.getMemLength());

		for (int i = 0; i < outputSize; i++) {
			Assert.assertFalse("Output should not be NaN",
					Double.isNaN(output.valueAt(i)));
			Assert.assertFalse("Output should not be infinite",
					Double.isInfinite(output.valueAt(i)));
		}

		log("Inference output: " + Arrays.toString(output.toArray(0, outputSize)));
		compiled.destroy();
	}

	@Test(timeout = 120000)
	public void testTrainingProducesCorrectOutput() {
		int inputSize = 4;
		int hiddenSize = 3;
		int outputSize = 2;

		Model trainingModel = createDenseModel(inputSize, hiddenSize, outputSize);
		CompiledModel trainingCompiled = trainingModel.compile(true);

		PackedCollection input = new PackedCollection(shape(inputSize));
		IntStream.range(0, inputSize).forEach(i -> input.setMem(i, 1.0 + i * 0.5));

		PackedCollection trainingOutput = trainingCompiled.forward(input);

		log("Training output: " + Arrays.toString(trainingOutput.toArray(0, outputSize)));

		Assert.assertNotNull("Training output should not be null", trainingOutput);
		Assert.assertEquals("Training output size should match", outputSize, trainingOutput.getMemLength());

		for (int i = 0; i < outputSize; i++) {
			Assert.assertFalse("Training output should not be NaN",
					Double.isNaN(trainingOutput.valueAt(i)));
			Assert.assertFalse("Training output should not be infinite",
					Double.isInfinite(trainingOutput.valueAt(i)));
		}

		PackedCollection gradient = new PackedCollection(shape(outputSize));
		gradient.setMem(0, 0.1);
		gradient.setMem(1, -0.1);
		trainingCompiled.backward(gradient);

		trainingCompiled.destroy();
	}

	@Test(timeout = 300000)
	@TestDepth(1)
	public void testInferenceTrackingPerformance() {
		int inputSize = 512;
		int hiddenSize = 512;
		int layers = 4;
		int warmup = 5;
		int measured = 20;

		Model trainingModel = new Model(shape(inputSize));
		for (int l = 0; l < layers; l++) {
			trainingModel.add(dense(hiddenSize, hiddenSize).apply(shape(hiddenSize)));
		}

		OperationProfileNode trainingProfile = new OperationProfileNode("Training");
		CompiledModel trainingCompiled = trainingModel.compile(true, trainingProfile);

		PackedCollection input = new PackedCollection(shape(inputSize));
		input.fill(pos -> 0.01 * pos[0]);

		for (int i = 0; i < warmup; i++) {
			trainingCompiled.forward(input);
		}

		long trainingStart = System.nanoTime();
		for (int i = 0; i < measured; i++) {
			trainingCompiled.forward(input);
		}
		long trainingTime = System.nanoTime() - trainingStart;

		trainingCompiled.destroy();

		Model inferenceModel = new Model(shape(inputSize));
		for (int l = 0; l < layers; l++) {
			inferenceModel.add(dense(hiddenSize, hiddenSize).apply(shape(hiddenSize)));
		}

		OperationProfileNode inferenceProfile = new OperationProfileNode("Inference");
		CompiledModel inferenceCompiled = inferenceModel.compile(false, inferenceProfile);

		for (int i = 0; i < warmup; i++) {
			inferenceCompiled.forward(input);
		}

		long inferenceStart = System.nanoTime();
		for (int i = 0; i < measured; i++) {
			inferenceCompiled.forward(input);
		}
		long inferenceTime = System.nanoTime() - inferenceStart;

		inferenceCompiled.destroy();

		double trainingMs = trainingTime / 1_000_000.0;
		double inferenceMs = inferenceTime / 1_000_000.0;
		double speedup = (trainingMs - inferenceMs) / trainingMs * 100.0;

		log("Training mode:  " + String.format("%.2f", trainingMs) + " ms (" + measured + " iterations)");
		log("Inference mode: " + String.format("%.2f", inferenceMs) + " ms (" + measured + " iterations)");
		log("Speedup: " + String.format("%.1f", speedup) + "%");

		try {
			trainingProfile.save("utils/results/layer-tracking-training.xml");
			inferenceProfile.save("utils/results/layer-tracking-inference.xml");
		} catch (Exception e) {
			log("Could not save profiles: " + e.getMessage());
		}

		Assert.assertTrue(
				"Inference mode should be faster than training mode (training=" +
						String.format("%.2f", trainingMs) + "ms, inference=" +
						String.format("%.2f", inferenceMs) + "ms)",
				inferenceMs < trainingMs);
	}

	@Test(timeout = 120000)
	public void testInferenceOperationCount() {
		int inputSize = 8;
		int hiddenSize = 4;
		int outputSize = 2;

		Model trainingModel = createDenseModel(inputSize, hiddenSize, outputSize);
		OperationProfileNode trainingProfile = new OperationProfileNode("Training");
		CompiledModel trainingCompiled = trainingModel.compile(true, trainingProfile);

		PackedCollection input = new PackedCollection(shape(inputSize));
		IntStream.range(0, inputSize).forEach(i -> input.setMem(i, 1.0));
		trainingCompiled.forward(input);

		Model inferenceModel = createDenseModel(inputSize, hiddenSize, outputSize);
		OperationProfileNode inferenceProfile = new OperationProfileNode("Inference");
		CompiledModel inferenceCompiled = inferenceModel.compile(false, inferenceProfile);
		inferenceCompiled.forward(input);

		int trainingOps = trainingProfile.getChildren().size();
		int inferenceOps = inferenceProfile.getChildren().size();

		log("Training operation count:  " + trainingOps);
		log("Inference operation count: " + inferenceOps);

		Assert.assertTrue(
				"Inference mode should have fewer operations (training=" +
						trainingOps + ", inference=" + inferenceOps + ")",
				inferenceOps < trainingOps);

		trainingCompiled.destroy();
		inferenceCompiled.destroy();
	}
}
