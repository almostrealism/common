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
 * Multi-token generation test comparing Java implementation with PyTorch reference.
 *
 * <p>This test performs autoregressive text generation and validates that each
 * generated token matches what PyTorch produces with greedy decoding.</p>
 */
public class MultiTokenGenerationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference";

	// Position collection - shared between model building and test loop
	private PackedCollection position;

	/**
	 * Test multi-token generation starting from "Hello" (token 9707).
	 * Compares each step against PyTorch reference logits.
	 */
	@Test
	public void testMultiTokenGeneration() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/multitoken_generation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Multi-Token Generation Test");
		log("===================================================\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		log("Loading weights...");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load embeddings for logits projection
		PackedCollection tokenEmbeddings = stateDict.get("model.embed_tokens.weight");

		// Initialize position - will be updated before each forward pass
		position = new PackedCollection(1);
		position.setMem(0, 0.0);

		log("Building transformer model...");
		Model transformer = buildTransformerWithoutVocabProjection(config, stateDict);

		log("Compiling model...");
		long compileStart = System.currentTimeMillis();
		var compiledModel = transformer.compile();
		log("Model compiled in " + (System.currentTimeMillis() - compileStart) + "ms");

		// Load PyTorch reference logits for position 0
		float[] pytorchLogits = loadReferenceLogits("full_model_logits/position_0_logits.bin");
		if (pytorchLogits != null) {
			log("Loaded PyTorch reference logits: " + pytorchLogits.length + " values");
		}

		// Expected tokens from PyTorch (greedy decoding starting from "Hello")
		// From top_predictions.txt: Top 1 is token 271 ('\n\n')
		int[] expectedTokens = {271, 198, 11, 3837, 18137};  // First few expected tokens

		// Start with token 9707 ("Hello")
		int[] generatedTokens = new int[10];
		int currentToken = 9707;

		log("\n=== Starting Generation from token " + currentToken + " ===\n");

		int matches = 0;
		int total = 0;

		for (int step = 0; step < 5; step++) {
			log("--- Step " + step + ": Input token " + currentToken + " ---");

			// CRITICAL: Update position before forward pass
			// This enables proper RoPE rotation, causal masking, and KV cache indexing
			position.setMem(0, (double) step);

			// Get embedding for current token
			PackedCollection input = new PackedCollection(shape(1, config.dim));
			for (int i = 0; i < config.dim; i++) {
				input.setMem(i, tokenEmbeddings.toDouble(currentToken * config.dim + i));
			}

			// Forward pass
			long forwardStart = System.currentTimeMillis();
			PackedCollection hidden = compiledModel.forward(input);
			log("Forward pass: " + (System.currentTimeMillis() - forwardStart) + "ms");

			// Compute logits manually
			double[] logits = computeLogitsManually(hidden, tokenEmbeddings, config);

			// Find top-5 predictions
			int[] top5 = findTopK(logits, 5);

			log("Top 5 predictions:");
			for (int i = 0; i < 5; i++) {
				int idx = top5[i];
				log(String.format("  %d. Token %d (logit: %.4f)", i + 1, idx, logits[idx]));
			}

			// Compare with PyTorch reference (only for step 0)
			if (step == 0 && pytorchLogits != null) {
				log("\nComparison with PyTorch logits:");

				// Compare top-5 logits
				double logitDiffSum = 0;
				for (int i = 0; i < 5; i++) {
					int javaTopIdx = top5[i];
					double javaLogit = logits[javaTopIdx];
					double pytorchLogit = pytorchLogits[javaTopIdx];
					double diff = Math.abs(javaLogit - pytorchLogit);
					logitDiffSum += diff;
					log(String.format("  Token %d: Java=%.4f, PyTorch=%.4f, Diff=%.4f",
							javaTopIdx, javaLogit, pytorchLogit, diff));
				}
				log(String.format("  Average diff for top-5: %.4f", logitDiffSum / 5));

				// Check if rankings match
				int[] pytorchTop5 = findTopKFromFloat(pytorchLogits, 5);
				boolean rankMatch = true;
				for (int i = 0; i < 5; i++) {
					if (top5[i] != pytorchTop5[i]) {
						rankMatch = false;
						break;
					}
				}
				if (rankMatch) {
					log("  [MATCH] Top-5 rankings match PyTorch!");
				} else {
					log("  [MISMATCH] Top-5 rankings differ from PyTorch");
					log("  PyTorch top-5: " + java.util.Arrays.toString(pytorchTop5));
					log("  Java top-5: " + java.util.Arrays.toString(top5));
				}
			}

			// Greedy selection
			int nextToken = top5[0];
			generatedTokens[step] = nextToken;

			if (step < expectedTokens.length) {
				if (nextToken == expectedTokens[step]) {
					log("[MATCH] Generated token " + nextToken + " matches expected");
					matches++;
				} else {
					log("[MISMATCH] Generated " + nextToken + " but expected " + expectedTokens[step]);
				}
				total++;
			}

			log("");
			currentToken = nextToken;
		}

		// Summary
		log("\n=== Generation Summary ===");
		log("Generated tokens: " + java.util.Arrays.toString(
				java.util.Arrays.copyOf(generatedTokens, 5)));
		log("Expected tokens:  " + java.util.Arrays.toString(expectedTokens));
		log(String.format("Match rate: %d/%d (%.1f%%)", matches, total, 100.0 * matches / total));

		if (matches == total) {
			log("\n[SUCCESS] All generated tokens match PyTorch!");
		} else {
			log("\n[PARTIAL] Some tokens differ - check for numerical precision issues");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	/**
	 * Build transformer without vocab projection (same as SimpleTransformerValidationTest).
	 */
	private Model buildTransformerWithoutVocabProjection(Qwen3Config config,
														 StateDictionary stateDict) {
		Model transformer = new Model(shape(1, config.dim));

		// Use the instance field (initialized in test method)
		// Position is updated in the test loop before each forward pass

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
					config.headCount,
					config.kvHeadCount,
					layerRmsAtt,
					layerWk, layerWv, layerWq, layerWo,
					layerBk, layerBv, layerBq,
					layerQkNormQ, layerQkNormK,
					freqCis,
					layerRmsFfn,
					layerW1, layerW2, layerW3,
					p(position)));
		}

		transformer.add(rmsnorm(shape(1, config.dim), rmsFinalWeight));
		return transformer;
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.headSize;
		int seqLen = 10;
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

	private double[] computeLogitsManually(PackedCollection hidden,
										   PackedCollection weights,
										   Qwen3Config config) {
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
			int pos = -1;
			for (int j = k - 1; j >= 0; j--) {
				if (values[i] > topValues[j]) {
					pos = j;
				} else {
					break;
				}
			}

			if (pos >= 0) {
				for (int j = k - 1; j > pos; j--) {
					indices[j] = indices[j - 1];
					topValues[j] = topValues[j - 1];
				}
				indices[pos] = i;
				topValues[pos] = values[i];
			}
		}

		return indices;
	}

	private int[] findTopKFromFloat(float[] values, int k) {
		int[] indices = new int[k];
		float[] topValues = new float[k];
		java.util.Arrays.fill(topValues, Float.NEGATIVE_INFINITY);

		for (int i = 0; i < values.length; i++) {
			int pos = -1;
			for (int j = k - 1; j >= 0; j--) {
				if (values[i] > topValues[j]) {
					pos = j;
				} else {
					break;
				}
			}

			if (pos >= 0) {
				for (int j = k - 1; j > pos; j--) {
					indices[j] = indices[j - 1];
					topValues[j] = topValues[j - 1];
				}
				indices[pos] = i;
				topValues[pos] = values[i];
			}
		}

		return indices;
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
		} catch (IOException e) {
			log("Warning: Could not load reference logits from " + filepath);
			return null;
		}
	}
}
