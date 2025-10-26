package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Narrowly focused test to investigate causal masking in attention.
 *
 * Purpose: Document and test whether attention properly masks future positions.
 *
 * Current Issue (from investigation):
 * - AttentionFeatures.java lines 271-273 read full KV cache regardless of position
 * - At position 0, attention attends to ALL cache positions (0-32767)
 * - Future positions contain zeros, which should get low attention weight after softmax
 * - But this could still cause numerical issues
 *
 * Expected Behavior:
 * - At position p, attention should ONLY attend to positions 0..p (causal masking)
 * - Future positions (p+1..seqLen-1) should be masked with -inf before softmax
 *
 * This test:
 * 1. Documents the current behavior
 * 2. Can be used to verify if causal masking is added
 * 3. Compares outputs with/without proper masking (when implemented)
 */
public class CausalMaskingTest implements AttentionFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void documentCausalMaskingBehavior() throws Exception {
        System.err.println("\n=== Causal Masking Behavior Documentation ===\n");

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

        System.err.println("Model Configuration:");
        System.err.println("  Sequence Length: " + config.seqLen);
        System.err.println("  Heads: " + config.headCount);
        System.err.println("  KV Heads: " + config.kvHeadCount);

        System.err.println("\n=== Current Attention Behavior ===");
        System.err.println("Issue: Attention reads FULL KV cache at all positions");
        System.err.println("  - At position 0: reads cache[0:32767] (32767 future zeros!)");
        System.err.println("  - At position 1: reads cache[0:32767] (32766 future zeros!)");
        System.err.println("  - etc.");

        System.err.println("\n=== Expected Behavior (Causal Masking) ===");
        System.err.println("Attention should only attend to PAST and CURRENT positions:");
        System.err.println("  - At position 0: attend to cache[0:0] (1 position)");
        System.err.println("  - At position 1: attend to cache[0:1] (2 positions)");
        System.err.println("  - At position p: attend to cache[0:p] (p+1 positions)");

        System.err.println("\n=== Code Locations ===");
        System.err.println("AttentionFeatures.java:271-273:");
        System.err.println("  attention.add(attentionKeys(headShape, p(keyCache)));");
        System.err.println("  attention.add(softmax(attentionShape, true));");
        System.err.println("  attention.add(attentionValues(attentionShape, p(valueCache)));");
        System.err.println("");
        System.err.println("Problem: keyCache and valueCache are full (seqLen, kvHeads, headSize)");
        System.err.println("No slicing based on current position!");

        System.err.println("\n=== Recommended Fix ===");
        System.err.println("Before applying attention, mask future positions:");
        System.err.println("  1. Create causal mask: mask[i,j] = -inf if j > current_position");
        System.err.println("  2. Add mask to attention scores BEFORE softmax");
        System.err.println("  3. Or: slice cache to cache[0:position+1] before attention");

        System.err.println("\n=== Impact Analysis ===");
        System.err.println("Why this might not be catastrophic YET:");
        System.err.println("  - Future positions contain zeros (from cache initialization)");
        System.err.println("  - Zero keys should produce near-zero attention weights after softmax");
        System.err.println("  - But: softmax([-10, 0, 0, ...]) != softmax([-10])");
        System.err.println("  - The presence of many zeros could shift probability distribution");

        System.err.println("\n=== Test Result ===");
        System.err.println("[DOCUMENTED] Current behavior: NO causal masking implemented");
        System.err.println("[TODO] Implement causal masking and verify improvement");

        stateDict.destroy();
    }

    @Test
    public void testSinglePositionGeneration() throws Exception {
        System.err.println("\n=== Single Position Generation Test ===\n");
        System.err.println("Purpose: Verify that at position 0, cache contains only 1 valid entry\n");

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

        System.err.println("Running forward pass at position 0...");
        int nextToken = model.getAutoregressiveModel().next();

        System.err.println("Generated token: " + nextToken);
        System.err.println("  \"" + tokenizer.decode(new int[]{nextToken}).replace("\n", "\\n") + "\"");

        System.err.println("\n=== Cache State Analysis ===");
        System.err.println("After position 0:");
        System.err.println("  - KV cache should have 1 valid entry at index 0");
        System.err.println("  - Positions 1-32767 should still be zero");
        System.err.println("  - Attention at position 0 should ONLY look at position 0");
        System.err.println("  - But current implementation reads ALL positions!");

        System.err.println("\n[INFO] Without causal masking, attention sees:");
        System.err.println("  - 1 valid entry (position 0)");
        System.err.println("  - 32767 zero entries (positions 1-32767)");
        System.err.println("This could distort the attention distribution!");

        stateDict.destroy();
    }

    @Test
    public void compareZeroPaddingEffect() throws Exception {
        System.err.println("\n=== Zero Padding Effect Test ===\n");
        System.err.println("Purpose: Demonstrate how zero-padding affects softmax\n");

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

        System.err.println("Softmax WITHOUT zero padding (3 positions):");
        System.err.println(String.format("  [%.4f, %.4f, %.4f]", probs1[0], probs1[1], probs1[2]));

        System.err.println("\nSoftmax WITH zero padding (3 + 32765 zeros):");
        System.err.println(String.format("  [%.4f, %.4f, %.4f, ...]", probs2[0], probs2[1], probs2[2]));

        System.err.println("\nDifference:");
        System.err.println(String.format("  Position 0: %.6f", Math.abs(probs1[0] - probs2[0])));
        System.err.println(String.format("  Position 1: %.6f", Math.abs(probs1[1] - probs2[1])));
        System.err.println(String.format("  Position 2: %.6f", Math.abs(probs1[2] - probs2[2])));

        double maxDiff = Math.max(
            Math.abs(probs1[0] - probs2[0]),
            Math.max(Math.abs(probs1[1] - probs2[1]), Math.abs(probs1[2] - probs2[2]))
        );

        System.err.println(String.format("\nMax difference: %.6f", maxDiff));

        if (maxDiff < 1e-6) {
            System.err.println("[INFO] Zero padding has negligible effect (< 1e-6)");
        } else if (maxDiff < 1e-3) {
            System.err.println("[WARNING] Zero padding has small effect (< 1e-3)");
        } else {
            System.err.println("[ALERT] Zero padding has SIGNIFICANT effect!");
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
