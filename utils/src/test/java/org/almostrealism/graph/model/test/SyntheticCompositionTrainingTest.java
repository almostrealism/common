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

package org.almostrealism.graph.model.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Synthetic training tests for compositional model patterns.
 * These tests validate residual connections and product compositions.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Residual (skip) connections</li>
 *   <li>Product composition of branches</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class SyntheticCompositionTrainingTest implements ModelTestFeatures {
	static {
		if (TestUtils.getTrainTests()) {
			Console.root().addListener(OutputFeatures.fileOutput("results/logs/synthetic_composition_train.out"));
		}
	}

	/**
	 * Fixed coefficients for target functions.
	 */
	private final double[] coeff = { 0.3, -0.2, 0.5, 0.1 };

	/**
	 * Identity-like function for testing residual connections.
	 * output[i] = input[i] + small_perturbation
	 */
	private final UnaryOperator<PackedCollection> identityLikeFunc =
			in -> {
				PackedCollection out = new PackedCollection(in.getShape());
				for (int i = 0; i < in.getMemLength(); i++) {
					out.setMem(i, in.valueAt(i) + 0.1 * coeff[i % coeff.length]);
				}
				return out;
			};

	/**
	 * Simple scaling function for testing compositions.
	 */
	private final UnaryOperator<PackedCollection> scaleFunc =
			in -> {
				PackedCollection out = new PackedCollection(in.getShape());
				for (int i = 0; i < in.getMemLength(); i++) {
					out.setMem(i, coeff[i % coeff.length] * in.valueAt(i));
				}
				return out;
			};

	/**
	 * Test 5.1: Residual Block
	 *
	 * <p>Validates residual (skip) connections in training.</p>
	 *
	 * <p>Architecture: Input [4] - [Dense - Dense] + residual - Dense - Output [4]</p>
	 */
	@Test(timeout = 3 * 60000)
	public void residualBlock() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Test 5.1: Residual Block ===");

		int size = 4;
		int epochs = 300;
		int steps = 260;

		// Build model with residual connection
		SequentialBlock innerBlock = new SequentialBlock(shape(size));
		innerBlock.add(dense(size, size));
		innerBlock.add(dense(size, size));

		SequentialBlock block = new SequentialBlock(shape(size));
		block.add(residual(innerBlock));
		block.add(dense(size, size));

		Model model = new Model(shape(size), 1e-5);
		model.add(block);

		log("Model built with residual: " + size + " -> [dense-dense] + skip -> " + size);

		// Generate dataset - identity-like function benefits from residual
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(size)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, identityLikeFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("residualBlock", model, data, epochs, steps, 1.0, 0.5);

		log("Test 5.1 completed successfully");
	}

	/**
	 * Test 5.2: Simple Sequential Composition
	 *
	 * <p>Validates that multiple sequential blocks compose correctly.</p>
	 *
	 * <p>Architecture: Input [4] - Dense - Dense - Dense - Output [4]</p>
	 */
	@Test(timeout = 3 * 60000)
	public void sequentialComposition() throws FileNotFoundException {
		if (testDepth < 1) return;

		log("=== Test 5.2: Sequential Composition ===");

		int size = 4;
		int epochs = 300;
		int steps = 260;

		// Build model with multiple sequential dense layers
		SequentialBlock block = new SequentialBlock(shape(size));
		block.add(dense(size, size));
		block.add(dense(size, size));
		block.add(dense(size, size));

		Model model = new Model(shape(size), 1e-5);
		model.add(block);

		log("Model built: " + size + " -> dense -> dense -> dense -> " + size);

		// Generate dataset
		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(size)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, scaleFunc.apply(input)))
				.collect(Collectors.toList()));

		// Train
		train("sequentialComposition", model, data, epochs, steps, 1.5, 0.75);

		log("Test 5.2 completed successfully");
	}
}
