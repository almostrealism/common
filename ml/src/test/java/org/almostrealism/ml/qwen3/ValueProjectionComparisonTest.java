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
 * Compare Java's value projection with PyTorch reference.
 */
public class ValueProjectionComparisonTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void compareValueProjection() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/value_projection_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Value Projection Comparison: Java vs PyTorch");
		log("===================================================\n");

		// Load PyTorch reference data
		double[] pytorchVProj = loadBinaryLogits(REFERENCE_DIR + "/v_proj_both_positions.bin");
		double[] pytorchVExpanded = loadBinaryLogits(REFERENCE_DIR + "/v_expanded_gqa.bin");

		// Config
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128

		log("Loaded PyTorch reference data:");
		log("  v_proj: " + pytorchVProj.length + " values (expected: " + (2 * kvDim) + ")");
		log("  v_expanded_gqa: " + pytorchVExpanded.length + " values (expected: " + (2 * dim) + ")");

		// Load weights
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection rmsWeight = stateDict.get("model.layers.0.input_layernorm.weight");
		PackedCollection wv = stateDict.get("model.layers.0.self_attn.v_proj.weight");
		PackedCollection bv = stateDict.get("model.layers.0.self_attn.v_proj.bias");

		log("\nWeight shapes:");
		log("  V projection: " + wv.getShape());
		log("  V bias: " + (bv != null ? bv.getShape().toString() : "null"));

		// Get token embeddings
		int token0 = 9707;
		int token1 = 271;

		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		// Apply RMSNorm
		double[] norm0 = applyRMSNorm(emb0, rmsWeight, 1e-6);
		double[] norm1 = applyRMSNorm(emb1, rmsWeight, 1e-6);

		// Apply V projection
		double[] vProj0 = applyDense(norm0, wv, bv);
		double[] vProj1 = applyDense(norm1, wv, bv);

		log("\n=== V Projection Comparison ===");
		log("\nPosition 0 (first 5 values):");
		log(String.format("  Java:    [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				vProj0[0], vProj0[1], vProj0[2], vProj0[3], vProj0[4]));
		log(String.format("  PyTorch: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchVProj[0], pytorchVProj[1], pytorchVProj[2], pytorchVProj[3], pytorchVProj[4]));

		log("\nPosition 1 (first 5 values):");
		log(String.format("  Java:    [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				vProj1[0], vProj1[1], vProj1[2], vProj1[3], vProj1[4]));
		log(String.format("  PyTorch: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchVProj[kvDim], pytorchVProj[kvDim+1], pytorchVProj[kvDim+2],
				pytorchVProj[kvDim+3], pytorchVProj[kvDim+4]));

		// Compute differences
		double maxDiff0 = 0, maxDiff1 = 0;
		for (int i = 0; i < kvDim; i++) {
			maxDiff0 = Math.max(maxDiff0, Math.abs(vProj0[i] - pytorchVProj[i]));
			maxDiff1 = Math.max(maxDiff1, Math.abs(vProj1[i] - pytorchVProj[kvDim + i]));
		}
		log(String.format("\nMax V projection difference: pos0=%.6f, pos1=%.6f", maxDiff0, maxDiff1));

		// Check GQA expansion
		log("\n=== GQA Expansion Check ===");
		// v_expanded_gqa has shape [kv_heads=2, seq_len=2, head_dim=64] -> need to check layout
		// After expansion, shape should be [heads=14, seq_len=2, head_dim=64]
		log("PyTorch v_expanded shape would be [2, 14, 64] = 1792 values");
		log("Actual size: " + pytorchVExpanded.length);

		// The PyTorch export saves [2, 14, 64] which is 1792 values
		// Index [0, h, d] = position 0, head h, dimension d
		// But the layout might be different... let me check the Python export

		// From the Python script:
		// value_states_expanded = value_states[:, :, None, :, :].expand(-1, -1, n_rep, -1, -1)
		// value_states_expanded = value_states_expanded.reshape(bsz, num_heads, q_len, head_dim)
		// save_tensor(value_states_expanded[0], output_dir / "v_expanded_gqa.bin")
		// So shape is [num_heads=14, q_len=2, head_dim=64] = 1792 values

		// Let me verify by checking if the values match expected GQA expansion
		// KV head 0 should be repeated for query heads 0-6
		// KV head 1 should be repeated for query heads 7-13

		// Position 0, KV head 0 values
		log("\nPosition 0, KV head 0 values (first 5):");
		log(String.format("  Java V proj: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				vProj0[0], vProj0[1], vProj0[2], vProj0[3], vProj0[4]));

		// Position 0, query head 0 values from PyTorch expanded
		// Index in [14, 2, 64]: head * 2 * 64 + pos * 64 + dim
		log(String.format("  PyTorch expanded head 0 pos 0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchVExpanded[0*2*64 + 0*64 + 0],
				pytorchVExpanded[0*2*64 + 0*64 + 1],
				pytorchVExpanded[0*2*64 + 0*64 + 2],
				pytorchVExpanded[0*2*64 + 0*64 + 3],
				pytorchVExpanded[0*2*64 + 0*64 + 4]));

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
