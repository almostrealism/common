package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;

/**
 * Test to validate embedding lookup.
 */
public class Qwen3EmbeddingTest extends TestSuiteBase implements AttentionFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

	@Test(timeout = 30000)
	public void testEmbeddingLookup() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		log("\n=== Embedding Lookup Test ===\n");

		// Load embeddings
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");

		log("Embedding table shape: " + embeddings.getShape());
		log("Embedding size: " + embeddings.getMemLength());

		// Load tokenizer
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);

		// Test token: "Hello" -> 9707
		int token = 9707;
		int dim = 896;

		log("\nTesting token " + token + " (\"Hello\")");
		log("Expected embedding index: " + token + " * " + dim + " = " + (token * dim));

		// Extract embedding using range (as model does)
		PackedCollection embedding = embeddings.range(shape(dim), token * dim);

		log("Embedding first 10 values:");
		for (int i = 0; i < 10; i++) {
			log(String.format("  [%d] %.6f\n", i, embedding.toDouble(i)));
		}

		// Verify embedding is not all zeros
		double sum = 0;
		for (int i = 0; i < dim; i++) {
			sum += Math.abs(embedding.toDouble(i));
		}

		log("\nEmbedding L1 norm: " + sum);

		if (sum < 0.001) {
			log("[ERROR] WARNING: Embedding appears to be all zeros!");
		} else {
			log("[OK] Embedding looks valid");
		}

		// Test a few more tokens
		String[] testWords = {"Tell", "me", "a", "story"};
		int[] testTokens = tokenizer.encode("Tell me a story", false, false);

		log("\n\"Tell me a story\" tokens: " + Arrays.toString(testTokens));

		for (int i = 0; i < testTokens.length && i < testWords.length; i++) {
			PackedCollection emb = embeddings.range(shape(dim), testTokens[i] * dim);
			double norm = 0;
			for (int j = 0; j < dim; j++) {
				norm += Math.abs(emb.toDouble(j));
			}
			log(String.format("Token %d: L1 norm = %.4f\n", testTokens[i], norm));
		}

		stateDict.destroy();
	}
}
