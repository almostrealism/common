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

package org.almostrealism.algebra;

import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Diagnostic test to understand why large vocab projection generates
 * unrolled code instead of using loops or MemoryDataCopy.
 *
 * <p>This test does NOT override ScopeSettings.maxStatements to see
 * what protections trigger or fail.</p>
 */
public class LargeOutputDiagnosticTest extends TestSuiteBase implements MatrixFeatures, LayerFeatures, ConsoleFeatures {

	/**
	 * Test 1: Raw matmul with output size just below default maxStatements.
	 * Default maxStatements = 65536.
	 */
	@Test
	public void testMatmulBelowLimit() {
		int inputSize = 128;
		int outputSize = 60000;  // Below 65536 default

		log("=== Test 1: Matmul Below Limit ===");
		log("maxStatements default: " + ScopeSettings.maxStatements);
		log("Input: " + inputSize + ", Output: " + outputSize);

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));

		try {
			long start = System.currentTimeMillis();
			CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
			result.get();  // Compile
			long compileTime = System.currentTimeMillis() - start;
			log("Compile time: " + compileTime + "ms");
			log("=== PASSED ===\n");
		} catch (Exception e) {
			log("Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
			throw e;
		}
	}

	/**
	 * Test 2: Raw matmul with output size above default maxStatements.
	 * Should hit protection and throw an exception.
	 */
	@Test
	public void testMatmulAboveLimit() {
		int inputSize = 128;
		int outputSize = 70000;  // Above 65536 default

		log("=== Test 2: Matmul Above Limit ===");
		log("maxStatements default: " + ScopeSettings.maxStatements);
		log("Input: " + inputSize + ", Output: " + outputSize);

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));

		try {
			long start = System.currentTimeMillis();
			CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
			result.get();  // Compile
			long compileTime = System.currentTimeMillis() - start;
			log("Compile time: " + compileTime + "ms");
			log("WARNING: Expected exception but compilation succeeded!");
		} catch (IllegalArgumentException e) {
			log("Got expected IllegalArgumentException: " + e.getMessage());
			log("=== PASSED (protection worked) ===\n");
			return;
		} catch (Exception e) {
			log("Got different exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Test 3: Dense layer via Model with output size above default limit.
	 * This is the actual path used by Qwen3.
	 */
	@Test
	public void testDenseModelAboveLimit() {
		int inputSize = 128;
		int outputSize = 70000;  // Above 65536 default

		log("=== Test 3: Dense Model Above Limit ===");
		log("maxStatements default: " + ScopeSettings.maxStatements);
		log("Input: " + inputSize + ", Output: " + outputSize);

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));

		try {
			Model model = new Model(shape(1, inputSize));
			model.add(dense(weights));
			log("Model created successfully");

			long start = System.currentTimeMillis();
			CompiledModel compiled = model.compile(false);  // inference only
			long compileTime = System.currentTimeMillis() - start;
			log("Compile time: " + compileTime + "ms");
			log("WARNING: Expected exception but model compiled!");
		} catch (IllegalArgumentException e) {
			log("Got expected IllegalArgumentException: " + e.getMessage());
			log("=== PASSED (protection worked) ===\n");
			return;
		} catch (Exception e) {
			log("Got exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Test 4: Check what happens at boundary - exactly at limit.
	 */
	@Test
	public void testAtExactLimit() {
		int inputSize = 128;
		int outputSize = ScopeSettings.maxStatements;

		log("=== Test 4: At Exact Limit ===");
		log("maxStatements: " + ScopeSettings.maxStatements);
		log("Input: " + inputSize + ", Output: " + outputSize);

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));

		try {
			long start = System.currentTimeMillis();
			CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
			result.get();
			long compileTime = System.currentTimeMillis() - start;
			log("Compile time: " + compileTime + "ms");
			log("=== PASSED ===\n");
		} catch (Exception e) {
			log("Exception at exact limit: " + e.getClass().getSimpleName() + " - " + e.getMessage());
		}
	}

	/**
	 * Test 5: Verify weightedSum path is being used for large outputs in matmul.
	 */
	@Test
	public void testWeightedSumPath() {
		int inputSize = 128;
		int outputSize = 5000;  // Above 1000 threshold for weightedSum

		log("=== Test 5: Verify WeightedSum Path ===");
		log("Input: " + inputSize + ", Output: " + outputSize);
		log("Expected: Should use weightedSum path (not repeat+multiply+sum)");

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));

		long start = System.currentTimeMillis();
		CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
		result.get();
		long compileTime = System.currentTimeMillis() - start;

		log("Compile time: " + compileTime + "ms");

		// If using repeat+multiply+sum O(n^2) path, compile would be very slow
		// With weightedSum, it should be fast
		if (compileTime < 5000) {
			log("=== PASSED (fast compile suggests weightedSum path) ===\n");
		} else {
			log("WARNING: Slow compile suggests O(n^2) path may be used\n");
		}
	}
}
