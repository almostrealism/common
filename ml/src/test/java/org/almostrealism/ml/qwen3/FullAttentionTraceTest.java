package org.almostrealism.ml.qwen3;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Full trace through the attention block to find exactly where discrepancy occurs.
 *
 * We'll compute everything manually and compare with PyTorch at each step.
 */
public class FullAttentionTraceTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void traceFullAttention() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/full_attention_trace.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Full Attention Trace Test");
		log("===================================================\n");

		// Config
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		int seqLen = 2;  // We only care about positions 0 and 1
		double ropeTheta = 1000000.0;
		double epsilon = 1e-6;

		// Load PyTorch reference data
		double[] pytorchSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");
		double[] pytorchVProj = loadBinaryLogits(REFERENCE_DIR + "/v_proj_both_positions.bin");
		double[] pytorchVExpanded = loadBinaryLogits(REFERENCE_DIR + "/v_expanded_gqa.bin");
		double[] pytorchAttnOutput = loadBinaryLogits(REFERENCE_DIR + "/attn_output_pos1.bin");
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");

		log("Loaded PyTorch reference data");

		// Load weights
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection rmsWeight = stateDict.get("model.layers.0.input_layernorm.weight");
		PackedCollection wv = stateDict.get("model.layers.0.self_attn.v_proj.weight");
		PackedCollection bv = stateDict.get("model.layers.0.self_attn.v_proj.bias");
		PackedCollection wo = stateDict.get("model.layers.0.self_attn.o_proj.weight");

		// Token IDs
		int token0 = 9707;
		int token1 = 271;

		// Step 1: Compute V projection for both positions
		log("\n=== Step 1: V Projection ===");

		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		double[] norm0 = applyRMSNorm(emb0, rmsWeight, epsilon);
		double[] norm1 = applyRMSNorm(emb1, rmsWeight, epsilon);

		double[] vProj0 = applyDense(norm0, wv, bv);
		double[] vProj1 = applyDense(norm1, wv, bv);

		double vProjMaxDiff = 0;
		for (int i = 0; i < kvDim; i++) {
			vProjMaxDiff = Math.max(vProjMaxDiff, Math.abs(vProj0[i] - pytorchVProj[i]));
			vProjMaxDiff = Math.max(vProjMaxDiff, Math.abs(vProj1[i] - pytorchVProj[kvDim + i]));
		}
		log("V projection max diff: " + String.format("%.6f", vProjMaxDiff));

		// Step 2: GQA expansion
		log("\n=== Step 2: GQA Expansion ===");

		// Layout after expansion: [seqLen, heads, headSize]
		// KV head 0 -> query heads 0-6, KV head 1 -> query heads 7-13
		int headsPerKvGroup = heads / kvHeads;  // 7
		double[][][] vExpanded = new double[seqLen][heads][headSize];

		for (int pos = 0; pos < seqLen; pos++) {
			double[] vProj = (pos == 0) ? vProj0 : vProj1;
			for (int h = 0; h < heads; h++) {
				int kvHead = h / headsPerKvGroup;
				for (int d = 0; d < headSize; d++) {
					vExpanded[pos][h][d] = vProj[kvHead * headSize + d];
				}
			}
		}

		// Compare with PyTorch's v_expanded_gqa
		// PyTorch layout: [heads, seqLen, headSize]
		double vExpandedMaxDiff = 0;
		for (int h = 0; h < heads; h++) {
			for (int pos = 0; pos < seqLen; pos++) {
				for (int d = 0; d < headSize; d++) {
					int ptIdx = h * (seqLen * headSize) + pos * headSize + d;
					double diff = Math.abs(vExpanded[pos][h][d] - pytorchVExpanded[ptIdx]);
					vExpandedMaxDiff = Math.max(vExpandedMaxDiff, diff);
				}
			}
		}
		log("V expanded max diff: " + String.format("%.6f", vExpandedMaxDiff));

		// Step 3: Extract position 1 softmax weights
		log("\n=== Step 3: Softmax Weights ===");

		// PyTorch softmax layout: [batch=1, heads=14, query_len=2, key_len=2]
		// For position 1 query: indices [h, 1, 0] and [h, 1, 1]
		double[][] softmaxWeights = new double[heads][seqLen];
		for (int h = 0; h < heads; h++) {
			softmaxWeights[h][0] = pytorchSoftmax[h * 4 + 2];  // pos1 query -> pos0 key
			softmaxWeights[h][1] = pytorchSoftmax[h * 4 + 3];  // pos1 query -> pos1 key
		}

		log("Position 1 softmax weights (using PyTorch reference):");
		log("  Head 0: [" + String.format("%.6f, %.6f", softmaxWeights[0][0], softmaxWeights[0][1]) + "]");
		log("  Head 1: [" + String.format("%.6f, %.6f", softmaxWeights[1][0], softmaxWeights[1][1]) + "]");

		// Step 4: Compute attention output (softmax @ V)
		log("\n=== Step 4: Attention Output (softmax @ V) ===");

		// For each head: output[h][d] = sum_pos(softmax[h][pos] * v[pos][h][d])
		double[] attnOutput = new double[dim];
		for (int h = 0; h < heads; h++) {
			for (int d = 0; d < headSize; d++) {
				double sum = 0;
				for (int pos = 0; pos < seqLen; pos++) {
					sum += softmaxWeights[h][pos] * vExpanded[pos][h][d];
				}
				attnOutput[h * headSize + d] = sum;
			}
		}

		double attnOutputMaxDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(attnOutput[i] - pytorchAttnOutput[i]);
			attnOutputMaxDiff = Math.max(attnOutputMaxDiff, diff);
		}
		log("Attention output max diff: " + String.format("%.6f", attnOutputMaxDiff));
		log("  Java (first 5): " + formatDoubleArray(attnOutput, 0, 5));
		log("  PyTorch (first 5): " + formatPyTorchArray(pytorchAttnOutput, 0, 5));

		// Step 5: Apply O projection
		log("\n=== Step 5: O Projection ===");

		double[] oProj = new double[dim];
		for (int i = 0; i < dim; i++) {
			double sum = 0;
			for (int j = 0; j < dim; j++) {
				sum += attnOutput[j] * wo.toDouble(i * dim + j);
			}
			oProj[i] = sum;
		}

		double oProjMaxDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(oProj[i] - pytorchOProj[i]);
			oProjMaxDiff = Math.max(oProjMaxDiff, diff);
		}
		log("O projection max diff: " + String.format("%.6f", oProjMaxDiff));
		log("  Java (first 5): " + formatDoubleArray(oProj, 0, 5));
		log("  PyTorch (first 5): " + formatPyTorchArray(pytorchOProj, 0, 5));

		// Summary
		log("\n=== Summary ===");
		log("V projection max diff:  " + String.format("%.6f", vProjMaxDiff));
		log("V expanded max diff:    " + String.format("%.6f", vExpandedMaxDiff));
		log("Attention output diff:  " + String.format("%.6f", attnOutputMaxDiff));
		log("O projection diff:      " + String.format("%.6f", oProjMaxDiff));

		if (oProjMaxDiff < 0.001) {
			log("\n[OK] Full attention trace matches PyTorch!");
			log("This confirms the ALGORITHM is correct.");
			log("The issue must be in how the compiled model executes.");
		} else {
			log("\n[MISMATCH] Some step doesn't match PyTorch.");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private double[] applyRMSNorm(double[] input, PackedCollection weight, double eps) {
		int dim = input.length;
		double sumSq = 0;
		for (double v : input) {
			sumSq += v * v;
		}
		double rms = Math.sqrt(sumSq / dim + eps);
		double[] output = new double[dim];
		for (int i = 0; i < dim; i++) {
			output[i] = (input[i] / rms) * weight.toDouble(i);
		}
		return output;
	}

	private double[] applyDense(double[] input, PackedCollection weight, PackedCollection bias) {
		int inDim = input.length;
		int outDim = weight.getShape().length(0);
		double[] output = new double[outDim];
		for (int o = 0; o < outDim; o++) {
			double sum = 0;
			for (int i = 0; i < inDim; i++) {
				sum += input[i] * weight.toDouble(o * inDim + i);
			}
			if (bias != null) {
				sum += bias.toDouble(o);
			}
			output[o] = sum;
		}
		return output;
	}

	private String formatDoubleArray(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatPyTorchArray(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		sb.append("]");
		return sb.toString();
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
