package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
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
 * Debug test to isolate where the full attention method diverges from step-by-step.
 * We build the attention structure incrementally to find the exact point of failure.
 */
public class AttentionMethodDebugTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1KeyBranchOutput() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_key_branch_debug.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  LAYER 1 KEY BRANCH OUTPUT TEST");
		log("=".repeat(70) + "\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		int dim = config.dim;
		int heads = config.headCount;
		int kvHeads = config.kvHeadCount;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		int seqLen = config.seqLen;

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load weights for layer 1
		String prefix = "model.layers.1.";
		PackedCollection rmsAttWeight = stateDict.get(prefix + "input_layernorm.weight");
		PackedCollection wk = stateDict.get(prefix + "self_attn.k_proj.weight");
		PackedCollection bk = stateDict.get(prefix + "self_attn.k_proj.bias");

		log("K projection weight shape: " + wk.getShape());
		log("K projection bias shape: " + bk.getShape());

		// Load reference inputs and outputs
		float[] pytorchAfterLayer0 = loadReferenceOutput("after_layer_0.bin");
		float[] pytorchKProj = loadReferenceOutput("layer1_k_proj.bin");
		float[] pytorchKRope = loadReferenceOutput("layer1_k_rope.bin");
		float[] pytorchKExpanded = loadReferenceOutput("layer1_k_expanded.bin");

		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, pytorchAfterLayer0[i]);
		}

		// Step 1: Test RMSNorm
		log("\n=== Step 1: RMSNorm ===");
		Model rmsnormModel = new Model(shape(1, dim));
		rmsnormModel.add(rmsnorm(shape(1, dim), rmsAttWeight, 1e-6));
		PackedCollection afterRmsNorm = rmsnormModel.compile().forward(input);

		float[] pytorchInputNorm = loadReferenceOutput("layer1_input_norm.bin");
		double maxRmsDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxRmsDiff = Math.max(maxRmsDiff, Math.abs(afterRmsNorm.toDouble(i) - pytorchInputNorm[i]));
		}
		log(String.format("  RMSNorm max diff: %.6f %s", maxRmsDiff, maxRmsDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Step 2: Test K projection (using normalized input)
		log("\n=== Step 2: K Projection ===");
		PackedCollection normInput = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			normInput.setMem(i, pytorchInputNorm[i]);
		}

		Model kProjModel = new Model(shape(1, dim));
		kProjModel.add(dense(wk, bk));
		PackedCollection kProj = kProjModel.compile().forward(normInput);

		double maxKProjDiff = 0;
		for (int i = 0; i < kvDim; i++) {
			maxKProjDiff = Math.max(maxKProjDiff, Math.abs(kProj.toDouble(i) - pytorchKProj[i]));
		}
		log(String.format("  K projection max diff: %.6f %s", maxKProjDiff, maxKProjDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Step 3: Test K RoPE using the same structure as attention method
		log("\n=== Step 3: K RoPE (using attention method structure) ===");

		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, config.ropeTheta);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Use the exact same structure as attention method
		Model kRopeModel = new Model(shape(1, kvDim));
		kRopeModel.add(reshapeToSplitHalfRope(kvDim, kvHeads, headSize));
		kRopeModel.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, p(position)));
		kRopeModel.add(reshapeFromSplitHalfRope(kvHeads, headSize));
		kRopeModel.add(reshape(shape(kvHeads, headSize), shape(kvDim)));

		PackedCollection kRope = kRopeModel.compile().forward(kProj);

		double maxKRopeDiff = 0;
		for (int i = 0; i < kvDim; i++) {
			maxKRopeDiff = Math.max(maxKRopeDiff, Math.abs(kRope.toDouble(i) - pytorchKRope[i]));
		}
		log(String.format("  K RoPE max diff: %.6f %s", maxKRopeDiff, maxKRopeDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Step 4: Test GQA expand
		log("\n=== Step 4: GQA Expand ===");
		Model kExpandModel = new Model(shape(1, kvDim));
		kExpandModel.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		PackedCollection kExpanded = kExpandModel.compile().forward(kRope);

		double maxKExpandDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxKExpandDiff = Math.max(maxKExpandDiff, Math.abs(kExpanded.toDouble(i) - pytorchKExpanded[i]));
		}
		log(String.format("  K expanded max diff: %.6f %s", maxKExpandDiff, maxKExpandDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Step 5: Test the full K branch as a single unit
		log("\n=== Step 5: Full K Branch (single compilation) ===");

		// Create a cache
		PackedCollection keyCache = new PackedCollection(shape(seqLen, dim));
		keyCache.clear();

		// Build the K branch exactly like attention() does
		Model fullKBranchModel = new Model(shape(1, dim));
		SequentialBlock kBlock = new SequentialBlock(shape(1, dim));

		// RMSNorm first
		kBlock.add(rmsnorm(shape(1, dim), rmsAttWeight, 1e-6));

		// K projection
		kBlock.add(dense(wk, bk));

		// RoPE
		kBlock.add(reshapeToSplitHalfRope(kvDim, kvHeads, headSize));
		kBlock.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, p(position)));
		kBlock.add(reshapeFromSplitHalfRope(kvHeads, headSize));
		kBlock.add(reshape(shape(kvHeads, headSize), shape(kvDim)));

		// GQA expand
		kBlock.add(reshape(shape(kvDim), shape(1, kvDim)));
		kBlock.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));

		fullKBranchModel.add(kBlock);

		PackedCollection fullKBranchOutput = fullKBranchModel.compile().forward(input);

		double maxFullKDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxFullKDiff = Math.max(maxFullKDiff, Math.abs(fullKBranchOutput.toDouble(i) - pytorchKExpanded[i]));
		}
		log(String.format("  Full K branch max diff: %.6f %s", maxFullKDiff, maxFullKDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Step 6: Test cache write
		log("\n=== Step 6: Cache Write ===");

		// Write to cache using the CollectionReceptor pattern
		PackedCollection kCacheForWrite = new PackedCollection(shape(seqLen, dim));
		kCacheForWrite.clear();

		// Write fullKBranchOutput to cache at position 0
		kCacheForWrite.setMem(0, fullKBranchOutput, 0, dim);

		// Verify cache contents
		double maxCacheDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxCacheDiff = Math.max(maxCacheDiff, Math.abs(kCacheForWrite.toDouble(i) - pytorchKExpanded[i]));
		}
		log(String.format("  Cache write max diff: %.6f %s", maxCacheDiff, maxCacheDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Step 7: Test cache read via attentionKeysStandard
		log("\n=== Step 7: Cache Read via attentionKeysStandard ===");

		// Load Q rope reference
		float[] pytorchQRope = loadReferenceOutput("layer1_q_rope.bin");
		PackedCollection qRope = new PackedCollection(shape(heads, headSize));
		for (int i = 0; i < dim; i++) {
			qRope.setMem(i, pytorchQRope[i]);
		}

		// Create cache with expected K values
		PackedCollection kCacheForRead = new PackedCollection(shape(seqLen, heads, headSize));
		kCacheForRead.clear();
		for (int i = 0; i < dim; i++) {
			kCacheForRead.setMem(i, pytorchKExpanded[i]);
		}

		// Use attentionKeysStandard to compute attention scores
		Model attnKeysModel = new Model(shape(heads, headSize));
		attnKeysModel.add(attentionKeysStandard(shape(heads, headSize), p(kCacheForRead)));
		PackedCollection attnScores = attnKeysModel.compile().forward(qRope);

		log("  Attention scores shape: " + attnScores.getShape());

		// Load reference attention scores
		float[] pytorchAttnScores = loadReferenceOutput("layer1_attn_scores.bin");

		// The reference is (heads, 1, seqLen) but we only care about position 0
		// Our output should be (heads, seqLen) traverseEach
		log("  First few attention scores:");
		for (int h = 0; h < Math.min(3, heads); h++) {
			// Score at position 0 for each head
			double javaScore = attnScores.toDouble(h * seqLen);
			double pytorchScore = pytorchAttnScores[h];  // First position for each head
			log(String.format("    Head %d: Java=%.6f, PyTorch=%.6f, diff=%.6f",
					h, javaScore, pytorchScore, javaScore - pytorchScore));
		}

		stateDict.destroy();
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
