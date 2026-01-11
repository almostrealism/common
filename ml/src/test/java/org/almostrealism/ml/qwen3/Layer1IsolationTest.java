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
 * Test layer 1 in isolation to verify it works when given correct input.
 * This helps isolate whether the divergence is in layer 1 itself or in how
 * layers are chained together.
 */
public class Layer1IsolationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1Isolated() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 1 Isolation Test ===\n");
		log("Purpose: Run only layer 1, using PyTorch's layer 0 output as input");
		log("This isolates whether layer 1 itself is correct\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PyTorch's output from layer 0 (this is the CORRECT input for layer 1)
		float[] pytorchLayer0Output = loadReferenceOutput("after_layer_0.bin");
		log("Loaded PyTorch layer 0 output: " + pytorchLayer0Output.length + " values");

		// Create input from PyTorch's layer 0 output
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchLayer0Output[i]);
		}

		// Build a model with ONLY layer 1
		log("\nBuilding layer 1 only...");
		Model layer1Only = buildSingleLayer(config, stateDict, 1); // layer index 1

		// Compile and run
		log("Compiling...");
		org.almostrealism.model.CompiledModel compiled = layer1Only.compile();

		log("Running forward pass through layer 1...");
		PackedCollection output = compiled.forward(input);

		// Load PyTorch reference (after layer 1)
		float[] pytorchLayer1Output = loadReferenceOutput("after_layer_1.bin");

		// Compare
		float[] arOutput = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			arOutput[i] = (float) output.toDouble(i);
		}

		compareOutputs("Layer 1 isolated", pytorchLayer1Output, arOutput);

		stateDict.destroy();
	}

	/**
	 * Build a model with only a single transformer layer at the specified index.
	 */
	private Model buildSingleLayer(Qwen3Config config, StateDictionary stateDict, int layerIndex) {
		Model model = new Model(shape(1, config.dim));

		// Compute RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(config);

		// Position 0
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

	private void compareOutputs(String name, float[] expected, float[] actual) {
		double maxDiff = 0;
		int maxDiffIdx = -1;
		double sumDiff = 0;
		double sumSqDiff = 0;
		double sumExpected = 0;
		double sumActual = 0;

		for (int i = 0; i < expected.length; i++) {
			double diff = actual[i] - expected[i];
			double absDiff = Math.abs(diff);
			if (absDiff > maxDiff) {
				maxDiff = absDiff;
				maxDiffIdx = i;
			}
			sumDiff += absDiff;
			sumSqDiff += diff * diff;
			sumExpected += expected[i];
			sumActual += actual[i];
		}

		double meanDiff = sumDiff / expected.length;
		double rmse = Math.sqrt(sumSqDiff / expected.length);
		double meanExpected = sumExpected / expected.length;
		double meanActual = sumActual / expected.length;

		log("=== " + name + " Comparison ===");
		log(String.format("Mean Absolute Difference: %.6f", meanDiff));
		log(String.format("RMSE: %.6f", rmse));
		log(String.format("Max Absolute Difference: %.6f at index %d", maxDiff, maxDiffIdx));
		log(String.format("PyTorch mean: %.6f", meanExpected));
		log(String.format("AR mean: %.6f", meanActual));
		log("");

		log("First 10 values comparison:");
		log(String.format("%-6s%-16s%-16s%-16s", "Idx", "PyTorch", "AR", "Diff"));
		log("-------------------------------------------------------");
		for (int i = 0; i < Math.min(10, expected.length); i++) {
			log(String.format("%-6d%-16.6f%-16.6f%-16.6f", i, expected[i], actual[i], actual[i] - expected[i]));
		}
		log("");

		if (maxDiff < 1e-4) {
			log("[EXCELLENT] Outputs match within 1e-4 tolerance");
		} else if (maxDiff < 0.01) {
			log("[GOOD] Outputs match within 0.01 tolerance");
		} else if (maxDiff < 0.1) {
			log("[WARNING] Small divergence detected (< 0.1)");
		} else {
			log("[FAIL] Significant divergence detected: " + maxDiff);
		}
	}
}
