package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Test single forward pass (non-autoregressive) to validate model computation.
 *
 * This test isolates the model's forward pass from the autoregressive generation loop
 * to determine if the issue is in the model itself or in the generation mechanics.
 */
public class Qwen3SingleStepTest implements AttentionFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void testSingleForwardPass() throws Exception {
        System.out.println("\n=== Single Forward Pass Test ===\n");

        // Load model
        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        // Encode "Hello" - should be [9707]
        String input = "Hello";
        int[] tokens = tokenizer.encode(input, false, false);  // No special tokens

        System.out.println("Input: \"" + input + "\"");
        System.out.println("Tokens: " + java.util.Arrays.toString(tokens));

        if (tokens.length != 1 || tokens[0] != 9707) {
            System.err.println("[ERROR] Tokenization failed!");
            System.err.println("Expected: [9707], Got: " + java.util.Arrays.toString(tokens));
            return;
        }

        System.out.println("[OK] Tokenization correct: [9707]\n");

        // Get embedding for token 9707
        PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
        PackedCollection tokenEmbedding = embeddings.range(shape(896), 9707 * 896);

        System.out.println("Token embedding shape: " + tokenEmbedding.getShape());
        System.out.println("Token embedding first 5 values:");
        for (int i = 0; i < 5; i++) {
            System.out.printf("  [%d] %.6f\n", i, tokenEmbedding.toDouble(i));
        }

        // TODO: Run single forward pass through the model
        // This requires access to the compiled model without AutoregressiveModel wrapper

        System.out.println("\n[INFO] Single forward pass test requires direct model access");
        System.out.println("[INFO] Need to expose compiled transformer for testing");

        stateDict.destroy();
    }

    @Test
    public void testPositionZeroLogits() throws Exception {
        System.out.println("\n=== Position 0 Logits Test ===\n");

        // This test validates that at position 0, the model produces correct logits
        // We'll compare against PyTorch logits for "Hello" at position 0

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        System.out.println("Testing forward pass at position 0 with token 9707 (\"Hello\")");

        // Set temperature to 0 for greedy decoding
        model.setTemperature(0.0);

        // Get the autoregressive model
        org.almostrealism.ml.AutoregressiveModel arModel = model.getAutoregressiveModel();

        // Set position to 0
        arModel.setCurrentStep(0);

        // Set current token to 9707 ("Hello")
        arModel.setCurrentToken(9707);

        // Run one step to get logits
        System.err.println("Running forward pass...");
        int nextToken = arModel.next();

        System.err.println("\n=== RESULTS ===");
        System.err.println("Generated token: " + nextToken);
        System.err.println("Token string: \"" + tokenizer.decode(new int[]{nextToken}) + "\"");

        // Expected from PyTorch:
        // Top prediction should be token 271 ("\n\n") with logit ~12.84

        System.err.println("\n=== EXPECTED ===");
        System.err.println("Top token: 271 (\\n\\n)");

        if (nextToken == 271) {
            System.err.println("\n[PASS] Model generated correct token!");
        } else {
            System.err.println("\n[FAIL] Model generated token " + nextToken + " instead of 271");
            System.err.println("Diff: " + Math.abs(nextToken - 271));
        }

        stateDict.destroy();
    }

    private double computeL1Norm(PackedCollection vec, int size) {
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += Math.abs(vec.toDouble(i));
        }
        return sum;
    }
}
