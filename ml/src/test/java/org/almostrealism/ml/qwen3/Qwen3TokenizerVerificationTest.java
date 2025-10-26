package org.almostrealism.ml.qwen3;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Verification test for the refactored byte-level BPE tokenizer.
 */
public class Qwen3TokenizerVerificationTest {

    private static final String TOKENIZER_PATH = "/workspace/project/common/ml/qwen3_weights/tokenizer.bin";

    @Test
    public void testTokenizerEncoding() throws IOException {
        System.out.println("\n=== Tokenizer Verification Test ===\n");

        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);

        // Test cases based on PyTorch reference
        testEncodeDecode(tokenizer, "Hello", new int[]{9707});
        testEncodeDecode(tokenizer, "Tell me a story", null);  // Don't know expected tokens yet
        testEncodeDecode(tokenizer, " world", null);
        testEncodeDecode(tokenizer, "The quick brown fox", null);
        testEncodeDecode(tokenizer, "Hello world!", null);

        System.out.println("\n=== All Tests Complete ===\n");
    }

    private void testEncodeDecode(Qwen3Tokenizer tokenizer, String text, int[] expectedTokens) {
        System.out.println("Testing: \"" + text + "\"");

        // Encode without special tokens
        int[] tokens = tokenizer.encode(text, false, false);
        System.out.println("  Encoded: " + Arrays.toString(tokens) + " (" + tokens.length + " tokens)");

        if (expectedTokens != null) {
            if (Arrays.equals(tokens, expectedTokens)) {
                System.out.println("  [MATCH] Encoding matches expected");
            } else {
                System.out.println("  [MISMATCH] Expected: " + Arrays.toString(expectedTokens));
            }
        }

        // Decode
        String decoded = tokenizer.decode(tokens);
        System.out.println("  Decoded: \"" + decoded + "\"");

        // Check round-trip
        if (decoded.equals(text)) {
            System.out.println("  [OK] Round-trip successful");
        } else {
            System.out.println("  [ERROR] Round-trip failed");
            System.out.println("    Original: \"" + text + "\"");
            System.out.println("    Decoded:  \"" + decoded + "\"");
        }

        System.out.println();
    }
}
