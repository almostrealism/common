package org.almostrealism.ml.qwen3;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.graph.CollectionReceptor;
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
 * Test that inspects cache contents to understand what's being stored.
 *
 * This test creates the attention block with EXTERNAL caches so we can inspect them.
 */
public class CacheInspectionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testCacheInspection() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/cache_inspection.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Cache Inspection Test");
		log("===================================================\n");

		// Config from Qwen
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		int seqLen = 4;  // Small for testing
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
		log("Weights loaded.");

		// Compute RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, ropeTheta);

		// Position
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);
		Producer<PackedCollection> positionProducer = dynamicPosition(position);

		// Create EXTERNAL caches so we can inspect them
		PackedCollection keyCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		keyCache.clear();
		valueCache.clear();

		log("Created external caches:");
		log("  keyCache shape: " + keyCache.getShape());
		log("  valueCache shape: " + valueCache.getShape());

		// Build attention block with external caches
		log("\nBuilding attention block with external caches...");
		Block attentionBlock = buildAttentionWithExternalCaches(
				heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, freqCis, positionProducer, epsilon,
				keyCache, valueCache, seqLen);

		Model transformer = new Model(shape(1, dim));
		transformer.add(attentionBlock);

		// Compile
		log("Compiling model...");
		CompiledModel compiledModel = transformer.compile();
		log("Model compiled.");

		// Token IDs
		int token0 = 9707;  // "Hello"
		int token1 = 271;   // "\n\n"

		// Manually compute expected V projections
		log("\n=== Computing Expected Values ===");
		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		double[] norm0 = applyRMSNorm(emb0, rmsAttWeight, epsilon);
		double[] norm1 = applyRMSNorm(emb1, rmsAttWeight, epsilon);

		double[] vProj0 = applyDense(norm0, wv, bv);
		double[] vProj1 = applyDense(norm1, wv, bv);

		log("Expected V proj pos0 [0-4]: " + formatArray(vProj0, 0, 5));
		log("Expected V proj pos1 [0-4]: " + formatArray(vProj1, 0, 5));

		// === Before running any forward passes ===
		log("\n=== Cache Contents Before Forward Pass ===");
		log("keyCache pos0 [0-4]: " + formatPacked(keyCache, 0, 5));
		log("keyCache pos1 [0-4]: " + formatPacked(keyCache, kvDim, 5));
		log("valueCache pos0 [0-4]: " + formatPacked(valueCache, 0, 5));
		log("valueCache pos1 [0-4]: " + formatPacked(valueCache, kvDim, 5));

		// === Run position 0 ===
		log("\n=== Running Position 0 ===");
		position.setMem(0, 0.0);
		PackedCollection input0 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input0.setMem(i, embeddings.toDouble(token0 * dim + i));
		}
		PackedCollection output0 = compiledModel.forward(input0);
		log("Output 0 [0-4]: " + formatPacked(output0, 0, 5));

		// Inspect caches after position 0
		log("\n=== Cache Contents After Position 0 ===");
		log("keyCache pos0 [0-4]: " + formatPacked(keyCache, 0, 5));
		log("keyCache pos1 [0-4]: " + formatPacked(keyCache, kvDim, 5));
		log("valueCache pos0 [0-4]: " + formatPacked(valueCache, 0, 5));
		log("valueCache pos1 [0-4]: " + formatPacked(valueCache, kvDim, 5));

		// Compare valueCache[0] with expected
		double vCachePos0Diff = 0;
		for (int i = 0; i < 5; i++) {
			vCachePos0Diff = Math.max(vCachePos0Diff, Math.abs(valueCache.toDouble(i) - vProj0[i]));
		}
		log("valueCache pos0 vs expected diff: " + String.format("%.6f", vCachePos0Diff));

		// === Run position 1 ===
		log("\n=== Running Position 1 ===");
		position.setMem(0, 1.0);
		PackedCollection input1 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input1.setMem(i, embeddings.toDouble(token1 * dim + i));
		}
		PackedCollection output1 = compiledModel.forward(input1);
		log("Output 1 [0-4]: " + formatPacked(output1, 0, 5));

		// Inspect caches after position 1
		log("\n=== Cache Contents After Position 1 ===");
		log("keyCache pos0 [0-4]: " + formatPacked(keyCache, 0, 5));
		log("keyCache pos1 [0-4]: " + formatPacked(keyCache, kvDim, 5));
		log("valueCache pos0 [0-4]: " + formatPacked(valueCache, 0, 5));
		log("valueCache pos1 [0-4]: " + formatPacked(valueCache, kvDim, 5));

		// Compare valueCache[0] with expected (should be unchanged)
		vCachePos0Diff = 0;
		for (int i = 0; i < 5; i++) {
			vCachePos0Diff = Math.max(vCachePos0Diff, Math.abs(valueCache.toDouble(i) - vProj0[i]));
		}
		log("valueCache pos0 vs expected (should be unchanged): " + String.format("%.6f", vCachePos0Diff));

		// Compare valueCache[1] with expected
		double vCachePos1Diff = 0;
		for (int i = 0; i < 5; i++) {
			vCachePos1Diff = Math.max(vCachePos1Diff, Math.abs(valueCache.toDouble(kvDim + i) - vProj1[i]));
		}
		log("valueCache pos1 vs expected: " + String.format("%.6f", vCachePos1Diff));

		// Load PyTorch reference
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");
		log("\nPyTorch o_proj pos1 [0-4]: " + formatArray(pytorchOProj, 0, 5));

		// Compare
		double maxDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxDiff = Math.max(maxDiff, Math.abs(output1.toDouble(i) - pytorchOProj[i]));
		}
		log(String.format("Output 1 vs PyTorch max diff: %.6f", maxDiff));

		log("\n=== Summary ===");
		if (vCachePos0Diff < 0.001 && vCachePos1Diff < 0.001) {
			log("[OK] Cache contents match expected values");
			if (maxDiff < 0.01) {
				log("[OK] Output matches PyTorch");
			} else {
				log("[FAIL] Output doesn't match PyTorch - issue may be in attention computation");
			}
		} else {
			log("[FAIL] Cache contents don't match expected values");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	/**
	 * Build attention block with externally provided caches.
	 */
	private Block buildAttentionWithExternalCaches(
			int heads, int kvHeads,
			PackedCollection rmsAttWeight,
			PackedCollection wk, PackedCollection wv,
			PackedCollection wq, PackedCollection wo,
			PackedCollection bk, PackedCollection bv, PackedCollection bq,
			PackedCollection freqCis,
			Producer<PackedCollection> position,
			double epsilon,
			PackedCollection keyCache, PackedCollection valueCache,
			int seqLen) {

		int dim = rmsAttWeight.getShape().length(0);
		int headSize = freqCis.getShape().length(1) * 2;
		int kvDim = dim * kvHeads / heads;
		ComputeRequirement[] requirements = new ComputeRequirement[0];

		SequentialBlock attention = new SequentialBlock(shape(1, dim));

		attention.add(rmsnorm(shape(1, dim), rmsAttWeight, epsilon, requirements));

		SequentialBlock keys = attention.branch();
		SequentialBlock values = attention.branch();

		// KEYS
		keys.add(bk != null ? dense(wk, bk) : dense(wk));
		keys.add(reshape(shape(kvDim), shape(kvHeads, headSize / 2, 2)));
		keys.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, position));
		keys.andThen(into(keyCache.reshape(shape(seqLen, kvDim)), position));

		// VALUES
		values.add(bv != null ? dense(wv, bv) : dense(wv));
		values.andThen(into(valueCache.reshape(shape(seqLen, kvDim)), position));

		// QUERY
		attention.add(bq != null ? dense(wq, bq) : dense(wq));
		attention.add(reshape(shape(dim), shape(heads, headSize / 2, 2)));
		attention.add(ropeRotation(shape(heads, headSize / 2, 2), freqCis, position));
		attention.add(reshape(shape(heads, headSize / 2, 2), shape(heads, headSize)));
		attention.add(attentionKeys(shape(heads, headSize), p(keyCache)));

		// Causal mask
		org.almostrealism.collect.CollectionProducer indices = integers(0, seqLen);
		org.almostrealism.collect.CollectionProducer maskRow = greaterThan(indices, position, c(-10000.0), c(0.0), false);
		org.almostrealism.collect.CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		attention.add(layer("causal_mask", shape(heads, seqLen).traverseEach(), shape(heads, seqLen).traverseEach(),
				input -> add(input, causalMask), requirements));

		attention.add(softmax(shape(heads, seqLen).traverseEach(), true));
		attention.add(attentionValues(shape(heads, seqLen).traverseEach(), p(valueCache)));
		attention.add(dense(wo));

		attention.reshape(shape(1, dim));

		return attention;
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
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		return sb.append("]").toString();
	}

	private String formatPacked(PackedCollection c, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(offset + i)));
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
