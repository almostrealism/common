package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Critical test to determine if compiled operations capture cache values
 * at compile time or read from cache at runtime.
 *
 * This is the key test to identify if the issue is in expression embedding.
 */
public class CompileTimeVsRuntimeCacheTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testCompileTimeVsRuntimeAccess() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/compile_time_vs_runtime.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Compile-Time vs Runtime Cache Access Test");
		log("===================================================\n");

		int seqLen = 4;
		int kvDim = 128;

		// Create cache with all zeros initially
		PackedCollection cache = new PackedCollection(shape(seqLen, kvDim));
		cache.clear();

		log("Cache shape: " + cache.getShape());
		log("Initial cache[0, 0-4]: " + formatValues(cache, 0, 5));

		// Create producer that reads from cache
		Producer<PackedCollection> cacheProducer = p(cache);

		// === Test 1: Simple direct read, write AFTER creating producer ===
		log("\n=== Test 1: Direct read after producer creation ===");

		CollectionProducer directRead = c(cacheProducer);
		log("Created directRead producer");
		log("Cache is still zeros: " + formatValues(cache, 0, 5));

		// Now write to cache AFTER creating the producer
		for (int i = 0; i < 5; i++) {
			cache.setMem(i, 1.0 + i * 0.1);  // 1.0, 1.1, 1.2, 1.3, 1.4
		}
		log("Wrote values to cache: " + formatValues(cache, 0, 5));

		// Evaluate - should see the new values
		PackedCollection result1 = directRead.get().evaluate();
		log("directRead result[0-4]: " + formatValues(result1, 0, 5));

		double test1Diff = 0;
		for (int i = 0; i < 5; i++) {
			test1Diff = Math.max(test1Diff, Math.abs(result1.toDouble(i) - (1.0 + i * 0.1)));
		}
		log("Test 1 max diff: " + String.format("%.6f", test1Diff));
		log("Test 1: " + (test1Diff < 0.0001 ? "PASS - Runtime access" : "FAIL - Compile-time capture"));

		// === Test 2: With enumerate transformation, write AFTER creating producer ===
		log("\n=== Test 2: Enumerate transformation, write after producer creation ===");

		cache.clear();
		log("Cleared cache: " + formatValues(cache, 0, 5));

		CollectionProducer enumRead = enumerate(1, 1, cacheProducer).reshape(shape(seqLen, kvDim));
		log("Created enumRead producer with enumerate transformation");

		// Write AFTER creating the producer chain
		for (int i = 0; i < kvDim; i++) {
			cache.setMem(i, 2.0 + i * 0.01);
		}
		log("Wrote values to cache[0]: " + formatValues(cache, 0, 5));

		// Evaluate
		PackedCollection result2 = enumRead.get().evaluate();
		log("enumRead result shape: " + result2.getShape());
		// Due to enumerate transposition, first 5 values come from different rows
		log("enumRead result[0-4]: " + formatValues(result2, 0, 5));

		// === Test 3: Full GQA transformation chain, write AFTER creating producer ===
		log("\n=== Test 3: Full GQA transformation, write after producer creation ===");

		int seqLen3 = 2;
		int heads = 14;
		int kvHeads = 2;
		int headSize = 64;
		int kvDim3 = kvHeads * headSize;
		int headsPerKvGroup = heads / kvHeads;

		PackedCollection cache3 = new PackedCollection(shape(seqLen3, kvHeads, headSize));
		cache3.clear();

		Producer<PackedCollection> cacheProducer3 = p(cache3);

		// Build the full GQA + enumerate transformation chain
		CollectionProducer traversed3 = traverse(2, cacheProducer3);
		CollectionProducer repeated3 = traversed3.repeat(headsPerKvGroup);
		Producer<PackedCollection> expanded3 = reshape(shape(seqLen3, heads, headSize), repeated3);
		Producer<PackedCollection> reshaped3 = reshape(shape(seqLen3, heads * headSize), expanded3);
		CollectionProducer enumerated3 = enumerate(1, 1, reshaped3);
		CollectionProducer finalV3 = enumerated3.reshape(shape(heads, headSize, seqLen3));

		log("Created full transformation chain (GQA + enumerate + reshape)");
		log("Cache3 is still zeros");

		// Write AFTER creating the full transformation chain
		for (int i = 0; i < kvDim3; i++) {
			cache3.setMem(0 * kvDim3 + i, 3.0 + i * 0.001);  // Position 0
			cache3.setMem(1 * kvDim3 + i, 4.0 + i * 0.001);  // Position 1
		}
		log("Wrote to cache3:");
		log("  Pos 0, kv 0 [0-4]: " + formatValues(cache3, 0, 5));
		log("  Pos 1, kv 0 [0-4]: " + formatValues(cache3, kvDim3, 5));

		// Evaluate
		PackedCollection result3 = finalV3.get().evaluate();
		log("Full chain result shape: " + result3.getShape());

		// V[0, 0, 0] should be cache3[0, 0, 0] = 3.0
		// V[0, 0, 1] should be cache3[1, 0, 0] = 4.0
		double v_0_0_0 = result3.toDouble(0);
		double v_0_0_1 = result3.toDouble(1);
		log("V[0,0,0]: " + String.format("%.4f", v_0_0_0) + " (expected 3.0000)");
		log("V[0,0,1]: " + String.format("%.4f", v_0_0_1) + " (expected 4.0000)");

		double test3Diff = Math.max(Math.abs(v_0_0_0 - 3.0), Math.abs(v_0_0_1 - 4.0));
		log("Test 3 max diff: " + String.format("%.6f", test3Diff));
		log("Test 3: " + (test3Diff < 0.0001 ? "PASS - Runtime access" : "FAIL - Compile-time capture"));

		// === Test 4: Update cache and re-evaluate WITHOUT recreating producer ===
		log("\n=== Test 4: Update cache and re-evaluate ===");

		// Clear and write different values
		for (int i = 0; i < kvDim3; i++) {
			cache3.setMem(0 * kvDim3 + i, 5.0 + i * 0.001);  // Position 0 - new values
			cache3.setMem(1 * kvDim3 + i, 6.0 + i * 0.001);  // Position 1 - new values
		}
		log("Updated cache3 with new values:");
		log("  Pos 0, kv 0 [0-4]: " + formatValues(cache3, 0, 5));
		log("  Pos 1, kv 0 [0-4]: " + formatValues(cache3, kvDim3, 5));

		// Re-evaluate the SAME producer chain (not recreated)
		PackedCollection result4 = finalV3.get().evaluate();

		// Should now see the new values
		double v4_0_0_0 = result4.toDouble(0);
		double v4_0_0_1 = result4.toDouble(1);
		log("V[0,0,0] after update: " + String.format("%.4f", v4_0_0_0) + " (expected 5.0000)");
		log("V[0,0,1] after update: " + String.format("%.4f", v4_0_0_1) + " (expected 6.0000)");

		double test4Diff = Math.max(Math.abs(v4_0_0_0 - 5.0), Math.abs(v4_0_0_1 - 6.0));
		log("Test 4 max diff: " + String.format("%.6f", test4Diff));
		log("Test 4: " + (test4Diff < 0.0001 ? "PASS - Runtime access" : "FAIL - Compile-time capture"));

		log("\n=== Summary ===");
		if (test1Diff < 0.0001 && test3Diff < 0.0001 && test4Diff < 0.0001) {
			log("[OK] All tests pass - cache is accessed at RUNTIME, not captured at compile time");
			log("     The issue must be elsewhere in the attention block execution.");
		} else {
			log("[FAIL] Some tests failed - cache may be captured at COMPILE TIME");
			log("     This would explain the position 1 mismatch!");
		}

		log("\n=== Test Complete ===");
	}

	private String formatValues(PackedCollection c, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(offset + i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
