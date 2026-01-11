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
import org.almostrealism.model.SequentialBlock;
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
 * Deep debugging of layer 23 to find the exact bug.
 */
public class Layer23DebugTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void compareLayer23Weights() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer23_weights_debug.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 23 Weight Analysis ===\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Compare layer 22 and layer 23 weights
		for (int layer : new int[]{22, 23}) {
			log(String.format("\n=== Layer %d Weights ===", layer));
			String prefix = String.format("model.layers.%d", layer);

			String[] weightNames = {
				"input_layernorm.weight",
				"post_attention_layernorm.weight",
				"self_attn.q_proj.weight",
				"self_attn.k_proj.weight",
				"self_attn.v_proj.weight",
				"self_attn.o_proj.weight",
				"self_attn.q_proj.bias",
				"self_attn.k_proj.bias",
				"self_attn.v_proj.bias",
				"mlp.gate_proj.weight",
				"mlp.up_proj.weight",
				"mlp.down_proj.weight"
			};

			for (String weightName : weightNames) {
				PackedCollection w = stateDict.get(prefix + "." + weightName);
				if (w != null) {
					double[] stats = computeStats(w);
					log(String.format("  %s: shape=%s, mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
						weightName, w.getShape(), stats[0], stats[1], stats[2], stats[3]));

					// Check for anomalies
					if (Double.isNaN(stats[0]) || Double.isInfinite(stats[0])) {
						log("    *** WARNING: Contains NaN/Inf values! ***");
					}
					if (stats[3] > 100 || stats[2] < -100) {
						log("    *** WARNING: Extreme values detected! ***");
					}
				} else {
					log(String.format("  %s: NULL", weightName));
				}
			}
		}

		stateDict.destroy();
	}

	@Test
	public void testLayer23Components() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer23_components_debug.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 23 Component-by-Component Analysis ===\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PyTorch's input to layer 23
		float[] pytorchInput = loadReferenceOutput("after_layer_22.bin");
		float[] pytorchOutput = loadReferenceOutput("after_layer_23.bin");

		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchInput[i]);
		}

		log(String.format("Input stats: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			computeMean(pytorchInput), computeStd(pytorchInput),
			computeMin(pytorchInput), computeMax(pytorchInput)));
		log(String.format("Expected output stats: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			computeMean(pytorchOutput), computeStd(pytorchOutput),
			computeMin(pytorchOutput), computeMax(pytorchOutput)));

		// Test just the RMSNorm
		log("\n--- Testing RMSNorm (input_layernorm) ---");
		testRmsNormOnly(config, stateDict, input);

		// Test just the attention
		log("\n--- Testing Attention Block ---");
		testAttentionOnly(config, stateDict, input);

		// Test just the FFN
		log("\n--- Testing FFN Block ---");
		testFfnOnly(config, stateDict, input);

		// Now test full transformer layer
		log("\n--- Testing Full Transformer Layer ---");
		Model model = buildSingleLayer(config, stateDict, 23);
		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		float[] arOutput = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			arOutput[i] = (float) output.toDouble(i);
		}

		double mae = computeMAE(pytorchOutput, arOutput);
		log(String.format("Full layer 23 MAE: %.6f", mae));
		log(String.format("AR output stats: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			computeMean(arOutput), computeStd(arOutput),
			computeMin(arOutput), computeMax(arOutput)));

		// Compare first 20 values
		log("\nFirst 20 values comparison:");
		log(String.format("%-5s %-15s %-15s %-15s", "Idx", "PyTorch", "AR", "Diff"));
		for (int i = 0; i < 20; i++) {
			log(String.format("%-5d %-15.6f %-15.6f %-15.6f",
				i, pytorchOutput[i], arOutput[i], pytorchOutput[i] - arOutput[i]));
		}

		stateDict.destroy();
	}

	private void testRmsNormOnly(Qwen3Config config, StateDictionary stateDict, PackedCollection input) {
		String prefix = "model.layers.23";
		PackedCollection rmsWeight = stateDict.get(prefix + ".input_layernorm.weight");

		Model model = new Model(shape(1, config.dim));
		model.add(rmsnorm(shape(1, config.dim), rmsWeight, 1e-6));

		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		double[] stats = computeStats(output);
		log(String.format("  After RMSNorm: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			stats[0], stats[1], stats[2], stats[3]));

		// Check for NaN/Inf
		boolean hasNaN = false, hasInf = false;
		for (int i = 0; i < config.dim; i++) {
			double v = output.toDouble(i);
			if (Double.isNaN(v)) hasNaN = true;
			if (Double.isInfinite(v)) hasInf = true;
		}
		if (hasNaN || hasInf) {
			log("  *** WARNING: RMSNorm output contains NaN/Inf! ***");
		}
	}

	private void testAttentionOnly(Qwen3Config config, StateDictionary stateDict, PackedCollection input) {
		String prefix = "model.layers.23";

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

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

		Block attention = attention(
			config.headCount, config.kvHeadCount,
			rmsAttWeight, wk, wv, wq, wo,
			bk, bv, bq, qkNormQ, qkNormK,
			freqCis, p(position), 1e-6);

		Model model = new Model(shape(1, config.dim));
		model.add(attention);

		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		double[] stats = computeStats(output);
		log(String.format("  Attention output: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			stats[0], stats[1], stats[2], stats[3]));

		// Check for NaN/Inf
		boolean hasNaN = false, hasInf = false;
		for (int i = 0; i < config.dim; i++) {
			double v = output.toDouble(i);
			if (Double.isNaN(v)) hasNaN = true;
			if (Double.isInfinite(v)) hasInf = true;
		}
		if (hasNaN || hasInf) {
			log("  *** WARNING: Attention output contains NaN/Inf! ***");
		}
	}

	private void testFfnOnly(Qwen3Config config, StateDictionary stateDict, PackedCollection input) {
		String prefix = "model.layers.23";

		PackedCollection rmsFfnWeight = stateDict.get(prefix + ".post_attention_layernorm.weight");
		PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
		PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
		PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

		Model model = new Model(shape(1, config.dim));
		model.add(feedForward(rmsFfnWeight, w1, w2, w3, 1e-6));

		CompiledModel compiled = model.compile();
		PackedCollection output = compiled.forward(input);

		double[] stats = computeStats(output);
		log(String.format("  FFN output: mean=%.6f, std=%.6f, range=[%.4f, %.4f]",
			stats[0], stats[1], stats[2], stats[3]));

		// Check for NaN/Inf
		boolean hasNaN = false, hasInf = false;
		for (int i = 0; i < config.dim; i++) {
			double v = output.toDouble(i);
			if (Double.isNaN(v)) hasNaN = true;
			if (Double.isInfinite(v)) hasInf = true;
		}
		if (hasNaN || hasInf) {
			log("  *** WARNING: FFN output contains NaN/Inf! ***");
		}
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

	private double[] computeStats(PackedCollection c) {
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
		return new double[]{mean, std, min, max};
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
}
