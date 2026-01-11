package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.CollectionReceptor;
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
 * Test to verify KV cache writes respect dynamic position updates.
 *
 * The attention mechanism uses `into(cache, position)` to write K/V at the current position.
 * This test verifies that different position values cause writes to different cache locations.
 */
public class KVCacheWritePositionTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testKVCacheWriteWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/kv_cache_write_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  KV Cache Write Position Test");
		log("===================================================\n");

		int seqLen = 5;
		int dim = 4;

		// Create KV cache - must be 2D for position-based writes
		PackedCollection keyCache = new PackedCollection(shape(seqLen, dim));
		keyCache.fill(0.0);

		// Create position collection
		PackedCollection position = new PackedCollection(1);

		log("=== Test 1: Direct CollectionReceptor Usage ===");
		log("");

		// Test 1: Direct CollectionReceptor usage (bypassing compiled model)
		for (int pos = 0; pos < seqLen; pos++) {
			position.setMem(0, (double) pos);

			// Create input with distinct values
			PackedCollection input = new PackedCollection(shape(dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, (pos + 1) * 10 + i);  // Values: 10-13, 20-23, 30-33, ...
			}

			// Create receptor with position
			CollectionReceptor receptor = into(keyCache, p(position));

			// Push input to cache (this should write to position)
			// Use p(input) to wrap the PackedCollection as a Producer
			Runnable push = receptor.push(p(input)).get();
			push.run();

			log(String.format("Position %d: Wrote [%.0f, %.0f, %.0f, %.0f]",
					pos, input.toDouble(0), input.toDouble(1), input.toDouble(2), input.toDouble(3)));
		}

		log("");
		log("Cache Contents After Direct Writes:");
		boolean allCorrect = true;
		for (int row = 0; row < seqLen; row++) {
			double[] expected = new double[dim];
			double[] actual = new double[dim];
			for (int col = 0; col < dim; col++) {
				expected[col] = (row + 1) * 10 + col;
				actual[col] = keyCache.toDouble(row * dim + col);
			}

			boolean rowCorrect = true;
			for (int col = 0; col < dim; col++) {
				if (Math.abs(actual[col] - expected[col]) > 0.001) {
					allCorrect = false;
					rowCorrect = false;
				}
			}

			log(String.format("  Row %d: [%.0f, %.0f, %.0f, %.0f] %s",
					row, actual[0], actual[1], actual[2], actual[3],
					rowCorrect ? "[OK]" : "[WRONG]"));
		}

		log("");
		if (allCorrect) {
			log("[PASS] Direct CollectionReceptor writes work correctly!");
		} else {
			log("[FAIL] Direct CollectionReceptor writes are NOT working!");
		}

		// Test 2: Using andThen pattern like attention does
		log("");
		log("=== Test 2: Using andThen() Pattern (like attention) ===");
		log("");

		// Reset cache
		keyCache.fill(0.0);
		position.setMem(0, 0.0);

		// Build model with andThen pattern
		SequentialBlock block = new SequentialBlock(shape(dim));

		// Simple passthrough layer
		block.add(layer("passthrough", shape(dim), shape(dim), input -> input));

		// Add cache write via andThen
		block.andThen(into(keyCache, p(position)));

		log("Compiling model...");
		Model model = new Model(shape(dim));
		model.add(block);
		CompiledModel compiled = model.compile();

		for (int pos = 0; pos < seqLen; pos++) {
			position.setMem(0, (double) pos);

			PackedCollection input = new PackedCollection(shape(dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, (pos + 1) * 100 + i);  // Values: 100-103, 200-203, ...
			}

			compiled.forward(input);

			log(String.format("Position %d: Input [%.0f, %.0f, %.0f, %.0f]",
					pos, input.toDouble(0), input.toDouble(1), input.toDouble(2), input.toDouble(3)));
		}

		log("");
		log("Cache Contents After Compiled Model Writes:");
		allCorrect = true;
		for (int row = 0; row < seqLen; row++) {
			double[] expected = new double[dim];
			double[] actual = new double[dim];
			for (int col = 0; col < dim; col++) {
				expected[col] = (row + 1) * 100 + col;
				actual[col] = keyCache.toDouble(row * dim + col);
			}

			boolean rowCorrect = true;
			for (int col = 0; col < dim; col++) {
				if (Math.abs(actual[col] - expected[col]) > 0.001) {
					allCorrect = false;
					rowCorrect = false;
				}
			}

			log(String.format("  Row %d: [%.0f, %.0f, %.0f, %.0f] (expected [%.0f, %.0f, %.0f, %.0f]) %s",
					row, actual[0], actual[1], actual[2], actual[3],
					expected[0], expected[1], expected[2], expected[3],
					rowCorrect ? "[OK]" : "[WRONG]"));
		}

		log("");
		if (allCorrect) {
			log("[PASS] Compiled model cache writes (andThen pattern) work correctly!");
		} else {
			log("[FAIL] Compiled model cache writes are NOT working - position may be baked in!");
			log("This indicates the CollectionReceptor position producer is being evaluated at compile time.");
		}

		log("\n=== Test Complete ===");
	}
}
