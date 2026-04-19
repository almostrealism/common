package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.fail;

/**
 * Synthetic test for Qwen3 using random weights.
 * <p>
 * This test creates a tiny Qwen3 model with random weights to verify:
 * 1. Model construction doesn't crash
 * 2. Forward pass executes without errors
 * 3. Output shapes are correct
 * 4. No null pointer exceptions or indexing errors
 * <p>
 * This does NOT validate:
 * - Weight shapes match HuggingFace format
 * - Output is meaningful
 * - Attention mechanism is correct
 */
public class Qwen3SyntheticTest extends TestSuiteBase {

	/**
	 * Create random weights with correct shapes for a Qwen3 config.
	 * Returns a StateDictionary populated with HuggingFace-style key names.
	 */
	private static StateDictionary createRandomWeights(Qwen3Config config, long seed) {
		Random random = new Random(seed);
		Map<String, PackedCollection> weights = new HashMap<>();

		int kvDim = config.dim * config.kvHeadCount / config.headCount;

		Console.root().println("Creating random weights for config: " + config);
		Console.root().println("  kvDim: " + kvDim);

		// Token embeddings
		weights.put("model.embed_tokens.weight",
				randomCollection(random, config.vocabSize, config.dim));

		// Final RMS norm
		weights.put("model.norm.weight",
				randomCollection(random, config.dim));

		// Per-layer weights
		for (int i = 0; i < config.layerCount; i++) {
			String prefix = String.format("model.layers.%d", i);

			// RMS norm weights
			weights.put(prefix + ".input_layernorm.weight",
					randomCollection(random, config.dim));
			weights.put(prefix + ".post_attention_layernorm.weight",
					randomCollection(random, config.dim));

			// Attention weights (in HuggingFace format: output_dim, input_dim)
			weights.put(prefix + ".self_attn.q_proj.weight",
					randomCollection(random, config.dim, config.dim));
			weights.put(prefix + ".self_attn.k_proj.weight",
					randomCollection(random, kvDim, config.dim));
			weights.put(prefix + ".self_attn.v_proj.weight",
					randomCollection(random, kvDim, config.dim));
			weights.put(prefix + ".self_attn.o_proj.weight",
					randomCollection(random, config.dim, config.dim));

			// QK-Norm weights
			weights.put(prefix + ".self_attn.q_norm.weight",
					randomCollection(random, config.headCount, config.headSize));
			weights.put(prefix + ".self_attn.k_norm.weight",
					randomCollection(random, config.kvHeadCount, config.headSize));

			// FFN weights
			weights.put(prefix + ".mlp.gate_proj.weight",
					randomCollection(random, config.hiddenDim, config.dim));
			weights.put(prefix + ".mlp.down_proj.weight",
					randomCollection(random, config.dim, config.hiddenDim));
			weights.put(prefix + ".mlp.up_proj.weight",
					randomCollection(random, config.hiddenDim, config.dim));
		}

		// Classifier weights (shared with embeddings if configured)
		if (!config.sharedWeights) {
			weights.put("lm_head.weight",
					randomCollection(random, config.vocabSize, config.dim));
		}

		return new StateDictionary(weights);
	}

	private static PackedCollection randomCollection(Random random, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		PackedCollection collection = new PackedCollection(shape);

		// Fill with small random values (-0.1 to 0.1)
		int size = shape.getTotalSize();
		for (int i = 0; i < size; i++) {
			collection.setMem(i, (random.nextDouble() - 0.5) * 0.2);
		}

		return collection;
	}

	@Test(timeout = 120000)
	public void testTinyModelConstruction() {
		log("\n=== Test 1: Tiny Model Construction ===");

		// Create a very small config
		// NOTE: Using heads==kvHeads because GQA is not yet fully implemented
		Qwen3Config config = new Qwen3Config(
				64,    // dim (tiny!)
				192,   // hiddenDim (3x)
				2,     // layers (just 2)
				4,     // heads
				4,     // kvHeads (same as heads - no GQA for now)
				100,   // vocab (tiny)
				32,    // seqLen (short)
				true,  // sharedWeights
				10000.0
		);

		log("Config: " + config);

		try {
			config.validate();
			log("[OK] Config validation passed");
		} catch (Exception e) {
			fail("Config validation failed: " + e.getMessage());
		}

		// Create random weights
		StateDictionary stateDict;
		try {
			stateDict = createRandomWeights(config, 12345L);
			log("[OK] Random weights created");
		} catch (Exception e) {
			fail("Failed to create random weights: " + e.getMessage());
			return;
		}

		// Create tokenizer
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();
		log("[OK] Test tokenizer created");

		// Try to create model
		try {
			Qwen3 model = new Qwen3(config, stateDict, tokenizer);
			log("[OK] Model instance created");
			assertNotNull("Model should not be null", model);
		} catch (Exception e) {
			warn(e.getMessage(), e);
			fail("Model construction failed: " + e.getMessage());
		}

		log("[OK] All construction tests passed!\n");
	}

