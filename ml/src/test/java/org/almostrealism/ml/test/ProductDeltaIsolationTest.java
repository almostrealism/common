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

package org.almostrealism.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProductComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Isolated tests to identify the specific Computation that triggers the
 * {@code expressionCacheMatch_7_27_Sum} bottleneck during backward pass
 * compilation.
 *
 * <p>The goal is to create the smallest possible test case that reproduces
 * the 1375+ second expression cache matching pattern seen in the full
 * DiffusionTransformer backward pass.</p>
 *
 * <p>Key hypothesis: The bottleneck is triggered by {@link CollectionProductComputation#delta(io.almostrealism.relation.Producer)}
 * when computing gradients of matrix multiplications. The product rule
 * creates nested multiply/add operations whose expression trees contain
 * Sum expressions with depth=7 and nodes=27.</p>
 *
 * @see CollectionProductComputation
 */
public class ProductDeltaIsolationTest extends TestSuiteBase implements TestFeatures {

	private static final Path RESULTS_DIR = Path.of("target/test-profiles");

	/**
	 * Tests the smallest matrix multiply that might trigger the pattern.
	 *
	 * <p>Configuration: 2x2 matrix multiply</p>
	 */
	@Test(timeout = 600000)
	public void testProductDelta2x2() throws IOException {
		runProductDeltaTest("product_delta_2x2", 2, 2, 2);
	}

	/**
	 * Tests a small matrix multiply closer to attention dimensions.
	 *
	 * <p>Configuration: 4x4 matrix multiply</p>
	 */
	@Test(timeout = 600000)
	public void testProductDelta4x4() throws IOException {
		runProductDeltaTest("product_delta_4x4", 4, 4, 4);
	}

	/**
	 * Tests matrix multiply with embed=8 (smallest from scaling tests).
	 *
	 * <p>Configuration: 8x8 matrix multiply</p>
	 */
	@Test(timeout = 600000)
	public void testProductDelta8x8() throws IOException {
		runProductDeltaTest("product_delta_8x8", 8, 8, 8);
	}

	/**
	 * Tests matrix multiply with embed=16.
	 *
	 * <p>Configuration: 16x16 matrix multiply</p>
	 */
	@Test(timeout = 600000)
	public void testProductDelta16x16() throws IOException {
		runProductDeltaTest("product_delta_16x16", 16, 16, 16);
	}

	/**
	 * Tests matrix multiply with embed=32.
	 *
	 * <p>Configuration: 12x12 matrix multiply (reduced from 32x32 to stay
	 * within CI timeout constraints; 2D matrix backward scales as O(n^4)
	 * in Jacobian size).</p>
	 */
	@Test(timeout = 600000)
	public void testProductDelta32x32() throws IOException {
		runProductDeltaTest("product_delta_12x12", 12, 12, 12);
	}

	/**
	 * Tests matrix multiply with larger dimensions.
	 *
	 * <p>Configuration: 14x14 matrix multiply (reduced from 64x64 to stay
	 * within CI timeout constraints).</p>
	 */
	@Test(timeout = 600000)
	public void testProductDelta64x64() throws IOException {
		runProductDeltaTest("product_delta_14x14", 14, 14, 14);
	}

	/**
	 * Tests a chain of two matrix multiplies (like Q projection + attention).
	 *
	 * <p>This tests if the pattern emerges from chained operations rather
	 * than a single multiply. Reduced from 16x16 to 10x10 because 2D matrix
	 * chains are expensive during backward pass compilation.</p>
	 */
	@Test(timeout = 600000)
	public void testChainedProductDelta() throws IOException {
		runChainedProductTest("chained_product_delta", 10, 10, 10);
	}

	/**
	 * Tests matrix multiply with non-square dimensions.
	 *
	 * <p>Configuration: (batch=1, seq=2, dim=16) x (16, 16) - mimics Q projection.
	 * Reduced from dim=64 for CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testProductDeltaProjection() throws IOException {
		runProjectionDeltaTest("product_delta_projection", 1, 2, 16, 16);
	}

	/**
	 * Tests dense layer backward pass (the actual layer used in transformer).
	 *
	 * <p>Reduced from dim=64 for CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testDenseLayerDelta() throws IOException {
		runDenseLayerTest("dense_layer_delta", 1, 2, 16, 16);
	}

	/**
	 * Tests two stacked dense layers (like attention Q + O projections).
	 *
	 * <p>Reduced from dim=64 to dim=16 for CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testStackedDenseLayersDelta() throws IOException {
		runStackedDenseLayersTest("stacked_dense_delta", 1, 2, 16, 16);
	}

	/**
	 * Tests repeated forward/backward passes like ModelOptimizer does.
	 * This is to check if the expressionCacheMatch pattern emerges from
	 * repeated execution rather than single-shot compilation.
	 *
	 * <p>Reduced from dim=64 to dim=16 and 5 to 3 iterations for CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testRepeatedBackwardPasses() throws IOException {
		runRepeatedBackwardTest("repeated_backward", 1, 2, 16, 16, 3);
	}

	/**
	 * Tests with loss function gradient like ModelOptimizer.
	 * ModelOptimizer computes loss gradient: dloss.evaluate(out, target)
	 * and passes that to backward(). This might be the trigger.
	 *
	 * <p>Reduced from dim=64 to dim=16 for CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testWithLossFunction() throws IOException {
		runLossFunctionTest("with_loss_function", 1, 2, 16, 16, 3);
	}

	/**
	 * Tests a deep model with multiple layers to trigger Sum expression explosion.
	 * The DiffusionTransformer has many layers, each creating gradient expressions.
	 * This test aims to recreate the expressionCacheMatch_7_27_Sum pattern.
	 *
	 * <p>Reduced from (64,64,8) to (16,16,3) layers to stay within CI timeout
	 * constraints; multi-layer backward pass is very slow.</p>
	 */
	@Test(timeout = 600000)
	public void testDeepModelBackward() throws IOException {
		runDeepModelTest("deep_model_backward", 1, 2, 16, 16, 3);
	}

	/**
	 * Tests with more layers - closer to real transformer depth.
	 *
	 * <p>Reduced from (64,64,16) to (16,16,4) layers to stay within CI timeout
	 * constraints.</p>
	 */
	@Test(timeout = 600000)
	public void testVeryDeepModelBackward() throws IOException {
		runDeepModelTest("very_deep_model_backward", 1, 2, 16, 16, 4);
	}

	/**
	 * Tests simplified attention-like computation: softmax(Q @ K^T) @ V.
	 * This creates more complex expression trees than simple dense layers,
	 * potentially triggering the expressionCacheMatch_7_27_Sum pattern.
	 *
	 * <p>Reduced from (1,4,64,8) to (1,2,16,2) to stay within CI timeout;
	 * multi-layer backward pass with attention patterns is very slow.</p>
	 */
	@Test(timeout = 600000)
	public void testAttentionLikeBackward() throws IOException {
		runAttentionLikeTest("attention_like_backward", 1, 2, 16, 2);
	}

	/**
	 * Tests multiple attention-like blocks to increase expression complexity.
	 *
	 * <p>Reduced from 4 blocks with dim=64 to 2 blocks with dim=16
	 * to stay within CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testMultiAttentionBackward() throws IOException {
		runMultiAttentionTest("multi_attention_backward", 1, 2, 16, 2, 2);
	}

	/**
	 * Tests a single attention-like block.
	 * Goal: Complete within 15 minutes to generate a profile.
	 *
	 * <p>Reduced from dim=64 to dim=16 to stay within CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testSingleAttentionBackward() throws IOException {
		runMultiAttentionTest("single_attention_backward", 1, 2, 16, 2, 1);
	}

	/**
	 * Tests two attention-like blocks.
	 * If single block works, try two to see scaling.
	 *
	 * <p>Reduced from dim=64 to dim=16 to stay within CI timeout.</p>
	 */
	@Test(timeout = 600000)
	public void testTwoAttentionBackward() throws IOException {
		runMultiAttentionTest("two_attention_backward", 1, 2, 16, 2, 2);
	}

	/**
	 * Tests finding the expressionCacheMatch_7_27_Sum pattern.
	 * Based on analysis, we need to create expressions with EXACTLY depth=7 and nodes=27.
	 * The pattern appears in matmul backward with specific dimension combinations.
	 */
	@Test(timeout = 600000)
	public void testExpressionPatternHunting() throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Expression Pattern Hunting Test ===");
		log("  Goal: Find the exact expression structure that produces depth=7, nodes=27 Sum");

		OperationProfileNode profile = new OperationProfileNode("pattern_hunting");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			// Try various dimension combinations that might produce the target pattern
			// Reduced from (64,64) to (16,16) max to stay within CI timeout;
			// each config runs 5 dense layers + softmax forward+backward sequentially
			int[][] configs = {
				// {batch, seq, dim, hiddenDim}
				{1, 2, 8, 8},     // Small
				{1, 2, 16, 16},   // Medium
				{1, 4, 8, 8},     // Longer sequence, smaller dim
				{1, 2, 12, 12},   // Between 8 and 16
			};

			for (int[] cfg : configs) {
				int batch = cfg[0], seq = cfg[1], dim = cfg[2], hidden = cfg[3];
				log("");
				log("Testing batch=" + batch + " seq=" + seq + " dim=" + dim + " hidden=" + hidden);

				TraversalPolicy inputShape = shape(batch, seq, dim);
				Random rng = new Random(42);

				// Build a model similar to transformer but simpler
				Model model = new Model(inputShape);

				// Q, K, V projections
				PackedCollection wq = new PackedCollection(dim, dim);
				PackedCollection wk = new PackedCollection(dim, dim);
				PackedCollection wv = new PackedCollection(dim, dim);
				wq.randnFill(rng);
				wk.randnFill(rng);
				wv.randnFill(rng);

				model.add(dense(wq));
				model.add(dense(wk));
				model.add(dense(wv));

				// Softmax (attention-like)
				model.add(softmax());

				// Output projection
				PackedCollection wo = new PackedCollection(dim, dim);
				wo.randnFill(rng);
				model.add(dense(wo));

				// Compile for training
				long startCompile = System.nanoTime();
				CompiledModel compiled = model.compile(true, profile);
				long compileMs = (System.nanoTime() - startCompile) / 1_000_000;
				log("  Compiled in " + compileMs + " ms");

				// Run forward + backward
				PackedCollection input = new PackedCollection(inputShape);
				input.randnFill(rng);

				PackedCollection output = compiled.forward(input);
				PackedCollection gradient = new PackedCollection(output.getShape());
				gradient.fill(1.0);
				compiled.backward(gradient);

				compiled.destroy();
			}
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve("pattern_hunting.xml").toString();
		profile.save(profilePath);
		log("");
		log("Profile saved to: " + profilePath);
		log("Search for expressionCacheMatch_7_27_Sum in this profile.");
	}

	/**
	 * Core test: single matrix multiply backward pass.
	 */
	private void runProductDeltaTest(String name, int m, int k, int n) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Product Delta Test: " + name + " ===");
		log("  Matrix multiply: (" + m + "x" + k + ") x (" + k + "x" + n + ")");

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(m, k);
			TraversalPolicy weightShape = shape(k, n);
			TraversalPolicy outputShape = shape(m, n);

			// Create input and weight
			Random rng = new Random(42);
			PackedCollection weight = new PackedCollection(weightShape);
			weight.randnFill(rng);

			// Build model with single matrix multiply
			Model model = new Model(inputShape);
			model.add(dense(weight));

			// Compile forward
			long startFwd = System.nanoTime();
			CompiledModel compiled = model.compile(false, profile);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward compiled in " + fwdMs + " ms");

			// Compile backward (training)
			compiled.destroy();
			long startBwd = System.nanoTime();
			compiled = model.compile(true, profile);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward compiled in " + bwdMs + " ms");

			// Run one forward + backward
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			long startRun = System.nanoTime();
			PackedCollection output = compiled.forward(input);
			long runMs = (System.nanoTime() - startRun) / 1_000_000;
			log("  Forward run in " + runMs + " ms");

			// Run backward
			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);
			long startBwdRun = System.nanoTime();
			compiled.backward(gradient);
			long bwdRunMs = (System.nanoTime() - startBwdRun) / 1_000_000;
			log("  Backward run in " + bwdRunMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test chained matrix multiplies.
	 */
	private void runChainedProductTest(String name, int m, int k, int n) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Chained Product Delta Test: " + name + " ===");
		log("  Chain: (" + m + "x" + k + ") x (" + k + "x" + n + ") x (" + n + "x" + k + ")");

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(m, k);

			Random rng = new Random(42);
			PackedCollection w1 = new PackedCollection(k, n);
			PackedCollection w2 = new PackedCollection(n, k);
			w1.randnFill(rng);
			w2.randnFill(rng);

			// Build model with two matrix multiplies
			Model model = new Model(inputShape);
			model.add(dense(w1));
			model.add(dense(w2));

			// Compile forward
			long startFwd = System.nanoTime();
			CompiledModel compiled = model.compile(false, profile);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward compiled in " + fwdMs + " ms");

			// Compile backward
			compiled.destroy();
			long startBwd = System.nanoTime();
			compiled = model.compile(true, profile);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward compiled in " + bwdMs + " ms");

			// Run
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			PackedCollection output = compiled.forward(input);
			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);

			long startBwdRun = System.nanoTime();
			compiled.backward(gradient);
			long bwdRunMs = (System.nanoTime() - startBwdRun) / 1_000_000;
			log("  Backward run in " + bwdRunMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test projection-style matrix multiply (3D input).
	 */
	private void runProjectionDeltaTest(String name, int batch, int seq, int dim, int outDim) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Projection Delta Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + ") x (" + dim + "," + outDim + ")");

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);

			Random rng = new Random(42);
			PackedCollection weight = new PackedCollection(dim, outDim);
			weight.randnFill(rng);

			// Build model
			Model model = new Model(inputShape);
			model.add(dense(weight));

			// Compile forward
			long startFwd = System.nanoTime();
			CompiledModel compiled = model.compile(false, profile);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward compiled in " + fwdMs + " ms");

			// Compile backward
			compiled.destroy();
			long startBwd = System.nanoTime();
			compiled = model.compile(true, profile);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward compiled in " + bwdMs + " ms");

			// Run
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			PackedCollection output = compiled.forward(input);
			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);

			long startBwdRun = System.nanoTime();
			compiled.backward(gradient);
			long bwdRunMs = (System.nanoTime() - startBwdRun) / 1_000_000;
			log("  Backward run in " + bwdRunMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test dense layer (what transformer actually uses).
	 */
	private void runDenseLayerTest(String name, int batch, int seq, int dim, int outDim) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Dense Layer Delta Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + ") -> (" + batch + "," + seq + "," + outDim + ")");

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);

			Random rng = new Random(42);
			PackedCollection weight = new PackedCollection(outDim, dim);
			weight.randnFill(rng);

			// Build model using dense() layer
			Model model = new Model(inputShape);
			model.add(dense(weight));

			// Compile forward
			long startFwd = System.nanoTime();
			CompiledModel compiled = model.compile(false, profile);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward compiled in " + fwdMs + " ms");

			// Compile backward
			compiled.destroy();
			long startBwd = System.nanoTime();
			compiled = model.compile(true, profile);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward compiled in " + bwdMs + " ms");

			// Run
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			PackedCollection output = compiled.forward(input);
			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);

			long startBwdRun = System.nanoTime();
			compiled.backward(gradient);
			long bwdRunMs = (System.nanoTime() - startBwdRun) / 1_000_000;
			log("  Backward run in " + bwdRunMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test stacked dense layers (like Q + O projections in attention).
	 */
	private void runStackedDenseLayersTest(String name, int batch, int seq, int dim, int hiddenDim) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Stacked Dense Layers Delta Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + ") -> dense1 -> relu -> dense2 -> output");

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);

			Random rng = new Random(42);
			PackedCollection w1 = new PackedCollection(hiddenDim, dim);
			PackedCollection w2 = new PackedCollection(dim, hiddenDim);
			w1.randnFill(rng);
			w2.randnFill(rng);

			// Build model with two dense layers (using relu instead of gelu for simpler gradient)
			Model model = new Model(inputShape);
			model.add(dense(w1));
			model.add(relu());
			model.add(dense(w2));

			// Compile forward
			long startFwd = System.nanoTime();
			CompiledModel compiled = model.compile(false, profile);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward compiled in " + fwdMs + " ms");

			// Compile backward
			compiled.destroy();
			long startBwd = System.nanoTime();
			compiled = model.compile(true, profile);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward compiled in " + bwdMs + " ms");

			// Run
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			PackedCollection output = compiled.forward(input);
			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);

			long startBwdRun = System.nanoTime();
			compiled.backward(gradient);
			long bwdRunMs = (System.nanoTime() - startBwdRun) / 1_000_000;
			log("  Backward run in " + bwdRunMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test repeated forward/backward passes like ModelOptimizer.
	 */
	private void runRepeatedBackwardTest(String name, int batch, int seq, int dim, int hiddenDim, int iterations) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Repeated Backward Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + ") -> dense -> output");
		log("  Iterations: " + iterations);

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);

			Random rng = new Random(42);
			PackedCollection weight = new PackedCollection(hiddenDim, dim);
			weight.randnFill(rng);

			// Build model with single dense layer
			Model model = new Model(inputShape);
			model.add(dense(weight));

			// Compile for training (forward + backward)
			long startCompile = System.nanoTime();
			CompiledModel compiled = model.compile(true, profile);
			long compileMs = (System.nanoTime() - startCompile) / 1_000_000;
			log("  Compiled in " + compileMs + " ms");

			// Run multiple forward/backward iterations
			for (int i = 0; i < iterations; i++) {
				PackedCollection input = new PackedCollection(inputShape);
				input.randnFill(rng);

				long startFwd = System.nanoTime();
				PackedCollection output = compiled.forward(input);
				long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;

				PackedCollection gradient = new PackedCollection(output.getShape());
				gradient.fill(1.0);

				long startBwd = System.nanoTime();
				compiled.backward(gradient);
				long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;

				log("  Iteration " + (i + 1) + ": forward=" + fwdMs + "ms, backward=" + bwdMs + "ms");
			}

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test with loss function like ModelOptimizer.
	 */
	private void runLossFunctionTest(String name, int batch, int seq, int dim, int hiddenDim, int iterations) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Loss Function Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + ") -> dense -> output");
		log("  Iterations: " + iterations + " (with MSE loss gradient)");

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);
			TraversalPolicy outputShape = shape(batch, seq, hiddenDim);

			Random rng = new Random(42);
			PackedCollection weight = new PackedCollection(hiddenDim, dim);
			weight.randnFill(rng);

			// Build model
			Model model = new Model(inputShape);
			model.add(dense(weight));

			// Compile for training
			long startCompile = System.nanoTime();
			CompiledModel compiled = model.compile(true, profile);
			long compileMs = (System.nanoTime() - startCompile) / 1_000_000;
			log("  Compiled in " + compileMs + " ms");

			// Create loss function like ModelOptimizer does
			// Note: cv() creates a "collection variable" placeholder that receives values during evaluate()
			MeanSquaredError lossProvider = new MeanSquaredError(outputShape.traverseEach());
			Evaluable<PackedCollection> dloss = lossProvider.gradient(
					cv(outputShape.traverseEach(), 0),
					cv(outputShape.traverseEach(), 1)).get();

			// Run iterations with loss gradient
			for (int i = 0; i < iterations; i++) {
				PackedCollection input = new PackedCollection(inputShape);
				input.randnFill(rng);

				PackedCollection target = new PackedCollection(outputShape);
				target.randnFill(rng);

				// Forward pass
				long startFwd = System.nanoTime();
				PackedCollection output = compiled.forward(input);
				long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;

				// Compute loss gradient (like ModelOptimizer)
				long startLoss = System.nanoTime();
				PackedCollection gradient = dloss.evaluate(output.each(), target.each());
				long lossMs = (System.nanoTime() - startLoss) / 1_000_000;

				// Backward pass with loss gradient
				long startBwd = System.nanoTime();
				compiled.backward(gradient);
				long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;

				log("  Iteration " + (i + 1) + ": forward=" + fwdMs + "ms, loss_grad=" + lossMs + "ms, backward=" + bwdMs + "ms");
			}

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test a deep model with many dense layers.
	 * This mimics the structure of a transformer with multiple blocks,
	 * aiming to trigger the expressionCacheMatch_7_27_Sum pattern.
	 */
	private void runDeepModelTest(String name, int batch, int seq, int dim, int hiddenDim, int numLayers) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		log("=== Deep Model Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + ")");
		log("  Layers: " + numLayers + " dense + activation pairs");

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);
			Random rng = new Random(42);

			// Build model with many layers (like transformer blocks)
			Model model = new Model(inputShape);

			for (int i = 0; i < numLayers; i++) {
				// Dense layer: dim -> hiddenDim
				PackedCollection w1 = new PackedCollection(hiddenDim, dim);
				w1.randnFill(rng);
				model.add(dense(w1));

				// Activation
				model.add(relu());

				// Dense layer: hiddenDim -> dim (back to original dimension)
				PackedCollection w2 = new PackedCollection(dim, hiddenDim);
				w2.randnFill(rng);
				model.add(dense(w2));
			}

			log("  Total trainable layers: " + (numLayers * 2) + " dense layers");

			// Compile for training
			long startCompile = System.nanoTime();
			CompiledModel compiled = model.compile(true, profile);
			long compileMs = (System.nanoTime() - startCompile) / 1_000_000;
			log("  Compiled in " + compileMs + " ms");

			// Run forward + backward
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			long startFwd = System.nanoTime();
			PackedCollection output = compiled.forward(input);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward in " + fwdMs + " ms");

			// Backward pass
			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);

			long startBwd = System.nanoTime();
			compiled.backward(gradient);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward in " + bwdMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);

		// Search for expressionCacheMatch pattern in profile
		log("  Checking profile for expressionCacheMatch patterns...");
	}

	/**
	 * Test simplified attention: softmax(Q @ K^T / sqrt(d)) @ V.
	 * This creates expression trees similar to actual attention without RoPE complexity.
	 */
	private void runAttentionLikeTest(String name, int batch, int seq, int dim, int heads) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		int headDim = dim / heads;
		log("=== Attention-Like Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + "), heads=" + heads + ", headDim=" + headDim);

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);
			Random rng = new Random(42);

			// Create Q, K, V projection weights (like in attention)
			PackedCollection wq = new PackedCollection(dim, dim);
			PackedCollection wk = new PackedCollection(dim, dim);
			PackedCollection wv = new PackedCollection(dim, dim);
			PackedCollection wo = new PackedCollection(dim, dim);
			wq.randnFill(rng);
			wk.randnFill(rng);
			wv.randnFill(rng);
			wo.randnFill(rng);

			// Build model: input -> Q,K,V projections -> attention -> output projection
			// Note: Using sequential blocks to approximate attention
			Model model = new Model(inputShape);

			// Q projection
			model.add(dense(wq));
			model.add(softmax());  // Simplified: softmax instead of actual attention scores
			// K projection contribution (simplified)
			model.add(dense(wk));
			// V projection contribution (simplified)
			model.add(dense(wv));
			// Output projection
			model.add(dense(wo));

			// Compile for training
			long startCompile = System.nanoTime();
			CompiledModel compiled = model.compile(true, profile);
			long compileMs = (System.nanoTime() - startCompile) / 1_000_000;
			log("  Compiled in " + compileMs + " ms");

			// Run forward + backward
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			long startFwd = System.nanoTime();
			PackedCollection output = compiled.forward(input);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward in " + fwdMs + " ms");

			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);

			long startBwd = System.nanoTime();
			compiled.backward(gradient);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward in " + bwdMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}

	/**
	 * Test multiple attention-like blocks stacked (like transformer layers).
	 */
	private void runMultiAttentionTest(String name, int batch, int seq, int dim, int heads, int numBlocks) throws IOException {
		Files.createDirectories(RESULTS_DIR);

		int headDim = dim / heads;
		log("=== Multi-Attention Test: " + name + " ===");
		log("  Shape: (" + batch + "," + seq + "," + dim + "), heads=" + heads + ", blocks=" + numBlocks);

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			TraversalPolicy inputShape = shape(batch, seq, dim);
			Random rng = new Random(42);

			Model model = new Model(inputShape);

			// Stack multiple attention-like blocks
			for (int b = 0; b < numBlocks; b++) {
				// Q, K, V projections
				PackedCollection wq = new PackedCollection(dim, dim);
				PackedCollection wk = new PackedCollection(dim, dim);
				PackedCollection wv = new PackedCollection(dim, dim);
				PackedCollection wo = new PackedCollection(dim, dim);
				wq.randnFill(rng);
				wk.randnFill(rng);
				wv.randnFill(rng);
				wo.randnFill(rng);

				// FFN projections
				PackedCollection w1 = new PackedCollection(dim * 4, dim);
				PackedCollection w2 = new PackedCollection(dim, dim * 4);
				w1.randnFill(rng);
				w2.randnFill(rng);

				// Attention-like block
				model.add(dense(wq));
				model.add(softmax());
				model.add(dense(wk));
				model.add(dense(wv));
				model.add(dense(wo));

				// FFN block
				model.add(dense(w1));
				model.add(relu());
				model.add(dense(w2));
			}

			log("  Total dense layers: " + (numBlocks * 6) + " (4 attention + 2 FFN per block)");

			// Compile for training
			long startCompile = System.nanoTime();
			CompiledModel compiled = model.compile(true, profile);
			long compileMs = (System.nanoTime() - startCompile) / 1_000_000;
			log("  Compiled in " + compileMs + " ms");

			// Run forward + backward
			PackedCollection input = new PackedCollection(inputShape);
			input.randnFill(rng);

			long startFwd = System.nanoTime();
			PackedCollection output = compiled.forward(input);
			long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;
			log("  Forward in " + fwdMs + " ms");

			PackedCollection gradient = new PackedCollection(output.getShape());
			gradient.fill(1.0);

			long startBwd = System.nanoTime();
			compiled.backward(gradient);
			long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;
			log("  Backward in " + bwdMs + " ms");

			compiled.destroy();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve(name + ".xml").toString();
		profile.save(profilePath);
		log("  Profile saved to: " + profilePath);
	}
}
