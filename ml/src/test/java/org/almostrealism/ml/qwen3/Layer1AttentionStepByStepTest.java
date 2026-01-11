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
 * Step-by-step comparison of layer 1 attention with PyTorch reference.
 * This test compares each intermediate value to pinpoint divergence.
 */
public class Layer1AttentionStepByStepTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1AttentionStepByStep() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_step_by_step.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  LAYER 1 ATTENTION STEP-BY-STEP COMPARISON");
		log("=".repeat(70) + "\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		int dim = config.dim;
		int heads = config.headCount;
		int kvHeads = config.kvHeadCount;
		int headSize = dim / heads;
		int kvDim = kvHeads * headSize;
		int headsPerKvGroup = heads / kvHeads;

		log(String.format("Config: dim=%d, heads=%d, kvHeads=%d, headSize=%d, kvDim=%d, headsPerKvGroup=%d",
				dim, heads, kvHeads, headSize, kvDim, headsPerKvGroup));

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load weights
		PackedCollection wq = stateDict.get("model.layers.1.self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get("model.layers.1.self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get("model.layers.1.self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get("model.layers.1.self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get("model.layers.1.self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get("model.layers.1.self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get("model.layers.1.self_attn.v_proj.bias");

		// Load layer 1 input (normalized with input_layernorm)
		float[] pytorchInputNorm = loadReferenceOutput("layer1_input_norm.bin");
		PackedCollection inputNorm = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			inputNorm.setMem(i, pytorchInputNorm[i]);
		}

		log("\n=== Step 1: Q Projection ===");
		float[] pytorchQ = loadReferenceOutput("layer1_q_proj.bin");
		Model qModel = new Model(shape(1, dim));
		qModel.add(dense(wq, bq));
		PackedCollection javaQ = qModel.compile().forward(inputNorm);
		compareAndLog("Q projection", pytorchQ, javaQ);

		log("\n=== Step 2: K Projection ===");
		float[] pytorchK = loadReferenceOutput("layer1_k_proj.bin");
		Model kModel = new Model(shape(1, dim));
		kModel.add(dense(wk, bk));
		PackedCollection javaK = kModel.compile().forward(inputNorm);
		compareAndLog("K projection", pytorchK, javaK);

		log("\n=== Step 3: V Projection ===");
		float[] pytorchV = loadReferenceOutput("layer1_v_proj.bin");
		Model vModel = new Model(shape(1, dim));
		vModel.add(dense(wv, bv));
		PackedCollection javaV = vModel.compile().forward(inputNorm);
		compareAndLog("V projection", pytorchV, javaV);

		log("\n=== Step 4: Q RoPE Rotation ===");
		// At position 0, cos=1 and sin=0, so RoPE should not change the values
		float[] pytorchQRope = loadReferenceOutput("layer1_q_rope.bin");

		// Build RoPE pipeline for Q
		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		Model qRopeModel = new Model(shape(1, dim));
		qRopeModel.add(reshapeToSplitHalfRope(dim, heads, headSize));
		qRopeModel.add(ropeRotation(shape(heads, headSize / 2, 2), freqCis, p(position)));
		qRopeModel.add(reshapeFromSplitHalfRope(heads, headSize));
		qRopeModel.add(reshape(shape(heads, headSize), shape(dim)));
		PackedCollection javaQRope = qRopeModel.compile().forward(javaQ);

		// PyTorch Q after RoPE is (1, 14, 1, 64) flattened = 896 values
		// Java outputs (dim) = 896 values
		compareAndLog("Q after RoPE", pytorchQRope, javaQRope);

		log("\n=== Step 5: K RoPE Rotation ===");
		float[] pytorchKRope = loadReferenceOutput("layer1_k_rope.bin");

		Model kRopeModel = new Model(shape(1, kvDim));
		kRopeModel.add(reshapeToSplitHalfRope(kvDim, kvHeads, headSize));
		kRopeModel.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, p(position)));
		kRopeModel.add(reshapeFromSplitHalfRope(kvHeads, headSize));
		kRopeModel.add(reshape(shape(kvHeads, headSize), shape(kvDim)));
		PackedCollection javaKRope = kRopeModel.compile().forward(javaK);

		compareAndLog("K after RoPE", pytorchKRope, javaKRope);

		log("\n=== Step 6: K GQA Expansion ===");
		float[] pytorchKExpanded = loadReferenceOutput("layer1_k_expanded.bin");

		Model kExpandModel = new Model(shape(1, kvDim));
		kExpandModel.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		PackedCollection javaKExpanded = kExpandModel.compile().forward(javaKRope);

		compareAndLog("K after GQA expansion", pytorchKExpanded, javaKExpanded);

		log("\n=== Step 7: V GQA Expansion ===");
		float[] pytorchVExpanded = loadReferenceOutput("layer1_v_expanded.bin");

		Model vExpandModel = new Model(shape(1, kvDim));
		vExpandModel.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		PackedCollection javaVExpanded = vExpandModel.compile().forward(javaV);

		compareAndLog("V after GQA expansion", pytorchVExpanded, javaVExpanded);

		log("\n=== Step 8: Attention Scores (Q @ K^T / sqrt(headSize)) ===");
		float[] pytorchAttnScores = loadReferenceOutput("layer1_attn_scores.bin");

		// Manual computation: reshape Q and K to (heads, headSize), then Q @ K^T / sqrt(headSize)
		// For single token: Q is (heads, headSize), K is (heads, headSize)
		// Attention scores = sum(Q[h,:] * K[h,:]) / sqrt(64) for each head
		double scale = 1.0 / Math.sqrt(headSize);
		double[] javaAttnScores = new double[heads];
		for (int h = 0; h < heads; h++) {
			double dotProduct = 0;
			for (int d = 0; d < headSize; d++) {
				int idx = h * headSize + d;
				double qVal = javaQRope.toDouble(idx);
				double kVal = javaKExpanded.toDouble(idx);
				dotProduct += qVal * kVal;
			}
			javaAttnScores[h] = dotProduct * scale;
		}

		log("  PyTorch attention scores (14 values):");
		StringBuilder pySb = new StringBuilder("    [");
		for (int i = 0; i < Math.min(14, pytorchAttnScores.length); i++) {
			if (i > 0) pySb.append(", ");
			pySb.append(String.format("%.4f", pytorchAttnScores[i]));
		}
		pySb.append("]");
		log(pySb.toString());

		log("  Java attention scores (14 values):");
		StringBuilder javaSb = new StringBuilder("    [");
		for (int i = 0; i < heads; i++) {
			if (i > 0) javaSb.append(", ");
			javaSb.append(String.format("%.4f", javaAttnScores[i]));
		}
		javaSb.append("]");
		log(javaSb.toString());

		double maxScoreDiff = 0;
		for (int i = 0; i < Math.min(heads, pytorchAttnScores.length); i++) {
			double diff = Math.abs(javaAttnScores[i] - pytorchAttnScores[i]);
			maxScoreDiff = Math.max(maxScoreDiff, diff);
		}
		log(String.format("  Max diff: %.6f %s", maxScoreDiff, maxScoreDiff < 0.01 ? "[GOOD]" : "[DIVERGENCE]"));

		log("\n=== Step 9: Attention Probs (softmax) ===");
		// For single token at position 0, softmax of single value = 1.0
		float[] pytorchAttnProbs = loadReferenceOutput("layer1_attn_probs.bin");
		log("  PyTorch: all 1.0 (single token softmax)");
		log("  Expected Java: all 1.0");

		log("\n=== Step 10: Attention Output (probs @ V) ===");
		float[] pytorchAttnOutput = loadReferenceOutput("layer1_attn_output.bin");
		// For single token, attn_output = 1.0 * V_expanded = V_expanded
		compareAndLog("Attention output (should equal V_expanded)", pytorchAttnOutput, javaVExpanded);

		log("\n=== Step 11: O Projection ===");
		float[] pytorchOProj = loadReferenceOutput("layer1_o_proj.bin");

		Model oProjModel = new Model(shape(1, dim));
		oProjModel.add(dense(wo));
		// Use V_expanded as input (which equals attention output for single token)
		PackedCollection javaOProj = oProjModel.compile().forward(javaVExpanded);

		compareAndLog("O projection output", pytorchOProj, javaOProj);

		log("\n" + "=".repeat(70));
		log("  STEP-BY-STEP COMPARISON COMPLETE");
		log("=".repeat(70));

		stateDict.destroy();
	}

	private void compareAndLog(String name, float[] expected, PackedCollection actual) {
		int minLen = (int) Math.min(expected.length, actual.getShape().getTotalSize());

		double maxDiff = 0;
		int maxDiffIdx = -1;
		double sumDiff = 0;

		for (int i = 0; i < minLen; i++) {
			double diff = Math.abs(actual.toDouble(i) - expected[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxDiffIdx = i;
			}
			sumDiff += diff;
		}

		double meanDiff = sumDiff / minLen;
		log(String.format("  %s:", name));
		log(String.format("    Mean diff: %.6f, Max diff: %.6f at index %d", meanDiff, maxDiff, maxDiffIdx));
		log(String.format("    Compared %d values (expected %d, actual %d)", minLen, expected.length, (int) actual.getShape().getTotalSize()));

		// Show first 5 values
		log("    First 5 values:");
		for (int i = 0; i < Math.min(5, minLen); i++) {
			log(String.format("      [%d] PyTorch=%.6f, Java=%.6f, diff=%.6f",
					i, expected[i], actual.toDouble(i), actual.toDouble(i) - expected[i]));
		}

		String status;
		if (maxDiff < 1e-4) {
			status = "[EXCELLENT]";
		} else if (maxDiff < 0.01) {
			status = "[GOOD]";
		} else if (maxDiff < 0.1) {
			status = "[WARNING]";
		} else {
			status = "[FAIL]";
		}
		log(String.format("    Status: %s", status));
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.dim / config.headCount;
		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) {
			freqs[i] = 1.0 / Math.pow(config.ropeTheta, (2.0 * i) / headSize);
		}

		PackedCollection freqCis = new PackedCollection(shape(config.seqLen, freqDim, 2));
		for (int pos = 0; pos < config.seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = pos * freqs[i];
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}
		return freqCis;
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
