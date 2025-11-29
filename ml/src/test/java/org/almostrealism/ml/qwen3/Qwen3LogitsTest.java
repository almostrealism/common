package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.util.Arrays;

/**
 * Test to compare logits output with PyTorch reference.
 */
public class Qwen3LogitsTest {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void testLogitsForHello() throws Exception {
        System.out.println("\n=== Logits Comparison Test ===");
        
        // Load model
        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );
        
        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);
        
        model.setTemperature(0.0);  // Greedy decoding
        
        // Encode "Hello"
        String prompt = "Hello";
        int[] tokens = tokenizer.encode(prompt, false, false);  // No BOS/EOS
        
        System.out.println("Prompt: '" + prompt + "'");
        System.out.println("Encoded tokens: " + Arrays.toString(tokens));
        System.out.println("Token count: " + tokens.length);
        
        // Decode to verify
        String decoded = tokenizer.decode(tokens);
        System.out.println("Decoded: '" + decoded + "'");
        
        // Run one forward pass
        System.out.println("\nRunning forward pass...");
        
        // Get the model's internal state
        // We need to access the raw logits before sampling
        // For now, let's just generate and see what token is selected
        
        final int[] generatedToken = {-1};
        final int[] tokenCount = {0};
        
        model.run(1, prompt, token -> {
            System.out.println("Generated token: '" + token + "'");
            // Try to extract token ID
            int[] encoded = tokenizer.encode(token, false, false);
            if (encoded.length > 0) {
                generatedToken[0] = encoded[0];
                System.out.println("Generated token ID: " + encoded[0]);
            }
            tokenCount[0]++;
        });
        
        System.out.println("\nGenerated " + tokenCount[0] + " tokens");
        
        // Compare with PyTorch
        System.out.println("\n=== PyTorch Reference ===");
        System.out.println("Expected top token: 271 (\\n\\n) with logit 12.84");
        System.out.println("\n=== Our Implementation ===");
        System.out.println("Generated token ID: " + generatedToken[0]);
        
        if (generatedToken[0] == 271) {
            System.out.println("[MATCH] Model generated correct token");
        } else {
            System.out.println("[MISMATCH] Expected 271, got " + generatedToken[0]);
        }
        
        stateDict.destroy();
        System.out.println("\n=== Test Complete ===\n");
    }
}
