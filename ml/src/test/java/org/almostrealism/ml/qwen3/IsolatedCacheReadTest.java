package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test to isolate cache read/write coherence in compiled operations.
 *
 * This test creates a minimal scenario:
 * 1. Create a cache
 * 2. Write known values to cache
 * 3. Compile an operation that reads from cache
 * 4. Execute and verify the values
 */
public class IsolatedCacheReadTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testBasicCacheReadWrite() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/isolated_cache_read.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Isolated Cache Read/Write Test");
		log("===================================================\n");

		int seqLen = 4;
		int kvDim = 128;  // Same as Qwen: 2 kv heads * 64 head size

		// Create cache
		PackedCollection cache = new PackedCollection(shape(seqLen, kvDim));
		cache.clear();

		log("Cache shape: " + cache.getShape());
		log("Cache memory length: " + cache.getMemLength());

		// Create position holder
		PackedCollection position = new PackedCollection(1);

		// Create a simple operation that reads from cache directly
		Producer<PackedCollection> cacheProducer = p(cache);

		// Simple direct read (no enumerate transformation)
		CollectionProducer directRead = c(cacheProducer);

		// === Test 1: Write to position 0, read back directly ===
		log("\n=== Test 1: Direct cache read after write ===");

		// Write known pattern to position 0
		for (int i = 0; i < kvDim; i++) {
			cache.setMem(0 * kvDim + i, 1.0 + i * 0.01);  // Pattern: 1.00, 1.01, 1.02, ...
		}
		log("Wrote pattern to position 0");
		log("Cache[0, 0-4]: " + formatCache(cache, 0, 5));

		// Read back via producer evaluation
		PackedCollection result = directRead.get().evaluate();
		log("Direct read result shape: " + result.getShape());
		log("Direct read[0, 0-4]: " + formatResult(result, 0, 5));

		// Verify position 0 values
		double directDiff = 0;
		for (int i = 0; i < 5; i++) {
			double expected = 1.0 + i * 0.01;
			double actual = result.toDouble(i);
			directDiff = Math.max(directDiff, Math.abs(expected - actual));
		}
		log("Direct read max diff: " + String.format("%.6f", directDiff));

		// === Test 2: Write to position 1, verify both positions via direct read ===
		log("\n=== Test 2: Write to position 1, direct read ===");

		// Write different pattern to position 1
		for (int i = 0; i < kvDim; i++) {
			cache.setMem(1 * kvDim + i, 2.0 + i * 0.01);  // Pattern: 2.00, 2.01, 2.02, ...
		}
		log("Wrote pattern to position 1");
		log("Cache[0, 0-4]: " + formatCache(cache, 0, 5));
		log("Cache[1, 0-4]: " + formatCache(cache, kvDim, 5));

		// Read via direct producer again
		result = directRead.get().evaluate();

		// Verify position 0 values (should still be there)
		log("\nVerifying position 0 via direct read:");
		log("Direct read[0, 0-4]: " + formatResult(result, 0, 5));
		double pos0Diff = 0;
		for (int i = 0; i < 5; i++) {
			double expected = 1.0 + i * 0.01;
			double actual = result.toDouble(i);
			pos0Diff = Math.max(pos0Diff, Math.abs(expected - actual));
		}
		log("Position 0 max diff: " + String.format("%.6f", pos0Diff));

		// Verify position 1 values
		log("\nVerifying position 1 via direct read:");
		log("Direct read[1, 0-4]: " + formatResult(result, kvDim, 5));
		double pos1Diff = 0;
		for (int i = 0; i < 5; i++) {
			double expected = 2.0 + i * 0.01;
			double actual = result.toDouble(kvDim + i);
			pos1Diff = Math.max(pos1Diff, Math.abs(expected - actual));
		}
		log("Position 1 max diff: " + String.format("%.6f", pos1Diff));

		// === Test 3: Test what enumerate does ===
		log("\n=== Test 3: Enumerate transformation ===");

		// Build expression that reads from cache with enumerate
		CollectionProducer enumRead = enumerate(1, 1, cacheProducer);
		log("Enumerate output shape: " + shape(enumRead));

		// Evaluate
		PackedCollection enumResult = enumRead.get().evaluate();
		log("Enumerate result shape: " + enumResult.getShape());
		log("Enumerate result[0, 0-4]: " + formatResult(enumResult, 0, 5));

		// Compare with direct cache access
		log("\nComparing enumerate with direct access:");
		log("Direct[0-4]:    " + formatCache(cache, 0, 5));
		log("Enumerate[0-4]: " + formatResult(enumResult, 0, 5));

		log("\n=== Summary ===");
		boolean allPassed = directDiff < 0.0001 && pos0Diff < 0.0001 && pos1Diff < 0.0001;
		if (allPassed) {
			log("[OK] Direct cache read/write works correctly!");
		} else {
			log("[FAIL] Direct read tests failed:");
			if (directDiff >= 0.0001) log("  - Direct read failed");
			if (pos0Diff >= 0.0001) log("  - Position 0 read failed");
			if (pos1Diff >= 0.0001) log("  - Position 1 read failed");
		}

		log("\n=== Test Complete ===");
	}

	private String formatCache(PackedCollection cache, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", cache.toDouble(offset + i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatResult(PackedCollection result, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", result.toDouble(offset + i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
