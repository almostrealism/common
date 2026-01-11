package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Debug test to understand KV cache behavior at different positions.
 */
public class CacheDebugTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testCacheContentsAtDifferentPositions() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/cache_debug.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Cache Debug Test");
		log("===================================================\n");

		// Minimal attention config
		int dim = 8;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;  // 4
		int seqLen = 10;
		int hiddenDim = 16;

		log("Config: dim=" + dim + ", heads=" + heads + ", seqLen=" + seqLen);

		// Create weights
		PackedCollection rmsAttWeight = ones(dim);
		PackedCollection wq = random(dim, dim);
		PackedCollection wk = random(dim, dim);
		PackedCollection wv = random(dim, dim);
		PackedCollection wo = random(dim, dim);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		// Create position
		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create external KV cache so we can inspect it
		PackedCollection keyCache = new PackedCollection(seqLen, kvHeads, headSize);
		PackedCollection valueCache = new PackedCollection(seqLen, kvHeads, headSize);
		keyCache.clear();
		valueCache.clear();

		log("\nInitial cache state (should be all zeros):");
		logCacheSlice(keyCache, 0, "Key cache pos 0");
		logCacheSlice(keyCache, 1, "Key cache pos 1");

		// Build a simplified model that exposes the cache
		// For this test, we'll manually run the attention computation
		log("\n=== Building minimal attention model ===");

		Block attentionBlock = attention(heads, kvHeads,
			rmsAttWeight, wk, wv, wq, wo,
			null, null, null, null, null,
			freqCis, dynamicPosition, 1e-6);

		Model model = new Model(shape(1, dim));
		model.add(attentionBlock);
		CompiledModel compiled = model.compile();

		// Test input
		PackedCollection input0 = new PackedCollection(shape(1, dim));
		PackedCollection input1 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input0.setMem(i, 1.0 + i * 0.1);  // [1.0, 1.1, 1.2, ...]
			input1.setMem(i, 2.0 + i * 0.1);  // [2.0, 2.1, 2.2, ...]
		}

		log("\nInput 0: " + formatArray(input0, 0, dim));
		log("Input 1: " + formatArray(input1, 0, dim));

		// Position 0
		log("\n=== Position 0 ===");
		position.setMem(0, 0.0);
		PackedCollection output0 = compiled.forward(input0);
		log("Output 0: " + formatArray(output0, 0, dim));

		// Position 1
		log("\n=== Position 1 ===");
		position.setMem(0, 1.0);
		PackedCollection output1 = compiled.forward(input1);
		log("Output 1: " + formatArray(output1, 0, dim));

		// Position 1 again with SAME input as position 0
		// If attention is working correctly, this should give different output
		// because the query at pos 1 should attend to cached key at pos 0
		log("\n=== Position 1 with input0 (should differ from position 0 output due to context) ===");
		position.setMem(0, 1.0);
		PackedCollection output1b = compiled.forward(input0);
		log("Output 1b (same input as pos 0): " + formatArray(output1b, 0, dim));

		// Compare: The real test is whether position 1 with input1 differs from
		// position 0 with input1 (fresh model, no context).

		// Create fresh model for comparison
		log("\n=== Fresh model: Position 0 with input1 (no context) ===");
		Block attentionBlock2 = attention(heads, kvHeads,
			rmsAttWeight, wk, wv, wq, wo,
			null, null, null, null, null,
			freqCis, dynamicPosition, 1e-6);

		Model model2 = new Model(shape(1, dim));
		model2.add(attentionBlock2);
		CompiledModel compiled2 = model2.compile();

		position.setMem(0, 0.0);
		PackedCollection outputFresh = compiled2.forward(input1);
		log("Fresh output (pos 0, input1): " + formatArray(outputFresh, 0, dim));

		// Compare with output1 (which should have context from position 0)
		log("\nOutput 1 (pos 1, input1, WITH context from pos 0): " + formatArray(output1, 0, dim));

		// If context IS being used, output1 should differ from outputFresh
		// because output1 attends to both position 0 (9707) and position 1 (input1)
		boolean contextUsed = false;
		double maxDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(output1.toDouble(i) - outputFresh.toDouble(i));
			maxDiff = Math.max(maxDiff, diff);
			if (diff > 1e-6) {
				contextUsed = true;
			}
		}

		log(String.format("\nMax difference: %.9f", maxDiff));

		if (contextUsed) {
			log("[OK] Context is being used! Position 1 output differs from fresh position 0.");
		} else {
			log("[PROBLEM] Context NOT being used. Position 1 output matches fresh position 0.");
			log("This means the attention at position 1 is not incorporating the KV cache from position 0.");
		}

		log("\n=== Test Complete ===");
	}

	private PackedCollection ones(int size) {
		PackedCollection c = new PackedCollection(size);
		for (int i = 0; i < size; i++) c.setMem(i, 1.0);
		return c;
	}

	private PackedCollection random(int rows, int cols) {
		PackedCollection c = new PackedCollection(shape(rows, cols));
		java.util.Random rand = new java.util.Random(42);
		for (int i = 0; i < rows * cols; i++) {
			c.setMem(i, rand.nextGaussian() * 0.1);
		}
		return c;
	}

	private PackedCollection createFreqCis(int seqLen, int headSize) {
		double theta = 10000.0;
		int freqDim = headSize / 2;
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));

		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
				double angle = pos * freq;
				freqCis.setMem((pos * freqDim + i) * 2, Math.cos(angle));
				freqCis.setMem((pos * freqDim + i) * 2 + 1, Math.sin(angle));
			}
		}
		return freqCis;
	}

	private void logCacheSlice(PackedCollection cache, int pos, String label) {
		int sliceSize = cache.getShape().length(1) * cache.getShape().length(2);
		int offset = pos * sliceSize;
		StringBuilder sb = new StringBuilder();
		sb.append(label).append(": [");
		for (int i = 0; i < Math.min(sliceSize, 8); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", cache.toDouble(offset + i)));
		}
		if (sliceSize > 8) sb.append(", ...");
		sb.append("]");
		log(sb.toString());
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
}
