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
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Test that compares intermediate attention values step by step against PyTorch.
 */
public class IntermediateComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testIntermediateValues() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/intermediate_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Intermediate Values Comparison Test");
		log("===================================================\n");

		// Config from Qwen
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		int seqLen = 4;
		double ropeTheta = 1000000.0;
		double epsilon = 1e-6;

		// Load weights
		log("Loading weights...");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection rmsAttWeight = stateDict.get("model.layers.0.input_layernorm.weight");
		PackedCollection wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get("model.layers.0.self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get("model.layers.0.self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get("model.layers.0.self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get("model.layers.0.self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get("model.layers.0.self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get("model.layers.0.self_attn.v_proj.bias");
		// QK-Norm weights (Qwen3 specific) - may be null if not exported
		PackedCollection qkNormQ = stateDict.get("model.layers.0.self_attn.q_norm.weight");
		PackedCollection qkNormK = stateDict.get("model.layers.0.self_attn.k_norm.weight");
		log("Weights loaded.");
		boolean hasQKNorm = (qkNormQ != null && qkNormK != null);
		if (hasQKNorm) {
			log("QK-Norm Q shape: " + qkNormQ.getShape());
			log("QK-Norm K shape: " + qkNormK.getShape());
		} else {
			log("WARNING: QK-Norm weights not found in StateDictionary - skipping QK-Norm");
		}

		// Compute RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, ropeTheta);

		// Position
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);
		Producer<PackedCollection> positionProducer = dynamicPosition(position);

		// Token IDs
		int token0 = 9707;  // "Hello"
		int token1 = 271;   // "\n\n"

		// Load PyTorch references
		log("\n=== Loading PyTorch References ===");
		double[] pytorchQRope = loadBinaryLogits(REFERENCE_DIR + "/q_rope_pos1.bin");
		double[] pytorchKRopeBoth = loadBinaryLogits(REFERENCE_DIR + "/k_rope_both.bin");
		double[] pytorchKExpandedGQA = loadBinaryLogits(REFERENCE_DIR + "/k_expanded_gqa.bin");
		double[] pytorchVExpandedGQA = loadBinaryLogits(REFERENCE_DIR + "/v_expanded_gqa.bin");
		double[] pytorchAttnScoresBefore = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_before_mask.bin");
		double[] pytorchAttnScoresAfter = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_after_mask.bin");
		double[] pytorchSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");
		double[] pytorchAttnOutput = loadBinaryLogits(REFERENCE_DIR + "/attn_output_pos1.bin");
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");

		log("q_rope_pos1 size: " + pytorchQRope.length);
		log("k_rope_both size: " + pytorchKRopeBoth.length);
		log("k_expanded_gqa size: " + pytorchKExpandedGQA.length);
		log("v_expanded_gqa size: " + pytorchVExpandedGQA.length);
		log("attn_scores_before_mask size: " + pytorchAttnScoresBefore.length);
		log("attn_scores_after_mask size: " + pytorchAttnScoresAfter.length);
		log("attn_weights_softmax size: " + pytorchSoftmax.length);
		log("attn_output_pos1 size: " + pytorchAttnOutput.length);
		log("o_proj_pos1 size: " + pytorchOProj.length);

		// === Step 1: Compute Q projection with RoPE ===
		log("\n=== Step 1: Query RoPE ===");
		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		double[] norm1 = applyRMSNorm(emb1, rmsAttWeight, epsilon);
		double[] qProj1 = applyDense(norm1, wq, bq);
		// Apply QK-Norm (per-head RMSNorm) before RoPE if available
		double[] qNormed1 = hasQKNorm ? applyPerHeadRMSNorm(qProj1, qkNormQ, 1e-6, heads, headSize) : qProj1;
		double[] qRope1 = applyRoPE(qNormed1, freqCis, 1, heads, headSize);

		log("Java Q RoPE [0-4]: " + formatArray(qRope1, 0, 5));
		log("PyTorch Q RoPE [0-4]: " + formatArray(pytorchQRope, 0, 5));

		double qRopeDiff = maxAbsDiff(qRope1, pytorchQRope);
		log(String.format("Q RoPE max diff: %.6f", qRopeDiff));

		// === Step 2: Build K projection for both positions ===
		log("\n=== Step 2: Key RoPE ===");
		double[] norm0 = applyRMSNorm(emb0, rmsAttWeight, epsilon);
		double[] kProj0 = applyDense(norm0, wk, bk);
		// Apply QK-Norm (per-head RMSNorm) before RoPE if available
		double[] kNormed0 = hasQKNorm ? applyPerHeadRMSNorm(kProj0, qkNormK, 1e-6, kvHeads, headSize) : kProj0;
		double[] kRope0 = applyRoPE(kNormed0, freqCis, 0, kvHeads, headSize);

		double[] kProj1 = applyDense(norm1, wk, bk);
		double[] kNormed1 = hasQKNorm ? applyPerHeadRMSNorm(kProj1, qkNormK, 1e-6, kvHeads, headSize) : kProj1;
		double[] kRope1 = applyRoPE(kNormed1, freqCis, 1, kvHeads, headSize);

		log("Java K RoPE pos0 [0-4]: " + formatArray(kRope0, 0, 5));
		log("Java K RoPE pos0 [32-36]: " + formatArray(kRope0, 32, 5));  // Middle of kvHead 0
		log("Java K RoPE pos0 [60-64]: " + formatArray(kRope0, 60, 5));  // End of kvHead 0
		log("Java K RoPE pos0 [64-68]: " + formatArray(kRope0, 64, 5));  // Start of kvHead 1
		log("Java K RoPE pos1 [0-4]: " + formatArray(kRope1, 0, 5));

		// k_rope_both is shaped (kvHeads=2, seqLen=2, headSize=64) = 256 floats
		// Layout: kvHead0[pos0, headSize], kvHead0[pos1, headSize], kvHead1[pos0, headSize], kvHead1[pos1, headSize]
		// We need to extract: pos0 = [kvHead0_pos0, kvHead1_pos0], pos1 = [kvHead0_pos1, kvHead1_pos1]
		double[] pytorchKRope0 = new double[kvDim];
		double[] pytorchKRope1 = new double[kvDim];
		// kvHead 0, pos 0: indices 0-63
		System.arraycopy(pytorchKRopeBoth, 0, pytorchKRope0, 0, headSize);
		// kvHead 0, pos 1: indices 64-127
		System.arraycopy(pytorchKRopeBoth, headSize, pytorchKRope1, 0, headSize);
		// kvHead 1, pos 0: indices 128-191
		System.arraycopy(pytorchKRopeBoth, 2 * headSize, pytorchKRope0, headSize, headSize);
		// kvHead 1, pos 1: indices 192-255
		System.arraycopy(pytorchKRopeBoth, 3 * headSize, pytorchKRope1, headSize, headSize);

		log("PyTorch K RoPE pos0 [0-4]: " + formatArray(pytorchKRope0, 0, 5));
		log("PyTorch K RoPE pos0 [32-36]: " + formatArray(pytorchKRope0, 32, 5));  // Middle of kvHead 0
		log("PyTorch K RoPE pos0 [60-64]: " + formatArray(pytorchKRope0, 60, 5));  // End of kvHead 0
		log("PyTorch K RoPE pos0 [64-68]: " + formatArray(pytorchKRope0, 64, 5));  // Start of kvHead 1
		log("PyTorch K RoPE pos1 [0-4]: " + formatArray(pytorchKRope1, 0, 5));

		double kRope0Diff = maxAbsDiff(kRope0, pytorchKRope0);
		double kRope1Diff = maxAbsDiff(kRope1, pytorchKRope1);
		int maxDiffIdx0 = findMaxAbsDiffIdx(kRope0, pytorchKRope0);
		log(String.format("K RoPE pos0 max diff: %.6f at index %d (Java=%.4f, PyTorch=%.4f)",
			kRope0Diff, maxDiffIdx0, kRope0[maxDiffIdx0], pytorchKRope0[maxDiffIdx0]));
		log(String.format("K RoPE pos1 max diff: %.6f", kRope1Diff));

		// === Step 2b: GQA Expansion ===
		log("\n=== Step 2b: GQA Expansion ===");
		// Expand K for GQA: each kvHead is duplicated headsPerKvGroup times
		int headsPerKvGroup = heads / kvHeads; // 7
		double[] kExpandedGQA0 = new double[dim];
		double[] kExpandedGQA1 = new double[dim];
		for (int h = 0; h < heads; h++) {
			int kvHead = h / headsPerKvGroup;
			for (int i = 0; i < headSize; i++) {
				kExpandedGQA0[h * headSize + i] = kRope0[kvHead * headSize + i];
				kExpandedGQA1[h * headSize + i] = kRope1[kvHead * headSize + i];
			}
		}

		// k_expanded_gqa is shaped (heads=14, seqLen=2, headSize=64) = 1792 floats
		// Layout: head0[pos0, headSize], head0[pos1, headSize], head1[pos0, headSize], head1[pos1, headSize], ...
		// We need: pos0 = [head0_pos0, head1_pos0, ...], pos1 = [head0_pos1, head1_pos1, ...]
		double[] pytorchKExpanded0 = new double[dim];
		double[] pytorchKExpanded1 = new double[dim];
		for (int h = 0; h < heads; h++) {
			// head h, pos 0: indices [h * 2 * headSize, h * 2 * headSize + headSize)
			System.arraycopy(pytorchKExpandedGQA, h * 2 * headSize, pytorchKExpanded0, h * headSize, headSize);
			// head h, pos 1: indices [h * 2 * headSize + headSize, h * 2 * headSize + 2 * headSize)
			System.arraycopy(pytorchKExpandedGQA, h * 2 * headSize + headSize, pytorchKExpanded1, h * headSize, headSize);
		}

		log("Java K Expanded GQA pos0 [0-4]: " + formatArray(kExpandedGQA0, 0, 5));
		log("PyTorch K Expanded GQA pos0 [0-4]: " + formatArray(pytorchKExpanded0, 0, 5));

		double kExpandedDiff0 = maxAbsDiff(kExpandedGQA0, pytorchKExpanded0);
		double kExpandedDiff1 = maxAbsDiff(kExpandedGQA1, pytorchKExpanded1);
		log(String.format("K Expanded GQA pos0 max diff: %.6f", kExpandedDiff0));
		log(String.format("K Expanded GQA pos1 max diff: %.6f", kExpandedDiff1));

		// === Step 3: Compute attention scores manually ===
		log("\n=== Step 3: Attention Scores ===");
		// For position 1, we attend to positions 0 and 1 (2 active key positions)
		// Q: (heads, headSize) = (14, 64)
		// K: (kvHeads, headSize) with 2 positions filled
		// Expand K for GQA: kvHead[0] -> heads[0-6], kvHead[1] -> heads[7-13]

		int numActiveKeys = 2;  // Only positions 0 and 1 are filled
		double[] attnScores = new double[heads * numActiveKeys];
		for (int h = 0; h < heads; h++) {
			int kvHead = h / headsPerKvGroup;
			for (int s = 0; s < numActiveKeys; s++) {
				double[] keyAtPos;
				if (s == 0) {
					keyAtPos = new double[headSize];
					for (int i = 0; i < headSize; i++) {
						keyAtPos[i] = kRope0[kvHead * headSize + i];
					}
				} else {
					keyAtPos = new double[headSize];
					for (int i = 0; i < headSize; i++) {
						keyAtPos[i] = kRope1[kvHead * headSize + i];
					}
				}

				double dot = 0;
				for (int i = 0; i < headSize; i++) {
					dot += qRope1[h * headSize + i] * keyAtPos[i];
				}
				attnScores[h * numActiveKeys + s] = dot / Math.sqrt(headSize);
			}
		}

		// PyTorch attn_scores_before_mask is shaped (heads=14, query_positions=2, key_positions=2)
		// Layout: head0[q0_k0, q0_k1, q1_k0, q1_k1], head1[...], ...
		// We need query position 1 scores: for each head, values at indices [h*4 + 2, h*4 + 3]
		double[] pytorchAttnPos1 = new double[heads * numActiveKeys];
		for (int h = 0; h < heads; h++) {
			// q1_k0 at index h*4 + 2, q1_k1 at index h*4 + 3
			pytorchAttnPos1[h * numActiveKeys + 0] = pytorchAttnScoresBefore[h * 4 + 2];
			pytorchAttnPos1[h * numActiveKeys + 1] = pytorchAttnScoresBefore[h * 4 + 3];
		}

		log("Java attn scores (before mask) [0-4]: " + formatArray(attnScores, 0, 5));
		log("PyTorch attn scores pos1 (before mask) [0-4]: " + formatArray(pytorchAttnPos1, 0, 5));

		double attnScoresDiff = maxAbsDiff(attnScores, pytorchAttnPos1);
		log(String.format("Attn scores (before mask) max diff: %.6f", attnScoresDiff));

		// === Step 4: Apply causal mask ===
		log("\n=== Step 4: Causal Mask ===");
		// For query position 1, both key positions 0 and 1 are valid (no masking needed)
		double[] attnScoresAfterMask = new double[heads * numActiveKeys];
		System.arraycopy(attnScores, 0, attnScoresAfterMask, 0, heads * numActiveKeys);

		// Extract PyTorch position 1 after-mask scores
		double[] pytorchAttnPos1AfterMask = new double[heads * numActiveKeys];
		for (int h = 0; h < heads; h++) {
			pytorchAttnPos1AfterMask[h * numActiveKeys + 0] = pytorchAttnScoresAfter[h * 4 + 2];
			pytorchAttnPos1AfterMask[h * numActiveKeys + 1] = pytorchAttnScoresAfter[h * 4 + 3];
		}

		log("Java attn scores (after mask) [0-4]: " + formatArray(attnScoresAfterMask, 0, 5));
		log("PyTorch attn scores pos1 (after mask) [0-4]: " + formatArray(pytorchAttnPos1AfterMask, 0, 5));

		// === Step 5: Softmax ===
		log("\n=== Step 5: Softmax ===");
		double[] softmaxScores = new double[heads * numActiveKeys];
		for (int h = 0; h < heads; h++) {
			// Find max for numerical stability
			double maxScore = Double.NEGATIVE_INFINITY;
			for (int s = 0; s < numActiveKeys; s++) {
				maxScore = Math.max(maxScore, attnScoresAfterMask[h * numActiveKeys + s]);
			}
			// Compute exp and sum
			double sumExp = 0;
			for (int s = 0; s < numActiveKeys; s++) {
				double exp = Math.exp(attnScoresAfterMask[h * numActiveKeys + s] - maxScore);
				softmaxScores[h * numActiveKeys + s] = exp;
				sumExp += exp;
			}
			// Normalize
			for (int s = 0; s < numActiveKeys; s++) {
				softmaxScores[h * numActiveKeys + s] /= sumExp;
			}
		}

		// Extract PyTorch position 1 softmax scores
		double[] pytorchSoftmaxPos1 = new double[heads * numActiveKeys];
		for (int h = 0; h < heads; h++) {
			pytorchSoftmaxPos1[h * numActiveKeys + 0] = pytorchSoftmax[h * 4 + 2];
			pytorchSoftmaxPos1[h * numActiveKeys + 1] = pytorchSoftmax[h * 4 + 3];
		}

		log("Java softmax [0-4]: " + formatArray(softmaxScores, 0, 5));
		log("PyTorch softmax pos1 [0-4]: " + formatArray(pytorchSoftmaxPos1, 0, 5));

		double softmaxDiff = maxAbsDiff(softmaxScores, pytorchSoftmaxPos1);
		log(String.format("Softmax max diff: %.6f", softmaxDiff));

		// === Step 6: Attention Output ===
		log("\n=== Step 6: Attention Output ===");
		// Value projection (without RoPE)
		double[] vProj0 = applyDense(norm0, wv, bv);
		double[] vProj1 = applyDense(norm1, wv, bv);

		// Weighted sum of values: output = sum(softmax[k] * V[k])
		double[] attnOutput = new double[dim];
		for (int h = 0; h < heads; h++) {
			int kvHead = h / headsPerKvGroup;
			double w0 = softmaxScores[h * numActiveKeys + 0];
			double w1 = softmaxScores[h * numActiveKeys + 1];
			for (int i = 0; i < headSize; i++) {
				double v0 = vProj0[kvHead * headSize + i];
				double v1 = vProj1[kvHead * headSize + i];
				attnOutput[h * headSize + i] = w0 * v0 + w1 * v1;
			}
		}

		log("Java attn output [0-4]: " + formatArray(attnOutput, 0, 5));
		log("PyTorch attn output [0-4]: " + formatArray(pytorchAttnOutput, 0, 5));

		double attnOutputDiff = maxAbsDiff(attnOutput, pytorchAttnOutput);
		log(String.format("Attn output max diff: %.6f", attnOutputDiff));

		// === Step 7: Output Projection ===
		log("\n=== Step 7: Output Projection ===");
		double[] oProj = applyDense(attnOutput, wo, null);  // o_proj has no bias

		log("Java o_proj [0-4]: " + formatArray(oProj, 0, 5));
		log("PyTorch o_proj [0-4]: " + formatArray(pytorchOProj, 0, 5));

		double oProjDiff = maxAbsDiff(oProj, pytorchOProj);
		log(String.format("O proj max diff: %.6f", oProjDiff));

		// === Summary ===
		log("\n=== Summary ===");
		log(String.format("Q RoPE max diff: %.6f %s", qRopeDiff, qRopeDiff < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("K RoPE pos0 max diff: %.6f %s", kRope0Diff, kRope0Diff < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("K RoPE pos1 max diff: %.6f %s", kRope1Diff, kRope1Diff < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("K Expanded GQA pos0 max diff: %.6f %s", kExpandedDiff0, kExpandedDiff0 < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("K Expanded GQA pos1 max diff: %.6f %s", kExpandedDiff1, kExpandedDiff1 < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("Attn scores (before mask) max diff: %.6f %s", attnScoresDiff, attnScoresDiff < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("Softmax max diff: %.6f %s", softmaxDiff, softmaxDiff < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("Attn output max diff: %.6f %s", attnOutputDiff, attnOutputDiff < 0.001 ? "[OK]" : "[FAIL]"));
		log(String.format("O proj max diff: %.6f %s", oProjDiff, oProjDiff < 0.001 ? "[OK]" : "[FAIL]"));

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private double[] applyRMSNorm(double[] input, PackedCollection weight, double eps) {
		int dim = input.length;
		double sumSq = 0;
		for (double v : input) sumSq += v * v;
		double rms = Math.sqrt(sumSq / dim + eps);
		double[] output = new double[dim];
		for (int i = 0; i < dim; i++) output[i] = (input[i] / rms) * weight.toDouble(i);
		return output;
	}

	private double[] applyDense(double[] input, PackedCollection weight, PackedCollection bias) {
		int inDim = input.length;
		int outDim = weight.getShape().length(0);
		double[] output = new double[outDim];
		for (int o = 0; o < outDim; o++) {
			double sum = 0;
			for (int i = 0; i < inDim; i++) sum += input[i] * weight.toDouble(o * inDim + i);
			if (bias != null) sum += bias.toDouble(o);
			output[o] = sum;
		}
		return output;
	}

	private double[] applyRoPE(double[] input, PackedCollection freqCis, int pos, int numHeads, int headSize) {
		int freqDim = headSize / 2;
		double[] output = new double[input.length];

		for (int h = 0; h < numHeads; h++) {
			for (int f = 0; f < freqDim; f++) {
				int cosIdx = (pos * freqDim + f) * 2;
				int sinIdx = cosIdx + 1;
				double cos = freqCis.toDouble(cosIdx);
				double sin = freqCis.toDouble(sinIdx);

				// Split-half: x1 = input[0:freqDim], x2 = input[freqDim:headSize]
				int x1Idx = h * headSize + f;
				int x2Idx = h * headSize + freqDim + f;
				double x1 = input[x1Idx];
				double x2 = input[x2Idx];

				// Apply rotation: out1 = x1*cos - x2*sin, out2 = x2*cos + x1*sin
				output[x1Idx] = x1 * cos - x2 * sin;
				output[x2Idx] = x2 * cos + x1 * sin;
			}
		}
		return output;
	}

	/**
	 * Apply per-head RMSNorm (QK-Norm).
	 * weight has shape (numHeads, headSize) flattened.
	 */
	private double[] applyPerHeadRMSNorm(double[] input, PackedCollection weight, double eps, int numHeads, int headSize) {
		double[] output = new double[input.length];
		for (int h = 0; h < numHeads; h++) {
			// Compute RMS for this head
			double sumSq = 0;
			for (int i = 0; i < headSize; i++) {
				double v = input[h * headSize + i];
				sumSq += v * v;
			}
			double rms = Math.sqrt(sumSq / headSize + eps);

			// Normalize and scale
			for (int i = 0; i < headSize; i++) {
				int idx = h * headSize + i;
				output[idx] = (input[idx] / rms) * weight.toDouble(idx);
			}
		}
		return output;
	}

	private PackedCollection computeRopeFreqs(int seqLen, int headSize, double theta) {
		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) freqs[i] = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
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

	private static Producer<PackedCollection> dynamicPosition(PackedCollection position) {
		return new DynamicCollectionProducer(position.getShape(), args -> position);
	}

	private String formatArray(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		int actualCount = Math.min(count, arr.length - offset);
		for (int i = 0; i < actualCount; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		return sb.append("]").toString();
	}

	private double maxAbsDiff(double[] a, double[] b) {
		double maxDiff = 0;
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			maxDiff = Math.max(maxDiff, Math.abs(a[i] - b[i]));
		}
		return maxDiff;
	}

	private int findMaxAbsDiffIdx(double[] a, double[] b) {
		double maxDiff = 0;
		int maxIdx = 0;
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			double diff = Math.abs(a[i] - b[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxIdx = i;
			}
		}
		return maxIdx;
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
