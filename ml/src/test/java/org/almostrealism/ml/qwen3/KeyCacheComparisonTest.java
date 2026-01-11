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
 * Compare key cache values (after RoPE) with PyTorch reference.
 */
public class KeyCacheComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testKeyCacheComparison() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/key_cache_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Key Cache Comparison Test (vs PyTorch)");
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

		// Load PyTorch references
		log("Loading PyTorch reference files...");
		double[] pytorchKProj = loadBinaryLogits(REFERENCE_DIR + "/k_proj_both_positions.bin");
		double[] pytorchKRope = loadBinaryLogits(REFERENCE_DIR + "/k_rope_both.bin");
		double[] pytorchQProj = loadBinaryLogits(REFERENCE_DIR + "/q_proj_both_positions.bin");
		double[] pytorchQRope = loadBinaryLogits(REFERENCE_DIR + "/q_rope_pos1.bin");
		double[] pytorchAttnScores = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_before_mask.bin");
		double[] pytorchSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");

		int freqDimLog = headSize / 2;  // 32
		log("PyTorch K proj pos0 [0-4]: " + formatArray(pytorchKProj, 0, 5));
		log("PyTorch K proj pos1 [0-4]: " + formatArray(pytorchKProj, kvDim, 5));
		log("PyTorch K proj pos1 [32-36] (second half): " + formatArray(pytorchKProj, kvDim + freqDimLog, 5));

		// IMPORTANT: k_rope has DIFFERENT layout than k_proj!
		// k_proj: (seqLen=2, kvDim=128) -> pos0 at [0:128], pos1 at [128:256]
		// k_rope: (kvHeads=2, seqLen=2, headSize=64) -> kv0-pos0 at [0:64], kv0-pos1 at [64:128], kv1-pos0 at [128:192], kv1-pos1 at [192:256]
		log("\n=== K RoPE Layout (kvHeads=2, seqLen=2, headDim=64) ===");
		log("PyTorch K rope kv0-pos0 [0-4]: " + formatArray(pytorchKRope, 0, 5));
		log("PyTorch K rope kv0-pos1 [0-4]: " + formatArray(pytorchKRope, headSize, 5));  // offset 64
		log("PyTorch K rope kv0-pos1 [32-36]: " + formatArray(pytorchKRope, headSize + freqDimLog, 5));  // offset 64+32=96
		log("PyTorch K rope kv1-pos0 [0-4]: " + formatArray(pytorchKRope, 2 * headSize, 5));  // offset 128
		log("PyTorch K rope kv1-pos1 [0-4]: " + formatArray(pytorchKRope, 3 * headSize, 5));  // offset 192

		// Load weights
		log("\nLoading weights...");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection rmsAttWeight = stateDict.get("model.layers.0.input_layernorm.weight");
		PackedCollection wk = stateDict.get("model.layers.0.self_attn.k_proj.weight");
		PackedCollection bk = stateDict.get("model.layers.0.self_attn.k_proj.bias");
		log("Weights loaded.");

		// Compute RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, ropeTheta);

		// Verify RoPE frequencies
		log("\n=== RoPE Frequencies ===");
		log("freqCis[pos0, freq0] (cos, sin): " + String.format("(%.6f, %.6f)", freqCis.toDouble(0), freqCis.toDouble(1)));
		log("freqCis[pos1, freq0] (cos, sin): " + String.format("(%.6f, %.6f)", freqCis.toDouble(64), freqCis.toDouble(65)));

		// Token IDs
		int token0 = 9707;
		int token1 = 271;

		// === Manual K projection ===
		log("\n=== Manual K Projection ===");
		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		double[] norm0 = applyRMSNorm(emb0, rmsAttWeight, epsilon);
		double[] norm1 = applyRMSNorm(emb1, rmsAttWeight, epsilon);

		double[] kProj0 = applyDense(norm0, wk, bk);
		double[] kProj1 = applyDense(norm1, wk, bk);

		log("Java K proj pos0 [0-4]: " + formatArray(kProj0, 0, 5));
		log("Java K proj pos1 [0-4]: " + formatArray(kProj1, 0, 5));
		log("Java K proj pos1 [32-36] (second half): " + formatArray(kProj1, 32, 5));
		log("Java K proj pos1 [64-68] (kv head 1): " + formatArray(kProj1, 64, 5));

		// Compare K projection with PyTorch
		double kProjDiff0 = maxDiff(kProj0, pytorchKProj, 0, kvDim);
		double kProjDiff1 = maxDiff(kProj1, pytorchKProj, kvDim, kvDim);
		log("K proj pos0 max diff vs PyTorch: " + String.format("%.6f", kProjDiff0));
		log("K proj pos1 max diff vs PyTorch: " + String.format("%.6f", kProjDiff1));

		// PyTorch k_rope layout indices (kvHeads=2, seqLen=2, headDim=64)
		int pyKRopeKv0Pos0 = 0;                // kv_head 0, pos 0: [0:64]
		int pyKRopeKv0Pos1 = headSize;         // kv_head 0, pos 1: [64:128]
		int pyKRopeKv1Pos0 = 2 * headSize;     // kv_head 1, pos 0: [128:192]
		int pyKRopeKv1Pos1 = 3 * headSize;     // kv_head 1, pos 1: [192:256]

		// === Manual RoPE application (Interleaved - current Java implementation) ===
		log("\n=== Manual RoPE Application (Interleaved Format) ===");
		double[] kRopeInterleaved0 = applyRoPE(kProj0, freqCis, 0, kvHeads, headSize);
		double[] kRopeInterleaved1 = applyRoPE(kProj1, freqCis, 1, kvHeads, headSize);

		log("Java K rope (interleaved) kv0-pos0 [0-4]: " + formatArray(kRopeInterleaved0, 0, 5));
		log("Java K rope (interleaved) kv0-pos1 [0-4]: " + formatArray(kRopeInterleaved1, 0, 5));
		log("PyTorch K rope kv0-pos0 [0-4]: " + formatArray(pytorchKRope, pyKRopeKv0Pos0, 5));
		log("PyTorch K rope kv0-pos1 [0-4]: " + formatArray(pytorchKRope, pyKRopeKv0Pos1, 5));

		// Compare interleaved K rope with PyTorch (correct indices)
		double kRopeInterleavedDiff0_kv0 = maxDiff(kRopeInterleaved0, pytorchKRope, pyKRopeKv0Pos0, headSize);
		double kRopeInterleavedDiff1_kv0 = maxDiff(kRopeInterleaved1, pytorchKRope, pyKRopeKv0Pos1, headSize);
		double kRopeInterleavedDiff0_kv1 = maxDiff(kRopeInterleaved0, pytorchKRope, pyKRopeKv1Pos0, headSize);
		double kRopeInterleavedDiff1_kv1 = maxDiff(kRopeInterleaved1, pytorchKRope, pyKRopeKv1Pos1, headSize);
		log("K rope (interleaved) kv0-pos0 max diff vs PyTorch: " + String.format("%.6f", kRopeInterleavedDiff0_kv0));
		log("K rope (interleaved) kv0-pos1 max diff vs PyTorch: " + String.format("%.6f", kRopeInterleavedDiff1_kv0));
		log("K rope (interleaved) kv1-pos0 max diff vs PyTorch: " + String.format("%.6f", kRopeInterleavedDiff0_kv1));
		log("K rope (interleaved) kv1-pos1 max diff vs PyTorch: " + String.format("%.6f", kRopeInterleavedDiff1_kv1));

		// === Manual RoPE application (Split-Half - PyTorch format) ===
		log("\n=== Manual RoPE Application (Split-Half Format) ===");
		double[] kRopeSplitHalf0 = applyRoPESplitHalf(kProj0, freqCis, 0, kvHeads, headSize);
		double[] kRopeSplitHalf1 = applyRoPESplitHalf(kProj1, freqCis, 1, kvHeads, headSize);

		log("Java K rope (split-half) kv0-pos0 [0-4]: " + formatArray(kRopeSplitHalf0, 0, 5));
		log("Java K rope (split-half) kv0-pos1 [0-4]: " + formatArray(kRopeSplitHalf1, 0, 5));
		log("PyTorch K rope kv0-pos0 [0-4]: " + formatArray(pytorchKRope, pyKRopeKv0Pos0, 5));
		log("PyTorch K rope kv0-pos1 [0-4]: " + formatArray(pytorchKRope, pyKRopeKv0Pos1, 5));

		// === Detailed trace of first element rotation ===
		log("\n=== Detailed RoPE Trace (element 0, pos 1) ===");
		int freqDim = headSize / 2;  // 32
		double x0 = kProj1[0];
		double y0_interleaved = kProj1[1];  // interleaved: pairs (0,1)
		double y0_splitHalf = kProj1[freqDim];  // split-half: pairs (0,32)
		log("K proj[0] = " + String.format("%.4f", x0));
		log("K proj[1] (interleaved pair) = " + String.format("%.4f", y0_interleaved));
		log("K proj[32] (split-half pair) = " + String.format("%.4f", y0_splitHalf));

		int freqIdx = (1 * freqDim + 0) * 2;
		double cos0 = freqCis.toDouble(freqIdx);
		double sin0 = freqCis.toDouble(freqIdx + 1);
		log("cos(pos1, freq0) = " + String.format("%.6f", cos0));
		log("sin(pos1, freq0) = " + String.format("%.6f", sin0));

		double out_interleaved = x0 * cos0 - y0_interleaved * sin0;
		double out_splitHalf = x0 * cos0 - y0_splitHalf * sin0;
		log("Interleaved result[0] = x*cos - y*sin = " + String.format("%.4f", out_interleaved));
		log("Split-half result[0] = x*cos - y*sin = " + String.format("%.4f", out_splitHalf));
		log("PyTorch expected kv0-pos1[0] = " + String.format("%.4f", pytorchKRope[pyKRopeKv0Pos1]));

		// What y value would give PyTorch result?
		double expectedY = (x0 * cos0 - pytorchKRope[pyKRopeKv0Pos1]) / sin0;
		log("To get PyTorch result, y would need to be: " + String.format("%.4f", expectedY));

		// Verify by computing what PyTorch second-half output should be
		// For split-half: output[i+32] = x[i+32]*cos + x[i]*sin
		double expectedSecondHalf = y0_splitHalf * cos0 + x0 * sin0;
		log("My computed output[32] = y*cos + x*sin = " + String.format("%.4f", expectedSecondHalf));
		log("PyTorch output[32] = " + String.format("%.4f", pytorchKRope[pyKRopeKv0Pos1 + freqDim]));

		// Cross-check: if we use rotate_half formula: q * cos + rotate_half(q) * sin
		// rotate_half([x1,x2]) = [-x2, x1]
		// output[0] = x[0]*cos + (-x[32])*sin = x*cos - y*sin (same as before)
		// output[32] = x[32]*cos + x[0]*sin = y*cos + x*sin (same as before)
		// So formula is the same, but maybe the cos/sin application is different?

		// Let me verify: given PyTorch's k_rope[0] and k_rope[32], solve for cos and sin
		// Assuming x=kproj[0], y=kproj[32]:
		// k_rope[0] = x*cos - y*sin
		// k_rope[32] = y*cos + x*sin
		double pyKRope0val = pytorchKRope[pyKRopeKv0Pos1];
		double pyKRope32val = pytorchKRope[pyKRopeKv0Pos1 + freqDim];
		double pyKProj0val = pytorchKProj[kvDim];  // pos1 in k_proj layout
		double pyKProj32val = pytorchKProj[kvDim + freqDim];
		log("\n=== Solving for PyTorch's actual cos/sin (pos1) ===");
		log("PyTorch K proj pos1[0] = " + String.format("%.4f", pyKProj0val));
		log("PyTorch K proj pos1[32] = " + String.format("%.4f", pyKProj32val));
		log("PyTorch K rope kv0-pos1[0] = " + String.format("%.4f", pyKRope0val));
		log("PyTorch K rope kv0-pos1[32] = " + String.format("%.4f", pyKRope32val));

		// Solve: x*cos - y*sin = a, y*cos + x*sin = b
		// From these: cos = (a*x + b*y)/(x^2 + y^2), sin = (b*x - a*y)/(x^2 + y^2)
		double denom = pyKProj0val*pyKProj0val + pyKProj32val*pyKProj32val;
		double solvedCos = (pyKRope0val*pyKProj0val + pyKRope32val*pyKProj32val) / denom;
		double solvedSin = (pyKRope32val*pyKProj0val - pyKRope0val*pyKProj32val) / denom;
		log("Solved cos = " + String.format("%.6f", solvedCos) + " (expected " + String.format("%.6f", cos0) + ")");
		log("Solved sin = " + String.format("%.6f", solvedSin) + " (expected " + String.format("%.6f", sin0) + ")");

		// Compare split-half K rope with PyTorch (using correct indices)
		// Compare kv_head 0 only (first 64 elements of Java array)
		double kRopeSplitHalfDiff0_kv0 = maxDiff(kRopeSplitHalf0, pytorchKRope, pyKRopeKv0Pos0, headSize);
		double kRopeSplitHalfDiff1_kv0 = maxDiff(kRopeSplitHalf1, pytorchKRope, pyKRopeKv0Pos1, headSize);
		log("\nK rope (split-half) kv0-pos0 max diff vs PyTorch: " + String.format("%.6f", kRopeSplitHalfDiff0_kv0));
		log("K rope (split-half) kv0-pos1 max diff vs PyTorch: " + String.format("%.6f", kRopeSplitHalfDiff1_kv0));

		// Element-by-element trace with correct indices
		log("\n=== Element-by-element rotation trace (kv0-pos1) ===");
		for (int i = 0; i < 5; i++) {
			double xi = kProj1[i];
			double yi = kProj1[freqDim + i];  // split-half pair
			int fidx = (1 * freqDim + i) * 2;
			double ci = freqCis.toDouble(fidx);
			double si = freqCis.toDouble(fidx + 1);
			double rot = xi * ci - yi * si;
			log(String.format("  i=%d: x=%.4f, y=%.4f, cos=%.4f, sin=%.4f -> %.4f (PyTorch: %.4f)",
				i, xi, yi, ci, si, rot, pytorchKRope[pyKRopeKv0Pos1 + i]));
		}

		// === Check attention scores ===
		log("\n=== PyTorch Attention Scores (before mask) ===");
		log("PyTorch attention scores layout: (heads, query_len, key_len) = (14, 2, 2)");
		// For position 1 query (row 1), attending to keys 0 and 1
		// Layout: [h, q, k] = h * 4 + q * 2 + k
		log("PyTorch attn[h=0, q=1, k=0]: " + String.format("%.6f", pytorchAttnScores[0 * 4 + 1 * 2 + 0]));
		log("PyTorch attn[h=0, q=1, k=1]: " + String.format("%.6f", pytorchAttnScores[0 * 4 + 1 * 2 + 1]));
		log("PyTorch attn[h=1, q=1, k=0]: " + String.format("%.6f", pytorchAttnScores[1 * 4 + 1 * 2 + 0]));
		log("PyTorch attn[h=1, q=1, k=1]: " + String.format("%.6f", pytorchAttnScores[1 * 4 + 1 * 2 + 1]));

		log("\n=== PyTorch Softmax Weights ===");
		log("PyTorch softmax[h=0, q=1, k=0]: " + String.format("%.6f", pytorchSoftmax[0 * 4 + 1 * 2 + 0]));
		log("PyTorch softmax[h=0, q=1, k=1]: " + String.format("%.6f", pytorchSoftmax[0 * 4 + 1 * 2 + 1]));
		log("PyTorch softmax[h=1, q=1, k=0]: " + String.format("%.6f", pytorchSoftmax[1 * 4 + 1 * 2 + 0]));
		log("PyTorch softmax[h=1, q=1, k=1]: " + String.format("%.6f", pytorchSoftmax[1 * 4 + 1 * 2 + 1]));

		log("\n=== Summary ===");
		if (kProjDiff0 < 0.001 && kProjDiff1 < 0.001) {
			log("[OK] K projection matches PyTorch");
		} else {
			log("[FAIL] K projection doesn't match PyTorch");
		}

		double interleavedMaxDiff = Math.max(Math.max(kRopeInterleavedDiff0_kv0, kRopeInterleavedDiff1_kv0),
			Math.max(kRopeInterleavedDiff0_kv1, kRopeInterleavedDiff1_kv1));
		if (interleavedMaxDiff < 0.001) {
			log("[OK] K rope (interleaved) matches PyTorch");
		} else {
			log("[FAIL] K rope (interleaved) doesn't match PyTorch - max diff: " +
				String.format("%.4f", interleavedMaxDiff));
		}

		double splitHalfMaxDiff = Math.max(kRopeSplitHalfDiff0_kv0, kRopeSplitHalfDiff1_kv0);
		if (splitHalfMaxDiff < 0.001) {
			log("[OK] K rope (split-half) matches PyTorch - THIS IS THE CORRECT FORMAT!");
		} else {
			log("[FAIL] K rope (split-half) doesn't match PyTorch - max diff: " +
				String.format("%.4f", splitHalfMaxDiff));
		}

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

	private double[] applyRoPE(double[] input, PackedCollection freqCis, int pos, int kvHeads, int headSize) {
		double[] output = new double[input.length];
		int freqDim = headSize / 2;
		for (int h = 0; h < kvHeads; h++) {
			for (int i = 0; i < freqDim; i++) {
				// Interleaved format: pairs are (x0, x1), (x2, x3), ...
				int baseIdx = h * headSize + i * 2;
				double x0 = input[baseIdx];
				double x1 = input[baseIdx + 1];

				int freqIdx = (pos * freqDim + i) * 2;
				double cos = freqCis.toDouble(freqIdx);
				double sin = freqCis.toDouble(freqIdx + 1);

				// RoPE rotation: (x0, x1) -> (x0*cos - x1*sin, x0*sin + x1*cos)
				output[baseIdx] = x0 * cos - x1 * sin;
				output[baseIdx + 1] = x0 * sin + x1 * cos;
			}
		}
		return output;
	}

	private double[] applyRoPESplitHalf(double[] input, PackedCollection freqCis, int pos, int kvHeads, int headSize) {
		double[] output = new double[input.length];
		int freqDim = headSize / 2;
		for (int h = 0; h < kvHeads; h++) {
			int headOffset = h * headSize;
			for (int i = 0; i < freqDim; i++) {
				// Split-half format: first half are x values, second half are y values
				// Pair (x[i], y[i]) where x[i] = input[headOffset + i], y[i] = input[headOffset + freqDim + i]
				double x = input[headOffset + i];
				double y = input[headOffset + freqDim + i];

				int freqIdx = (pos * freqDim + i) * 2;
				double cos = freqCis.toDouble(freqIdx);
				double sin = freqCis.toDouble(freqIdx + 1);

				// RoPE rotation: (x, y) -> (x*cos - y*sin, x*sin + y*cos)
				output[headOffset + i] = x * cos - y * sin;
				output[headOffset + freqDim + i] = x * sin + y * cos;
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

	private double maxDiff(double[] java, double[] pytorch, int pytorchOffset, int count) {
		double max = 0;
		for (int i = 0; i < count; i++) {
			max = Math.max(max, Math.abs(java[i] - pytorch[pytorchOffset + i]));
		}
		return max;
	}

	private String formatArray(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		return sb.append("]").toString();
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
