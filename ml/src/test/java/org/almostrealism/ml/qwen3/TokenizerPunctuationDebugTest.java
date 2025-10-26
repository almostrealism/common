package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.tokenization.ByteLevelEncoder;
import org.almostrealism.ml.tokenization.PreTokenizer;
import org.almostrealism.ml.tokenization.RegexPreTokenizer;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Debug test for punctuation handling.
 */
public class TokenizerPunctuationDebugTest {

    private static final String TOKENIZER_PATH = "/workspace/project/common/ml/qwen3_weights/tokenizer.bin";

    @Test
    public void testPunctuationEncoding() throws IOException {
        PreTokenizer preTokenizer = new RegexPreTokenizer();
        String text = "Hello world!";
        List<String> segments = preTokenizer.preTokenize(text);

        System.out.println("\nInput: \"" + text + "\"");
        System.out.println("Pre-tokenized segments:");
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            String encoded = ByteLevelEncoder.encode(seg);
            System.out.println("  [" + i + "] \"" + seg + "\" -> ByteLevel: \"" + encoded + "\"");
        }

        // Load tokenizer and check vocab
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);

        // Check if "!" is in vocab
        String[] vocab = tokenizer.getVocab();
        for (int i = 0; i < vocab.length; i++) {
            if (vocab[i].equals("!")) {
                System.out.println("\nFound '!' in vocab at index: " + i);
                break;
            }
        }

        // Encode just "!"
        int[] tokens = tokenizer.encode("!", false, false);
        System.out.println("\nEncode '!': " + Arrays.toString(tokens));
        if (tokens.length > 0 && tokens[0] >= 0 && tokens[0] < vocab.length) {
            System.out.println("Token " + tokens[0] + ": '" + vocab[tokens[0]] + "'");
        }

        // Check what token 0 is
        System.out.println("\nToken 0: '" + vocab[0] + "'");

        // Test the full string
        tokens = tokenizer.encode("Hello world!", false, false);
        System.out.println("\nEncode 'Hello world!': " + Arrays.toString(tokens));
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i] >= 0 && tokens[i] < vocab.length) {
                System.out.println("  Token " + tokens[i] + ": '" + vocab[tokens[i]] + "'");
            }
        }
    }
}
