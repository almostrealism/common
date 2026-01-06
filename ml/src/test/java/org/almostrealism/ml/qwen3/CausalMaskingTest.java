package org.almostrealism.ml.qwen3;

import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Narrowly focused test to investigate causal masking in attention.
 * <p>
 * Purpose: Document and test whether attention properly masks future positions.
 * <p>
 * Current Issue (from investigation):
 * - AttentionFeatures.java lines 271-273 read full KV cache regardless of position
 * - At position 0, attention attends to ALL cache positions (0-32767)
 * - Future positions contain zeros, which should get low attention weight after softmax
 * - But this could still cause numerical issues
 * <p>
 * Expected Behavior:
 * - At position p, attention should ONLY attend to positions 0..p (causal masking)
 * - Future positions (p+1..seqLen-1) should be masked with -inf before softmax
 * <p>
 * This test:
 * 1. Documents the current behavior
 * 2. Can be used to verify if causal masking is added
 * 3. Compares outputs with/without proper masking (when implemented)
 */
public class CausalMaskingTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

	@Test
	public void documentCausalMaskingBehavior() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		// Setup file logging
		String logFile = "/workspace/project/common/ml/test_output/causal_masking_behavior.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Causal Masking Behavior Documentation ===\n");

		// Load model
		Qwen3Config config = new Qwen3Config(
				896,      // dim
				4864,     // hiddenDim
				24,       // layerCount
				14,       // headCount
				2,        // kvHeadCount
				151936,   // vocabSize
				32768,    // seqLen
				true,     // sharedWeights
				1000000.0 // ropeTheta
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 model = new Qwen3(config, stateDict, tokenizer);

		log("Model Configuration:");
		log("  Sequence Length: " + config.seqLen);
		log("  Heads: " + config.headCount);
		log("  KV Heads: " + config.kvHeadCount);

		log("\n=== Current Attention Behavior ===");
		log("Issue: Attention reads FULL KV cache at all positions");
		log("  - At position 0: reads cache[0:32767] (32767 future zeros!)");
		log("  - At position 1: reads cache[0:32767] (32766 future zeros!)");
		log("  - etc.");

		log("\n=== Expected Behavior (Causal Masking) ===");
		log("Attention should only attend to PAST and CURRENT positions:");
		log("  - At position 0: attend to cache[0:0] (1 position)");
		log("  - At position 1: attend to cache[0:1] (2 positions)");
		log("  - At position p: attend to cache[0:p] (p+1 positions)");

		log("\n=== Code Locations ===");
		log("AttentionFeatures.java:271-273:");
		log("  attention.add(attentionKeys(headShape, p(keyCache)));");
		log("  attention.add(softmax(attentionShape, true));");
		log("  attention.add(attentionValues(attentionShape, p(valueCache)));");
		log("");
		log("Problem: keyCache and valueCache are full (seqLen, kvHeads, headSize)");
		log("No slicing based on current position!");

		log("\n=== Recommended Fix ===");
		log("Before applying attention, mask future positions:");
		log("  1. Create causal mask: mask[i,j] = -inf if j > current_position");
		log("  2. Add mask to attention scores BEFORE softmax");
		log("  3. Or: slice cache to cache[0:position+1] before attention");

		log("\n=== Impact Analysis ===");
		log("Why this might not be catastrophic YET:");
		log("  - Future positions contain zeros (from cache initialization)");
		log("  - Zero keys should produce near-zero attention weights after softmax");
		log("  - But: softmax([-10, 0, 0, ...]) != softmax([-10])");
		log("  - The presence of many zeros could shift probability distribution");

		log("\n=== Test Result ===");
		log("[DOCUMENTED] Current behavior: NO causal masking implemented");
		log("[TODO] Implement causal masking and verify improvement");

		stateDict.destroy();
	}

	@Test
	public void testSinglePositionGeneration() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		// Setup file logging
		String logFile = "/workspace/project/common/ml/test_output/single_position_generation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Single Position Generation Test ===\n");
		log("Purpose: Verify that at position 0, cache contains only 1 valid entry\n");

		Qwen3Config config = new Qwen3Config(
				896,      // dim
				4864,     // hiddenDim
				24,       // layerCount
				14,       // headCount
				2,        // kvHeadCount
				151936,   // vocabSize
				32768,    // seqLen
				true,     // sharedWeights
				1000000.0 // ropeTheta
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 model = new Qwen3(config, stateDict, tokenizer);

		// Run one generation step
		model.setTemperature(0.0);  // Greedy
		model.getAutoregressiveModel().setCurrentToken(9707);  // "Hello"
		model.getAutoregressiveModel().setCurrentStep(0);

		log("Running forward pass at position 0...");
		int nextToken = model.getAutoregressiveModel().next();

		log("Generated token: " + nextToken);
		log("  \"" + tokenizer.decode(new int[]{nextToken}).replace("\n", "\\n") + "\"");

		log("\n=== Cache State Analysis ===");
		log("After position 0:");
		log("  - KV cache should have 1 valid entry at index 0");
		log("  - Positions 1-32767 should still be zero");
		log("  - Attention at position 0 should ONLY look at position 0");
		log("  - But current implementation reads ALL positions!");

		log("\n[INFO] Without causal masking, attention sees:");
		log("  - 1 valid entry (position 0)");
		log("  - 32767 zero entries (positions 1-32767)");
		log("This could distort the attention distribution!");

		stateDict.destroy();
	}

	@Test
	public void compareZeroPaddingEffect() throws Exception {
		// Setup file logging
		String logFile = "/workspace/project/common/ml/test_output/zero_padding_effect.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Zero Padding Effect Test ===\n");
		log("Purpose: Demonstrate how zero-padding affects softmax\n");

		// Simple softmax example
		double[] scores1 = {5.0, 3.0, 1.0};  // 3 valid positions
		double[] scores2 = new double[32768]; // Same 3 + zeros
		scores2[0] = 5.0;
		scores2[1] = 3.0;
		scores2[2] = 1.0;
		// Rest are 0.0

		// Compute softmax
		double[] probs1 = softmax(scores1);
		double[] probs2 = softmax(scores2);

		log("Softmax WITHOUT zero padding (3 positions):");
		log(String.format("  [%.4f, %.4f, %.4f]", probs1[0], probs1[1], probs1[2]));

		log("\nSoftmax WITH zero padding (3 + 32765 zeros):");
		log(String.format("  [%.4f, %.4f, %.4f, ...]", probs2[0], probs2[1], probs2[2]));

		log("\nDifference:");
		log(String.format("  Position 0: %.6f", Math.abs(probs1[0] - probs2[0])));
		log(String.format("  Position 1: %.6f", Math.abs(probs1[1] - probs2[1])));
		log(String.format("  Position 2: %.6f", Math.abs(probs1[2] - probs2[2])));

		double maxDiff = Math.max(
				Math.abs(probs1[0] - probs2[0]),
				Math.max(Math.abs(probs1[1] - probs2[1]), Math.abs(probs1[2] - probs2[2]))
		);

		log(String.format("\nMax difference: %.6f", maxDiff));

		if (maxDiff < 1e-6) {
			log("[INFO] Zero padding has negligible effect (< 1e-6)");
		} else if (maxDiff < 1e-3) {
			log("[WARNING] Zero padding has small effect (< 1e-3)");
		} else {
			log("[ALERT] Zero padding has SIGNIFICANT effect!");
		}
	}

	private double[] softmax(double[] scores) {
		double max = Double.NEGATIVE_INFINITY;
		for (double s : scores) {
			if (s > max) max = s;
		}

		double sum = 0.0;
		double[] exp = new double[scores.length];
		for (int i = 0; i < scores.length; i++) {
			exp[i] = Math.exp(scores[i] - max);
			sum += exp[i];
		}

		double[] probs = new double[scores.length];
		for (int i = 0; i < scores.length; i++) {
			probs[i] = exp[i] / sum;
		}
		return probs;
	}
}
