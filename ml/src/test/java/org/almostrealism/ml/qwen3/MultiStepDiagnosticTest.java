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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Systematic multi-step diagnostic test.
 *
 * This test runs multiple forward passes and logs EVERYTHING:
 * - Input token and embedding (first 8 values)
 * - Position value
 * - Hidden state after forward (first 8 values)
 * - Top 5 logits
 *
 * Purpose: Identify WHERE things go wrong in multi-token generation.
 */
public class MultiStepDiagnosticTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference";

	private PackedCollection position;

	@Test
	public void testMultiStepGeneration() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/multistep_diagnostic.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  MULTI-STEP DIAGNOSTIC TEST");
		log("=".repeat(70) + "\n");

		// Load config matching Qwen2.5-0.5B
		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		log("Loading weights...");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		PackedCollection tokenEmbeddings = stateDict.get("model.embed_tokens.weight");
		log("Token embeddings shape: " + tokenEmbeddings.getShape());

		// Initialize position
		position = new PackedCollection(1);
		position.setMem(0, 0.0);

		log("Building model with " + config.layerCount + " layers...");
		Model transformer = buildModel(config, stateDict);

		log("Compiling model...");
		long compileStart = System.currentTimeMillis();
		CompiledModel compiled = transformer.compile();
		log("Compiled in " + (System.currentTimeMillis() - compileStart) + "ms\n");

		// Test sequence: "Hello" -> expected "\n\n" -> expected "\n" -> ...
		// Token 9707 = "Hello"
		// Token 271 = "\n\n" (expected after Hello at position 0)
		// Token 198 = "\n" (expected after \n\n at position 1)

		int[] testTokens = {9707, 271, 198};  // Hello, \n\n, \n
		String[] tokenNames = {"Hello", "\\n\\n", "\\n"};

		log("=".repeat(70));
		log("RUNNING 3 FORWARD PASSES");
		log("=".repeat(70) + "\n");

		double[][] allLogits = new double[3][];

		for (int step = 0; step < 3; step++) {
			log("-".repeat(50));
			log(String.format("STEP %d: Token %d (\"%s\")", step, testTokens[step], tokenNames[step]));
			log("-".repeat(50));

			// Update position BEFORE forward
			position.setMem(0, (double) step);
			log(String.format("Position set to: %.1f", position.toDouble(0)));

			// Get input embedding
			int tokenId = testTokens[step];
			PackedCollection input = new PackedCollection(shape(1, config.dim));
			for (int i = 0; i < config.dim; i++) {
				input.setMem(i, tokenEmbeddings.toDouble(tokenId * config.dim + i));
			}
			log("Input embedding (first 8): " + formatFirst8(input));

			// Forward pass
			PackedCollection hidden = compiled.forward(input);
			log("Hidden state (first 8): " + formatFirst8(hidden));

			// Compute logits
			double[] logits = computeLogits(hidden, tokenEmbeddings, config);
			allLogits[step] = logits;

			// Find top 5
			int[] top5 = findTopK(logits, 5);
			log("\nTop 5 predictions:");
			for (int i = 0; i < 5; i++) {
				log(String.format("  %d. Token %d (logit: %.4f)", i + 1, top5[i], logits[top5[i]]));
			}

			// Compare with PyTorch reference for step 0
			if (step == 0) {
				log("\nComparing with PyTorch reference:");
				float[] pytorchLogits = loadReferenceLogits("full_model_logits/position_0_logits.bin");
				if (pytorchLogits != null) {
					int[] pytorchTop5 = findTopKFloat(pytorchLogits, 5);
					log("  PyTorch top 5: " + formatIntArray(pytorchTop5));
					log("  Java top 5:    " + formatIntArray(top5));

					// Check if top-1 matches
					if (top5[0] == pytorchTop5[0]) {
						log("  [MATCH] Top prediction matches!");
					} else {
						log("  [MISMATCH] Top prediction differs!");
					}

					// Compare logit values for top 5
					log("\nLogit comparison for top 5:");
					for (int i = 0; i < 5; i++) {
						int idx = top5[i];
						double javaVal = logits[idx];
						float pytorchVal = pytorchLogits[idx];
						double diff = Math.abs(javaVal - pytorchVal);
						log(String.format("  Token %d: Java=%.4f, PyTorch=%.4f, Diff=%.4f %s",
								idx, javaVal, pytorchVal, diff, diff > 0.1 ? "[!]" : ""));
					}
				}
			}

			log("");
		}

		// Check if outputs for steps 1 and 2 are different from step 0
		log("=".repeat(70));
		log("ANALYSIS: Checking if outputs differ between steps");
		log("=".repeat(70));

		double diff01 = computeMaxDiff(allLogits[0], allLogits[1]);
		double diff12 = computeMaxDiff(allLogits[1], allLogits[2]);

		log(String.format("\nMax logit diff between step 0 and 1: %.6f", diff01));
		log(String.format("Max logit diff between step 1 and 2: %.6f", diff12));

		if (diff01 < 0.001) {
			log("\n[CRITICAL] Steps 0 and 1 have nearly IDENTICAL logits!");
			log("This indicates position/cache is NOT affecting computation.");
		} else {
			log("\n[GOOD] Logits differ between steps - position is affecting computation.");
		}

		// Check top predictions
		log("\n" + "=".repeat(70));
		log("TOP PREDICTIONS PER STEP:");
		log("=".repeat(70));
		for (int step = 0; step < 3; step++) {
			int[] top5 = findTopK(allLogits[step], 5);
			log(String.format("Step %d: Top token = %d (logit=%.4f)",
					step, top5[0], allLogits[step][top5[0]]));
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private Model buildModel(Qwen3Config config, StateDictionary stateDict) {
		Model transformer = new Model(shape(1, config.dim));

		PackedCollection rmsFinalWeight = stateDict.get("model.norm.weight");
		PackedCollection freqCis = computeRopeFreqs(config);

		for (int i = 0; i < config.layerCount; i++) {
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

			transformer.add(transformer(
					config.headCount, config.kvHeadCount,
					layerRmsAtt, layerWk, layerWv, layerWq, layerWo,
					layerBk, layerBv, layerBq, layerQkNormQ, layerQkNormK,
					freqCis, layerRmsFfn, layerW1, layerW2, layerW3,
					p(position), 1e-6));
		}

		transformer.add(rmsnorm(shape(1, config.dim), rmsFinalWeight, 1e-6));
		return transformer;
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.headSize;
		int seqLen = 32;  // Small for testing
		double theta = config.ropeTheta;

		int freqDim = headSize / 2;
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));

		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
				double angle = pos * freq;
				freqCis.setMem((pos * freqDim + i) * 2, Math.cos(angle));
				freqCis.setMem((pos * freqDim + i) * 2 + 1, Math.sin(angle));
			}
		}

		return freqCis;
	}

	private double[] computeLogits(PackedCollection hidden, PackedCollection weights, Qwen3Config config) {
		double[] logits = new double[config.vocabSize];
		for (int v = 0; v < config.vocabSize; v++) {
			double sum = 0;
			for (int d = 0; d < config.dim; d++) {
				sum += hidden.toDouble(d) * weights.toDouble(v * config.dim + d);
			}
			logits[v] = sum;
		}
		return logits;
	}

	private int[] findTopK(double[] values, int k) {
		int[] indices = new int[k];
		double[] topValues = new double[k];
		java.util.Arrays.fill(topValues, Double.NEGATIVE_INFINITY);

		for (int i = 0; i < values.length; i++) {
			for (int j = k - 1; j >= 0; j--) {
				if (values[i] > topValues[j]) {
					if (j < k - 1) {
						indices[j + 1] = indices[j];
						topValues[j + 1] = topValues[j];
					}
					indices[j] = i;
					topValues[j] = values[i];
				} else {
					break;
				}
			}
		}
		return indices;
	}

	private int[] findTopKFloat(float[] values, int k) {
		int[] indices = new int[k];
		float[] topValues = new float[k];
		java.util.Arrays.fill(topValues, Float.NEGATIVE_INFINITY);

		for (int i = 0; i < values.length; i++) {
			for (int j = k - 1; j >= 0; j--) {
				if (values[i] > topValues[j]) {
					if (j < k - 1) {
						indices[j + 1] = indices[j];
						topValues[j + 1] = topValues[j];
					}
					indices[j] = i;
					topValues[j] = values[i];
				} else {
					break;
				}
			}
		}
		return indices;
	}

	private double computeMaxDiff(double[] a, double[] b) {
		double maxDiff = 0;
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			maxDiff = Math.max(maxDiff, Math.abs(a[i] - b[i]));
		}
		return maxDiff;
	}

	private String formatFirst8(PackedCollection c) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < 8 && i < c.getShape().getTotalSize(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatIntArray(int[] arr) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < arr.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(arr[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	private float[] loadReferenceLogits(String filename) {
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
		} catch (Exception e) {
			log("Warning: Could not load reference: " + filepath);
			return null;
		}
	}
}
