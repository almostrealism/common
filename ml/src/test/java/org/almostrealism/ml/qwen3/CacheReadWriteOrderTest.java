package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test to verify cache read/write ordering in a branch pattern like attention uses.
 *
 * This tests whether:
 * 1. Branch writes to cache BEFORE main reads from cache
 * 2. The cache contents at position are available for the main path read
 */
public class CacheReadWriteOrderTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testCacheReadAfterBranchWrite() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/cache_read_write_order.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Cache Read/Write Order Test");
		log("===================================================\n");

		int seqLen = 10;
		int dim = 4;

		// Create cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.fill(0.0);

		// Create position
		PackedCollection position = new PackedCollection(2);

		// Create model that:
		// 1. Branch: writes input + position-based offset to cache at position
		// 2. Main: reads from cache and outputs
		Model model = new Model(shape(dim));

		SequentialBlock main = new SequentialBlock(shape(dim));

		// Branch: processes input and writes to cache
		SequentialBlock branch = main.branch();
		branch.add(layer("branchProcess", shape(dim), shape(dim),
				input -> {
					// Add a position-dependent value to input
					// This way we can verify what was written to cache
					// position is shape (2), we need first element only, then fill dim values
					CollectionProducer posVal = subset(shape(1), c(p(position)), 0).repeat(dim).reshape(dim);
					return add(input, multiply(posVal, scalar(10.0)));  // input + pos * 10
				}));

		// Write to cache at position
		branch.andThen(into(cache, p(position)));

		// Main: reads from cache (all rows) and selects at position
		main.add(layer("mainRead", shape(dim), shape(dim),
				input -> {
					// Read from cache at current position
					CollectionProducer cacheRow = subset(shape(1, dim), c(p(cache)), p(position)).reshape(dim);
					return cacheRow;  // Output what was read from cache at position
				}));

		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Input: all 1s
		PackedCollection input = new PackedCollection(shape(dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 1.0);
		}

		log("Input: [1.0, 1.0, 1.0, 1.0]");
		log("");

		// Test at different positions
		log("=== Testing Cache Read After Branch Write ===");
		log("");

		double[][] outputs = new double[5][dim];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			position.setMem(1, 0.0);

			PackedCollection output = compiled.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Position %d: Output = [", pos));
			for (int i = 0; i < dim; i++) {
				outputs[pos][i] = output.toDouble(i);
				if (i > 0) sb.append(", ");
				sb.append(String.format("%.1f", outputs[pos][i]));
			}
			sb.append("]");

			// Expected: input + pos * 10 = 1 + pos * 10 for each element
			double expected = 1.0 + pos * 10.0;
			sb.append(String.format("  (expected: %.1f)", expected));

			log(sb.toString());
		}

		log("");
		log("=== Cache Contents ===");
		for (int row = 0; row < seqLen; row++) {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Row %d: [", row));
			for (int col = 0; col < dim; col++) {
				if (col > 0) sb.append(", ");
				sb.append(String.format("%.1f", cache.toDouble(row * dim + col)));
			}
			sb.append("]");
			log(sb.toString());
		}

		// Check results
		log("");
		boolean allCorrect = true;
		for (int pos = 0; pos < 5; pos++) {
			double expected = 1.0 + pos * 10.0;
			if (Math.abs(outputs[pos][0] - expected) > 0.1) {
				log(String.format("[FAIL] Position %d: expected %.1f, got %.1f", pos, expected, outputs[pos][0]));
				allCorrect = false;
			}
		}

		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			for (int i = 0; i < dim; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 0.001) {
					allSame = false;
					break;
				}
			}
		}

		log("");
		if (allSame) {
			log("[FAIL] All outputs identical - cache read/write order issue!");
			log("The main path may be reading from cache BEFORE the branch writes.");
		} else if (allCorrect) {
			log("[PASS] Cache read/write order is correct!");
		} else {
			log("[PARTIAL] Outputs differ but don't match expected values.");
		}

		log("\n=== Test Complete ===");
	}
}
