package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
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
 * Test the Q path only, with pre-populated K and V caches.
 * This isolates the cache read issue from the cache write.
 */
public class QPathOnlyTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1QPathWithPrePopulatedCache() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_q_path_only.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  LAYER 1 Q PATH ONLY TEST (Pre-populated cache)");
		log("=".repeat(70) + "\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		int dim = config.dim;
		int heads = config.headCount;
		int headSize = dim / heads;  // 64
		int seqLen = config.seqLen;
		double epsilon = 1e-6;

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load weights for layer 1
		String prefix = "model.layers.1.";
		PackedCollection rmsAttWeight = stateDict.get(prefix + "input_layernorm.weight");
		PackedCollection wq = stateDict.get(prefix + "self_attn.q_proj.weight");
		PackedCollection wo = stateDict.get(prefix + "self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get(prefix + "self_attn.q_proj.bias");

		log("Weights loaded");

		// Load references
		float[] pytorchAfterLayer0 = loadReferenceOutput("after_layer_0.bin");
		float[] pytorchKExpanded = loadReferenceOutput("layer1_k_expanded.bin");
		float[] pytorchVExpanded = loadReferenceOutput("layer1_v_expanded.bin");
		float[] pytorchOProj = loadReferenceOutput("layer1_o_proj.bin");

		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, pytorchAfterLayer0[i]);
		}

		// Create RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, config.ropeTheta);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// PRE-POPULATE the caches with PyTorch reference values
		log("\n=== Pre-populating caches with PyTorch values ===");

		PackedCollection keyCache = new PackedCollection(seqLen, heads, headSize);
		PackedCollection valueCache = new PackedCollection(seqLen, heads, headSize);
		keyCache.clear();
		valueCache.clear();

		// Write PyTorch K and V values to position 0
		for (int i = 0; i < dim; i++) {
			keyCache.setMem(i, pytorchKExpanded[i]);
			valueCache.setMem(i, pytorchVExpanded[i]);
		}

		log("Key cache populated (first 5): " + formatFirst(keyCache, 5));
		log("Value cache populated (first 5): " + formatFirst(valueCache, 5));

		// Build and test Q path step by step to find the divergence point
		log("\n=== Testing Q path step by step ===");

		TraversalPolicy inputShape = shape(1, dim);
		TraversalPolicy headShapeComplex = shape(heads, headSize / 2, 2);
		TraversalPolicy headShape = shape(heads, headSize);
		TraversalPolicy attentionShape = shape(heads, seqLen).traverseEach();

		// Load more references for intermediate comparison
		float[] pytorchInputNorm = loadReferenceOutput("layer1_input_norm.bin");
		float[] pytorchQRope = loadReferenceOutput("layer1_q_rope.bin");
		float[] pytorchAttnScores = loadReferenceOutput("layer1_attn_scores.bin");
		float[] pytorchAttnProbs = loadReferenceOutput("layer1_attn_probs.bin");

		// Step 1: RMSNorm + Q projection + RoPE
		log("\n--- Step 1: Q after RoPE ---");
		Model qRopeModel = new Model(inputShape);
		qRopeModel.add(rmsnorm(inputShape, rmsAttWeight, epsilon));
		qRopeModel.add(dense(wq, bq));
		qRopeModel.add(reshapeToSplitHalfRope(dim, heads, headSize));
		qRopeModel.add(ropeRotation(headShapeComplex, freqCis, p(position)));
		qRopeModel.add(reshapeFromSplitHalfRope(heads, headSize));
		PackedCollection qRopeOutput = qRopeModel.compile().forward(input);

		double maxQRopeDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxQRopeDiff = Math.max(maxQRopeDiff, Math.abs(qRopeOutput.toDouble(i) - pytorchQRope[i]));
		}
		log(String.format("  Q RoPE max diff: %.6f %s", maxQRopeDiff, maxQRopeDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Step 2: Attention scores
		log("\n--- Step 2: Attention scores ---");
		Model attnScoresModel = new Model(headShape);
		attnScoresModel.add(attentionKeysStandard(headShape, p(keyCache)));
		PackedCollection attnScoresOutput = attnScoresModel.compile().forward(qRopeOutput);

		log("  Attention scores shape: " + attnScoresOutput.getShape());
		// PyTorch reference shape is (heads,) for position 0 - just the first score per head
		log("  First few attention scores (position 0 for each head):");
		for (int h = 0; h < Math.min(3, heads); h++) {
			// Our output is (heads, seqLen) traverseEach, so position 0 for head h is at h * seqLen
			double javaScore = attnScoresOutput.toDouble(h * seqLen);
			double pytorchScore = pytorchAttnScores[h];
			log(String.format("    Head %d pos 0: Java=%.6f, PyTorch=%.6f, diff=%.6f",
					h, javaScore, pytorchScore, javaScore - pytorchScore));
		}

		// Step 3: After causal mask
		log("\n--- Step 3: After causal mask ---");
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow = greaterThan(indices, p(position), c(-10000.0), c(0.0), false);
		// Use correct pattern: reshape to (1, 1, seqLen) then repeat to (heads, 1, seqLen)
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		Model maskedModel = new Model(headShape);
		maskedModel.add(attentionKeysStandard(headShape, p(keyCache)));
		maskedModel.add(layer("causal_mask", attentionShape, attentionShape,
				in -> add(in, causalMask)));
		PackedCollection maskedOutput = maskedModel.compile().forward(qRopeOutput);

		log("  After mask (first 3 heads, first 5 positions):");
		for (int h = 0; h < Math.min(3, heads); h++) {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("    Head %d: [", h));
			for (int p = 0; p < 5; p++) {
				if (p > 0) sb.append(", ");
				sb.append(String.format("%.2f", maskedOutput.toDouble(h * seqLen + p)));
			}
			sb.append(", ...]");
			log(sb.toString());
		}

		// Step 4: After softmax
		log("\n--- Step 4: After softmax ---");
		Model softmaxModel = new Model(headShape);
		softmaxModel.add(attentionKeysStandard(headShape, p(keyCache)));
		softmaxModel.add(layer("causal_mask", attentionShape, attentionShape,
				in -> add(in, causalMask)));
		softmaxModel.add(softmax(attentionShape, true));
		PackedCollection softmaxOutput = softmaxModel.compile().forward(qRopeOutput);

		log("  After softmax (first 3 heads, first 5 positions):");
		for (int h = 0; h < Math.min(3, heads); h++) {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("    Head %d: [", h));
			for (int p = 0; p < 5; p++) {
				if (p > 0) sb.append(", ");
				sb.append(String.format("%.4f", softmaxOutput.toDouble(h * seqLen + p)));
			}
			sb.append(", ...]");
			log(sb.toString());
		}
		// For position 0, softmax should be [1, 0, 0, 0, ...] for each head
		log("  Expected: [1.0000, 0.0000, 0.0000, 0.0000, 0.0000, ...] for each head");

		// Step 5: After attention values
		log("\n--- Step 5: After attentionValuesStandard ---");
		Model attnValuesModel = new Model(headShape);
		attnValuesModel.add(attentionKeysStandard(headShape, p(keyCache)));
		attnValuesModel.add(layer("causal_mask", attentionShape, attentionShape,
				in -> add(in, causalMask)));
		attnValuesModel.add(softmax(attentionShape, true));
		attnValuesModel.add(attentionValuesStandard(attentionShape, p(valueCache)));
		PackedCollection attnValuesOutput = attnValuesModel.compile().forward(qRopeOutput);

		log("  Attention values output shape: " + attnValuesOutput.getShape());
		log("  First 10 values: " + formatFirst(attnValuesOutput, 10));
		log("  Expected (V at position 0, first 10): " + formatFirst(valueCache, 10));

		// Compare with expected (should be V at position 0)
		double maxAttnValuesDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxAttnValuesDiff = Math.max(maxAttnValuesDiff,
					Math.abs(attnValuesOutput.toDouble(i) - pytorchVExpanded[i]));
		}
		log(String.format("  Attention values max diff from V[0]: %.6f %s",
				maxAttnValuesDiff, maxAttnValuesDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Full Q path
		log("\n--- Full Q path ---");
		SequentialBlock qPath = new SequentialBlock(inputShape);
		qPath.add(rmsnorm(inputShape, rmsAttWeight, epsilon));
		qPath.add(dense(wq, bq));
		qPath.add(reshapeToSplitHalfRope(dim, heads, headSize));
		qPath.add(ropeRotation(headShapeComplex, freqCis, p(position)));
		qPath.add(reshapeFromSplitHalfRope(heads, headSize));
		qPath.add(attentionKeysStandard(headShape, p(keyCache)));
		qPath.add(layer("causal_mask", attentionShape, attentionShape,
				in -> add(in, causalMask)));
		qPath.add(softmax(attentionShape, true));
		qPath.add(attentionValuesStandard(attentionShape, p(valueCache)));
		qPath.add(dense(wo));
		qPath.reshape(inputShape);

		log("\n=== Compiling and running full Q path ===");
		Model model = new Model(inputShape);
		model.add(qPath);
		PackedCollection output = model.compile().forward(input);

		log("Output shape: " + output.getShape());

		// Compare with PyTorch
		log("\n=== Comparison with PyTorch ===");
		double maxDiff = 0;
		int maxDiffIdx = -1;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(output.toDouble(i) - pytorchOProj[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxDiffIdx = i;
			}
		}

		log(String.format("  Max diff: %.6f at index %d", maxDiff, maxDiffIdx));
		log("\n  First 10 values:");
		for (int i = 0; i < Math.min(10, dim); i++) {
			log(String.format("    [%d] PyTorch=%.6f, Java=%.6f, diff=%.6f",
					i, pytorchOProj[i], output.toDouble(i), output.toDouble(i) - pytorchOProj[i]));
		}

		String status;
		if (maxDiff < 1e-4) status = "[EXCELLENT]";
		else if (maxDiff < 0.01) status = "[GOOD]";
		else if (maxDiff < 0.1) status = "[WARNING]";
		else status = "[FAIL]";
		log(String.format("\n  Status: %s", status));

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