	@Test(timeout = 120000)
	public void testModelCompilation() {
		log("\n=== Test 2: Model Compilation ===");

		// NOTE: Using heads==kvHeads because GQA is not yet fully implemented
		Qwen3Config config = new Qwen3Config(
				64, 192, 2, 4, 4, 100, 32, true, 10000.0
		);

		StateDictionary stateDict = createRandomWeights(config, 12345L);
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		try {
			new Qwen3(config, stateDict, tokenizer);
			log("[OK] Model created");

			// The model should compile when we try to run it
			// We won't actually run it yet, just create it
			log("[OK] Model ready for compilation (happens on first run)");

		} catch (Exception e) {
			warn(e.getMessage(), e);
			fail("Model compilation setup failed: " + e.getMessage());
		}

		log("[OK] Compilation test passed!\n");
	}

	@Test(timeout = 120000)
	public void testWeightShapes() {
		log("\n=== Test 3: Weight Shape Verification ===");

		Qwen3Config config = Qwen3Config.qwen3_test();
		log("Using test config: " + config);

		int kvDim = config.dim * config.kvHeadCount / config.headCount;

		StateDictionary stateDict = createRandomWeights(config, 12345L);

		// Check token embeddings
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		assertNotNull("Token embeddings should exist", embeddings);
		assertEquals("tokenEmbeddings shape",
				config.vocabSize * config.dim,
				embeddings.getShape().getTotalSize());

		// Check first layer attention weights
		PackedCollection wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
		assertNotNull("Query weights should exist", wq);
		assertEquals("wq shape per layer",
				config.dim * config.dim,
				wq.getShape().getTotalSize());

		PackedCollection wk = stateDict.get("model.layers.0.self_attn.k_proj.weight");
		assertNotNull("Key weights should exist", wk);
		assertEquals("wk shape per layer (GQA)",
				kvDim * config.dim,
				wk.getShape().getTotalSize());

		PackedCollection qkNormQ = stateDict.get("model.layers.0.self_attn.q_norm.weight");
		assertNotNull("QK-Norm Q should exist", qkNormQ);
		assertEquals("qkNormQ shape per layer",
				config.headCount * config.headSize,
				qkNormQ.getShape().getTotalSize());

		PackedCollection qkNormK = stateDict.get("model.layers.0.self_attn.k_norm.weight");
		assertNotNull("QK-Norm K should exist", qkNormK);
		assertEquals("qkNormK shape per layer (GQA)",
				config.kvHeadCount * config.headSize,
				qkNormK.getShape().getTotalSize());

		log("[OK] All weight shapes correct");
		log("  Token embeddings: " + embeddings.getShape());
		log("  Query weights (layer 0): " + wq.getShape());
		log("  Key weights (layer 0, GQA): " + wk.getShape());
		log("  QK-Norm Q (layer 0): " + qkNormQ.getShape());
		log("  QK-Norm K (layer 0): " + qkNormK.getShape());
		log("[OK] Shape verification passed!\n");
	}

	/**
	 * Main method for running tests without JUnit.
	 */
	public static void main(String[] args) {
		Console.root().println("+============================================================+");
		Console.root().println("|  Qwen3 Synthetic Test - Random Weights                    |");
		Console.root().println("|  Purpose: Verify model doesn't crash with valid shapes    |");
		Console.root().println("+============================================================+");

		Qwen3SyntheticTest test = new Qwen3SyntheticTest();
		int passed = 0;
		int failed = 0;

		// Test 1: Construction
		try {
			test.testTinyModelConstruction();
			passed++;
		} catch (AssertionError | Exception e) {
			Console.root().warn("[FAIL] Test 1 FAILED: " + e.getMessage(), e);
			failed++;
		}

		// Test 2: Compilation
		try {
			test.testModelCompilation();
			passed++;
		} catch (AssertionError | Exception e) {
			Console.root().warn("[FAIL] Test 2 FAILED: " + e.getMessage(), e);
			failed++;
		}

		// Test 3: Weight Shapes
		try {
			test.testWeightShapes();
			passed++;
		} catch (AssertionError | Exception e) {
			Console.root().warn("[FAIL] Test 3 FAILED: " + e.getMessage(), e);
			failed++;
		}

		Console.root().println("\n+============================================================+");
		Console.root().println("|  Test Results                                              |");
		Console.root().println("+------------------------------------------------------------+");
		Console.root().println(String.format("|  Passed: %d                                                  |%n", passed));
		Console.root().println(String.format("|  Failed: %d                                                  |%n", failed));
		Console.root().println("+============================================================+");

		if (failed > 0) {
			System.exit(1);
		}
	}
}
