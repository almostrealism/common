package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
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
 * Verifies the hypothesis that the layer 23 reference includes final norm.
 *
 * <p>This test:
 * 1. Runs AR layer 23 with correct input (layer 22 PyTorch output)
 * 2. Applies final RMSNorm to AR output
 * 3. Compares with reference `after_layer_23.bin`
 * 4. If error drops significantly, hypothesis is confirmed</p>
 */
public class FinalNormCorrectionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";
	private static final int DIM = 896;

	/**
	 * Test applying final norm to AR layer 23 output.
	 */
	@Test
	public void testFinalNormCorrection() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/final_norm_correction.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Final Norm Correction Test");
		log("===================================================\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Load reference
		float[] layer22Output = loadReferenceOutput("after_layer_22.bin");
		float[] layer23Ref = loadReferenceOutput("after_layer_23.bin");
		PackedCollection finalNormWeight = stateDict.get("model.norm.weight");

		if (layer22Output == null || layer23Ref == null || finalNormWeight == null) {
			log("ERROR: Cannot load required data");
			stateDict.destroy();
			return;
		}

		log("Input (layer 22 output): " + statsFromFloat(layer22Output));
		log("Reference (layer 23): " + statsFromFloat(layer23Ref));

		// Create input
		PackedCollection arInput = new PackedCollection(shape(config.dim));
		for (int i = 0; i < config.dim; i++) {
			arInput.setMem(i, layer22Output[i]);
		}

		// Run AR layer 23
		log("\n--- Step 1: Run AR Layer 23 ---");
		SequentialBlock layer23Block = buildTransformerLayer(config, stateDict, 23, freqCis, position);
		Model layer23Model = new Model(shape(config.dim));
		layer23Model.add(layer23Block);
		PackedCollection arLayer23Output = layer23Model.compile().forward(arInput);

		log("AR layer 23 output (pre-norm): " + stats(arLayer23Output, config.dim));

		// Compare WITHOUT final norm (should show ~3.3 mean error as before)
		double errorWithoutNorm = meanAbsError(arLayer23Output, layer23Ref, config.dim);
		log(String.format("\nError WITHOUT final norm: %.6f", errorWithoutNorm));

		// Apply final norm to AR output
		log("\n--- Step 2: Apply Final Norm to AR Output ---");
		double[] arNormed = applyRMSNorm(arLayer23Output, finalNormWeight, config.dim);
		log("AR layer 23 output (post-norm): " + statsFromDouble(arNormed));

		// Compare WITH final norm (should show much lower error)
		double errorWithNorm = meanAbsErrorFloat(arNormed, layer23Ref, config.dim);
		log(String.format("\nError WITH final norm: %.6f", errorWithNorm));

		// Calculate improvement
		double improvement = (errorWithoutNorm - errorWithNorm) / errorWithoutNorm * 100;
		log(String.format("Improvement: %.1f%%", improvement));

		log("\n===================================================");
		log("  Conclusions");
		log("===================================================\n");

		if (errorWithNorm < errorWithoutNorm * 0.5) {
			log("HYPOTHESIS CONFIRMED!");
			log("Applying final norm significantly reduces error.");
			log("The reference `after_layer_23.bin` contains final_norm(layer_23_output).");
			log("\nThis means:");
			log("  1. Our AR layer 23 implementation is CORRECT");
			log("  2. We need to add final RMSNorm to the model");
			log("  3. The model should work correctly for inference");
		} else {
			log("HYPOTHESIS NOT FULLY CONFIRMED");
			log("Applying final norm does not sufficiently reduce error.");
			log("There may be additional issues to investigate.");
		}

		// Show top errors after correction
		if (errorWithNorm < errorWithoutNorm * 0.5) {
			log("\n--- Top 5 Remaining Errors After Final Norm ---");
			showTopErrors(arNormed, layer23Ref, config.dim, 5);
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private SequentialBlock buildTransformerLayer(Qwen3Config config, StateDictionary stateDict,
												  int layerIdx, PackedCollection freqCis,
												  PackedCollection position) {
		SequentialBlock model = new SequentialBlock(shape(1, config.dim));
		String prefix = String.format("model.layers.%d", layerIdx);

		PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
		PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
		PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
		PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
		PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
		PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
		PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
		PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");
		PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
		PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");
		PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
		PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
		PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
		PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

		model.accum(attention(
				config.headCount, config.kvHeadCount,
				layerRmsAtt,
				layerWk, layerWv, layerWq, layerWo,
				layerBk, layerBv, layerBq,
				layerQkNormQ, layerQkNormK,
				freqCis,
				p(position)));

		model.accum(feedForward(layerRmsFfn, layerW1, layerW2, layerW3));

		return model;
	}

	private double[] applyRMSNorm(PackedCollection input, PackedCollection weights, int dim) {
		double sumSq = 0;
		for (int i = 0; i < dim; i++) {
			double v = input.toDouble(i);
			sumSq += v * v;
		}
		double rms = Math.sqrt(sumSq / dim + 1e-6);

		double[] output = new double[dim];
		for (int i = 0; i < dim; i++) {
			output[i] = (input.toDouble(i) / rms) * weights.toDouble(i);
		}
		return output;
	}

	private double meanAbsError(PackedCollection a, float[] b, int dim) {
		double sum = 0;
		for (int i = 0; i < dim; i++) {
			sum += Math.abs(a.toDouble(i) - b[i]);
		}
		return sum / dim;
	}

	private double meanAbsErrorFloat(double[] a, float[] b, int dim) {
		double sum = 0;
		for (int i = 0; i < dim; i++) {
			sum += Math.abs(a[i] - b[i]);
		}
		return sum / dim;
	}

	private void showTopErrors(double[] output, float[] expected, int dim, int count) {
		boolean[] shown = new boolean[dim];
		for (int n = 0; n < count; n++) {
			double maxE = 0;
			int maxI = 0;
			for (int i = 0; i < dim; i++) {
				if (shown[i]) continue;
				double e = Math.abs(output[i] - expected[i]);
				if (e > maxE) {
					maxE = e;
					maxI = i;
				}
			}
			shown[maxI] = true;
			log(String.format("  idx=%d: AR=%.4f, Expected=%.4f, Error=%.4f",
					maxI, output[maxI], expected[maxI], maxE));
		}
	}

	private String stats(PackedCollection c, int dim) {
		double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (int i = 0; i < dim; i++) {
			double v = c.toDouble(i);
			sum += v;
			sumSq += v * v;
			if (v < min) min = v;
			if (v > max) max = v;
		}
		double mean = sum / dim;
		double std = Math.sqrt(sumSq / dim - mean * mean);
		return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
	}

	private String statsFromFloat(float[] arr) {
		double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (float v : arr) {
			sum += v;
			sumSq += v * v;
			if (v < min) min = v;
			if (v > max) max = v;
		}
		double mean = sum / arr.length;
		double std = Math.sqrt(sumSq / arr.length - mean * mean);
		return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
	}

	private String statsFromDouble(double[] arr) {
		double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (double v : arr) {
			sum += v;
			sumSq += v * v;
			if (v < min) min = v;
			if (v > max) max = v;
		}
		double mean = sum / arr.length;
		double std = Math.sqrt(sumSq / arr.length - mean * mean);
		return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
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

	private float[] loadReferenceOutput(String filename) {
		String filepath = REFERENCE_DIR + "/" + filename;
		try (FileChannel channel = FileChannel.open(Paths.get(filepath), StandardOpenOption.READ)) {
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
		} catch (IOException e) {
			return null;
		}
	}
}
