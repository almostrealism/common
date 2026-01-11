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
 * Test to verify the attentionValues transformation chain works correctly with cache.
 *
 * This mirrors the exact transformations done in AttentionFeatures.attentionValues
 * and AttentionFeatures.expandValuesForGQA to identify where values diverge.
 */
public class AttentionValuesTransformTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testAttentionValuesTransform() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/attention_values_transform.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Attention Values Transform Test");
		log("===================================================\n");

		// Use Qwen config values
		int seqLen = 2;  // Just 2 positions for simplicity
		int heads = 14;
		int kvHeads = 2;
		int headSize = 64;
		int dim = heads * headSize;  // 896
		int kvDim = kvHeads * headSize;  // 128
		int headsPerKvGroup = heads / kvHeads;  // 7

		log("Config:");
		log("  seqLen=" + seqLen);
		log("  heads=" + heads + ", kvHeads=" + kvHeads);
		log("  headSize=" + headSize + ", headsPerKvGroup=" + headsPerKvGroup);
		log("  dim=" + dim + ", kvDim=" + kvDim);

		// Create value cache with shape (seqLen, kvHeads, headSize)
		PackedCollection valueCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		valueCache.clear();

		log("\nValue cache shape: " + valueCache.getShape());
		log("Value cache total size: " + valueCache.getMemLength());

		// Write known values to cache
		// Position 0: pattern 1.0, 1.01, 1.02, ...
		// Position 1: pattern 2.0, 2.01, 2.02, ...
		log("\n=== Writing known patterns to cache ===");
		for (int i = 0; i < kvDim; i++) {
			valueCache.setMem(0 * kvDim + i, 1.0 + i * 0.001);  // Position 0
			valueCache.setMem(1 * kvDim + i, 2.0 + i * 0.001);  // Position 1
		}

		log("Cache pos 0, kv 0, head[0-4]: " + formatValues(valueCache, 0, 5));
		log("Cache pos 0, kv 1, head[0-4]: " + formatValues(valueCache, headSize, 5));
		log("Cache pos 1, kv 0, head[0-4]: " + formatValues(valueCache, kvDim, 5));
		log("Cache pos 1, kv 1, head[0-4]: " + formatValues(valueCache, kvDim + headSize, 5));

		Producer<PackedCollection> cacheProducer = p(valueCache);

		// === Step 1: GQA Expansion ===
		// expandValuesForGQA: (seqLen, kvHeads, headSize) -> (seqLen, heads, headSize)
		log("\n=== Step 1: GQA Expansion ===");

		// traverse(2, values) keeps (seqLen, kvHeads) and traverses headSize
		// repeat(headsPerKvGroup) repeats each headSize element 7 times
		CollectionProducer traversed = traverse(2, cacheProducer);
		log("After traverse(2): shape=" + shape(traversed));

		CollectionProducer repeated = traversed.repeat(headsPerKvGroup);
		log("After repeat(" + headsPerKvGroup + "): shape=" + shape(repeated));

		// Reshape to (seqLen, heads, headSize)
		Producer<PackedCollection> expanded = reshape(shape(seqLen, heads, headSize), repeated);
		log("After reshape to (seqLen, heads, headSize): shape=" + shape(expanded));

		// Evaluate GQA expansion
		PackedCollection expandedResult = expanded.get().evaluate();
		log("GQA expanded shape: " + expandedResult.getShape());
		log("GQA expanded total size: " + expandedResult.getMemLength());

		// Check GQA expansion: head 0 and head 6 should have same values (both from kv head 0)
		// head 7 and head 13 should have same values (both from kv head 1)
		log("\nVerifying GQA expansion:");
		log("  Pos 0, Head 0 [0-4]: " + formatValues(expandedResult, 0, 5));
		log("  Pos 0, Head 6 [0-4]: " + formatValues(expandedResult, 6 * headSize, 5));
		log("  Pos 0, Head 7 [0-4]: " + formatValues(expandedResult, 7 * headSize, 5));
		log("  Pos 0, Head 13 [0-4]: " + formatValues(expandedResult, 13 * headSize, 5));

		// Expected: Head 0 and 6 both from kv head 0 (values 1.000, 1.001, 1.002, ...)
		// Head 7 and 13 both from kv head 1 (values 1.064, 1.065, 1.066, ... since kv head 1 starts at offset 64)
		double h0_h6_diff = 0;
		double h7_h13_diff = 0;
		for (int i = 0; i < 5; i++) {
			h0_h6_diff = Math.max(h0_h6_diff, Math.abs(expandedResult.toDouble(i) - expandedResult.toDouble(6 * headSize + i)));
			h7_h13_diff = Math.max(h7_h13_diff, Math.abs(expandedResult.toDouble(7 * headSize + i) - expandedResult.toDouble(13 * headSize + i)));
		}
		log("  Head 0 vs Head 6 max diff: " + String.format("%.6f", h0_h6_diff));
		log("  Head 7 vs Head 13 max diff: " + String.format("%.6f", h7_h13_diff));

		// === Step 2: Reshape to (seqLen, dim) ===
		log("\n=== Step 2: Reshape to (seqLen, dim) ===");
		Producer<PackedCollection> reshaped = reshape(shape(seqLen, dim), expanded);
		log("After reshape to (seqLen, dim): shape=" + shape(reshaped));

		PackedCollection reshapedResult = reshaped.get().evaluate();
		log("Reshaped result shape: " + reshapedResult.getShape());

		// === Step 3: Enumerate(1, 1, v) ===
		log("\n=== Step 3: Enumerate transformation ===");
		CollectionProducer enumerated = enumerate(1, 1, reshaped);
		log("After enumerate(1, 1, ...): shape=" + shape(enumerated));

		PackedCollection enumResult = enumerated.get().evaluate();
		log("Enumerate result shape: " + enumResult.getShape());
		log("Enumerate result[0-4]: " + formatValues(enumResult, 0, 5));

		// === Step 4: Final reshape to (heads, headSize, seqLen) ===
		log("\n=== Step 4: Final reshape to (heads, headSize, seqLen) ===");
		CollectionProducer finalV = enumerated.reshape(shape(heads, headSize, seqLen));
		log("After reshape to (heads, headSize, seqLen): shape=" + shape(finalV));

		PackedCollection finalResult = finalV.get().evaluate();
		log("Final V shape: " + finalResult.getShape());

		// At this point, finalResult[h, d, pos] should be:
		// - For h in [0-6]: values from kv head 0
		// - For h in [7-13]: values from kv head 1
		// The value at [h, d, pos] comes from cache[pos, kv_head(h), d]

		log("\nVerifying final V tensor:");
		// For head 0, element 0, position 0: should be cache[0, 0, 0] = 1.000
		// For head 0, element 0, position 1: should be cache[1, 0, 0] = 2.000
		log("  V[0, 0, 0]: " + String.format("%.4f", finalResult.toDouble(0)));  // head 0, elem 0, pos 0
		log("  V[0, 0, 1]: " + String.format("%.4f", finalResult.toDouble(1)));  // head 0, elem 0, pos 1
		log("  V[7, 0, 0]: " + String.format("%.4f", finalResult.toDouble(7 * headSize * seqLen)));  // head 7, elem 0, pos 0
		log("  V[7, 0, 1]: " + String.format("%.4f", finalResult.toDouble(7 * headSize * seqLen + 1)));  // head 7, elem 0, pos 1

		// Expected values:
		// V[0, 0, 0] = cache[0, 0, 0] = 1.000
		// V[0, 0, 1] = cache[1, 0, 0] = 2.000
		// V[7, 0, 0] = cache[0, 1, 0] = 1.064 (kv head 1 starts at offset 64)
		// V[7, 0, 1] = cache[1, 1, 0] = 2.064

		double expectedV_0_0_0 = 1.0 + 0 * 0.001;  // 1.000
		double expectedV_0_0_1 = 2.0 + 0 * 0.001;  // 2.000
		double expectedV_7_0_0 = 1.0 + 64 * 0.001; // 1.064 (kv head 1, element 0, position 0)
		double expectedV_7_0_1 = 2.0 + 64 * 0.001; // 2.064 (kv head 1, element 0, position 1)

		log("\nExpected vs Actual:");
		log("  V[0,0,0]: expected=" + String.format("%.4f", expectedV_0_0_0) + ", actual=" + String.format("%.4f", finalResult.toDouble(0)));
		log("  V[0,0,1]: expected=" + String.format("%.4f", expectedV_0_0_1) + ", actual=" + String.format("%.4f", finalResult.toDouble(1)));
		log("  V[7,0,0]: expected=" + String.format("%.4f", expectedV_7_0_0) + ", actual=" + String.format("%.4f", finalResult.toDouble(7 * headSize * seqLen)));
		log("  V[7,0,1]: expected=" + String.format("%.4f", expectedV_7_0_1) + ", actual=" + String.format("%.4f", finalResult.toDouble(7 * headSize * seqLen + 1)));

		log("\n=== Summary ===");
		log("If values match, the transformation chain works correctly.");
		log("If values differ, the issue is in the transformation chain, not the cache itself.");

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
