package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test to verify that branches receive updated input on each forward pass.
 *
 * This test isolates the branch behavior to determine if the bug is in
 * how branches handle input data across multiple forward passes.
 */
public class BranchInputIsolationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testBranchReceivesDifferentInputs() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/branch_input_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  Branch Input Isolation Test");
		log("=".repeat(70) + "\n");

		int dim = 4;
		int seqLen = 5;

		// External cache to capture branch output
		PackedCollection branchCache = new PackedCollection(shape(seqLen, dim));
		branchCache.clear();

		// Position collection
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Weights for dense layers (identity matrix)
		PackedCollection identityWeights = new PackedCollection(shape(dim, dim));
		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				identityWeights.setMem(i * dim + j, i == j ? 1.0 : 0.0);
			}
		}

		log("Building model with branch that writes to external cache...\n");

		// Model structure similar to KVCacheAccumulationTest
		Model model = new Model(shape(dim));
		SequentialBlock main = new SequentialBlock(shape(dim));

		// RMSNorm first (to match KVCacheAccumulationTest structure)
		PackedCollection rmsWeight = new PackedCollection(dim);
		for (int i = 0; i < dim; i++) rmsWeight.setMem(i, 1.0);
		main.add(rmsnorm(shape(dim), rmsWeight, 1e-6));

		// Branch: dense -> write to cache
		SequentialBlock branch = main.branch();
		branch.add(dense(identityWeights));
		branch.andThen(into(branchCache, p(position)));

		// Main path: dense (so we have something after the branch point)
		main.add(dense(identityWeights));

		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create different inputs for each step
		PackedCollection[] inputs = new PackedCollection[3];
		for (int i = 0; i < 3; i++) {
			inputs[i] = new PackedCollection(shape(dim));
			for (int j = 0; j < dim; j++) {
				inputs[i].setMem(j, (i + 1) * 10.0 + j);  // Step 0: [10,11,12,13], Step 1: [20,21,22,23], etc.
			}
		}

		log("Inputs:");
		for (int i = 0; i < 3; i++) {
			log(String.format("  Step %d: %s", i, formatArray(inputs[i])));
		}

		log("\n" + "=".repeat(50));
		log("Running 3 forward passes");
		log("=".repeat(50) + "\n");

		for (int step = 0; step < 3; step++) {
			position.setMem(0, (double) step);

			log(String.format("--- Step %d (position=%d) ---", step, step));
			log("Input: " + formatArray(inputs[step]));

			log("Cache BEFORE forward:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(branchCache, row, dim)));
			}

			PackedCollection output = compiled.forward(inputs[step]);

			log("Cache AFTER forward:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(branchCache, row, dim)));
			}

			// Verify branch wrote correct data
			boolean correct = true;
			for (int j = 0; j < dim; j++) {
				double expected = (step + 1) * 10.0 + j;
				double actual = branchCache.toDouble(step * dim + j);
				if (Math.abs(expected - actual) > 0.001) {
					correct = false;
					log(String.format("  ERROR: Expected %.1f, got %.4f at position %d", expected, actual, j));
				}
			}

			if (correct) {
				log("  Branch write: [CORRECT] - Branch received step " + step + " input");
			} else {
				log("  Branch write: [INCORRECT] - Branch did NOT receive step " + step + " input!");
			}

			log("");
		}

		log("=".repeat(50));
		log("Final Cache Analysis");
		log("=".repeat(50) + "\n");

		log("Full cache state:");
		for (int row = 0; row < 3; row++) {
			log(String.format("  Row %d: %s", row, formatCacheRow(branchCache, row, dim)));
		}

		// Check if all rows are identical (the bug symptom)
		boolean allIdentical = true;
		for (int row = 1; row < 3; row++) {
			for (int j = 0; j < dim; j++) {
				if (Math.abs(branchCache.toDouble(row * dim + j) - branchCache.toDouble(j)) > 0.001) {
					allIdentical = false;
					break;
				}
			}
		}

		log("\n");
		if (allIdentical) {
			log("[BUG CONFIRMED] All cache rows are IDENTICAL!");
			log("The branch is NOT receiving updated input on each forward pass.");
			log("This is the root cause of the KV cache bug.");
		} else {
			log("[WORKING] Cache rows are different as expected.");
			log("The branch IS receiving updated input on each forward pass.");
		}

		log("\n=== Test Complete ===");
	}

	@Test
	public void testBranchWithDenseLayer() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/branch_dense_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  Branch with Dense Layer Test");
		log("=".repeat(70) + "\n");

		int dim = 4;
		int seqLen = 5;

		// External cache
		PackedCollection branchCache = new PackedCollection(shape(seqLen, dim));
		branchCache.clear();

		// Position collection
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Simple weight matrix (identity-like)
		PackedCollection weights = new PackedCollection(shape(dim, dim));
		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				weights.setMem(i * dim + j, i == j ? 1.0 : 0.0);  // Identity matrix
			}
		}

		// RMS weight
		PackedCollection rmsWeight = new PackedCollection(dim);
		for (int i = 0; i < dim; i++) rmsWeight.setMem(i, 1.0);

		log("Building model with branch containing dense layer...\n");

		Model model = new Model(shape(dim));
		SequentialBlock main = new SequentialBlock(shape(dim));

		// RMSNorm first
		main.add(rmsnorm(shape(dim), rmsWeight, 1e-6));

		// Branch: dense -> write to cache
		SequentialBlock branch = main.branch();
		branch.add(dense(weights));
		branch.andThen(into(branchCache, p(position)));

		// Main: dense
		main.add(dense(weights));

		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Different inputs
		PackedCollection[] inputs = new PackedCollection[3];
		for (int i = 0; i < 3; i++) {
			inputs[i] = new PackedCollection(shape(dim));
			for (int j = 0; j < dim; j++) {
				inputs[i].setMem(j, (i + 1) * 10.0 + j);
			}
		}

		log("Running 3 forward passes with different inputs...\n");

		for (int step = 0; step < 3; step++) {
			position.setMem(0, (double) step);

			log(String.format("Step %d: Input=%s", step, formatArray(inputs[step])));
			compiled.forward(inputs[step]);
			log(String.format("  Cache row %d: %s", step, formatCacheRow(branchCache, step, dim)));
		}

		log("\nFinal analysis:");
		boolean allIdentical = true;
		for (int row = 1; row < 3; row++) {
			for (int j = 0; j < dim; j++) {
				if (Math.abs(branchCache.toDouble(row * dim + j) - branchCache.toDouble(j)) > 0.001) {
					allIdentical = false;
					break;
				}
			}
		}

		if (allIdentical) {
			log("[BUG] All rows identical - dense branch not getting updated input");
		} else {
			log("[OK] Rows differ - dense branch receives updated input correctly");
		}

		log("\n=== Test Complete ===");
	}

	private String formatArray(PackedCollection c) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < c.getShape().getTotalSize(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.2f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatCacheRow(PackedCollection cache, int row, int cols) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < cols; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.2f", cache.toDouble(row * cols + i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
