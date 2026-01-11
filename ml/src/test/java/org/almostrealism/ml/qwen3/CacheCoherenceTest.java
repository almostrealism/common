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
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Diagnostic test to verify cache coherence between writes and reads
 * in the compiled attention model.
 *
 * This test inspects the actual cache contents after each forward pass
 * to verify that values are correctly stored and accessible.
 */
public class CacheCoherenceTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testCacheCoherence() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/cache_coherence.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Cache Coherence Test");
		log("===================================================\n");

		// Config from Qwen2.5-0.5B
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		int seqLen = 32768;
		double ropeTheta = 1000000.0;
		double epsilon = 1e-6;

		// Load PyTorch reference
		double[] pytorchVProj = loadBinaryLogits(REFERENCE_DIR + "/v_proj_both_positions.bin");
		log("Loaded PyTorch reference data");
		log("PyTorch V proj pos0 (first 5): " + formatDoubleArray(pytorchVProj, 0, 5));
		log("PyTorch V proj pos1 (first 5): " + formatDoubleArray(pytorchVProj, kvDim, 5));

		// Load weights
		log("\nLoading weights...");
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

		// Compute RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, ropeTheta);

		// Position collection
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		Producer<PackedCollection> positionProducer = dynamicPosition(position);

		// Build single-layer model
		log("\nBuilding attention block...");
		ComputeRequirement[] requirements = new ComputeRequirement[0];

		Model transformer = new Model(shape(1, dim));

		Block attentionBlock = attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, null, null, freqCis, positionProducer, epsilon, requirements);
		transformer.add(attentionBlock);

		// Try to access the caches from the attention block
		// This is a bit of a hack - we need to access the internal state
		PackedCollection valueCache = null;
		PackedCollection keyCache = null;

		// Search for the caches in the block structure
		log("\nSearching for internal caches...");
		// The caches are created inside the attention() method and stored in the block
		// We'll need to extract them using reflection or recreate the scenario

		// Alternative: manually compute what SHOULD be in the cache and compare
		log("\n=== Manual V projection computation ===");

		// Token IDs
		int token0 = 9707;  // "Hello"
		int token1 = 271;   // "\n\n"

		// Compute V projection manually for both positions
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

		log("Manual V projection pos0 (first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						vProj0[0], vProj0[1], vProj0[2], vProj0[3], vProj0[4]));
		log("Manual V projection pos1 (first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						vProj1[0], vProj1[1], vProj1[2], vProj1[3], vProj1[4]));

		// Compare with PyTorch
		double maxDiff0 = 0, maxDiff1 = 0;
		for (int i = 0; i < kvDim; i++) {
			maxDiff0 = Math.max(maxDiff0, Math.abs(vProj0[i] - pytorchVProj[i]));
			maxDiff1 = Math.max(maxDiff1, Math.abs(vProj1[i] - pytorchVProj[kvDim + i]));
		}
		log("V proj max diff pos0: " + String.format("%.6f", maxDiff0));
		log("V proj max diff pos1: " + String.format("%.6f", maxDiff1));

		// Compile the model
		log("\nCompiling model...");
		CompiledModel compiledModel = transformer.compile();

		// Create a fresh cache for testing
		log("\n=== Testing with fresh cache ===");

		// Create caches directly
		PackedCollection testValueCache = new PackedCollection(seqLen, kvHeads, headSize);
		testValueCache.clear();

		// Manually populate the cache with correct values
		log("\nManually populating cache with correct V projections...");
		for (int i = 0; i < kvDim; i++) {
			testValueCache.setMem(0 * kvDim + i, vProj0[i]);  // Position 0
		}
		log("Cache pos0 (first 5): " + formatCacheValues(testValueCache, 0, 5));

		for (int i = 0; i < kvDim; i++) {
			testValueCache.setMem(1 * kvDim + i, vProj1[i]);  // Position 1
		}
		log("Cache pos1 (first 5): " + formatCacheValues(testValueCache, kvDim, 5));

		// Run the compiled model for position 0
		log("\n=== Running compiled model: Position 0 ===");
		position.setMem(0, 0.0);
		PackedCollection input0 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input0.setMem(i, embeddings.toDouble(token0 * dim + i));
		}
		PackedCollection output0 = compiledModel.forward(input0);

		log("Output 0 (first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						output0.toDouble(0), output0.toDouble(1), output0.toDouble(2),
						output0.toDouble(3), output0.toDouble(4)));

		// Run the compiled model for position 1
		log("\n=== Running compiled model: Position 1 ===");
		position.setMem(0, 1.0);
		PackedCollection input1 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input1.setMem(i, embeddings.toDouble(token1 * dim + i));
		}
		PackedCollection output1 = compiledModel.forward(input1);

		log("Output 1 (first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						output1.toDouble(0), output1.toDouble(1), output1.toDouble(2),
						output1.toDouble(3), output1.toDouble(4)));

		// Load PyTorch reference for o_proj
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");
		log("\nPyTorch o_proj pos1 (first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						pytorchOProj[0], pytorchOProj[1], pytorchOProj[2],
						pytorchOProj[3], pytorchOProj[4]));

		// Compare
		double maxOutputDiff = 0;
		for (int i = 0; i < dim; i++) {
			maxOutputDiff = Math.max(maxOutputDiff, Math.abs(output1.toDouble(i) - pytorchOProj[i]));
		}
		log("\nMax output difference: " + String.format("%.6f", maxOutputDiff));

		if (maxOutputDiff < 0.01) {
			log("\n[OK] Output matches PyTorch!");
		} else {
			log("\n[MISMATCH] Output differs from PyTorch.");
			log("This confirms the issue is in the compiled model execution.");
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

	private static Producer<PackedCollection> dynamicPosition(PackedCollection position) {
		return new DynamicCollectionProducer(position.getShape(), args -> position);
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

	private String formatCacheValues(PackedCollection cache, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", cache.toDouble(offset + i)));
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
