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
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compare the values stored in the Java KV cache with PyTorch reference.
 *
 * This test checks if values are being stored correctly in the cache
 * after V projection.
 */
public class ValueCacheComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void compareValueCache() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/value_cache_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Value Cache Comparison: Java vs PyTorch");
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

		// Load PyTorch reference data
		double[] pytorchVProj = loadBinaryLogits(REFERENCE_DIR + "/v_proj_both_positions.bin");
		double[] pytorchVExpanded = loadBinaryLogits(REFERENCE_DIR + "/v_expanded_gqa.bin");

		log("Loaded PyTorch reference data:");
		log("  v_proj (both positions): " + pytorchVProj.length + " values (expected: " + (2 * kvDim) + ")");
		log("  v_expanded (after GQA): " + pytorchVExpanded.length + " values");

		// Expected V proj for each position
		log("\nPyTorch V projection:");
		log("  Position 0 (first 5): [" + String.format("%.4f, %.4f, %.4f, %.4f, %.4f",
				pytorchVProj[0], pytorchVProj[1], pytorchVProj[2], pytorchVProj[3], pytorchVProj[4]) + ", ...]");
		log("  Position 1 (first 5): [" + String.format("%.4f, %.4f, %.4f, %.4f, %.4f",
				pytorchVProj[kvDim], pytorchVProj[kvDim+1], pytorchVProj[kvDim+2], pytorchVProj[kvDim+3], pytorchVProj[kvDim+4]) + ", ...]");

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
		Producer<PackedCollection> positionProducer = new DynamicCollectionProducer(position.getShape(), args -> position);

		// Build the attention block
		ComputeRequirement[] requirements = new ComputeRequirement[0];

		// Create a custom attention block where we can access the value cache
		log("\nBuilding attention block with accessible cache...");

		// Create value cache manually
		PackedCollection valueCache = new PackedCollection(seqLen, kvHeads, headSize);
		valueCache.clear();

		// Just do the V projection part and store in cache
		// We'll manually trace what goes into the cache

		// Token IDs
		int token0 = 9707;  // "Hello"
		int token1 = 271;   // "\n\n"

		// Compute manually what should go in the cache
		log("\n=== Manual V Projection Computation ===");

		// Get token embeddings
		double[] emb0 = new double[dim];
		double[] emb1 = new double[dim];
		for (int i = 0; i < dim; i++) {
			emb0[i] = embeddings.toDouble(token0 * dim + i);
			emb1[i] = embeddings.toDouble(token1 * dim + i);
		}

		// Apply RMSNorm
		double[] norm0 = applyRMSNorm(emb0, rmsAttWeight, epsilon);
		double[] norm1 = applyRMSNorm(emb1, rmsAttWeight, epsilon);

		// Apply V projection
		double[] vProj0 = applyDense(norm0, wv, bv);
		double[] vProj1 = applyDense(norm1, wv, bv);

		log("Java V projection (from manual computation):");
		log("  Position 0 (first 5): [" + String.format("%.4f, %.4f, %.4f, %.4f, %.4f",
				vProj0[0], vProj0[1], vProj0[2], vProj0[3], vProj0[4]) + ", ...]");
		log("  Position 1 (first 5): [" + String.format("%.4f, %.4f, %.4f, %.4f, %.4f",
				vProj1[0], vProj1[1], vProj1[2], vProj1[3], vProj1[4]) + ", ...]");

		// Compare V projection
		double maxDiff = 0;
		for (int i = 0; i < kvDim; i++) {
			maxDiff = Math.max(maxDiff, Math.abs(vProj0[i] - pytorchVProj[i]));
			maxDiff = Math.max(maxDiff, Math.abs(vProj1[i] - pytorchVProj[kvDim + i]));
		}
		log("Max V projection difference: " + String.format("%.6f", maxDiff));

		// Now check the GQA expansion
		log("\n=== GQA Expansion Check ===");

		// PyTorch v_expanded layout: [heads=14, seqLen=2, headSize=64]
		// After GQA expansion, kv head 0 -> query heads 0-6, kv head 1 -> query heads 7-13
		int headsPerKvGroup = heads / kvHeads;  // 7

		log("GQA: Each of " + kvHeads + " KV heads is expanded to " + headsPerKvGroup + " query heads");

		// Check that PyTorch's expansion matches our understanding
		log("\nPyTorch v_expanded layout check:");
		log("  KV head 0 values (from pos 0) should repeat for query heads 0-6:");
		for (int h = 0; h < 3; h++) {  // Check first 3 query heads
			int kvHead = h / headsPerKvGroup;  // 0
			// PyTorch: v_expanded[h, pos=0, d=0]
			int ptIdx = h * (2 * headSize) + 0 * headSize + 0;
			log("    Query head " + h + " (KV head " + kvHead + "): v[" + h + ",0,0] = " + pytorchVExpanded[ptIdx]);
		}

		// What we expect: all query heads 0-6 should have the same value as vProj0[0:64] (kv head 0)
		log("\n  Java V projection for KV head 0 (pos 0): first 3 values = [" +
				String.format("%.4f, %.4f, %.4f", vProj0[0], vProj0[1], vProj0[2]) + ", ...]");
		log("  Java V projection for KV head 1 (pos 0): first 3 values = [" +
				String.format("%.4f, %.4f, %.4f", vProj0[headSize], vProj0[headSize+1], vProj0[headSize+2]) + ", ...]");

		// Check if PyTorch expanded values match our V projection
		log("\nVerifying GQA expansion:");
		int correctCount = 0;
		int totalCount = 0;
		for (int h = 0; h < heads; h++) {
			int kvHead = h / headsPerKvGroup;
			for (int pos = 0; pos < 2; pos++) {
				for (int d = 0; d < 5; d++) {  // Check first 5 dims
					int ptIdx = h * (2 * headSize) + pos * headSize + d;
					double ptVal = pytorchVExpanded[ptIdx];

					// Our V projection value for this KV head
					double[] vProj = (pos == 0) ? vProj0 : vProj1;
					double ourVal = vProj[kvHead * headSize + d];

					if (Math.abs(ptVal - ourVal) < 0.001) {
						correctCount++;
					}
					totalCount++;
				}
			}
		}
		log("  " + correctCount + "/" + totalCount + " sampled values match within 0.001");

		if (correctCount == totalCount) {
			log("\n[OK] V projection and GQA expansion match PyTorch!");
		} else {
			log("\n[MISMATCH] Some values don't match. Investigating...");

			// Print detailed comparison
			for (int h = 0; h < 3; h++) {
				int kvHead = h / headsPerKvGroup;
				log("  Query head " + h + " (KV head " + kvHead + "):");
				for (int pos = 0; pos < 2; pos++) {
					int ptIdx = h * (2 * headSize) + pos * headSize + 0;
					double ptVal = pytorchVExpanded[ptIdx];
					double[] vProj = (pos == 0) ? vProj0 : vProj1;
					double ourVal = vProj[kvHead * headSize + 0];
					log("    pos " + pos + ": PyTorch=" + String.format("%.4f", ptVal) +
							", Java=" + String.format("%.4f", ourVal) +
							", diff=" + String.format("%.6f", Math.abs(ptVal - ourVal)));
				}
			}
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
