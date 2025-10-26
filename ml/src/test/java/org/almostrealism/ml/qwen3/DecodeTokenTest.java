package org.almostrealism.ml.qwen3;

import org.junit.Test;

/**
 * Quick test to decode specific tokens.
 */
public class DecodeTokenTest {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void decodeTokens() throws Exception {
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);

        // Tokens of interest
        int[] tokens = {198, 271, 27};

        System.out.println("Token decoding:");
        for (int token : tokens) {
            String decoded = tokenizer.decode(new int[]{token});
            System.out.printf("Token %d: \"%s\" (bytes: %s)\\n",
                    token,
                    decoded.replace("\n", "\\n").replace("\r", "\\r"),
                    bytesToHex(decoded.getBytes()));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
