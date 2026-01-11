package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test GQA expansion to verify it correctly duplicates KV heads.
 */
public class GQAExpandTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testGQAExpand() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/gqa_expand.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== GQA Expansion Test ===\n");

		// Qwen2.5-0.5B config
		int heads = 14;
		int kvHeads = 2;
		int headSize = 64;
		int dim = heads * headSize;      // 896
		int kvDim = kvHeads * headSize;  // 128
		int headsPerKvGroup = heads / kvHeads;  // 7

		log("Config:");
		log(String.format("  heads=%d, kvHeads=%d, headSize=%d", heads, kvHeads, headSize));
		log(String.format("  dim=%d, kvDim=%d, headsPerKvGroup=%d", dim, kvDim, headsPerKvGroup));

		// Create test input: (1, kvDim) = (1, 128)
		// Fill with pattern: kvHead 0 gets values 0-63, kvHead 1 gets values 64-127
		PackedCollection input = new PackedCollection(shape(1, kvDim));
		for (int kv = 0; kv < kvHeads; kv++) {
			for (int h = 0; h < headSize; h++) {
				int idx = kv * headSize + h;
				input.setMem(idx, kv * 1000 + h);  // 0-63 for kv0, 1000-1063 for kv1
			}
		}

		log("\nInput (1, 128):");
		log("  First 10 values: " + formatFirst(input, 10));
		log("  Values 64-73: " + formatRange(input, 64, 10));

		// Run GQA expansion
		Model gqaModel = new Model(shape(1, kvDim));
		gqaModel.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		PackedCollection output = gqaModel.compile().forward(input);

		log("\nOutput (1, 896):");
		log("  Shape: " + output.getShape());

		// Verify the expansion
		// For output heads 0-6 (query heads mapped to kvHead 0), we should see values 0-63
		// For output heads 7-13 (query heads mapped to kvHead 1), we should see values 1000-1063

		log("\n=== Verification ===");
		boolean allCorrect = true;

		for (int h = 0; h < heads; h++) {
			int kvHead = h / headsPerKvGroup;
			int expectedBase = kvHead * 1000;

			log(String.format("\nHead %d (maps to kvHead %d):", h, kvHead));

			// Check first 5 values of this head
			StringBuilder sb = new StringBuilder("  First 5: ");
			boolean headCorrect = true;
			for (int i = 0; i < 5; i++) {
				int outputIdx = h * headSize + i;
				double actual = output.toDouble(outputIdx);
				double expected = expectedBase + i;
				sb.append(String.format("%.0f ", actual));
				if (Math.abs(actual - expected) > 0.001) {
					headCorrect = false;
				}
			}
			log(sb.toString() + (headCorrect ? "[OK]" : "[WRONG]"));

			if (!headCorrect) {
				log("  Expected: " + expectedBase + " " + (expectedBase+1) + " " + (expectedBase+2) + " ...");
				allCorrect = false;
			}
		}

		log("\n=== Summary ===");
		if (allCorrect) {
			log("[PASS] GQA expansion is correct");
		} else {
			log("[FAIL] GQA expansion has errors");
		}
	}

	private String formatFirst(PackedCollection c, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(n, c.getShape().getTotalSize()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.0f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatRange(PackedCollection c, int start, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = start; i < Math.min(start + n, c.getShape().getTotalSize()); i++) {
			if (i > start) sb.append(", ");
			sb.append(String.format("%.0f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
