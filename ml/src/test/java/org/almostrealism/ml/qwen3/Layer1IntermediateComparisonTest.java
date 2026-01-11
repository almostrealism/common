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
 * Compare layer 1 intermediate results with PyTorch reference.
 * Tests each component: RMSNorm, Attention, FFN
 */
public class Layer1IntermediateComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1Intermediates() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_intermediate_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 1 Intermediate Comparison Test ===\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PyTorch's layer 0 output as input for layer 1
		float[] pytorchLayer0Output = loadReferenceOutput("after_layer_0.bin");
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchLayer0Output[i]);
		}

		// Verify input matches expected
		float[] pytorchLayer1InputNormInput = loadReferenceOutput("layer1_input_norm_input.bin");
		log("=== Step 0: Verify input ===");
		double inputMaxDiff = 0;
		for (int i = 0; i < config.dim; i++) {
			double diff = Math.abs(input.toDouble(i) - pytorchLayer1InputNormInput[i]);
			inputMaxDiff = Math.max(inputMaxDiff, diff);
		}
		log(String.format("Input vs PyTorch layer1_input_norm_input: max diff = %.10f", inputMaxDiff));

		// Test 1: Just RMSNorm (input_layernorm)
		log("\n=== Step 1: Test RMSNorm ===");
		PackedCollection rmsAttWeight = stateDict.get("model.layers.1.input_layernorm.weight");
		float[] pytorchInputNorm = loadReferenceOutput("layer1_input_norm.bin");

		Model rmsModel = new Model(shape(1, config.dim));
		rmsModel.add(rmsnorm(shape(1, config.dim), rmsAttWeight, null, 1e-6));
		PackedCollection rmsOutput = rmsModel.compile().forward(input);

		log("RMSNorm output comparison:");
		compareOutputs(pytorchInputNorm, rmsOutput, 10);

		// Test 2: Just the attention block (attention only, no residual)
		log("\n=== Step 2: Test Attention ===");
		float[] pytorchOProj = loadReferenceOutput("layer1_o_proj.bin");

		// Load layer 1 attention weights
		PackedCollection wq = stateDict.get("model.layers.1.self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get("model.layers.1.self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get("model.layers.1.self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get("model.layers.1.self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get("model.layers.1.self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get("model.layers.1.self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get("model.layers.1.self_attn.v_proj.bias");
		PackedCollection qkNormQ = stateDict.get("model.layers.1.self_attn.q_norm.weight");
		PackedCollection qkNormK = stateDict.get("model.layers.1.self_attn.k_norm.weight");

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		Model attModel = new Model(shape(1, config.dim));
		Block attBlock = attention(config.headCount, config.kvHeadCount,
				rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, qkNormQ, qkNormK,
				freqCis, p(position), 1e-6);
		attModel.add(attBlock);
		PackedCollection attOutput = attModel.compile().forward(input);

		log("Attention output comparison:");
		compareOutputs(pytorchOProj, attOutput, 10);

		// Test 3: Attention + residual
		log("\n=== Step 3: Test Attention + Residual ===");
		float[] pytorchPostNormInput = loadReferenceOutput("layer1_post_norm_input.bin");

		// Attention + residual = input + attention_output
		PackedCollection attPlusResidual = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			attPlusResidual.setMem(i, input.toDouble(i) + attOutput.toDouble(i));
		}

		log("Attention + Residual comparison:");
		compareOutputs(pytorchPostNormInput, attPlusResidual, 10);

		// Test 4: FFN only (post_attention_layernorm + MLP)
		log("\n=== Step 4: Test FFN ===");
		float[] pytorchMlp = loadReferenceOutput("layer1_mlp.bin");

		PackedCollection rmsFfnWeight = stateDict.get("model.layers.1.post_attention_layernorm.weight");
		PackedCollection w1 = stateDict.get("model.layers.1.mlp.gate_proj.weight");
		PackedCollection w2 = stateDict.get("model.layers.1.mlp.down_proj.weight");
		PackedCollection w3 = stateDict.get("model.layers.1.mlp.up_proj.weight");

		Model ffnModel = new Model(shape(1, config.dim));
		ffnModel.add(feedForward(rmsFfnWeight, w1, w2, w3, 1e-6));
		PackedCollection ffnOutput = ffnModel.compile().forward(attPlusResidual);

		log("FFN output comparison:");
		compareOutputs(pytorchMlp, ffnOutput, 10);

		// Test 5: Full layer output
		log("\n=== Step 5: Test Full Layer ===");
		float[] pytorchLayer1Output = loadReferenceOutput("after_layer_1.bin");

		// Full output = attPlusResidual + ffnOutput
		PackedCollection fullOutput = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			fullOutput.setMem(i, attPlusResidual.toDouble(i) + ffnOutput.toDouble(i));
		}

		log("Full layer output comparison (manual calc):");
		compareOutputs(pytorchLayer1Output, fullOutput, 10);

		// Test 6: Full layer via transformer()
		log("\n=== Step 6: Test Full Layer via transformer() ===");
		Model layer1Model = new Model(shape(1, config.dim));
		layer1Model.add(transformer(
				config.headCount, config.kvHeadCount,
				rmsAttWeight,
				wk, wv, wq, wo,
				bk, bv, bq,
				qkNormQ, qkNormK,
				freqCis,
				rmsFfnWeight,
				w1, w2, w3,
				p(position),
				1e-6));
		PackedCollection transformerOutput = layer1Model.compile().forward(input);

		log("Full layer output comparison (transformer() method):");
		compareOutputs(pytorchLayer1Output, transformerOutput, 10);

		stateDict.destroy();
	}

	private void compareOutputs(float[] expected, PackedCollection actual, int showN) {
		double maxDiff = 0;
		int maxDiffIdx = -1;
		double sumDiff = 0;

		for (int i = 0; i < expected.length; i++) {
			double diff = Math.abs(actual.toDouble(i) - expected[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxDiffIdx = i;
			}
			sumDiff += diff;
		}

		double meanDiff = sumDiff / expected.length;
		log(String.format("  Mean diff: %.6f, Max diff: %.6f at index %d", meanDiff, maxDiff, maxDiffIdx));

		log(String.format("  First %d values:", showN));
		for (int i = 0; i < Math.min(showN, expected.length); i++) {
			log(String.format("    [%d] PyTorch=%.6f, Java=%.6f, diff=%.6f",
					i, expected[i], actual.toDouble(i), actual.toDouble(i) - expected[i]));
		}

		if (maxDiff < 1e-4) {
			log("  [EXCELLENT] Match within 1e-4");
		} else if (maxDiff < 0.01) {
			log("  [GOOD] Match within 0.01");
		} else if (maxDiff < 0.1) {
			log("  [WARNING] Divergence < 0.1");
		} else {
			log("  [FAIL] Significant divergence: " + maxDiff);
		}
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
