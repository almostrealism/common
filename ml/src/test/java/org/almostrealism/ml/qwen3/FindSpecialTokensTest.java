package org.almostrealism.ml.qwen3;

import org.junit.Test;

import java.io.IOException;

/**
 * Find special tokens in Qwen3 vocabulary.
 */
public class FindSpecialTokensTest {

    private static final String TOKENIZER_PATH = "/workspace/project/common/ml/qwen3_weights/tokenizer.bin";

    @Test
    public void findSpecialTokens() throws IOException {
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        String[] vocab = tokenizer.getVocab();

        System.out.println("\n=== Searching for special tokens ===\n");

        // Check first 10 tokens
        System.out.println("First 10 tokens:");
        for (int i = 0; i < Math.min(10, vocab.length); i++) {
            System.out.println("  [" + i + "] '" + vocab[i] + "'");
        }

        // Search for UNK-like tokens
        System.out.println("\nSearching for UNK tokens:");
        for (int i = 0; i < vocab.length; i++) {
            String token = vocab[i];
            if (token.toLowerCase().contains("unk") ||
                token.equals("<unk>") ||
                token.equals("[UNK]")) {
                System.out.println("  [" + i + "] '" + token + "'");
            }
        }

        // Search for special markers
        System.out.println("\nSearching for special marker tokens:");
        for (int i = 0; i < vocab.length; i++) {
            String token = vocab[i];
            if (token.startsWith("<|") && token.endsWith("|>")) {
                System.out.println("  [" + i + "] '" + token + "'");
            }
        }

        // Check last 100 tokens (special tokens are usually at the end)
        System.out.println("\nLast 20 tokens:");
        for (int i = Math.max(0, vocab.length - 20); i < vocab.length; i++) {
            System.out.println("  [" + i + "] '" + vocab[i] + "'");
        }
    }
}
