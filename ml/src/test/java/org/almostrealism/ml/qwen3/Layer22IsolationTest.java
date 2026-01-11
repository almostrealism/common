package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Isolate layer 22 (0-indexed) to determine if the error explosion
 * is in this specific layer or accumulated from earlier layers.
 *
 * Test: Feed PyTorch's layer 21 output INTO AR's layer 22,
 * then compare with PyTorch's layer 22 output.
 */
public class Layer22IsolationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer22InIsolation() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer22_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 22 Isolation Test ===\n");
		log("Goal: Determine if layer 22 itself has a bug, or if error is accumulated");
		log("");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PyTorch's output after layer 21 (input to layer 22)
		float[] pytorchLayer21 = loadReferenceOutput("after_layer_21.bin");
		// Load PyTorch's output after layer 22 (expected output)
		float[] pytorchLayer22 = loadReferenceOutput("after_layer_22.bin");

		log(String.format("PyTorch layer 21 output stats: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			computeMean(pytorchLayer21), computeStd(pytorchLayer21),
			computeMin(pytorchLayer21), computeMax(pytorchLayer21)));
		log(String.format("PyTorch layer 22 output stats: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			computeMean(pytorchLayer22), computeStd(pytorchLayer22),
			computeMin(pytorchLayer22), computeMax(pytorchLayer22)));

		// Create input from PyTorch's layer 21 output
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchLayer21[i]);
		}

		// Build JUST layer 22
		Model model = buildSingleLayer(config, stateDict, 22);
		CompiledModel compiled = model.compile();

		// Run layer 22
		PackedCollection output = compiled.forward(input);

		// Compare with PyTorch layer 22 output
		float[] arOutput = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			arOutput[i] = (float) output.toDouble(i);
		}

		double mae = computeMAE(pytorchLayer22, arOutput);
		double rmse = computeRMSE(pytorchLayer22, arOutput);
		double maxErr = computeMaxError(pytorchLayer22, arOutput);

		log("\n=== Layer 22 Isolation Results ===");
		log(String.format("MAE:       %.6f", mae));
		log(String.format("RMSE:      %.6f", rmse));
		log(String.format("Max Error: %.6f", maxErr));

		log(String.format("\nAR output stats: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			computeMean(arOutput), computeStd(arOutput),
			computeMin(arOutput), computeMax(arOutput)));

		// Print comparison of first 20 values
		log("\n=== First 20 Values Comparison ===");
		log(String.format("%-5s %-15s %-15s %-15s", "Idx", "PyTorch", "AR", "Diff"));
		log("-".repeat(55));
		for (int i = 0; i < 20; i++) {
			log(String.format("%-5d %-15.6f %-15.6f %-15.6f",
				i, pytorchLayer22[i], arOutput[i], pytorchLayer22[i] - arOutput[i]));
		}

		// Find top 10 largest errors
		log("\n=== Top 10 Largest Errors ===");
		int[] sortedIndices = sortByError(pytorchLayer22, arOutput);
		log(String.format("%-5s %-15s %-15s %-15s", "Idx", "PyTorch", "AR", "Diff"));
		log("-".repeat(55));
		for (int i = 0; i < 10; i++) {
			int idx = sortedIndices[i];
			log(String.format("%-5d %-15.6f %-15.6f %-15.6f",
				idx, pytorchLayer22[idx], arOutput[idx], pytorchLayer22[idx] - arOutput[idx]));
		}

		// Interpretation
		log("\n=== Interpretation ===");
		if (mae < 0.01) {
			log("GOOD: Layer 22 in isolation matches PyTorch well.");
			log("The error explosion is due to ACCUMULATED error from earlier layers.");
		} else if (mae < 0.1) {
			log("WARNING: Layer 22 has moderate error. May contribute to explosion.");
		} else {
			log("CRITICAL: Layer 22 itself has significant error.");
			log("This layer is likely the source of the explosion.");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	@Test
	public void testMultipleLayersInIsolation() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layers_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Multiple Layers Isolation Test ===\n");
		log("Testing each layer 20-23 in ISOLATION (feeding PyTorch's previous layer output)");
		log("");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		log(String.format("%-8s %-15s %-15s %-15s %-15s", "Layer", "MAE", "RMSE", "Max Error", "Status"));
		log("-".repeat(70));

		for (int layerIdx = 20; layerIdx <= 23; layerIdx++) {
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
			else status = "*** ERROR ***";

			log(String.format("%-8d %-15.6f %-15.6f %-15.6f %s",
				layerIdx, mae, rmse, maxErr, status));
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
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

	private double computeMean(float[] arr) {
		double sum = 0;
		for (float v : arr) sum += v;
		return sum / arr.length;
	}

	private double computeStd(float[] arr) {
		double mean = computeMean(arr);
		double sum = 0;
		for (float v : arr) sum += (v - mean) * (v - mean);
		return Math.sqrt(sum / arr.length);
	}

	private double computeMin(float[] arr) {
		double min = arr[0];
		for (float v : arr) if (v < min) min = v;
		return min;
	}

	private double computeMax(float[] arr) {
		double max = arr[0];
		for (float v : arr) if (v > max) max = v;
		return max;
	}

	private int[] sortByError(float[] expected, float[] actual) {
		int[] indices = new int[expected.length];
		double[] errors = new double[expected.length];
		for (int i = 0; i < expected.length; i++) {
			indices[i] = i;
			errors[i] = Math.abs(expected[i] - actual[i]);
		}
		// Simple bubble sort for top errors (we only need top 10)
		for (int i = 0; i < Math.min(10, expected.length); i++) {
			for (int j = i + 1; j < expected.length; j++) {
				if (errors[j] > errors[i]) {
					double tmpE = errors[i]; errors[i] = errors[j]; errors[j] = tmpE;
					int tmpI = indices[i]; indices[i] = indices[j]; indices[j] = tmpI;
				}
			}
		}
		return indices;
	}
}
