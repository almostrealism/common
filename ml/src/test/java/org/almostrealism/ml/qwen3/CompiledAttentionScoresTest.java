package org.almostrealism.ml.qwen3;

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test to isolate exactly where compiled attention diverges from manual computation.
 * Uses fixed input values and compares attention scores between manual and compiled.
 */
public class CompiledAttentionScoresTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testAttentionValuesStandard() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/compiled_attention_values.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Compiled Attention Values Test");
		log("===================================================\n");

		// Small dimensions for testing
		int heads = 2;
		int headSize = 4;
		int seqLen = 2;
		int dim = heads * headSize;  // 8

		// Create known attention weights (after softmax)
		// Shape: (heads, seqLen) = (2, 2)
		PackedCollection attnWeights = new PackedCollection(shape(heads, seqLen).traverseEach());
		// Head 0: [0.7, 0.3] (attends 70% to pos0, 30% to pos1)
		// Head 1: [0.4, 0.6] (attends 40% to pos0, 60% to pos1)
		double[] attnData = {0.7, 0.3, 0.4, 0.6};
		for (int i = 0; i < attnData.length; i++) attnWeights.setMem(i, attnData[i]);

		// Value cache: (seqLen, heads, headSize) = (2, 2, 4) for expanded format
		PackedCollection valueCache = new PackedCollection(shape(seqLen, heads, headSize));
		// Position 0: [head0: 1,2,3,4], [head1: 5,6,7,8]
		// Position 1: [head0: 10,20,30,40], [head1: 50,60,70,80]
		double[] valueData = {1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 30, 40, 50, 60, 70, 80};
		for (int i = 0; i < valueData.length; i++) valueCache.setMem(i, valueData[i]);

		log("Attention weights shape: (heads=" + heads + ", seqLen=" + seqLen + ")");
		log("Attention weights: " + formatPacked(attnWeights, 0, attnData.length));
		log("Value cache shape: (seqLen=" + seqLen + ", heads=" + heads + ", headSize=" + headSize + ")");
		log("Value cache pos0: " + formatPacked(valueCache, 0, dim));
		log("Value cache pos1: " + formatPacked(valueCache, dim, dim));

		// === Manual computation ===
		log("\n=== Manual Attention Value Computation ===");
		// output[h, i] = sum_s(attn[h,s] * value[s,h,i])
		double[] manualOutput = new double[dim];
		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				double sum = 0;
				for (int s = 0; s < seqLen; s++) {
					double w = attnWeights.toDouble(h * seqLen + s);
					double v = valueCache.toDouble(s * dim + h * headSize + i);
					sum += w * v;
				}
				manualOutput[h * headSize + i] = sum;
			}
		}

		log("Manual output shape: (1, dim=" + dim + ")");
		log("Manual output: " + formatArray(manualOutput, 0, dim));

		// === Compiled computation using attentionValuesStandard ===
		log("\n=== Compiled Attention Value Computation ===");

		// Build a model that just computes attention values
		Model model = new Model(shape(heads, seqLen).traverseEach());
		model.add(attentionValuesStandard(shape(heads, seqLen).traverseEach(), p(valueCache)));

		CompiledModel compiledModel = model.compile();
		log("Model compiled.");

		// Run forward pass
		PackedCollection output = compiledModel.forward(attnWeights);
		log("Compiled output shape: " + output.getShape());
		log("Compiled output: " + formatPacked(output, 0, dim));

		// Compare
		log("\n=== Comparison ===");
		double maxDiff = 0;
		int maxDiffIdx = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(output.toDouble(i) - manualOutput[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxDiffIdx = i;
			}
		}
		log(String.format("Max absolute difference: %.6f at index %d", maxDiff, maxDiffIdx));

		if (maxDiff < 0.001) {
			log("[OK] Compiled attention values match manual computation");
		} else {
			log("[FAIL] Compiled attention values differ from manual computation");
			log("This indicates a bug in attentionValuesStandard");
		}

		log("\n=== Test Complete ===");
	}

	@Test
	public void testAttentionKeysStandard() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/compiled_attention_scores.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Compiled Attention Scores Test");
		log("===================================================\n");

		// Small dimensions for testing
		int heads = 2;
		int headSize = 4;
		int seqLen = 2;
		int dim = heads * headSize;  // 8

		// Create known query and key cache values
		// Query: (heads, headSize) = (2, 4)
		PackedCollection query = new PackedCollection(shape(heads, headSize));
		// Set some deterministic values
		double[] queryData = {1, 2, 3, 4, 5, 6, 7, 8};  // [head0: 1,2,3,4], [head1: 5,6,7,8]
		for (int i = 0; i < dim; i++) query.setMem(i, queryData[i]);

		// Key cache: (seqLen, heads, headSize) = (2, 2, 4) for expanded format
		PackedCollection keyCache = new PackedCollection(shape(seqLen, heads, headSize));
		// Position 0: [head0: 0.1,0.2,0.3,0.4], [head1: 0.5,0.6,0.7,0.8]
		// Position 1: [head0: 1.0,1.0,1.0,1.0], [head1: 2.0,2.0,2.0,2.0]
		double[] keyData = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0};
		for (int i = 0; i < keyData.length; i++) keyCache.setMem(i, keyData[i]);

		log("Query shape: (heads=" + heads + ", headSize=" + headSize + ")");
		log("Query values: " + formatPacked(query, 0, dim));
		log("Key cache shape: (seqLen=" + seqLen + ", heads=" + heads + ", headSize=" + headSize + ")");
		log("Key cache pos0: " + formatPacked(keyCache, 0, dim));
		log("Key cache pos1: " + formatPacked(keyCache, dim, dim));

		// === Manual computation ===
		log("\n=== Manual Attention Score Computation ===");
		// Attention scores = Q @ K^T / sqrt(headSize)
		// For each head h, for each key position s:
		// score[h,s] = sum_i(query[h,i] * key[s,h,i]) / sqrt(headSize)

		double[] manualScores = new double[heads * seqLen];
		for (int h = 0; h < heads; h++) {
			for (int s = 0; s < seqLen; s++) {
				double dot = 0;
				for (int i = 0; i < headSize; i++) {
					double q = query.toDouble(h * headSize + i);
					double k = keyCache.toDouble(s * dim + h * headSize + i);
					dot += q * k;
				}
				manualScores[h * seqLen + s] = dot / Math.sqrt(headSize);
			}
		}

		log("Manual scores (heads, seqLen) = (" + heads + ", " + seqLen + "):");
		for (int h = 0; h < heads; h++) {
			StringBuilder sb = new StringBuilder("  Head " + h + ": [");
			for (int s = 0; s < seqLen; s++) {
				if (s > 0) sb.append(", ");
				sb.append(String.format("%.4f", manualScores[h * seqLen + s]));
			}
			log(sb.append("]").toString());
		}

		// === Compiled computation using attentionKeysStandard ===
		log("\n=== Compiled Attention Score Computation ===");

		// Build a model that just computes attention scores
		Model model = new Model(shape(heads, headSize));
		model.add(attentionKeysStandard(shape(heads, headSize), p(keyCache)));

		CompiledModel compiledModel = model.compile();
		log("Model compiled.");

		// Run forward pass
		PackedCollection output = compiledModel.forward(query);
		log("Compiled output shape: " + output.getShape());

		log("Compiled scores (should match manual):");
		for (int h = 0; h < heads; h++) {
			StringBuilder sb = new StringBuilder("  Head " + h + ": [");
			for (int s = 0; s < seqLen; s++) {
				if (s > 0) sb.append(", ");
				sb.append(String.format("%.4f", output.toDouble(h * seqLen + s)));
			}
			log(sb.append("]").toString());
		}

		// Compare
		log("\n=== Comparison ===");
		double maxDiff = 0;
		for (int i = 0; i < heads * seqLen; i++) {
			double diff = Math.abs(output.toDouble(i) - manualScores[i]);
			maxDiff = Math.max(maxDiff, diff);
		}
		log(String.format("Max absolute difference: %.6f", maxDiff));

		if (maxDiff < 0.001) {
			log("[OK] Compiled attention scores match manual computation");
		} else {
			log("[FAIL] Compiled attention scores differ from manual computation");
			log("This indicates a bug in attentionKeysStandard");
		}

		log("\n=== Test Complete ===");
	}

	private String formatPacked(PackedCollection c, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.2f", c.toDouble(offset + i)));
		}
		return sb.append("]").toString();
	}

	private String formatArray(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.2f", arr[offset + i]));
		}
		return sb.append("]").toString();
	}
}
