package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Compare AR logits against PyTorch reference for full 24-layer model.
 */
public class CompareLogitsTest implements AttentionFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";
    private static final String PYTORCH_LOGITS = "/workspace/project/common/ml/qwen3_reference/full_model_logits/position_0_logits.bin";

    @Test
    public void compareFullModelLogits() throws Exception {
        System.err.println("\n=== Comparing AR vs PyTorch Full Model Logits ===\n");

        // Load PyTorch logits
        float[] pytorchLogits = loadPyTorchLogits();
        System.err.println("Loaded PyTorch logits: " + pytorchLogits.length + " values");

        // Get AR logits
        // TODO: We need a way to get raw logits from AR model
        // This requires exposing the logits before argmax in AutoregressiveModel

        System.err.println("\n[INFO] To compare logits, we need to:");
        System.err.println("1. Expose raw logits from AutoregressiveModel");
        System.err.println("2. Or run model.forward() manually with position set to 0");
        System.err.println("3. Compare AR logits vs PyTorch logits for all vocab");

        // For now, just show PyTorch top predictions
        System.err.println("\n=== PyTorch Top 10 Predictions ===");
        int[] topIndices = getTopK(pytorchLogits, 10);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);

        for (int i = 0; i < topIndices.length; i++) {
            int idx = topIndices[i];
            String token = tokenizer.decode(new int[]{idx}).replace("\n", "\\\\n");
            System.err.println((i+1) + ". Token " + idx + ": \"" + token + "\" (logit=" +
                String.format("%.4f", pytorchLogits[idx]) + ")");
        }

        // Show specific tokens
        System.err.println("\n=== Specific Tokens ===");
        int[] tokensOfInterest = {198, 271, 49, 27};
        for (int token : tokensOfInterest) {
            String tokenStr = tokenizer.decode(new int[]{token}).replace("\n", "\\\\n");
            float logit = pytorchLogits[token];
            int rank = getRank(pytorchLogits, token);
            System.err.println("Token " + token + ": \"" + tokenStr + "\" - logit=" +
                String.format("%.4f", logit) + ", rank=" + rank);
        }
    }

    private float[] loadPyTorchLogits() throws IOException {
        try (FileChannel channel = FileChannel.open(Paths.get(PYTORCH_LOGITS),
                StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(buffer);
            buffer.flip();

            int vocabSize = buffer.getInt();
            float[] logits = new float[vocabSize];
            for (int i = 0; i < vocabSize; i++) {
                logits[i] = buffer.getFloat();
            }
            return logits;
        }
    }

    private int[] getTopK(float[] logits, int k) {
        Integer[] indices = new Integer[logits.length];
        for (int i = 0; i < logits.length; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, (a, b) -> Float.compare(logits[b], logits[a]));
        int[] result = new int[k];
        for (int i = 0; i < k; i++) {
            result[i] = indices[i];
        }
        return result;
    }

    private int getRank(float[] logits, int tokenId) {
        float targetLogit = logits[tokenId];
        int rank = 1;
        for (float logit : logits) {
            if (logit > targetLogit) rank++;
        }
        return rank;
    }
}
