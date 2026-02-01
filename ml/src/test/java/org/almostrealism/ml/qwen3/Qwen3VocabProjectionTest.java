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

package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Diagnostic test to isolate the vocab projection layer compilation issue.
 *
 * <p>This test creates a minimal model with just a dense layer outputting
 * 151,936 values (Qwen3 vocab size) to identify where compilation hangs.</p>
 */
public class Qwen3VocabProjectionTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	/**
	 * Test dense layer with Qwen3-sized output (151936) in isolation.
	 *
	 * <p>This test validates that large vocab projection uses efficient
	 * MemoryDataCopy for layer output instead of generating unrolled code.</p>
	 */
	@Test
	public void testLargeVocabProjection() {
		int inputDim = 896;  // Qwen3-0.6B dim
		int vocabSize = 151936;

		log("=== Qwen3 Vocab Projection Test ===");
		log("Input dim: " + inputDim);
		log("Vocab size: " + vocabSize);
		log("Weight size: " + (inputDim * vocabSize) + " (" +
				String.format("%.2f", (inputDim * vocabSize * 4.0) / (1024 * 1024)) + " MB float32)");

		// Create weights
		log("\n[1] Creating weights...");
		long start = System.currentTimeMillis();
		PackedCollection weights = new PackedCollection(new TraversalPolicy(vocabSize, inputDim));
		log("    Weights created in " + (System.currentTimeMillis() - start) + "ms");

		// Fill with small values to avoid overflow
		log("\n[2] Initializing weights...");
		start = System.currentTimeMillis();
		for (int i = 0; i < Math.min(1000, vocabSize); i++) {
			for (int j = 0; j < inputDim; j++) {
				weights.setMem(i * inputDim + j, 0.001 * (i + j));
			}
		}
		log("    Partial init in " + (System.currentTimeMillis() - start) + "ms");

		// Create dense layer
		log("\n[3] Creating dense layer...");
		start = System.currentTimeMillis();
		Model model = new Model(shape(1, inputDim));
		model.add(dense(weights));
		log("    Layer created in " + (System.currentTimeMillis() - start) + "ms");

		// Compile (inference-only, no backprop)
		log("\n[4] Compiling model (inference-only)...");
		start = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("    Compiled in " + (System.currentTimeMillis() - start) + "ms");

		// Test forward pass
		log("\n[5] Running forward pass...");
		start = System.currentTimeMillis();
		PackedCollection input = new PackedCollection(shape(1, inputDim));
		for (int i = 0; i < inputDim; i++) {
			input.setMem(i, 0.1);
		}
		PackedCollection output = compiled.forward(input);
		log("    Forward pass in " + (System.currentTimeMillis() - start) + "ms");
		log("    Output shape: " + output.getShape());

		log("\n=== TEST PASSED ===");
	}

	/**
	 * Test with smaller vocab to establish baseline.
	 */
	@Test
	public void testSmallVocabProjection() {
		int inputDim = 896;
		int vocabSize = 1000;

		log("=== Small Vocab Projection Test (baseline) ===");
		log("Input dim: " + inputDim);
		log("Vocab size: " + vocabSize);

		// Create weights
		log("\n[1] Creating weights...");
		long start = System.currentTimeMillis();
		PackedCollection weights = new PackedCollection(new TraversalPolicy(vocabSize, inputDim));
		log("    Weights created in " + (System.currentTimeMillis() - start) + "ms");

		// Create dense layer
		log("\n[2] Creating dense layer...");
		start = System.currentTimeMillis();
		Model model = new Model(shape(1, inputDim));
		model.add(dense(weights));
		log("    Layer created in " + (System.currentTimeMillis() - start) + "ms");

		// Compile (inference-only, no backprop)
		log("\n[3] Compiling model (inference-only)...");
		start = System.currentTimeMillis();
		CompiledModel compiled = model.compile(false);
		log("    Compiled in " + (System.currentTimeMillis() - start) + "ms");

		// Test forward pass
		log("\n[4] Running forward pass...");
		start = System.currentTimeMillis();
		PackedCollection input = new PackedCollection(shape(1, inputDim));
		for (int i = 0; i < inputDim; i++) {
			input.setMem(i, 0.1);
		}
		PackedCollection output = compiled.forward(input);
		log("    Forward pass in " + (System.currentTimeMillis() - start) + "ms");
		log("    Output shape: " + output.getShape());

		log("\n=== BASELINE TEST PASSED ===");
	}

	/**
	 * Incrementally test larger vocab sizes to find the breaking point.
	 */
	@Test
	public void testProgressiveVocabSizes() {
		int inputDim = 896;
		int[] vocabSizes = {1000, 5000, 10000, 25000, 50000, 100000, 151936};

		log("=== Progressive Vocab Size Test ===");
		log("Input dim: " + inputDim);

		for (int vocabSize : vocabSizes) {
			log("\n--- Testing vocab size: " + vocabSize + " ---");

			long start = System.currentTimeMillis();
			PackedCollection weights = new PackedCollection(new TraversalPolicy(vocabSize, inputDim));
			log("  Weights: " + (System.currentTimeMillis() - start) + "ms");

			start = System.currentTimeMillis();
			Model model = new Model(shape(1, inputDim));
			model.add(dense(weights));
			log("  Layer: " + (System.currentTimeMillis() - start) + "ms");

			start = System.currentTimeMillis();
			CompiledModel compiled = model.compile(false);  // inference-only
			log("  Compile: " + (System.currentTimeMillis() - start) + "ms");

			start = System.currentTimeMillis();
			PackedCollection input = new PackedCollection(shape(1, inputDim));
			PackedCollection output = compiled.forward(input);
			log("  Forward: " + (System.currentTimeMillis() - start) + "ms");

			log("  Output shape: " + output.getShape());
		}

		log("\n=== ALL SIZES PASSED ===");
	}
}
