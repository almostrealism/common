package org.almostrealism.ml.qwen3;

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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Test that compares the FULL AR model (all layers + final norm) output
 * with PyTorch's hidden_states[24] which includes the final norm.
 *
 * Previous tests showed MAE=3.34 for "layer 23" because they compared
 * layer 23 output (before final norm) to hidden_states[24] (after final norm).
 */
public class FullModelComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testFullModelWithFinalNorm() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/full_model_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(80));
		log("  FULL MODEL COMPARISON TEST (All Layers + Final Norm)");
		log("=".repeat(80) + "\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PyTorch embeddings as input
		float[] pytorchEmbeddings = loadReferenceOutput("after_embeddings.bin");
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchEmbeddings[i]);
		}

		log("Input (PyTorch embeddings):");
		logStats("  ", input);

		// Load expected output (after all layers + final norm)
		float[] pytorchFinalNormOutput = loadReferenceOutput("after_final_norm.bin");
		log("\nExpected output (PyTorch hidden_states[24] = after final norm):");
		logFloatStats("  ", pytorchFinalNormOutput);

		// Build full AR model: all layers + final norm (no lm_head)
		Model fullModel = buildFullModel(config, stateDict);
		PackedCollection arOutput = fullModel.compile().forward(input);

		log("\nAR output (all layers + final norm):");
		logStats("  ", arOutput);

		// Compare
		float[] arOutputArr = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			arOutputArr[i] = (float) arOutput.toDouble(i);
		}
		double mae = computeMAE(pytorchFinalNormOutput, arOutputArr);
		double rmse = computeRMSE(pytorchFinalNormOutput, arOutputArr);

		log("\n--- Comparison ---");
		log(String.format("MAE: %.6f", mae));
		log(String.format("RMSE: %.6f", rmse));

		// Print first 10 values
		log("\n--- First 10 Values ---");
		log(String.format("%-5s %-12s %-12s %-12s", "Idx", "PyTorch", "AR", "Diff"));
		for (int i = 0; i < 10; i++) {
			log(String.format("%-5d %-12.4f %-12.4f %-12.4f",
				i, pytorchFinalNormOutput[i], arOutputArr[i], pytorchFinalNormOutput[i] - arOutputArr[i]));
		}

		// Test result interpretation
		log("\n--- Analysis ---");
		if (mae < 0.1) {
			log("SUCCESS: AR full model output closely matches PyTorch (MAE < 0.1)");
		} else if (mae < 1.0) {
			log("MODERATE: Some divergence exists (0.1 < MAE < 1.0)");
		} else {
			log("FAILURE: Significant divergence (MAE >= 1.0)");
		}

		stateDict.destroy();
		log("\n" + "=".repeat(80));
	}

	@Test
	public void testLayer23WithoutFinalNorm() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer23_without_final_norm.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(80));
		log("  LAYER 23 TEST (Comparing to correct PyTorch output)");
		log("=".repeat(80) + "\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load layer 23 input (PyTorch layer 22 output)
		float[] pytorchInput = loadReferenceOutput("after_layer_22.bin");
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchInput[i]);
		}

		log("Input (PyTorch layer 22 output):");
		logStats("  ", input);

		// Load correct expected output (layer 23 output BEFORE final norm)
		float[] pytorchLayer23Output = loadReferenceOutput("after_layer_23.bin");
		log("\nExpected (PyTorch layer 23 output, BEFORE final norm):");
		logFloatStats("  ", pytorchLayer23Output);

		// Build just layer 23
		Model layer23Model = buildSingleLayer(config, stateDict, 23);
		PackedCollection arOutput = layer23Model.compile().forward(input);

		log("\nAR layer 23 output:");
		logStats("  ", arOutput);

		// Compare
		float[] arOutputArr = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			arOutputArr[i] = (float) arOutput.toDouble(i);
		}
		double mae = computeMAE(pytorchLayer23Output, arOutputArr);
		double rmse = computeRMSE(pytorchLayer23Output, arOutputArr);

		log("\n--- Comparison ---");
		log(String.format("MAE: %.6f", mae));
		log(String.format("RMSE: %.6f", rmse));

		// Print first 10 values
		log("\n--- First 10 Values ---");
		log(String.format("%-5s %-12s %-12s %-12s", "Idx", "PyTorch", "AR", "Diff"));
		for (int i = 0; i < 10; i++) {
			log(String.format("%-5d %-12.4f %-12.4f %-12.4f",
				i, pytorchLayer23Output[i], arOutputArr[i], pytorchLayer23Output[i] - arOutputArr[i]));
		}

		stateDict.destroy();
		log("\n" + "=".repeat(80));
	}

	private Model buildFullModel(Qwen3Config config, StateDictionary stateDict) {
		Model model = new Model(shape(1, config.dim));

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// All 24 layers
		for (int i = 0; i < 24; i++) {
			String prefix = String.format("model.layers.%d", i);

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
		}

		// Final RMSNorm
		PackedCollection rmsFinalWeight = stateDict.get("model.norm.weight");
		model.add(rmsnorm(shape(1, config.dim), rmsFinalWeight, 1e-6));

		return model;
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

	private void logStats(String prefix, PackedCollection c) {
		int size = c.getShape().getTotalSize();
		double sum = 0, sumSq = 0;
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (int i = 0; i < size; i++) {
			double v = c.toDouble(i);
			sum += v;
			sumSq += v * v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double mean = sum / size;
		double variance = (sumSq / size) - (mean * mean);
		double std = Math.sqrt(Math.max(0, variance));
		log(String.format("%s mean=%.6f, std=%.6f, range=[%.4f, %.4f]", prefix, mean, std, min, max));
	}

	private void logFloatStats(String prefix, float[] arr) {
		double sum = 0, sumSq = 0;
		double min = arr[0], max = arr[0];
		for (float v : arr) {
			sum += v;
			sumSq += v * v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double mean = sum / arr.length;
		double variance = (sumSq / arr.length) - (mean * mean);
		double std = Math.sqrt(Math.max(0, variance));
		log(String.format("%s mean=%.6f, std=%.6f, range=[%.4f, %.4f]", prefix, mean, std, min, max));
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
}
