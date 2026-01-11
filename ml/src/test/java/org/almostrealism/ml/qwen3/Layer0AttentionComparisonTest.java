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
 * Compare Java's layer 0 attention computation with PyTorch reference.
 * This test manually computes attention step by step to identify where divergence occurs.
 */
public class Layer0AttentionComparisonTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void compareLayer0Attention() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer0_attention_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Layer 0 Attention Comparison: Java vs PyTorch");
		log("===================================================\n");

		// Load PyTorch reference data
		log("Loading PyTorch reference intermediates...");

		// attn_scores_before_mask.bin has shape [1, 14, 2, 2] = 56 values
		double[] pytorchScoresBeforeMask = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_before_mask.bin");
		double[] pytorchScoresAfterMask = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_after_mask.bin");
		double[] pytorchSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");

		// Q/K/V projections - both positions
		double[] pytorchQProj = loadBinaryLogits(REFERENCE_DIR + "/q_proj_both_positions.bin");
		double[] pytorchKProj = loadBinaryLogits(REFERENCE_DIR + "/k_proj_both_positions.bin");

		// RoPE'd values
		double[] pytorchQRopePos1 = loadBinaryLogits(REFERENCE_DIR + "/q_rope_pos1.bin");
		double[] pytorchKRopeBoth = loadBinaryLogits(REFERENCE_DIR + "/k_rope_both.bin");

		log("  attn_scores_before_mask: " + pytorchScoresBeforeMask.length + " values");
		log("  q_proj_both_positions: " + pytorchQProj.length + " values");
		log("  k_proj_both_positions: " + pytorchKProj.length + " values");
		log("  q_rope_pos1: " + pytorchQRopePos1.length + " values");
		log("  k_rope_both: " + pytorchKRopeBoth.length + " values");

		// Config from Qwen2.5-0.5B
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		double ropeTheta = 1000000.0;

		log("\n=== Config ===");
		log("dim=" + dim + ", heads=" + heads + ", kvHeads=" + kvHeads);
		log("headSize=" + headSize + ", kvDim=" + kvDim);

		// Print PyTorch attention scores for position 1 query
		log("\n=== PyTorch Attention Scores (Position 1 Query, Before Mask) ===");
		for (int h = 0; h < heads; h++) {
			// Index into [1, 14, 2, 2]: batch=0, head=h, query_pos=1, key_pos=0/1
			// Flattened: h * 4 + query_pos * 2 + key_pos
			int idx0 = h * 4 + 2;  // query_pos=1, key_pos=0
			int idx1 = h * 4 + 3;  // query_pos=1, key_pos=1
			log(String.format("  Head %2d: score(q1->k0)=%.4f, score(q1->k1)=%.4f",
					h, pytorchScoresBeforeMask[idx0], pytorchScoresBeforeMask[idx1]));
		}

		log("\n=== PyTorch Softmax Weights (Position 1 Query) ===");
		for (int h = 0; h < heads; h++) {
			int idx0 = h * 4 + 2;
			int idx1 = h * 4 + 3;
			log(String.format("  Head %2d: w0=%.6f, w1=%.6f",
					h, pytorchSoftmax[idx0], pytorchSoftmax[idx1]));
		}

		// Load weights
		log("\n=== Loading Weights ===");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Token embeddings
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		log("Token embeddings shape: " + embeddings.getShape());

		// Layer 0 weights
		PackedCollection rmsAttWeight = stateDict.get("model.layers.0.input_layernorm.weight");
		PackedCollection wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get("model.layers.0.self_attn.k_proj.weight");
		PackedCollection bq = stateDict.get("model.layers.0.self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get("model.layers.0.self_attn.k_proj.bias");

		log("RMSNorm weight shape: " + rmsAttWeight.getShape());
		log("Q projection weight shape: " + wq.getShape());
		log("K projection weight shape: " + wk.getShape());
		log("Q bias shape: " + (bq != null ? bq.getShape().toString() : "null"));
		log("K bias shape: " + (bk != null ? bk.getShape().toString() : "null"));

		// Get token embeddings
		int token0 = 9707;  // "Hello"
		int token1 = 271;   // "\n\n"

		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		log("\n=== Token Embeddings (first 5 values) ===");
		log(String.format("Token %d: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				token0, emb0[0], emb0[1], emb0[2], emb0[3], emb0[4]));
		log(String.format("Token %d: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				token1, emb1[0], emb1[1], emb1[2], emb1[3], emb1[4]));

		// Apply RMSNorm to both embeddings
		double[] norm0 = applyRMSNorm(emb0, rmsAttWeight, 1e-6);
		double[] norm1 = applyRMSNorm(emb1, rmsAttWeight, 1e-6);

		log("\n=== After RMSNorm (first 5 values) ===");
		log(String.format("Token %d: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				token0, norm0[0], norm0[1], norm0[2], norm0[3], norm0[4]));
		log(String.format("Token %d: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				token1, norm1[0], norm1[1], norm1[2], norm1[3], norm1[4]));

		// Compare with PyTorch RMSNorm output
		// hidden_after_rmsnorm.bin has shape [2, 896] for both positions
		double[] pytorchNorm = loadBinaryLogits(REFERENCE_DIR + "/hidden_after_rmsnorm.bin");
		log("\n=== PyTorch RMSNorm (first 5 values for each position) ===");
		log(String.format("Position 0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchNorm[0], pytorchNorm[1], pytorchNorm[2], pytorchNorm[3], pytorchNorm[4]));
		log(String.format("Position 1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchNorm[dim], pytorchNorm[dim+1], pytorchNorm[dim+2], pytorchNorm[dim+3], pytorchNorm[dim+4]));

		// Compute RMSNorm differences
		double maxNormDiff0 = 0, maxNormDiff1 = 0;
		for (int i = 0; i < dim; i++) {
			maxNormDiff0 = Math.max(maxNormDiff0, Math.abs(norm0[i] - pytorchNorm[i]));
			maxNormDiff1 = Math.max(maxNormDiff1, Math.abs(norm1[i] - pytorchNorm[dim + i]));
		}
		log(String.format("\nMax RMSNorm difference: pos0=%.6f, pos1=%.6f", maxNormDiff0, maxNormDiff1));

		// Apply Q/K projections
		double[] qProj0 = applyDense(norm0, wq, bq);
		double[] qProj1 = applyDense(norm1, wq, bq);
		double[] kProj0 = applyDense(norm0, wk, bk);
		double[] kProj1 = applyDense(norm1, wk, bk);

		log("\n=== After Q Projection (first 5 values) ===");
		log(String.format("Position 0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				qProj0[0], qProj0[1], qProj0[2], qProj0[3], qProj0[4]));
		log(String.format("Position 1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				qProj1[0], qProj1[1], qProj1[2], qProj1[3], qProj1[4]));

		log("\n=== PyTorch Q Projection (first 5 values for each position) ===");
		// q_proj_both_positions has shape [2, 896] for both positions
		log(String.format("Position 0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchQProj[0], pytorchQProj[1], pytorchQProj[2], pytorchQProj[3], pytorchQProj[4]));
		log(String.format("Position 1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchQProj[dim], pytorchQProj[dim+1], pytorchQProj[dim+2], pytorchQProj[dim+3], pytorchQProj[dim+4]));

		double maxQDiff0 = 0, maxQDiff1 = 0;
		for (int i = 0; i < dim; i++) {
			maxQDiff0 = Math.max(maxQDiff0, Math.abs(qProj0[i] - pytorchQProj[i]));
			maxQDiff1 = Math.max(maxQDiff1, Math.abs(qProj1[i] - pytorchQProj[dim + i]));
		}
		log(String.format("\nMax Q projection difference: pos0=%.6f, pos1=%.6f", maxQDiff0, maxQDiff1));

		log("\n=== After K Projection (first 5 values) ===");
		log(String.format("Position 0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				kProj0[0], kProj0[1], kProj0[2], kProj0[3], kProj0[4]));
		log(String.format("Position 1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				kProj1[0], kProj1[1], kProj1[2], kProj1[3], kProj1[4]));

		// k_proj_both_positions has shape [2, 128] for both positions
		log("\n=== PyTorch K Projection (first 5 values for each position) ===");
		log(String.format("Position 0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchKProj[0], pytorchKProj[1], pytorchKProj[2], pytorchKProj[3], pytorchKProj[4]));
		log(String.format("Position 1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchKProj[kvDim], pytorchKProj[kvDim+1], pytorchKProj[kvDim+2], pytorchKProj[kvDim+3], pytorchKProj[kvDim+4]));

		double maxKDiff0 = 0, maxKDiff1 = 0;
		for (int i = 0; i < kvDim; i++) {
			maxKDiff0 = Math.max(maxKDiff0, Math.abs(kProj0[i] - pytorchKProj[i]));
			maxKDiff1 = Math.max(maxKDiff1, Math.abs(kProj1[i] - pytorchKProj[kvDim + i]));
		}
		log(String.format("\nMax K projection difference: pos0=%.6f, pos1=%.6f", maxKDiff0, maxKDiff1));

		// Apply RoPE
		// Note: Qwen2.5-0.5B does NOT have QK-Norm, so we apply RoPE directly after projection
		double[] qRope0 = applyRope(qProj0, 0, heads, headSize, ropeTheta);
		double[] qRope1 = applyRope(qProj1, 1, heads, headSize, ropeTheta);
		double[] kRope0 = applyRope(kProj0, 0, kvHeads, headSize, ropeTheta);
		double[] kRope1 = applyRope(kProj1, 1, kvHeads, headSize, ropeTheta);

		log("\n=== After RoPE (first 5 values of Q at position 1) ===");
		log(String.format("Java Q pos1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				qRope1[0], qRope1[1], qRope1[2], qRope1[3], qRope1[4]));

		// q_rope_pos1 has shape [14, 64] for position 1 only
		log(String.format("PyTorch Q pos1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchQRopePos1[0], pytorchQRopePos1[1], pytorchQRopePos1[2], pytorchQRopePos1[3], pytorchQRopePos1[4]));

		double maxQRopeDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxQRopeDiff = Math.max(maxQRopeDiff, Math.abs(qRope1[i] - pytorchQRopePos1[i]));
		}
		log(String.format("\nMax Q RoPE difference at pos1: %.6f", maxQRopeDiff));

		log("\n=== After RoPE (first 5 values of K) ===");
		log(String.format("Java K pos0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				kRope0[0], kRope0[1], kRope0[2], kRope0[3], kRope0[4]));
		log(String.format("Java K pos1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				kRope1[0], kRope1[1], kRope1[2], kRope1[3], kRope1[4]));

		// k_rope_both has shape [2, 2, 64] for both positions
		log(String.format("PyTorch K pos0: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchKRopeBoth[0], pytorchKRopeBoth[1], pytorchKRopeBoth[2], pytorchKRopeBoth[3], pytorchKRopeBoth[4]));
		log(String.format("PyTorch K pos1: [%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
				pytorchKRopeBoth[kvDim], pytorchKRopeBoth[kvDim+1], pytorchKRopeBoth[kvDim+2], pytorchKRopeBoth[kvDim+3], pytorchKRopeBoth[kvDim+4]));

		double maxKRopeDiff0 = 0, maxKRopeDiff1 = 0;
		for (int i = 0; i < kvDim; i++) {
			maxKRopeDiff0 = Math.max(maxKRopeDiff0, Math.abs(kRope0[i] - pytorchKRopeBoth[i]));
			maxKRopeDiff1 = Math.max(maxKRopeDiff1, Math.abs(kRope1[i] - pytorchKRopeBoth[kvDim + i]));
		}
		log(String.format("\nMax K RoPE difference: pos0=%.6f, pos1=%.6f", maxKRopeDiff0, maxKRopeDiff1));

		// Compute attention scores for position 1 query
		log("\n=== Computing Attention Scores ===");
		// For GQA: each KV head serves multiple query heads
		// kvHead 0 -> query heads 0-6
		// kvHead 1 -> query heads 7-13
		int headsPerKvGroup = heads / kvHeads;  // 7

		double[][] javaScores = new double[heads][2];  // scores to pos0 and pos1
		for (int h = 0; h < heads; h++) {
			int kvHead = h / headsPerKvGroup;
			// Q at position 1, head h
			double[] qHead = new double[headSize];
			for (int i = 0; i < headSize; i++) {
				qHead[i] = qRope1[h * headSize + i];
			}
			// K at positions 0 and 1, KV head
			double[] kHead0 = new double[headSize];
			double[] kHead1 = new double[headSize];
			for (int i = 0; i < headSize; i++) {
				kHead0[i] = kRope0[kvHead * headSize + i];
				kHead1[i] = kRope1[kvHead * headSize + i];
			}
			// Dot products
			double score0 = 0, score1 = 0;
			for (int i = 0; i < headSize; i++) {
				score0 += qHead[i] * kHead0[i];
				score1 += qHead[i] * kHead1[i];
			}
			// Scale by 1/sqrt(headSize)
			double scale = 1.0 / Math.sqrt(headSize);
			javaScores[h][0] = score0 * scale;
			javaScores[h][1] = score1 * scale;
		}

		log("\n=== Java Attention Scores (Position 1 Query) ===");
		for (int h = 0; h < heads; h++) {
			int pytorchIdx0 = h * 4 + 2;
			int pytorchIdx1 = h * 4 + 3;
			double diff0 = javaScores[h][0] - pytorchScoresBeforeMask[pytorchIdx0];
			double diff1 = javaScores[h][1] - pytorchScoresBeforeMask[pytorchIdx1];
			log(String.format("  Head %2d: Java=(%.4f, %.4f), PyTorch=(%.4f, %.4f), diff=(%.4f, %.4f)",
					h, javaScores[h][0], javaScores[h][1],
					pytorchScoresBeforeMask[pytorchIdx0], pytorchScoresBeforeMask[pytorchIdx1],
					diff0, diff1));
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

	private double[] applyRope(double[] input, int position, int numHeads, int headSize, double theta) {
		double[] output = new double[input.length];
		int halfHeadSize = headSize / 2;

		for (int h = 0; h < numHeads; h++) {
			int offset = h * headSize;
			for (int i = 0; i < halfHeadSize; i++) {
				// Compute frequency: 1 / (theta^(2i/headSize))
				double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
				double angle = position * freq;
				double cos = Math.cos(angle);
				double sin = Math.sin(angle);

				// Apply rotation
				double x0 = input[offset + i];
				double x1 = input[offset + halfHeadSize + i];
				output[offset + i] = x0 * cos - x1 * sin;
				output[offset + halfHeadSize + i] = x0 * sin + x1 * cos;
			}
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
