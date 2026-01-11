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
 * Test with TWO branches writing to separate caches to see if that's where the bug is.
 */
public class TwoBranchTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testTwoBranchesReceiveDifferentInputs() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/two_branch_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  Two Branch Test");
		log("=".repeat(70) + "\n");

		int dim = 4;
		int seqLen = 5;

		// Two external caches
		PackedCollection cache1 = new PackedCollection(shape(seqLen, dim));
		PackedCollection cache2 = new PackedCollection(shape(seqLen, dim));
		cache1.clear();
		cache2.clear();

		// Position
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Two different weight matrices to distinguish branches
		PackedCollection weights1 = new PackedCollection(shape(dim, dim));
		PackedCollection weights2 = new PackedCollection(shape(dim, dim));
		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				weights1.setMem(i * dim + j, i == j ? 1.0 : 0.0);  // Identity
				weights2.setMem(i * dim + j, i == j ? 2.0 : 0.0);  // 2x Identity (doubles values)
			}
		}

		// RMS weight
		PackedCollection rmsWeight = new PackedCollection(dim);
		for (int i = 0; i < dim; i++) rmsWeight.setMem(i, 1.0);

		// Main weight (identity)
		PackedCollection mainWeights = new PackedCollection(shape(dim, dim));
		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				mainWeights.setMem(i * dim + j, i == j ? 1.0 : 0.0);
			}
		}

		log("Building model with TWO branches...\n");

		Model model = new Model(shape(dim));
		SequentialBlock main = new SequentialBlock(shape(dim));

		// RMSNorm
		main.add(rmsnorm(shape(dim), rmsWeight, 1e-6));

		// Branch 1: dense(weights1) -> write to cache1
		SequentialBlock branch1 = main.branch();
		branch1.add(dense(weights1));
		branch1.andThen(into(cache1, p(position)));

		// Branch 2: dense(weights2) -> write to cache2
		SequentialBlock branch2 = main.branch();
		branch2.add(dense(weights2));
		branch2.andThen(into(cache2, p(position)));

		// Main: dense
		main.add(dense(mainWeights));

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

		log("Running 3 forward passes...\n");

		for (int step = 0; step < 3; step++) {
			position.setMem(0, (double) step);

			log(String.format("--- Step %d ---", step));
			log("Input: " + formatArray(inputs[step]));

			compiled.forward(inputs[step]);

			log(String.format("Cache1 row %d: %s", step, formatCacheRow(cache1, step, dim)));
			log(String.format("Cache2 row %d: %s (should be 2x Cache1)", step, formatCacheRow(cache2, step, dim)));
			log("");
		}

		log("=".repeat(50));
		log("Final Analysis");
		log("=".repeat(50) + "\n");

		log("Cache1 (identity weights):");
		for (int row = 0; row < 3; row++) {
			log(String.format("  Row %d: %s", row, formatCacheRow(cache1, row, dim)));
		}

		log("\nCache2 (2x weights - should be 2x Cache1):");
		for (int row = 0; row < 3; row++) {
			log(String.format("  Row %d: %s", row, formatCacheRow(cache2, row, dim)));
		}

		// Check if cache1 rows are all identical (bug symptom)
		boolean cache1AllSame = true;
		boolean cache2AllSame = true;
		for (int row = 1; row < 3; row++) {
			for (int j = 0; j < dim; j++) {
				if (Math.abs(cache1.toDouble(row * dim + j) - cache1.toDouble(j)) > 0.001) {
					cache1AllSame = false;
				}
				if (Math.abs(cache2.toDouble(row * dim + j) - cache2.toDouble(j)) > 0.001) {
					cache2AllSame = false;
				}
			}
		}

		log("\n");
		if (cache1AllSame) {
			log("[BUG] Cache1 rows ALL IDENTICAL - branch1 not receiving different inputs");
		} else {
			log("[OK] Cache1 rows differ");
		}

		if (cache2AllSame) {
			log("[BUG] Cache2 rows ALL IDENTICAL - branch2 not receiving different inputs");
		} else {
			log("[OK] Cache2 rows differ");
		}

		// Check if cache2 is 2x cache1
		boolean cache2Is2xCache1 = true;
		for (int row = 0; row < 3; row++) {
			for (int j = 0; j < dim; j++) {
				double v1 = cache1.toDouble(row * dim + j);
				double v2 = cache2.toDouble(row * dim + j);
				if (Math.abs(v2 - 2 * v1) > 0.001) {
					cache2Is2xCache1 = false;
					break;
				}
			}
		}

		if (cache2Is2xCache1) {
			log("[OK] Cache2 = 2 * Cache1 as expected (both branches process same input)");
		} else {
			log("[BUG] Cache2 != 2 * Cache1 (branches processing different inputs?)");
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
			sb.append(String.format("%.4f", cache.toDouble(row * cols + i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
