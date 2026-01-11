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
 * Test to verify what gets stored in the value cache during forward pass.
 */
public class CacheStorageTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testValueCacheStorage() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/cache_storage.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Value Cache Storage Test");
		log("===================================================\n");

		// Config
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		int seqLen = 32768;
		double epsilon = 1e-6;

		// Load PyTorch reference
		double[] pytorchVProj = loadBinaryLogits(REFERENCE_DIR + "/v_proj_both_positions.bin");

		log("PyTorch V projection (position 0, first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						pytorchVProj[0], pytorchVProj[1], pytorchVProj[2], pytorchVProj[3], pytorchVProj[4]));
		log("PyTorch V projection (position 1, first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						pytorchVProj[kvDim], pytorchVProj[kvDim+1], pytorchVProj[kvDim+2],
						pytorchVProj[kvDim+3], pytorchVProj[kvDim+4]));

		// Load weights
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection rmsWeight = stateDict.get("model.layers.0.input_layernorm.weight");
		PackedCollection wv = stateDict.get("model.layers.0.self_attn.v_proj.weight");
		PackedCollection bv = stateDict.get("model.layers.0.self_attn.v_proj.bias");

		// Create value cache
		PackedCollection valueCache = new PackedCollection(seqLen, kvHeads, headSize);
		valueCache.clear();

		// Position
		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> positionProducer = new DynamicCollectionProducer(position.getShape(), args -> position);

		// Token IDs
		int token0 = 9707;  // "Hello"
		int token1 = 271;   // "\n\n"

		// Manually compute V projection and compare with PyTorch
		log("\n=== Manual V Projection Comparison ===");

		// Get token embeddings
		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		// Apply RMSNorm
		double[] norm0 = applyRMSNorm(emb0, rmsWeight, epsilon);
		double[] norm1 = applyRMSNorm(emb1, rmsWeight, epsilon);

		// Apply V projection
		double[] vProj0 = applyDense(norm0, wv, bv);
		double[] vProj1 = applyDense(norm1, wv, bv);

		log("Java V projection (position 0, first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						vProj0[0], vProj0[1], vProj0[2], vProj0[3], vProj0[4]));
		log("Java V projection (position 1, first 5): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f]",
						vProj1[0], vProj1[1], vProj1[2], vProj1[3], vProj1[4]));

		// Compare with PyTorch
		double maxDiff0 = 0;
		double maxDiff1 = 0;
		for (int i = 0; i < kvDim; i++) {
			maxDiff0 = Math.max(maxDiff0, Math.abs(vProj0[i] - pytorchVProj[i]));
			maxDiff1 = Math.max(maxDiff1, Math.abs(vProj1[i] - pytorchVProj[kvDim + i]));
		}
		log("Max difference (position 0): " + String.format("%.6f", maxDiff0));
		log("Max difference (position 1): " + String.format("%.6f", maxDiff1));

		// Now store into cache and verify
		log("\n=== Storing into Cache ===");
		for (int i = 0; i < kvDim; i++) {
			valueCache.setMem(0 * kvDim + i, vProj0[i]);  // Position 0
			valueCache.setMem(1 * kvDim + i, vProj1[i]);  // Position 1
		}

		log("Cache contents after manual storage:");
		log("  Position 0 (first 5): " + formatValues(valueCache, 0, 5));
		log("  Position 1 (first 5): " + formatValues(valueCache, kvDim, 5));

		// Verify against PyTorch
		double cacheMaxDiff0 = 0;
		double cacheMaxDiff1 = 0;
		for (int i = 0; i < kvDim; i++) {
			cacheMaxDiff0 = Math.max(cacheMaxDiff0, Math.abs(valueCache.toDouble(i) - pytorchVProj[i]));
			cacheMaxDiff1 = Math.max(cacheMaxDiff1, Math.abs(valueCache.toDouble(kvDim + i) - pytorchVProj[kvDim + i]));
		}
		log("Cache vs PyTorch - Max diff (pos 0): " + String.format("%.6f", cacheMaxDiff0));
		log("Cache vs PyTorch - Max diff (pos 1): " + String.format("%.6f", cacheMaxDiff1));

		log("\n=== Summary ===");
		if (maxDiff0 < 0.001 && maxDiff1 < 0.001) {
			log("[OK] V projection matches PyTorch!");
		} else {
			log("[MISMATCH] V projection differs from PyTorch!");
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

	private String formatValues(PackedCollection c, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(offset + i)));
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
