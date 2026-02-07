package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Minimal test to verify branching and caching work correctly together.
 */
public class BranchCacheTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testSimpleBranchWithCache() throws Exception {
		String logFile = "/workspace/project/common/ml/results/branch_cache_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  SIMPLE BRANCH WITH CACHE TEST");
		log("=".repeat(70) + "\n");

		int dim = 8;
		int seqLen = 4;

		// Create a cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Create position indicator
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Create a simple block with a branch that writes to cache
		SequentialBlock block = new SequentialBlock(shape(1, dim));

		// Create a branch that writes input to cache
		SequentialBlock branch = block.branch();
		branch.andThen(into(cache, p(position)));

		// Main path just passes through (identity)
		block.add(layer("identity", shape(1, dim), shape(1, dim), input -> c(input)));

		// Create test input
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, (i + 1) * 1.0);  // 1, 2, 3, 4, 5, 6, 7, 8
		}

		log("Input: " + formatFirst(input, dim));
		log("Cache before: " + formatFirst(cache, dim));

		// Run the model
		Model model = new Model(shape(1, dim));
		model.add(block);
		PackedCollection output = model.compile().forward(input);

		log("Cache after: " + formatFirst(cache, dim));
		log("Output: " + formatFirst(output, dim));

		// Verify: cache should contain input at position 0
		log("\n=== Verification ===");
		boolean pass = true;
		for (int i = 0; i < dim; i++) {
			double expected = (i + 1) * 1.0;
			double actual = cache.toDouble(i);
			if (Math.abs(actual - expected) > 1e-6) {
				log(String.format("  CACHE[%d] MISMATCH: expected=%.4f, actual=%.4f", i, expected, actual));
				pass = false;
			}
		}

		// Verify output equals input (identity)
		for (int i = 0; i < dim; i++) {
			double expected = (i + 1) * 1.0;
			double actual = output.toDouble(i);
			if (Math.abs(actual - expected) > 1e-6) {
				log(String.format("  OUTPUT[%d] MISMATCH: expected=%.4f, actual=%.4f", i, expected, actual));
				pass = false;
			}
		}

		log(pass ? "  [PASS] Branch and cache work correctly" : "  [FAIL] Branch or cache has issues");
	}

	@Test
	public void testBranchWithTransformAndCache() throws Exception {
		String logFile = "/workspace/project/common/ml/results/branch_transform_cache_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  BRANCH WITH TRANSFORM AND CACHE TEST");
		log("=".repeat(70) + "\n");

		int dim = 8;
		int seqLen = 4;

		// Create a cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Create position indicator
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Create a simple block with a branch that transforms and writes to cache
		SequentialBlock block = new SequentialBlock(shape(1, dim));

		// Create a branch that multiplies by 2 and writes to cache
		SequentialBlock branch = block.branch();
		branch.add(layer("multiply", shape(1, dim), shape(1, dim),
				input -> multiply(c(input), c(2.0))));
		branch.andThen(into(cache, p(position)));

		// Main path just passes through (identity)
		block.add(layer("identity", shape(1, dim), shape(1, dim), input -> c(input)));

		// Create test input
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, (i + 1) * 1.0);  // 1, 2, 3, 4, 5, 6, 7, 8
		}

		log("Input: " + formatFirst(input, dim));
		log("Expected cache (input * 2): " + formatExpected(dim, 2.0));
		log("Cache before: " + formatFirst(cache, dim));

		// Run the model
		Model model = new Model(shape(1, dim));
		model.add(block);
		PackedCollection output = model.compile().forward(input);

		log("Cache after: " + formatFirst(cache, dim));
		log("Output: " + formatFirst(output, dim));

		// Verify: cache should contain input * 2
		log("\n=== Verification ===");
		boolean pass = true;
		for (int i = 0; i < dim; i++) {
			double expected = (i + 1) * 2.0;  // Input * 2
			double actualCache = cache.toDouble(i);
			if (Math.abs(actualCache - expected) > 1e-6) {
				log(String.format("  CACHE[%d] MISMATCH: expected=%.4f, actual=%.4f", i, expected, actualCache));
				pass = false;
			}
		}

		// Output should equal input (identity)
		for (int i = 0; i < dim; i++) {
			double expected = (i + 1) * 1.0;
			double actual = output.toDouble(i);
			if (Math.abs(actual - expected) > 1e-6) {
				log(String.format("  OUTPUT[%d] MISMATCH: expected=%.4f, actual=%.4f", i, expected, actual));
				pass = false;
			}
		}

		log(pass ? "  [PASS] Branch, transform, and cache work correctly" : "  [FAIL] Something is wrong");
	}

	@Test
	public void testTwoBranchesWithCaches() throws Exception {
		String logFile = "/workspace/project/common/ml/results/two_branches_cache_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  TWO BRANCHES WITH CACHES TEST");
		log("=".repeat(70) + "\n");

		int dim = 8;
		int seqLen = 4;

		// Create two caches (like K and V caches)
		PackedCollection cache1 = new PackedCollection(shape(seqLen, dim));
		PackedCollection cache2 = new PackedCollection(shape(seqLen, dim));
		cache1.clear();
		cache2.clear();

		// Create position indicator
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Create a block with two branches (like K and V paths)
		SequentialBlock block = new SequentialBlock(shape(1, dim));

		// Branch 1: multiply by 2 and write to cache1
		SequentialBlock branch1 = block.branch();
		branch1.add(layer("multiply2", shape(1, dim), shape(1, dim),
				input -> multiply(c(input), c(2.0))));
		branch1.andThen(into(cache1, p(position)));

		// Branch 2: multiply by 3 and write to cache2
		SequentialBlock branch2 = block.branch();
		branch2.add(layer("multiply3", shape(1, dim), shape(1, dim),
				input -> multiply(c(input), c(3.0))));
		branch2.andThen(into(cache2, p(position)));

		// Main path just passes through
		block.add(layer("identity", shape(1, dim), shape(1, dim), input -> c(input)));

		// Create test input
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, (i + 1) * 1.0);  // 1, 2, 3, 4, 5, 6, 7, 8
		}

		log("Input: " + formatFirst(input, dim));
		log("Expected cache1 (input * 2): " + formatExpected(dim, 2.0));
		log("Expected cache2 (input * 3): " + formatExpected(dim, 3.0));

		// Run the model
		Model model = new Model(shape(1, dim));
		model.add(block);
		PackedCollection output = model.compile().forward(input);

		log("\nAfter execution:");
		log("Cache1: " + formatFirst(cache1, dim));
		log("Cache2: " + formatFirst(cache2, dim));
		log("Output: " + formatFirst(output, dim));

		// Verify
		log("\n=== Verification ===");
		boolean pass = true;

		// Check cache1
		for (int i = 0; i < dim; i++) {
			double expected = (i + 1) * 2.0;
			double actual = cache1.toDouble(i);
			if (Math.abs(actual - expected) > 1e-6) {
				log(String.format("  CACHE1[%d] MISMATCH: expected=%.4f, actual=%.4f", i, expected, actual));
				pass = false;
			}
		}

		// Check cache2
		for (int i = 0; i < dim; i++) {
			double expected = (i + 1) * 3.0;
			double actual = cache2.toDouble(i);
			if (Math.abs(actual - expected) > 1e-6) {
				log(String.format("  CACHE2[%d] MISMATCH: expected=%.4f, actual=%.4f", i, expected, actual));
				pass = false;
			}
		}

		log(pass ? "  [PASS] Two branches with caches work correctly" : "  [FAIL] Something is wrong");
	}

	private String formatFirst(PackedCollection c, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(n, c.getShape().getTotalSize()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.1f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatExpected(int dim, double factor) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < dim; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.1f", (i + 1) * factor));
		}
		sb.append("]");
		return sb.toString();
	}
}
