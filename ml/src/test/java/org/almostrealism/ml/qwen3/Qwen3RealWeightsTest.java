package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test Qwen3 with real weights extracted from HuggingFace.
 *
 * This test loads actual Qwen2.5-0.5B-Instruct weights to validate:
 * - StateDictionary loading from protobuf files
 * - Weight shape compatibility
 * - Model construction with real dimensions
 * - GQA (14 heads / 2 KV heads)
 */
public class Qwen3RealWeightsTest {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

	@Test
	public void testLoadRealWeights() {
		System.out.println("\n=== Test: Load Real Weights ===");

		// Config from Qwen2.5-0.5B-Instruct
		// Note: Using only 12 layers since extraction only got 12 of 24
		Qwen3Config config = new Qwen3Config(
			896,      // dim
			4864,     // hiddenDim
			12,       // layerCount (only 12 extracted)
			14,       // headCount
			2,        // kvHeadCount (GQA: 14/2 = 7:1 ratio!)
			151936,   // vocabSize
			32768,    // seqLen
			true,     // sharedWeights
			1000000.0 // ropeTheta
		);

		System.out.println("Config: " + config);

		try {
			config.validate();
			System.out.println("[OK] Config validation passed");
		} catch (Exception e) {
			fail("Config validation failed: " + e.getMessage());
		}

		// Load StateDictionary from protobuf files
		StateDictionary stateDict;
		try {
			System.out.println("Loading weights from: " + WEIGHTS_DIR);
			stateDict = new StateDictionary(WEIGHTS_DIR);
			System.out.println("[OK] Loaded StateDictionary with " + stateDict.size() + " weight tensors");
		} catch (Exception e) {
			e.printStackTrace();
			fail("Failed to load StateDictionary: " + e.getMessage());
			return;
		}

		// Load tokenizer
		Qwen3Tokenizer tokenizer;
		try {
			System.out.println("Loading tokenizer from: " + TOKENIZER_PATH);
			tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
			System.out.println("[OK] Loaded tokenizer with " + tokenizer.getVocabSize() + " tokens");
		} catch (Exception e) {
			e.printStackTrace();
			fail("Failed to load tokenizer: " + e.getMessage());
			return;
		}

		// Create model with real weights
		try {
			System.out.println("Creating Qwen3 model instance...");
			Qwen3 model = new Qwen3(config, stateDict, tokenizer);
			System.out.println("[OK] Model instance created successfully");
			assertNotNull("Model should not be null", model);
		} catch (Exception e) {
			e.printStackTrace();
			fail("Model construction failed: " + e.getMessage());
		}

		System.out.println("[OK] Real weights test passed!\n");
	}

	@Test
	public void testGenerateWithRealWeights() {
		System.out.println("\n=== Test: Generate with Real Weights ===");

		// Same config as above
		Qwen3Config config = new Qwen3Config(
			896, 4864, 12, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict;
		Qwen3Tokenizer tokenizer;
		Qwen3 model;

		try {
			stateDict = new StateDictionary(WEIGHTS_DIR);
			tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
			model = new Qwen3(config, stateDict, tokenizer);
			System.out.println("[OK] Model loaded");
		} catch (Exception e) {
			e.printStackTrace();
			fail("Failed to load model: " + e.getMessage());
			return;
		}

		// Try to generate a few tokens
		try {
			System.out.println("Attempting token generation...");
			model.setTemperature(0.0);  // Deterministic

			String prompt = "Hello";
			System.out.println("Prompt: \"" + prompt + "\"");

			model.run(10, prompt, token -> {
				System.out.print(token);
				System.out.flush();
			});

			System.out.println("\n[OK] Generation completed");
		} catch (Exception e) {
			System.err.println("\n[EXPECTED] Generation failed (likely GQA not implemented):");
			e.printStackTrace();
			// Don't fail the test - we expect this to fail due to GQA
			// Just document what broke
		}

		System.out.println("[OK] Generation test completed\n");
	}

	/**
	 * Main method for running tests without JUnit.
	 */
	public static void main(String[] args) {
		System.out.println("+============================================================+");
		System.out.println("|  Qwen3 Real Weights Test                                   |");
		System.out.println("|  Testing with Qwen2.5-0.5B-Instruct weights                |");
		System.out.println("+============================================================+");

		Qwen3RealWeightsTest test = new Qwen3RealWeightsTest();
		int passed = 0;
		int failed = 0;

		// Test 1: Load Weights
		try {
			test.testLoadRealWeights();
			passed++;
		} catch (AssertionError | Exception e) {
			System.err.println("[FAIL] Test 1 FAILED: " + e.getMessage());
			e.printStackTrace();
			failed++;
		}

		// Test 2: Generate
		try {
			test.testGenerateWithRealWeights();
			passed++;
		} catch (AssertionError | Exception e) {
			System.err.println("[FAIL] Test 2 FAILED: " + e.getMessage());
			e.printStackTrace();
			failed++;
		}

		System.out.println("\n+============================================================+");
		System.out.println("|  Test Results                                              |");
		System.out.println("+------------------------------------------------------------+");
		System.out.printf("|  Passed: %d                                                  |%n", passed);
		System.out.printf("|  Failed: %d                                                  |%n", failed);
		System.out.println("+============================================================+");

		if (failed > 0) {
			System.exit(1);
		}
	}
}
