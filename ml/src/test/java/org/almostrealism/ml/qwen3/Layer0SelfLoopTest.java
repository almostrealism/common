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
 * Test layer 0 with layer 0's output as input (self-loop).
 * This tests if the issue is with the input distribution or something layer-specific.
 */
public class Layer0SelfLoopTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer0WithLayer0Output() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer0_selfloop.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 0 Self-Loop Test ===\n");
		log("Purpose: Run layer 0 with layer 0's output as input");
		log("This tests if the issue is with input distribution\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PyTorch's output from layer 0 as input
		float[] pytorchLayer0Output = loadReferenceOutput("after_layer_0.bin");
		log("Loaded PyTorch layer 0 output: " + pytorchLayer0Output.length + " values");

		// Create input from PyTorch's layer 0 output
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchLayer0Output[i]);
		}

		// Build a model with layer 0 only
		log("\nBuilding layer 0...");
		Model layer0Only = buildSingleLayer(config, stateDict, 0); // layer index 0

		// Compile and run
		log("Compiling...");
		org.almostrealism.model.CompiledModel compiled = layer0Only.compile();

		log("Running forward pass...");
		PackedCollection output = compiled.forward(input);

		// We don't have a reference for layer0(layer0_output), but we can see if output is reasonable
		float[] arOutput = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			arOutput[i] = (float) output.toDouble(i);
		}

		// Check basic statistics
		double maxAbs = 0;
		double mean = 0;
		for (int i = 0; i < config.dim; i++) {
			maxAbs = Math.max(maxAbs, Math.abs(arOutput[i]));
			mean += arOutput[i];
		}
		mean /= config.dim;

		log("\n=== Output Statistics ===");
		log(String.format("Mean: %.6f", mean));
		log(String.format("Max absolute value: %.6f", maxAbs));

		log("\nFirst 10 output values:");
		for (int i = 0; i < 10; i++) {
			log(String.format("  [%d] = %.6f", i, arOutput[i]));
		}

		if (maxAbs > 100 || Double.isNaN(maxAbs) || Double.isInfinite(maxAbs)) {
			log("\n[FAIL] Output has numerical issues (NaN/Inf/explosion)");
		} else {
			log("\n[OK] Output is numerically stable");
		}

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
}
