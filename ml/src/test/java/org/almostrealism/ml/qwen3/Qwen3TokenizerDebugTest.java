package org.almostrealism.ml.qwen3;

import org.junit.Test;
import java.util.Arrays;

public class Qwen3TokenizerDebugTest {
    
    @Test
    public void testHelloEncoding() throws Exception {
        String tokenizerPath = "/workspace/project/common/ml/qwen3_weights/tokenizer.bin";
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(tokenizerPath);
        
        String text = "Hello";
        System.out.println("\nTesting: '" + text + "'");
        
        // Encode
        int[] tokens = tokenizer.encode(text, false, false);
        System.out.println("Encoded: " + Arrays.toString(tokens));
        System.out.println("Expected: [9707] (single token)");
        
        // Decode each token
        for (int i = 0; i < tokens.length; i++) {
            int tokenId = tokens[i];
            String tokenStr = tokenizer.getVocab()[tokenId];
            System.out.println("  Token " + i + ": ID=" + tokenId + " -> '" + tokenStr + "'");
        }
        
        // Check if "Hello" is in vocab
        String[] vocab = tokenizer.getVocab();
        boolean found = false;
        for (int i = 0; i < vocab.length; i++) {
            if (vocab[i].equals("Hello")) {
                System.out.println("\nFound 'Hello' in vocab at index: " + i);
                found = true;
                break;
            }
        }
        
        if (!found) {
            System.out.println("\n[ERROR] 'Hello' not found in vocabulary!");
        }
        
        // Check for the GPT-2 encoded version
        String gpt2Text = text.replace(" ", "\u0120");
        System.out.println("\nGPT-2 encoded text: '" + gpt2Text + "'");
        System.out.println("Checking if in vocab...");
        
        for (int i = 0; i < vocab.length; i++) {
            if (vocab[i].equals(gpt2Text)) {
                System.out.println("Found GPT-2 encoded version at index: " + i);
                found = true;
                break;
            }
        }
    }
}
