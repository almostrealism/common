package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
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
 * Verify that:
 * 1. Java's layer 0 output == PyTorch's after_layer_0.bin
 * 2. Layer 1 using Java's layer 0 output vs PyTorch's layer 0 output
 * This isolates whether the issue is with the reference data or the computation
 */
public class Layer1InputVerificationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void verifyLayer1Inputs() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_input_verification.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 1 Input Verification Test ===\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load references
		float[] pytorchLayer0Output = loadReferenceOutput("after_layer_0.bin");
		float[] pytorchLayer1Output = loadReferenceOutput("after_layer_1.bin");

		// Get embeddings for token
		int tokenId = 9707;
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");

		// Create input from embeddings
		PackedCollection embeddingsInput = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			embeddingsInput.setMem(i, embeddings.toDouble(tokenId * config.dim + i));
		}

		// Build and run layer 0
		log("=== Step 1: Run Layer 0 ===");
		Model layer0Model = buildSingleLayer(config, stateDict, 0);
		org.almostrealism.model.CompiledModel compiled0 = layer0Model.compile();
		PackedCollection javaLayer0Output = compiled0.forward(embeddingsInput);

		// Compare Java layer 0 output with PyTorch
		log("\nJava Layer 0 output vs PyTorch after_layer_0.bin:");
		double maxDiff0 = 0;
		for (int i = 0; i < config.dim; i++) {
			double diff = Math.abs(javaLayer0Output.toDouble(i) - pytorchLayer0Output[i]);
			maxDiff0 = Math.max(maxDiff0, diff);
		}
		log(String.format("  Max difference: %.10f", maxDiff0));

		if (maxDiff0 < 1e-4) {
			log("  [OK] Layer 0 outputs match perfectly");
		} else {
			log("  [FAIL] Layer 0 outputs differ!");
		}

		// Now run layer 1 with TWO different inputs:
		// A) PyTorch's after_layer_0.bin
		// B) Java's layer 0 output

		log("\n=== Step 2: Run Layer 1 with PyTorch's layer 0 output ===");
		Model layer1ModelA = buildSingleLayer(config, stateDict, 1);
		org.almostrealism.model.CompiledModel compiled1A = layer1ModelA.compile();

		PackedCollection pytorchInput = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			pytorchInput.setMem(i, pytorchLayer0Output[i]);
		}
		PackedCollection javaLayer1OutputA = compiled1A.forward(pytorchInput);

		log("\nLayer 1 output (PyTorch input) vs PyTorch after_layer_1.bin:");
		double maxDiffA = 0;
		int maxDiffIdxA = -1;
		for (int i = 0; i < config.dim; i++) {
			double diff = Math.abs(javaLayer1OutputA.toDouble(i) - pytorchLayer1Output[i]);
			if (diff > maxDiffA) {
				maxDiffA = diff;
				maxDiffIdxA = i;
			}
		}
		log(String.format("  Max difference: %.6f at index %d", maxDiffA, maxDiffIdxA));

		log("\n=== Step 3: Run Layer 1 with Java's layer 0 output ===");
		Model layer1ModelB = buildSingleLayer(config, stateDict, 1);
		org.almostrealism.model.CompiledModel compiled1B = layer1ModelB.compile();
		PackedCollection javaLayer1OutputB = compiled1B.forward(javaLayer0Output);

		log("\nLayer 1 output (Java input) vs PyTorch after_layer_1.bin:");
		double maxDiffB = 0;
		int maxDiffIdxB = -1;
		for (int i = 0; i < config.dim; i++) {
			double diff = Math.abs(javaLayer1OutputB.toDouble(i) - pytorchLayer1Output[i]);
			if (diff > maxDiffB) {
				maxDiffB = diff;
				maxDiffIdxB = i;
			}
		}
		log(String.format("  Max difference: %.6f at index %d", maxDiffB, maxDiffIdxB));

		log("\n=== Step 4: Compare the two layer 1 outputs ===");
		log("Layer 1 with PyTorch input vs Layer 1 with Java input:");
		double maxDiffAB = 0;
		for (int i = 0; i < config.dim; i++) {
			double diff = Math.abs(javaLayer1OutputA.toDouble(i) - javaLayer1OutputB.toDouble(i));
			maxDiffAB = Math.max(maxDiffAB, diff);
		}
		log(String.format("  Max difference: %.10f", maxDiffAB));

		if (maxDiffAB < 1e-6) {
			log("  [OK] Both layer 1 outputs are identical (input difference is negligible)");
		} else {
			log("  [INFO] Layer 1 outputs differ based on input");
		}

		log("\n=== Summary ===");
		log(String.format("Layer 0: Java vs PyTorch max diff = %.10f", maxDiff0));
		log(String.format("Layer 1 (PyTorch input): Java vs PyTorch max diff = %.6f", maxDiffA));
		log(String.format("Layer 1 (Java input): Java vs PyTorch max diff = %.6f", maxDiffB));
		log(String.format("Layer 1: PyTorch input vs Java input max diff = %.10f", maxDiffAB));

		if (maxDiffA > 0.1) {
			log("\n[DIAGNOSIS] Layer 1 computation itself has issues (max diff " + maxDiffA + ")");
		} else {
			log("\n[DIAGNOSIS] Layer 1 computation is correct");
		}

		stateDict.destroy();
	}

	private Model buildSingleLayer(Qwen3Config config, StateDictionary stateDict, int layerIndex) {
		Model model = new Model(shape(1, config.dim));

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		String prefix = String.format("model.layers.%d", layerIndex);

		PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
		PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
		PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
		PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
		PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
		PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
		PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
		PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
		PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");
		PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
		PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");
		PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
		PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
		PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

		model.add(transformer(
				config.headCount, config.kvHeadCount,
				layerRmsAtt,
				layerWk, layerWv, layerWq, layerWo,
				layerBk, layerBv, layerBq,
				layerQkNormQ, layerQkNormK,
				freqCis,
				layerRmsFfn,
				layerW1, layerW2, layerW3,
				p(position),
				1e-6));

		return model;
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
