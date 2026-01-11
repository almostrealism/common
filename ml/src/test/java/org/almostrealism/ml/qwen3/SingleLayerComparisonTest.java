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
 * Test a single transformer layer to isolate where the position 1 discrepancy originates.
 */
public class SingleLayerComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testSingleLayerAtPosition1() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/single_layer_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Single Layer Comparison: Java vs PyTorch");
		log("===================================================\n");

		// Config from Qwen2.5-0.5B
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;
		int seqLen = 32768;
		double ropeTheta = 1000000.0;
		double epsilon = 1e-6;

		// Load weights
		log("Loading weights...");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Token embeddings
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");

		// Layer 0 weights
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

		// Position collection
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		Producer<PackedCollection> positionProducer = dynamicPosition(position);

		// Build single-layer model with just attention (no FFN)
		log("\nBuilding single-layer model...");
		ComputeRequirement[] requirements = new ComputeRequirement[0];

		Model transformer = new Model(shape(1, dim));

		// Add just the attention block (without FFN for simplicity)
		Block attentionBlock = attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, null, null, freqCis, positionProducer, epsilon, requirements);
		transformer.add(attentionBlock);

		// Compile
		CompiledModel compiledModel = transformer.compile();
		log("Model compiled.");

		// Token IDs
		int token0 = 9707;  // "Hello"
		int token1 = 271;   // "\n\n"

		// Run position 0
		log("\n=== Position 0: Token " + token0 + " ===");
		position.setMem(0, 0.0);
		PackedCollection input0 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input0.setMem(i, embeddings.toDouble(token0 * dim + i));
		}
		PackedCollection output0 = compiledModel.forward(input0);

		log("Output 0 (first 5 values): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
						output0.toDouble(0), output0.toDouble(1), output0.toDouble(2),
						output0.toDouble(3), output0.toDouble(4)));

		// Run position 1
		log("\n=== Position 1: Token " + token1 + " ===");
		position.setMem(0, 1.0);
		PackedCollection input1 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input1.setMem(i, embeddings.toDouble(token1 * dim + i));
		}
		PackedCollection output1 = compiledModel.forward(input1);

		log("Output 1 (first 5 values): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
						output1.toDouble(0), output1.toDouble(1), output1.toDouble(2),
						output1.toDouble(3), output1.toDouble(4)));

		// Load PyTorch reference for layer 0 attention output
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");
		log("\nPyTorch o_proj pos1 (first 5 values): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
						pytorchOProj[0], pytorchOProj[1], pytorchOProj[2],
						pytorchOProj[3], pytorchOProj[4]));

		// Note: The attention block output IS the attention output (after o_proj)
		// The attention block does NOT include the residual connection
		// So we can directly compare output1 with PyTorch o_proj

		log("\nJava attention output (first 5 values): " +
				String.format("[%.4f, %.4f, %.4f, %.4f, %.4f, ...]",
						output1.toDouble(0), output1.toDouble(1), output1.toDouble(2),
						output1.toDouble(3), output1.toDouble(4)));

		// Compare with PyTorch o_proj
		double maxDiff = 0;
		double sumDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(output1.toDouble(i) - pytorchOProj[i]);
			maxDiff = Math.max(maxDiff, diff);
			sumDiff += diff;
		}
		double meanDiff = sumDiff / dim;

		log("\nComparison with PyTorch o_proj:");
		log(String.format("  Mean Absolute Difference: %.6f", meanDiff));
		log(String.format("  Max Absolute Difference: %.6f", maxDiff));

		if (maxDiff < 0.01) {
			log("\n[OK] Single layer attention output matches PyTorch closely!");
		} else {
			log("\n[MISMATCH] Single layer attention output differs from PyTorch.");
			log("This suggests the issue is in layer 0 attention computation.");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
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
