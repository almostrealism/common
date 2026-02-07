package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Test that uses Qwen3.java directly (with DynamicCollectionProducer for position)
 * to verify generation matches PyTorch.
 *
 * <p>This test is designed to isolate whether generation issues are in:
 * <ul>
 *   <li>The Qwen3 model itself (which uses DynamicCollectionProducer)</li>
 *   <li>Or in tests that use p(position) instead</li>
 * </ul></p>
 */
public class Qwen3DirectGenerationTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference";

	/**
	 * Test generation using the compiled model directly (bypassing AutoregressiveModel).
	 * This isolates the model's forward pass to verify logits match PyTorch.
	 */
	@Test
	public void testDirectGeneration() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/results/qwen3_direct_generation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Qwen3 Direct Generation Test");
		log("  (Using Compiled Model directly for clarity)");
		log("===================================================\n");

		// Load PyTorch reference logits for position 0
		float[] pytorchLogits0 = loadReferenceLogits("autoregressive/position_0_logits.bin");
		if (pytorchLogits0 != null) {
			log("Loaded PyTorch reference logits for position 0: " + pytorchLogits0.length + " values");
		} else {
			log("WARNING: Could not load PyTorch reference logits");
		}

		// Expected tokens from PyTorch autoregressive generation starting from "Hello"
		// Position 0: input 9707 ("Hello") -> output 271 ("\n\n")
		// Position 1: input 271 -> output 40 ("I")
		// Position 2: input 40 -> output 1079 (" am")
		// Position 3: input 1079 -> output 4460 (" trying")
		// Position 4: input 4460 -> output 311 (" to")
		// Full text: "Hello\n\nI am trying to"
		int[] inputSequence = {9707, 271, 40, 1079, 4460};
		int[] expectedOutputs = {271, 40, 1079, 4460, 311};

		// Create Qwen3 model using the standard constructor
		log("\nLoading Qwen3 model...");
		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 qwen3 = new Qwen3(config, stateDict, tokenizer);

		log("Model loaded successfully\n");

		// Get the compiled model and embeddings
		org.almostrealism.model.CompiledModel compiledModel = qwen3.getCompiledModel();
		PackedCollection embeddings = qwen3.getTokenEmbeddings();

		// Get the position collection from Qwen3 to update it
		PackedCollection position = qwen3.getPosition();

		log("Compiled model input shape: " + compiledModel.getInputShape());
		log("Compiled model output shape: " + compiledModel.getOutputShape());

		log("\n=== Direct Model Forward Pass Test ===\n");

		int[] generatedTokens = new int[5];
		int allMatch = 0;

		for (int step = 0; step < 5; step++) {
			int inputToken = inputSequence[step];
			log("--- Step " + step + ": Input token " + inputToken + " ---");

			// Set position
			position.setMem(0, (double) step);

			// Create input from embedding
			PackedCollection input = new PackedCollection(compiledModel.getInputShape());
			for (int i = 0; i < config.dim; i++) {
				input.setMem(i, embeddings.toDouble(inputToken * config.dim + i));
			}

			// Forward pass - output is already logits!
			PackedCollection logitsCollection = compiledModel.forward(input);

			// Convert to double array for analysis
			double[] logits = new double[config.vocabSize];
			for (int v = 0; v < config.vocabSize; v++) {
				logits[v] = logitsCollection.toDouble(v);
			}

			// Find top-5 predictions
			int[] top5 = findTopK(logits, 5);
			generatedTokens[step] = top5[0];

			log("\nTop 5 predictions from Qwen3 model:");
			for (int i = 0; i < 5; i++) {
				int idx = top5[i];
				log(String.format("  %d. Token %d (logit: %.4f)", i + 1, idx, logits[idx]));
			}

			// Compare with PyTorch for position 0 (only position we have reference for)
			if (step == 0 && pytorchLogits0 != null) {
				log("\nComparison with PyTorch logits:");
				int[] pytorchTop5 = findTopKFromFloat(pytorchLogits0, 5);
				for (int i = 0; i < 5; i++) {
					int javaIdx = top5[i];
					int pyIdx = pytorchTop5[i];
					double javaLogit = logits[javaIdx];
					double pyLogit = pytorchLogits0[pyIdx];
					log(String.format("  Rank %d: Java token %d (%.4f), PyTorch token %d (%.4f)",
							i + 1, javaIdx, javaLogit, pyIdx, pyLogit));
				}

				// Check if top prediction matches
				if (top5[0] == pytorchTop5[0]) {
					log("\n[MATCH] Top prediction matches PyTorch!");
					allMatch++;
				} else {
					log("\n[MISMATCH] Top prediction differs from PyTorch");
					log("  Java logit for PyTorch top token " + pytorchTop5[0] + ": " + logits[pytorchTop5[0]]);
					log("  PyTorch logit for Java top token " + top5[0] + ": " + pytorchLogits0[top5[0]]);
				}
			}

			// Check against expected
			if (top5[0] == expectedOutputs[step]) {
				log("[PASS] Generated token " + top5[0] + " matches expected " + expectedOutputs[step]);
			} else {
				log("[FAIL] Generated token " + top5[0] + " but expected " + expectedOutputs[step]);
			}

			log("");
		}

		// Summary
		log("\n=== Generation Summary ===");
		log("Input sequence:    " + java.util.Arrays.toString(inputSequence));
		log("Generated tokens:  " + java.util.Arrays.toString(generatedTokens));
		log("Expected tokens:   " + java.util.Arrays.toString(expectedOutputs));

		int matches = 0;
		for (int i = 0; i < Math.min(generatedTokens.length, expectedOutputs.length); i++) {
			if (generatedTokens[i] == expectedOutputs[i]) matches++;
		}
		log(String.format("Match rate: %d/%d", matches, expectedOutputs.length));

		// Check for identical outputs (the bug we're looking for)
		boolean allSame = true;
		for (int i = 1; i < generatedTokens.length; i++) {
			if (generatedTokens[i] != generatedTokens[0]) {
				allSame = false;
				break;
			}
		}
		if (allSame && generatedTokens.length > 1) {
			log("\n[WARNING] All generated tokens are identical: " + generatedTokens[0]);
			log("This suggests position handling may not be working correctly.");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");

		// Assert first token matches
		Assert.assertEquals("First generated token should match PyTorch",
				expectedOutputs[0], generatedTokens[0]);
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

	private int[] findTopK(double[] values, int k) {
		int[] indices = new int[k];
		double[] topValues = new double[k];
		java.util.Arrays.fill(topValues, Double.NEGATIVE_INFINITY);

		for (int i = 0; i < values.length; i++) {
			for (int j = 0; j < k; j++) {
				if (values[i] > topValues[j]) {
					for (int m = k - 1; m > j; m--) {
						indices[m] = indices[m - 1];
						topValues[m] = topValues[m - 1];
					}
					indices[j] = i;
					topValues[j] = values[i];
					break;
				}
			}
		}
		return indices;
	}

	private int[] findTopKFromFloat(float[] values, int k) {
		int[] indices = new int[k];
		float[] topValues = new float[k];
		java.util.Arrays.fill(topValues, Float.NEGATIVE_INFINITY);

		for (int i = 0; i < values.length; i++) {
			for (int j = 0; j < k; j++) {
				if (values[i] > topValues[j]) {
					for (int m = k - 1; m > j; m--) {
						indices[m] = indices[m - 1];
						topValues[m] = topValues[m - 1];
					}
					indices[j] = i;
					topValues[j] = values[i];
					break;
				}
			}
		}
		return indices;
	}
}
