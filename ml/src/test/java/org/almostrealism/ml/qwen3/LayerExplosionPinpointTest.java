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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Pinpoint exactly which layer(s) cause the error explosion observed between
 * layer 18 (MAE=0.045) and layer 24 (MAE=3.4).
 */
public class LayerExplosionPinpointTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void pinpointExplosionLayers() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer_explosion_pinpoint.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer Explosion Pinpoint Test ===\n");
		log("Testing layers 17-24 to find where error explodes\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Get embeddings for token 9707 ("Hello")
		int tokenId = 9707;
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection input = embeddings.range(
			shape(config.dim),
			tokenId * config.dim
		);

		log(String.format("%-8s %-15s %-15s %-15s %-15s", "Layers", "MAE", "RMSE", "Max Error", "Status"));
		log("-".repeat(75));

		// Test layers 17 through 24
		double prevMae = 0;
		for (int numLayers = 17; numLayers <= 24; numLayers++) {
			Model model = buildPartialModel(config, stateDict, numLayers);
			PackedCollection output = model.compile().forward(input);

			// Load corresponding PyTorch reference
			String refFile = "after_layer_" + (numLayers - 1) + ".bin";
			float[] pytorchOutput = loadReferenceOutput(refFile);

			float[] arOutput = new float[config.dim];
			for (int i = 0; i < config.dim; i++) {
				arOutput[i] = (float) output.toDouble(i);
			}

			double[] stats = computeStats(pytorchOutput, arOutput);
			double mae = stats[0];
			double rmse = stats[1];
			double maxErr = stats[2];

			String status = "";
			if (prevMae > 0 && mae / prevMae > 2) {
				status = "*** EXPLOSION ***";
			} else if (mae < 0.01) {
				status = "OK";
			} else if (mae < 0.1) {
				status = "WARNING";
			} else {
				status = "CRITICAL";
			}

			log(String.format("%-8d %-15.6f %-15.6f %-15.6f %s",
				numLayers, mae, rmse, maxErr, status));

			prevMae = mae;
		}

		// Also log some diagnostic info about layer 23's output
		log("\n=== Layer 23 Output Analysis ===");
		Model model23 = buildPartialModel(config, stateDict, 23);
		PackedCollection output23 = model23.compile().forward(input);

		float[] pytorch23 = loadReferenceOutput("after_layer_22.bin");

		log("First 10 values comparison at layer 23:");
		log(String.format("%-5s %-15s %-15s %-15s", "Idx", "PyTorch", "AR", "Diff"));
		log("-".repeat(55));
		for (int i = 0; i < 10; i++) {
			float ar = (float) output23.toDouble(i);
			log(String.format("%-5d %-15.6f %-15.6f %-15.6f", i, pytorch23[i], ar, pytorch23[i] - ar));
		}

		// Check range of values
		double minAR = Double.MAX_VALUE, maxAR = Double.MIN_VALUE;
		double minPT = Double.MAX_VALUE, maxPT = Double.MIN_VALUE;
		for (int i = 0; i < config.dim; i++) {
			double ar = output23.toDouble(i);
			minAR = Math.min(minAR, ar);
			maxAR = Math.max(maxAR, ar);
			minPT = Math.min(minPT, pytorch23[i]);
			maxPT = Math.max(maxPT, pytorch23[i]);
		}

		log("\nValue ranges:");
		log(String.format("  PyTorch: [%.4f, %.4f]", minPT, maxPT));
		log(String.format("  AR:      [%.4f, %.4f]", minAR, maxAR));

		// Check for NaN/Inf
		boolean hasNaN = false, hasInf = false;
		for (int i = 0; i < config.dim; i++) {
			double v = output23.toDouble(i);
			if (Double.isNaN(v)) hasNaN = true;
			if (Double.isInfinite(v)) hasInf = true;
		}
		log(String.format("  Has NaN: %s, Has Inf: %s", hasNaN, hasInf));

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private Model buildPartialModel(Qwen3Config config, StateDictionary stateDict, int numLayers) {
		Model model = new Model(shape(config.dim));

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		for (int i = 0; i < numLayers; i++) {
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

	private double[] computeStats(float[] expected, float[] actual) {
		double sumAbsDiff = 0;
		double sumSqDiff = 0;
		double maxAbsDiff = 0;

		for (int i = 0; i < expected.length; i++) {
			double diff = Math.abs(expected[i] - actual[i]);
			sumAbsDiff += diff;
			sumSqDiff += diff * diff;
			maxAbsDiff = Math.max(maxAbsDiff, diff);
		}

		double mae = sumAbsDiff / expected.length;
		double rmse = Math.sqrt(sumSqDiff / expected.length);
		return new double[]{mae, rmse, maxAbsDiff};
	}
}
