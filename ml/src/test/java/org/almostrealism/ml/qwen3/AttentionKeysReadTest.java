package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test to verify that attentionKeys reads from an external cache dynamically.
 *
 * The test:
 * 1. Creates external key cache
 * 2. Modifies cache between forward passes
 * 3. Verifies that attentionKeys output changes when cache changes
 */
public class AttentionKeysReadTest extends TestSuiteBase implements AttentionFeatures, LayerFeatures, ConsoleFeatures {

	@Test
	public void testAttentionKeysReadsFromCache() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/attention_keys_read_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  AttentionKeys Cache Read Test");
		log("===================================================\n");

		int heads = 2;
		int headSize = 4;
		int seqLen = 3;
		int kvHeads = 2;

		// External key cache - we'll modify this between forward passes
		PackedCollection keyCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		keyCache.clear();

		// Create model that uses attentionKeys to read from cache
		Model model = new Model(shape(heads, headSize));
		model.add(attentionKeys(shape(heads, headSize), p(keyCache)));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Fixed query input
		PackedCollection query = new PackedCollection(shape(heads, headSize));
		for (int i = 0; i < heads * headSize; i++) {
			query.setMem(i, 1.0);
		}

		log("Query: all 1.0s");
		log("");

		// Test 1: Empty cache
		log("=== Test 1: Empty Cache ===");
		PackedCollection output1 = compiled.forward(query);
		log("Cache state: all zeros");
		log("Output: " + formatArray(output1, 0, (int)output1.getShape().getTotalSize()));
		log("");

		// Test 2: Modify cache and run again
		log("=== Test 2: Modified Cache (row 0 = 1.0s) ===");
		for (int i = 0; i < kvHeads * headSize; i++) {
			keyCache.setMem(i, 1.0);  // Set row 0 to 1.0s
		}
		PackedCollection output2 = compiled.forward(query);
		log("Cache row 0: all 1.0s");
		log("Output: " + formatArray(output2, 0, (int)output2.getShape().getTotalSize()));
		log("");

		// Test 3: Modify cache again
		log("=== Test 3: Modified Cache (row 0 = 2.0s) ===");
		for (int i = 0; i < kvHeads * headSize; i++) {
			keyCache.setMem(i, 2.0);  // Set row 0 to 2.0s
		}
		PackedCollection output3 = compiled.forward(query);
		log("Cache row 0: all 2.0s");
		log("Output: " + formatArray(output3, 0, (int)output3.getShape().getTotalSize()));
		log("");

		// Compare outputs
		log("=== Analysis ===");
		boolean output1vs2Same = arraysEqual(output1, output2);
		boolean output2vs3Same = arraysEqual(output2, output3);

		log(String.format("Output 1 vs 2: %s", output1vs2Same ? "IDENTICAL" : "DIFFERENT"));
		log(String.format("Output 2 vs 3: %s", output2vs3Same ? "IDENTICAL" : "DIFFERENT"));

		if (output1vs2Same && output2vs3Same) {
			log("\n[FAIL] attentionKeys is NOT reading cache dynamically!");
			log("The cache contents change but output remains the same.");
		} else {
			log("\n[PASS] attentionKeys IS reading cache dynamically!");
		}

		log("\n=== Test Complete ===");
	}

	@Test
	public void testSimpleCacheRead() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/simple_cache_read_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Simple Cache Read Test");
		log("===================================================\n");

		int dim = 4;
		int seqLen = 3;

		// External cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Create model that simply reads from cache and outputs sum
		Model model = new Model(shape(dim));
		model.add(layer("readCache", shape(dim), shape(dim),
				input -> {
					// Read entire cache and sum rows
					CollectionProducer cacheProducer = c(p(cache));
					return cacheProducer.traverse(1).sum().reshape(dim);
				}));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		PackedCollection input = new PackedCollection(shape(dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 1.0);
		}

		// Test 1: Empty cache
		log("=== Test 1: Empty Cache ===");
		PackedCollection output1 = compiled.forward(input);
		log("Output: " + formatArray(output1, 0, dim));

		// Test 2: Fill cache row 0
		log("\n=== Test 2: Cache row 0 = [1,2,3,4] ===");
		for (int i = 0; i < dim; i++) {
			cache.setMem(i, i + 1);
		}
		PackedCollection output2 = compiled.forward(input);
		log("Output: " + formatArray(output2, 0, dim));

		// Test 3: Modify cache
		log("\n=== Test 3: Cache row 0 = [10,20,30,40] ===");
		for (int i = 0; i < dim; i++) {
			cache.setMem(i, (i + 1) * 10);
		}
		PackedCollection output3 = compiled.forward(input);
		log("Output: " + formatArray(output3, 0, dim));

		log("\n=== Analysis ===");
		boolean same12 = arraysEqual(output1, output2);
		boolean same23 = arraysEqual(output2, output3);

		if (same12 && same23) {
			log("[FAIL] Cache reads are NOT dynamic!");
		} else {
			log("[PASS] Cache reads ARE dynamic!");
		}

		log("\n=== Test Complete ===");
	}

	private String formatArray(PackedCollection c, int start, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = start; i < start + count && i < c.getShape().getTotalSize(); i++) {
			if (i > start) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private boolean arraysEqual(PackedCollection a, PackedCollection b) {
		if (a.getShape().getTotalSize() != b.getShape().getTotalSize()) return false;
		for (int i = 0; i < a.getShape().getTotalSize(); i++) {
			if (Math.abs(a.toDouble(i) - b.toDouble(i)) > 1e-6) return false;
		}
		return true;
	}
}
