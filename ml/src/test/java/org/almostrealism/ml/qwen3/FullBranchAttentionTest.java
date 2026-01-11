package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
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
 * Test that builds the full attention structure with branches exactly like attention() method,
 * then verifies the output against PyTorch reference.
 */
public class FullBranchAttentionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1FullBranchAttention() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_full_branch_attention.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  LAYER 1 FULL BRANCH ATTENTION TEST");
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
		double epsilon = 1e-6;

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load weights for layer 1
		String prefix = "model.layers.1.";
		PackedCollection rmsAttWeight = stateDict.get(prefix + "input_layernorm.weight");
		PackedCollection wq = stateDict.get(prefix + "self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get(prefix + "self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get(prefix + "self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get(prefix + "self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get(prefix + "self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get(prefix + "self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get(prefix + "self_attn.v_proj.bias");

		log("Weights loaded");
		log("  wq shape: " + wq.getShape());
		log("  wk shape: " + wk.getShape());
		log("  wo shape: " + wo.getShape());

		// Load reference
		float[] pytorchAfterLayer0 = loadReferenceOutput("after_layer_0.bin");
		float[] pytorchOProj = loadReferenceOutput("layer1_o_proj.bin");

		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, pytorchAfterLayer0[i]);
		}

		// Create RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, config.ropeTheta);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Build the attention structure exactly like attention() method
		log("\n=== Building attention structure ===");

		TraversalPolicy inputShape = shape(1, dim);

		// Create EXPANDED caches (seqLen, heads, headSize)
		PackedCollection keyCache = new PackedCollection(seqLen, heads, headSize);
		PackedCollection valueCache = new PackedCollection(seqLen, heads, headSize);
		keyCache.clear();
		valueCache.clear();

		SequentialBlock attention = new SequentialBlock(inputShape);

		// RMSNorm
		attention.add(rmsnorm(inputShape, rmsAttWeight, epsilon));

		// K branch
		SequentialBlock keys = attention.branch();

		TraversalPolicy kvHeadShapeComplex = shape(kvHeads, headSize / 2, 2);
		TraversalPolicy kvHeadShape = shape(kvHeads, headSize);

		keys.add(dense(wk, bk));
		keys.add(reshapeToSplitHalfRope(kvDim, kvHeads, headSize));
		keys.add(ropeRotation(kvHeadShapeComplex, freqCis, p(position)));
		keys.add(reshapeFromSplitHalfRope(kvHeads, headSize));
		keys.add(reshape(kvHeadShape, shape(kvDim)));
		keys.add(reshape(shape(kvDim), shape(1, kvDim)));
		keys.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		keys.andThen(into(keyCache.reshape(shape(seqLen, dim)), p(position)));

		// V branch
		SequentialBlock values = attention.branch();

		values.add(dense(wv, bv));
		values.add(reshape(shape(kvDim), shape(1, kvDim)));
		values.add(gqaExpand(kvDim, dim, kvHeads, heads, headSize));
		values.andThen(into(valueCache.reshape(shape(seqLen, dim)), p(position)));

		// Q path (main)
		TraversalPolicy headShapeComplex = shape(heads, headSize / 2, 2);
		TraversalPolicy headShape = shape(heads, headSize);
		TraversalPolicy attentionShape = shape(heads, seqLen).traverseEach();

		attention.add(dense(wq, bq));
		attention.add(reshapeToSplitHalfRope(dim, heads, headSize));
		attention.add(ropeRotation(headShapeComplex, freqCis, p(position)));
		attention.add(reshapeFromSplitHalfRope(heads, headSize));

		// Attention keys
		attention.add(attentionKeysStandard(headShape, p(keyCache)));

		// Causal mask - use correct pattern: reshape to (1, 1, seqLen) then repeat to (heads, 1, seqLen)
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow = greaterThan(indices, p(position), c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		attention.add(layer("causal_mask", attentionShape, attentionShape,
				in -> add(in, causalMask)));

		// Softmax
		attention.add(softmax(attentionShape, true));

		// Attention values
		attention.add(attentionValuesStandard(attentionShape, p(valueCache)));

		// O projection
		attention.add(dense(wo));

		attention.reshape(inputShape);

		// Compile and run
		log("\n=== Compiling model ===");
		Model model = new Model(inputShape);
		model.add(attention);
		CompiledModel compiled = model.compile();

		log("\n=== Running forward pass ===");
		PackedCollection output = compiled.forward(input);

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

		// Check cache contents
		log("\n=== Cache Inspection ===");
		log("Key cache first row (first 10 values): " + formatFirst(keyCache, 10));
		log("Value cache first row (first 10 values): " + formatFirst(valueCache, 10));

		// Check if caches are populated
		double keySum = 0, valueSum = 0;
		for (int i = 0; i < dim; i++) {
			keySum += Math.abs(keyCache.toDouble(i));
			valueSum += Math.abs(valueCache.toDouble(i));
		}
		log(String.format("Key cache first row sum: %.4f", keySum));
		log(String.format("Value cache first row sum: %.4f", valueSum));

		// Compare cache contents with PyTorch reference
		float[] pytorchKExpanded = loadReferenceOutput("layer1_k_expanded.bin");
		float[] pytorchVExpanded = loadReferenceOutput("layer1_v_expanded.bin");

		log("\n=== Cache vs PyTorch Reference ===");
		double maxKeyCacheDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxKeyCacheDiff = Math.max(maxKeyCacheDiff, Math.abs(keyCache.toDouble(i) - pytorchKExpanded[i]));
		}
		log(String.format("  Key cache max diff from PyTorch: %.6f %s",
				maxKeyCacheDiff, maxKeyCacheDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		double maxValueCacheDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxValueCacheDiff = Math.max(maxValueCacheDiff, Math.abs(valueCache.toDouble(i) - pytorchVExpanded[i]));
		}
		log(String.format("  Value cache max diff from PyTorch: %.6f %s",
				maxValueCacheDiff, maxValueCacheDiff < 0.01 ? "[GOOD]" : "[FAIL]"));

		// Print first 5 K cache values vs expected
		log("\n  Key cache vs PyTorch (first 5):");
		for (int i = 0; i < 5; i++) {
			log(String.format("    [%d] cache=%.6f, pytorch=%.6f, diff=%.6f",
					i, keyCache.toDouble(i), pytorchKExpanded[i], keyCache.toDouble(i) - pytorchKExpanded[i]));
		}

		// Print first 5 V cache values vs expected
		log("\n  Value cache vs PyTorch (first 5):");
		for (int i = 0; i < 5; i++) {
			log(String.format("    [%d] cache=%.6f, pytorch=%.6f, diff=%.6f",
					i, valueCache.toDouble(i), pytorchVExpanded[i], valueCache.toDouble(i) - pytorchVExpanded[i]));
		}

		// Now run a SECOND forward pass to verify cache is properly read
		log("\n=== Second Forward Pass (cache should already be populated) ===");

		// Reset position to 0
		position.setMem(0, 0.0);

		// Run forward again - this time branches will write to position 0 again (overwriting),
		// but importantly the Q path should now read from a pre-populated cache
		PackedCollection output2 = compiled.forward(input);

		double maxDiff2 = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(output2.toDouble(i) - pytorchOProj[i]);
			if (diff > maxDiff2) maxDiff2 = diff;
		}
		log(String.format("  Second pass max diff: %.6f", maxDiff2));
		log("  First 5 values (second pass):");
		for (int i = 0; i < 5; i++) {
			log(String.format("    [%d] PyTorch=%.6f, Java=%.6f, diff=%.6f",
					i, pytorchOProj[i], output2.toDouble(i), output2.toDouble(i) - pytorchOProj[i]));
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
