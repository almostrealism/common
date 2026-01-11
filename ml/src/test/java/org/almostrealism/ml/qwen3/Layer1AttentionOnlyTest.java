package org.almostrealism.ml.qwen3;

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
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
 * Test layer 1's attention block ONLY (no FFN).
 * This isolates whether the issue is in attention or FFN for layer 1.
 */
public class Layer1AttentionOnlyTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1AttentionOnly() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_attention_only.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 1 Attention Only Test ===\n");
		log("Purpose: Test ONLY layer 1's attention block (no FFN)");
		log("This isolates whether the issue is in attention vs FFN\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PyTorch's output from layer 0 as input
		float[] pytorchLayer0Output = loadReferenceOutput("after_layer_0.bin");
		log("Loaded PyTorch layer 0 output as input");

		// Create input
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchLayer0Output[i]);
		}

		// Build model with ONLY attention block for layer 1 (no FFN, no residual)
		log("\nBuilding layer 1 attention only...");

		// Config
		int dim = config.dim;
		int heads = config.headCount;
		int kvHeads = config.kvHeadCount;
		int headSize = dim / heads;
		int seqLen = config.seqLen;
		double epsilon = 1e-6;

		// Position
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// RoPE
		PackedCollection freqCis = computeRopeFreqs(config);

		// Layer 1 weights
		String prefix = "model.layers.1";
		PackedCollection rmsAttWeight = stateDict.get(prefix + ".input_layernorm.weight");
		PackedCollection wq = stateDict.get(prefix + ".self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get(prefix + ".self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get(prefix + ".self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get(prefix + ".self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get(prefix + ".self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get(prefix + ".self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get(prefix + ".self_attn.v_proj.bias");
		PackedCollection qkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
		PackedCollection qkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

		// Also load layer 0 weights for comparison
		String prefix0 = "model.layers.0";
		PackedCollection rmsAttWeight0 = stateDict.get(prefix0 + ".input_layernorm.weight");
		PackedCollection wq0 = stateDict.get(prefix0 + ".self_attn.q_proj.weight");

		// Check if weights are different
		log("\n=== Weight Comparison (Layer 0 vs Layer 1) ===");
		double rmsWeightDiff = compareFirstN(rmsAttWeight0, rmsAttWeight, 10);
		log(String.format("RMSNorm weight first 10 diff: %.6f", rmsWeightDiff));

		double wqDiff = compareFirstN(wq0, wq, 100);
		log(String.format("Q projection weight first 100 diff: %.6f", wqDiff));

		log(String.format("\nQK-Norm Q null? %b", qkNormQ == null));
		log(String.format("QK-Norm K null? %b", qkNormK == null));

		// Build attention block
		ComputeRequirement[] requirements = new ComputeRequirement[0];
		Block attentionBlock = attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, qkNormQ, qkNormK, freqCis, p(position), epsilon, requirements);

		Model model = new Model(shape(1, dim));
		model.add(attentionBlock);

		// Compile and run
		log("\nCompiling...");
		org.almostrealism.model.CompiledModel compiled = model.compile();

		log("Running forward pass...");
		PackedCollection output = compiled.forward(input);

		// Extract output
		float[] arOutput = new float[dim];
		for (int i = 0; i < dim; i++) {
			arOutput[i] = (float) output.toDouble(i);
		}

		// Basic stats
		double maxAbs = 0;
		double mean = 0;
		for (int i = 0; i < dim; i++) {
			maxAbs = Math.max(maxAbs, Math.abs(arOutput[i]));
			mean += arOutput[i];
		}
		mean /= dim;

		log("\n=== Attention Output Statistics ===");
		log(String.format("Mean: %.6f", mean));
		log(String.format("Max absolute value: %.6f", maxAbs));

		log("\nFirst 10 attention output values:");
		for (int i = 0; i < 10; i++) {
			log(String.format("  [%d] = %.6f", i, arOutput[i]));
		}

		// Compare with layer 0 attention output at same input
		// Actually we don't have that reference, so just check stability
		if (maxAbs > 100 || Double.isNaN(maxAbs) || Double.isInfinite(maxAbs)) {
			log("\n[FAIL] Attention output has numerical issues");
		} else {
			log("\n[OK] Attention output is numerically stable");
		}

		stateDict.destroy();
	}

	private double compareFirstN(PackedCollection a, PackedCollection b, int n) {
		double sumDiff = 0;
		for (int i = 0; i < Math.min(n, Math.min(a.getShape().getTotalSize(), b.getShape().getTotalSize())); i++) {
			sumDiff += Math.abs(a.toDouble(i) - b.toDouble(i));
		}
		return sumDiff;
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.dim / config.headCount;
		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) {
			freqs[i] = 1.0 / Math.pow(config.ropeTheta, (2.0 * i) / headSize);
		}

		PackedCollection freqCis = new PackedCollection(shape(config.seqLen, freqDim, 2));
		for (int pos = 0; pos < config.seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = pos * freqs[i];
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}
		return freqCis;
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
