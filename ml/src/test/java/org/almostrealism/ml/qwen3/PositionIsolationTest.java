package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test to isolate the position handling issue.
 * Compares outputs at different positions with various inputs.
 */
public class PositionIsolationTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

	@Test
	public void testPositionIsolation() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/position_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Position Isolation Test");
		log("===================================================\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 qwen3 = new Qwen3(config, stateDict, tokenizer);

		org.almostrealism.model.CompiledModel compiledModel = qwen3.getCompiledModel();
		PackedCollection embeddings = qwen3.getTokenEmbeddings();
		PackedCollection position = qwen3.getPosition();

		log("Model loaded.\n");

		// Test 1: Token 271 at position 0 (no prior context)
		// PyTorch "without context" gives: 220 with logit 14.54
		log("=== Test 1: Token 271 at position 0 (no context) ===");
		runForwardPass(compiledModel, embeddings, position, config, 271, 0);

		// Reload model to clear caches
		stateDict.destroy();
		stateDict = new StateDictionary(WEIGHTS_DIR);
		qwen3 = new Qwen3(config, stateDict, tokenizer);
		compiledModel = qwen3.getCompiledModel();
		embeddings = qwen3.getTokenEmbeddings();
		position = qwen3.getPosition();

		// Test 2: Token 9707 at position 0, then token 271 at position 1
		// This is the failing case - should give 40 with logit 17.31
		log("\n=== Test 2: Token 9707 at pos 0, then 271 at pos 1 ===");
		runForwardPass(compiledModel, embeddings, position, config, 9707, 0);
		log(""); // blank line
		runForwardPass(compiledModel, embeddings, position, config, 271, 1);

		// Test 3: Same tokens but at positions 2 and 3
		// If position 1-2 have a specific bug, positions 2-3 might work differently
		log("\n=== Test 3: Token 9707 at pos 2, then 271 at pos 3 ===");
		stateDict.destroy();
		stateDict = new StateDictionary(WEIGHTS_DIR);
		qwen3 = new Qwen3(config, stateDict, tokenizer);
		compiledModel = qwen3.getCompiledModel();
		embeddings = qwen3.getTokenEmbeddings();
		position = qwen3.getPosition();

		runForwardPass(compiledModel, embeddings, position, config, 9707, 2);
		log(""); // blank line
		runForwardPass(compiledModel, embeddings, position, config, 271, 3);

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private void runForwardPass(org.almostrealism.model.CompiledModel compiledModel,
								PackedCollection embeddings,
								PackedCollection position,
								Qwen3Config config,
								int token, int pos) {
		position.setMem(0, (double) pos);

		PackedCollection input = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, embeddings.toDouble(token * config.dim + i));
		}

		PackedCollection logitsCollection = compiledModel.forward(input);

		// Find top-5 predictions
		double[] logits = new double[config.vocabSize];
		for (int v = 0; v < config.vocabSize; v++) {
			logits[v] = logitsCollection.toDouble(v);
		}

		int[] top5 = findTopK(logits, 5);

		log("Position " + pos + ", Input token " + token + ":");
		log("  Top 5 predictions:");
		for (int i = 0; i < 5; i++) {
			int idx = top5[i];
			log(String.format("    %d. Token %d (logit: %.4f)", i + 1, idx, logits[idx]));
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
}
