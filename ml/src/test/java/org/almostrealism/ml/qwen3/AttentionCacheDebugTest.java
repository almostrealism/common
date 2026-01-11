package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Debug test to verify the cache read/write mechanism in attention.
 */
public class AttentionCacheDebugTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testCacheWriteRead() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/cache_debug.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  CACHE WRITE/READ DEBUG TEST");
		log("=".repeat(70) + "\n");

		// Config
		int seqLen = 10;  // Small for testing
		int heads = 14;
		int headSize = 64;
		int dim = heads * headSize;  // 896

		// Create a cache
		PackedCollection cache = new PackedCollection(shape(seqLen, heads, headSize));
		cache.clear();

		log("Cache shape: " + cache.getShape());
		log("Cache cleared - first 5 values: " + formatFirst(cache, 5));

		// Create test data - (1, dim) = (1, 896)
		PackedCollection testData = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			testData.setMem(i, i * 0.01);  // Values 0.0, 0.01, 0.02, ...
		}
		log("Test data first 5: " + formatFirst(testData, 5));

		// Create position indicator
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);  // Position 0

		// Write test data to cache at position 0 manually
		for (int i = 0; i < dim; i++) {
			cache.setMem(i, testData.toDouble(i));
		}

		log("\nAfter write to position 0:");
		log("  Cache row 0 first 5: " + formatFirst(cache, 5));
		log("  Cache row 1 first 5: " + formatRange(cache, heads * headSize, 5));

		// Verify the write
		log("\nVerifying cache contents at position 0:");
		boolean writeCorrect = true;
		for (int i = 0; i < Math.min(10, dim); i++) {
			double expected = i * 0.01;
			double actual = cache.toDouble(i);
			if (Math.abs(actual - expected) > 1e-6) {
				log(String.format("  [%d] MISMATCH: expected=%.4f, actual=%.4f", i, expected, actual));
				writeCorrect = false;
			}
		}
		log(writeCorrect ? "  [PASS] Write verified" : "  [FAIL] Write incorrect");

		// Test reading with attentionKeysStandard
		log("\n=== Testing attentionKeysStandard ===");

		// Create Q input (heads, headSize)
		PackedCollection qInput = new PackedCollection(shape(heads, headSize));
		// Fill with simple pattern
		for (int h = 0; h < heads; h++) {
			for (int d = 0; d < headSize; d++) {
				qInput.setMem(h * headSize + d, 1.0);  // All 1s for easy verification
			}
		}
		log("Q input: all 1.0");

		// Use attentionKeysStandard to compute Q @ K^T / sqrt(headSize)
		Model readModel = new Model(shape(heads, headSize));
		readModel.add(attentionKeysStandard(shape(heads, headSize), p(cache)));
		PackedCollection attnScores = readModel.compile().forward(qInput);

		log("\nAttention scores output shape: " + attnScores.getShape());
		log("Attention scores first 10: " + formatFirst(attnScores, 10));

		// Manual computation for verification
		// For position 0, each head's K values are the same as testData[head * headSize : (head+1) * headSize]
		// Q is all 1s, so Q @ K^T = sum(K values) for each head
		log("\nManual verification:");
		for (int h = 0; h < Math.min(3, heads); h++) {
			double sum = 0;
			for (int d = 0; d < headSize; d++) {
				sum += cache.toDouble(h * headSize + d);  // Position 0
			}
			double expected = sum / Math.sqrt(headSize);
			log(String.format("  Head %d: K sum = %.4f, expected score = %.4f", h, sum, expected));
		}
	}

	@Test
	public void testLayer1NoCacheAttention() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_no_cache.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  LAYER 1 ATTENTION - NO CACHE TEST");
		log("=".repeat(70) + "\n");
		log("Testing attention without caching to verify the algorithm is correct.");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		int dim = config.dim;
		int heads = config.headCount;
		int kvHeads = config.kvHeadCount;
		int headSize = dim / heads;
		int kvDim = kvHeads * headSize;

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load weights
		PackedCollection wq = stateDict.get("model.layers.1.self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get("model.layers.1.self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get("model.layers.1.self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get("model.layers.1.self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get("model.layers.1.self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get("model.layers.1.self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get("model.layers.1.self_attn.v_proj.bias");

		// Load reference inputs
		float[] pytorchInputNorm = loadReferenceOutput("layer1_input_norm.bin");
		PackedCollection inputNorm = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			inputNorm.setMem(i, pytorchInputNorm[i]);
		}

		// Load reference outputs
		float[] pytorchKExpanded = loadReferenceOutput("layer1_k_expanded.bin");
		float[] pytorchVExpanded = loadReferenceOutput("layer1_v_expanded.bin");
		float[] pytorchQRope = loadReferenceOutput("layer1_q_rope.bin");
		float[] pytorchOProj = loadReferenceOutput("layer1_o_proj.bin");

		// Compute Q, K, V projections
		Model qModel = new Model(shape(1, dim));
		qModel.add(dense(wq, bq));
		PackedCollection javaQ = qModel.compile().forward(inputNorm);

		Model kModel = new Model(shape(1, dim));
		kModel.add(dense(wk, bk));
		PackedCollection javaK = kModel.compile().forward(inputNorm);

		Model vModel = new Model(shape(1, dim));
		vModel.add(dense(wv, bv));
		PackedCollection javaV = vModel.compile().forward(inputNorm);

		// Compute RoPE for Q
		PackedCollection freqCis = computeRopeFreqs(config.seqLen, headSize, config.ropeTheta);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		Model qRopeModel = new Model(shape(1, dim));
		qRopeModel.add(reshapeToSplitHalfRope(dim, heads, headSize));
		qRopeModel.add(ropeRotation(shape(heads, headSize / 2, 2), freqCis, p(position)));
		qRopeModel.add(reshapeFromSplitHalfRope(heads, headSize));
		qRopeModel.add(reshape(shape(heads, headSize), shape(dim)));
		PackedCollection javaQRope = qRopeModel.compile().forward(javaQ);

		log("Q after RoPE verification:");
		double maxQRopeDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxQRopeDiff = Math.max(maxQRopeDiff, Math.abs(javaQRope.toDouble(i) - pytorchQRope[i]));
		}
		log(String.format("  Max Q RoPE diff: %.6f %s", maxQRopeDiff, maxQRopeDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Compute RoPE for K and GQA expand
		Model kRopeModel = new Model(shape(1, kvDim));
		kRopeModel.add(reshapeToSplitHalfRope(kvDim, kvHeads, headSize));
		kRopeModel.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, p(position)));
		kRopeModel.add(reshapeFromSplitHalfRope(kvHeads, headSize));
		kRopeModel.add(reshape(shape(kvHeads, headSize), shape(kvDim)));
		PackedCollection javaKRope = kRopeModel.compile().forward(javaK);

		Model kExpandModel = new Model(shape(1, kvDim));
		kExpandModel.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		PackedCollection javaKExpanded = kExpandModel.compile().forward(javaKRope);

		log("\nK after GQA expansion verification:");
		double maxKDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxKDiff = Math.max(maxKDiff, Math.abs(javaKExpanded.toDouble(i) - pytorchKExpanded[i]));
		}
		log(String.format("  Max K expanded diff: %.6f %s", maxKDiff, maxKDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// GQA expand V
		Model vExpandModel = new Model(shape(1, kvDim));
		vExpandModel.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		PackedCollection javaVExpanded = vExpandModel.compile().forward(javaV);

		log("\nV after GQA expansion verification:");
		double maxVDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxVDiff = Math.max(maxVDiff, Math.abs(javaVExpanded.toDouble(i) - pytorchVExpanded[i]));
		}
		log(String.format("  Max V expanded diff: %.6f %s", maxVDiff, maxVDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Now test attention directly using the cache-less approach
		// For single token at position 0:
		// - Attention scores = Q @ K^T / sqrt(headSize) = 14 scores
		// - Softmax of single value = 1.0 for all heads
		// - Attention output = 1.0 * V = V_expanded

		// So the O projection input should be V_expanded
		Model oProjModel = new Model(shape(1, dim));
		oProjModel.add(dense(wo));
		PackedCollection javaOProj = oProjModel.compile().forward(javaVExpanded);

		log("\n=== Final O projection comparison ===");
		compareOutputs(pytorchOProj, javaOProj, 10);

		stateDict.destroy();
	}

	private void compareOutputs(float[] expected, PackedCollection actual, int showN) {
		int minLen = (int) Math.min(expected.length, actual.getShape().getTotalSize());
		double maxDiff = 0;
		int maxDiffIdx = -1;

		for (int i = 0; i < minLen; i++) {
			double diff = Math.abs(actual.toDouble(i) - expected[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxDiffIdx = i;
			}
		}

		log(String.format("  Max diff: %.6f at index %d", maxDiff, maxDiffIdx));
		log(String.format("  First %d values:", showN));
		for (int i = 0; i < Math.min(showN, minLen); i++) {
			log(String.format("    [%d] PyTorch=%.6f, Java=%.6f, diff=%.6f",
					i, expected[i], actual.toDouble(i), actual.toDouble(i) - expected[i]));
		}

		String status;
		if (maxDiff < 1e-4) status = "[EXCELLENT]";
		else if (maxDiff < 0.01) status = "[GOOD]";
		else if (maxDiff < 0.1) status = "[WARNING]";
		else status = "[FAIL]";
		log(String.format("  Status: %s", status));
	}

	private PackedCollection computeRopeFreqs(int seqLen, int headSize, double theta) {
		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) {
			freqs[i] = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
		}

		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = pos * freqs[i];
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}
		return freqCis;
	}

	private String formatFirst(PackedCollection c, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(n, c.getShape().getTotalSize()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatRange(PackedCollection c, int start, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = start; i < Math.min(start + n, c.getShape().getTotalSize()); i++) {
			if (i > start) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private float[] loadReferenceOutput(String filename) throws Exception {
		try (FileChannel channel = FileChannel.open(
				Paths.get(REFERENCE_DIR, filename), StandardOpenOption.READ)) {
			ByteBuffer sizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			channel.read(sizeBuffer);
			sizeBuffer.flip();
			int size = sizeBuffer.getInt();

			ByteBuffer dataBuffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN);
			channel.read(dataBuffer);
			dataBuffer.flip();

			float[] data = new float[size];
			for (int i = 0; i < size; i++) {
				data[i] = dataBuffer.getFloat();
			}
			return data;
		}
	}
}
