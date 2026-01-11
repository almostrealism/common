package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manually compute attention output (softmax @ V @ W_o) using PyTorch reference data
 * to isolate whether the issue is algorithmic or in compiled model execution.
 */
public class ManualAttentionOutputTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void computeManualAttentionOutput() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/manual_attention_output.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Manual Attention Output Computation");
		log("===================================================\n");

		// Config
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int seqLen = 2;  // positions 0 and 1

		// Load PyTorch reference data
		double[] pytorchSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");
		double[] pytorchVExpanded = loadBinaryLogits(REFERENCE_DIR + "/v_expanded_gqa.bin");
		double[] pytorchAttnOutput = loadBinaryLogits(REFERENCE_DIR + "/attn_output_pos1.bin");
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");

		log("Loaded PyTorch reference data:");
		log("  softmax weights: " + pytorchSoftmax.length + " values (expected: " + (heads * seqLen * seqLen) + ")");
		log("  v_expanded_gqa: " + pytorchVExpanded.length + " values (expected: " + (seqLen * heads * headSize) + ")");
		log("  attn_output_pos1: " + pytorchAttnOutput.length + " values (expected: " + dim + ")");
		log("  o_proj_pos1: " + pytorchOProj.length + " values (expected: " + dim + ")");

		// Load O projection weights
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection wo = stateDict.get("model.layers.0.self_attn.o_proj.weight");
		log("\nO projection weight shape: " + wo.getShape());

		// PyTorch layout for softmax: [batch=1, heads=14, query_len=2, key_len=2]
		// For position 1 query: indices [h, 1, 0] and [h, 1, 1] for each head
		// Linear index: h * 4 + 2 (k=0) and h * 4 + 3 (k=1)

		// PyTorch layout for v_expanded: [heads=14, seq_len=2, head_dim=64]
		// Index [h, pos, d] = h * (seqLen * headSize) + pos * headSize + d

		log("\n=== Step 1: Extract Position 1 Softmax Weights ===");
		double[][] softmaxWeights = new double[heads][seqLen];
		for (int h = 0; h < heads; h++) {
			softmaxWeights[h][0] = pytorchSoftmax[h * 4 + 2];  // pos1 query attending to pos0 key
			softmaxWeights[h][1] = pytorchSoftmax[h * 4 + 3];  // pos1 query attending to pos1 key
			if (h < 3) {
				log(String.format("  Head %d: [%.6f, %.6f]", h, softmaxWeights[h][0], softmaxWeights[h][1]));
			}
		}

		log("\n=== Step 2: Extract V Expanded Values ===");
		// v_expanded[h][pos][d]
		double[][][] vExpanded = new double[heads][seqLen][headSize];
		for (int h = 0; h < heads; h++) {
			for (int pos = 0; pos < seqLen; pos++) {
				for (int d = 0; d < headSize; d++) {
					int idx = h * (seqLen * headSize) + pos * headSize + d;
					vExpanded[h][pos][d] = pytorchVExpanded[idx];
				}
			}
		}
		log("  v_expanded[0][0][:5]: " + String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
				vExpanded[0][0][0], vExpanded[0][0][1], vExpanded[0][0][2], vExpanded[0][0][3], vExpanded[0][0][4]));
		log("  v_expanded[0][1][:5]: " + String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
				vExpanded[0][1][0], vExpanded[0][1][1], vExpanded[0][1][2], vExpanded[0][1][3], vExpanded[0][1][4]));

		log("\n=== Step 3: Compute Attention Output (softmax @ V) ===");
		// For each head, compute: attn_output[h][d] = sum_pos(softmax[h][pos] * v[h][pos][d])
		double[][] attnOutput = new double[heads][headSize];
		for (int h = 0; h < heads; h++) {
			for (int d = 0; d < headSize; d++) {
				double sum = 0;
				for (int pos = 0; pos < seqLen; pos++) {
					sum += softmaxWeights[h][pos] * vExpanded[h][pos][d];
				}
				attnOutput[h][d] = sum;
			}
		}

		// Reshape to [dim] by concatenating all heads
		double[] attnOutputFlat = new double[dim];
		for (int h = 0; h < heads; h++) {
			for (int d = 0; d < headSize; d++) {
				attnOutputFlat[h * headSize + d] = attnOutput[h][d];
			}
		}

		log("  Manual attn_output[:5]: " + String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
				attnOutputFlat[0], attnOutputFlat[1], attnOutputFlat[2], attnOutputFlat[3], attnOutputFlat[4]));
		log("  PyTorch attn_output[:5]: " + String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
				pytorchAttnOutput[0], pytorchAttnOutput[1], pytorchAttnOutput[2], pytorchAttnOutput[3], pytorchAttnOutput[4]));

		// Compare
		double maxAttnDiff = 0;
		double sumAttnDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(attnOutputFlat[i] - pytorchAttnOutput[i]);
			maxAttnDiff = Math.max(maxAttnDiff, diff);
			sumAttnDiff += diff;
		}
		log(String.format("\n  Attention output comparison: mean=%.6f, max=%.6f", sumAttnDiff / dim, maxAttnDiff));

		log("\n=== Step 4: Apply O Projection ===");
		// o_proj = attn_output @ W_o^T
		// W_o shape is [dim, dim], so o_proj[i] = sum_j(attn_output[j] * W_o[i][j])
		double[] oProj = new double[dim];
		for (int i = 0; i < dim; i++) {
			double sum = 0;
			for (int j = 0; j < dim; j++) {
				sum += attnOutputFlat[j] * wo.toDouble(i * dim + j);
			}
			oProj[i] = sum;
		}

		log("  Manual o_proj[:5]: " + String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
				oProj[0], oProj[1], oProj[2], oProj[3], oProj[4]));
		log("  PyTorch o_proj[:5]: " + String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
				pytorchOProj[0], pytorchOProj[1], pytorchOProj[2], pytorchOProj[3], pytorchOProj[4]));

		// Compare
		double maxOProjDiff = 0;
		double sumOProjDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(oProj[i] - pytorchOProj[i]);
			maxOProjDiff = Math.max(maxOProjDiff, diff);
			sumOProjDiff += diff;
		}
		log(String.format("\n  O projection comparison: mean=%.6f, max=%.6f", sumOProjDiff / dim, maxOProjDiff));

		log("\n=== Summary ===");
		if (maxAttnDiff < 1e-4 && maxOProjDiff < 1e-4) {
			log("[OK] Manual computation matches PyTorch exactly!");
			log("     This confirms the algorithm is correct.");
			log("     Issue must be in compiled model's shape transformations.");
		} else if (maxAttnDiff < 1e-4) {
			log("[PARTIAL] Attention output matches, but O projection differs.");
			log("     Issue is in O projection weight indexing or layout.");
		} else {
			log("[MISMATCH] Attention output differs from PyTorch.");
			log("     Issue is in softmax @ V computation or V value extraction.");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private double[] loadBinaryLogits(String path) throws Exception {
		try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
			byte[] sizeBytes = new byte[4];
			dis.readFully(sizeBytes);
			int size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

			double[] logits = new double[size];
			byte[] floatBytes = new byte[4];
			for (int i = 0; i < size; i++) {
				dis.readFully(floatBytes);
				logits[i] = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
			}
			return logits;
		}
	}
}
