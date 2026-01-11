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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Test to verify cache behavior in the actual CompiledModel.
 *
 * This creates a simplified model that demonstrates the exact issue.
 */
public class CompiledModelCacheTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testCompiledModelCacheOrder() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/compiled_model_cache.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Compiled Model Cache Ordering Test");
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

		// Build attention block
		log("\nBuilding attention block with seqLen=" + seqLen + "...");
		ComputeRequirement[] requirements = new ComputeRequirement[0];

		Model transformer = new Model(shape(1, dim));

		Block attentionBlock = attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, null, null, freqCis, positionProducer, epsilon, requirements);
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

		// === Run position 0 ===
		log("\n=== Running Position 0 ===");
		position.setMem(0, 0.0);
		PackedCollection input0 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input0.setMem(i, embeddings.toDouble(token0 * dim + i));
		}
		PackedCollection output0 = compiledModel.forward(input0);
		log("Output 0 [0-4]: " + formatPacked(output0, 0, 5));

		// === Run position 1 ===
		log("\n=== Running Position 1 ===");
		position.setMem(0, 1.0);
		PackedCollection input1 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input1.setMem(i, embeddings.toDouble(token1 * dim + i));
		}
		PackedCollection output1 = compiledModel.forward(input1);
		log("Output 1 [0-4]: " + formatPacked(output1, 0, 5));

		// Load PyTorch reference
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");
		log("\nPyTorch o_proj pos1 [0-4]: " + formatArray(pytorchOProj, 0, 5));

		// Compare
		double maxDiff = 0;
		double sumDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(output1.toDouble(i) - pytorchOProj[i]);
			maxDiff = Math.max(maxDiff, diff);
			sumDiff += diff;
		}
		double meanDiff = sumDiff / dim;

		log("\nComparison with PyTorch:");
		log(String.format("  Mean diff: %.6f", meanDiff));
		log(String.format("  Max diff: %.6f", maxDiff));

		// Now let's try running position 0 AGAIN after position 1
		log("\n=== Running Position 0 AGAIN (after position 1) ===");
		position.setMem(0, 0.0);
		PackedCollection output0Again = compiledModel.forward(input0);
		log("Output 0 (again) [0-4]: " + formatPacked(output0Again, 0, 5));

		// Compare with first position 0 output
		double pos0Diff = 0;
		for (int i = 0; i < dim; i++) {
			pos0Diff = Math.max(pos0Diff, Math.abs(output0.toDouble(i) - output0Again.toDouble(i)));
		}
		log("Position 0: first vs second run max diff: " + String.format("%.6f", pos0Diff));

		if (pos0Diff > 0.0001) {
			log("\n[WARNING] Position 0 output CHANGED after running position 1!");
			log("This indicates the cache may be affecting position 0 results.");
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

	private String formatArray(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatPacked(PackedCollection c, int offset, int count) {
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
