package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Test ALL 24 layers in isolation to find which specific layers have bugs.
 */
public class AllLayersIsolationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testAllLayersInIsolation() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/all_layers_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(80));
		log("  ALL LAYERS ISOLATION TEST");
		log("  Testing each layer independently (feeding PyTorch's previous layer output)");
		log("=".repeat(80) + "\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		log(String.format("%-8s %-15s %-15s %-15s %-15s", "Layer", "MAE", "RMSE", "Max Error", "Status"));
		log("-".repeat(80));

		// Test all 24 layers
		for (int layerIdx = 0; layerIdx < 24; layerIdx++) {
			// Load PyTorch's input to this layer
			String inputFile = layerIdx == 0 ? "after_embeddings.bin" : "after_layer_" + (layerIdx - 1) + ".bin";
			float[] pytorchInput = loadReferenceOutput(inputFile);

			// Load PyTorch's expected output from this layer
			float[] pytorchOutput = loadReferenceOutput("after_layer_" + layerIdx + ".bin");

			// Create input from PyTorch's previous layer output
			PackedCollection input = new PackedCollection(shape(1, config.dim));
			for (int i = 0; i < config.dim; i++) {
				input.setMem(i, pytorchInput[i]);
			}

			// Build and run just this layer
			Model model = buildSingleLayer(config, stateDict, layerIdx);
			CompiledModel compiled = model.compile();
			PackedCollection output = compiled.forward(input);

			// Compare
			float[] arOutput = new float[config.dim];
			for (int i = 0; i < config.dim; i++) {
				arOutput[i] = (float) output.toDouble(i);
			}

			double mae = computeMAE(pytorchOutput, arOutput);
			double rmse = computeRMSE(pytorchOutput, arOutput);
			double maxErr = computeMaxError(pytorchOutput, arOutput);

			String status;
			if (mae < 0.001) status = "EXCELLENT";
			else if (mae < 0.01) status = "GOOD";
			else if (mae < 0.1) status = "WARNING";
			else status = "*** BUG ***";

			log(String.format("%-8d %-15.6f %-15.6f %-15.6f %s",
				layerIdx, mae, rmse, maxErr, status));
		}

		stateDict.destroy();
		log("\n" + "=".repeat(80));
		log("  TEST COMPLETE");
		log("=".repeat(80));
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
			layerRmsAtt, layerWk, layerWv, layerWq, layerWo,
			layerBk, layerBv, layerBq, layerQkNormQ, layerQkNormK,
			freqCis, layerRmsFfn, layerW1, layerW2, layerW3,
			p(position), 1e-6));

		return model;
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.headSize;
		int seqLen = 10;
		double theta = config.ropeTheta;
		int freqDim = headSize / 2;

		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (double) (2 * i) / headSize);
				double val = pos * freq;
				freqCis.setMem(pos * freqDim * 2 + i * 2, Math.cos(val));
				freqCis.setMem(pos * freqDim * 2 + i * 2 + 1, Math.sin(val));
			}
		}
		return freqCis;
	}

	private float[] loadReferenceOutput(String filename) throws IOException {
		try (FileChannel channel = FileChannel.open(
				Paths.get(REFERENCE_DIR, filename), StandardOpenOption.READ)) {
			ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			channel.read(buffer);
			buffer.flip();

			int size = buffer.getInt();
			float[] output = new float[size];
			for (int i = 0; i < size; i++) {
				output[i] = buffer.getFloat();
			}
			return output;
		}
	}

	private double computeMAE(float[] expected, float[] actual) {
		double sum = 0;
		for (int i = 0; i < expected.length; i++) {
			sum += Math.abs(expected[i] - actual[i]);
		}
		return sum / expected.length;
	}

	private double computeRMSE(float[] expected, float[] actual) {
		double sum = 0;
		for (int i = 0; i < expected.length; i++) {
			double diff = expected[i] - actual[i];
			sum += diff * diff;
		}
		return Math.sqrt(sum / expected.length);
	}

	private double computeMaxError(float[] expected, float[] actual) {
		double max = 0;
		for (int i = 0; i < expected.length; i++) {
			max = Math.max(max, Math.abs(expected[i] - actual[i]));
		}
		return max;
	}
}
