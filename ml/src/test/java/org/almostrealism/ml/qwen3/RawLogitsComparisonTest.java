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
 * Narrowly focused test to expose and compare raw logits from AR model vs PyTorch.
 *
 * This test isolates the logits computation by:
 * 1. Running a single forward pass (position 0, token 9707 "Hello")
 * 2. Extracting raw logits before argmax (all 151,936 values)
 * 3. Comparing against PyTorch reference
 * 4. Providing detailed statistics about divergence
 *
 * Purpose: Identify if the bug is in final projection or all logits.
 */
public class RawLogitsComparisonTest implements AttentionFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";
    private static final String PYTORCH_LOGITS = "/workspace/project/common/ml/qwen3_reference/full_model_logits/position_0_logits.bin";

    @Test
    public void compareRawLogits() throws Exception {
        System.err.println("\n=== Raw Logits Comparison Test ===\n");

        // Load PyTorch reference logits
        float[] pytorchLogits = loadPyTorchLogits();
        System.err.println("Loaded PyTorch reference: " + pytorchLogits.length + " logits\n");

        // Load AR model
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

        // Run single forward pass manually
        System.err.println("Running forward pass for token 9707 ('Hello') at position 0...\n");
        float[] arLogits = runSingleForwardPass(model, 9707, 0);

        System.err.println("Extracted AR logits: " + arLogits.length + " values\n");

        // Compare logits
        compareLogits(pytorchLogits, arLogits, tokenizer);
    }

    /**
     * Run a single forward pass through the model to get raw logits.
     *
     * @param model The Qwen3 model
     * @param tokenId Input token ID
     * @param position Position in sequence
     * @return Raw logits (vocab_size values)
     */
    private float[] runSingleForwardPass(Qwen3 model, int tokenId, int position) {
        // Get token embedding
        PackedCollection<?> tokenEmbeddings = model.getTokenEmbeddings();
        PackedCollection<?> input = tokenEmbeddings.range(
            shape(model.getConfig().dim),
            tokenId * model.getConfig().dim
        );

        // Set position (for RoPE)
        // Note: AutoregressiveModel sets position via step -> position.setMem((double) step)
        // We need to ensure position is set to 0

        // Get compiled model
        org.almostrealism.model.CompiledModel compiled = model.getCompiledModel();

        // Set position to 0 (important for RoPE)
        // Note: The position PackedCollection is created inside Qwen3.model() and set via AutoregressiveModel
        // When calling compiled model directly, position might not be 0
        // We'll use the AutoregressiveModel approach instead

        // Alternative: Use AutoregressiveModel to get logits
        // Set model to position 0 with our token
        model.getAutoregressiveModel().setCurrentStep(0);
        model.getAutoregressiveModel().setCurrentToken(tokenId);
        model.getAutoregressiveModel().setTemperature(0.0);  // Greedy (won't affect logits, just selection)

        // Call next() to run forward pass and get logits
        // We need to capture logits before argmax
        // Unfortunately AutoregressiveModel doesn't expose raw logits

        // Let's try direct approach: model.forward() should return logits
        PackedCollection<?> output = compiled.forward(input);

        // Extract logits - use toDouble() to get the raw values
        int vocabSize = model.getConfig().vocabSize;
        float[] logits = new float[vocabSize];

        // Output shape should be (vocabSize,) or (1, vocabSize)
        System.err.println("Output shape: " + output.getShape());
        System.err.println("Output mem length: " + output.getMemLength());

        // Extract directly from memory
        for (int i = 0; i < Math.min(vocabSize, output.getMemLength()); i++) {
            logits[i] = (float) output.toDouble(i);
        }

        return logits;
    }

    /**
     * Load PyTorch reference logits from binary file.
     */
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

    /**
     * Compare AR logits vs PyTorch logits and print detailed statistics.
     */
    private void compareLogits(float[] pytorchLogits, float[] arLogits, Qwen3Tokenizer tokenizer) {
        if (pytorchLogits.length != arLogits.length) {
            System.err.println("ERROR: Vocab size mismatch!");
            System.err.println("  PyTorch: " + pytorchLogits.length);
            System.err.println("  AR: " + arLogits.length);
            return;
        }

        int vocabSize = pytorchLogits.length;

        // Compute statistics
        double sumAbsDiff = 0.0;
        double sumSqDiff = 0.0;
        double maxAbsDiff = 0.0;
        int maxDiffToken = -1;

        for (int i = 0; i < vocabSize; i++) {
            double diff = Math.abs(pytorchLogits[i] - arLogits[i]);
            sumAbsDiff += diff;
            sumSqDiff += diff * diff;

            if (diff > maxAbsDiff) {
                maxAbsDiff = diff;
                maxDiffToken = i;
            }
        }

        double meanAbsDiff = sumAbsDiff / vocabSize;
        double rmse = Math.sqrt(sumSqDiff / vocabSize);

        System.err.println("=== Logits Comparison Statistics ===");
        System.err.println(String.format("Mean Absolute Difference: %.6f", meanAbsDiff));
        System.err.println(String.format("RMSE: %.6f", rmse));
        System.err.println(String.format("Max Absolute Difference: %.6f at token %d", maxAbsDiff, maxDiffToken));
        System.err.println();

        // Show top-K comparison
        System.err.println("=== Top 10 Predictions Comparison ===");
        System.err.println(String.format("%-6s %-10s %-15s %-15s %-10s",
            "Rank", "Token", "PyTorch Logit", "AR Logit", "Diff"));
        System.err.println("-".repeat(70));

        int[] pytorchTopK = getTopK(pytorchLogits, 10);
        int[] arTopK = getTopK(arLogits, 10);

        // Show PyTorch top 10
        System.err.println("\nPyTorch Top 10:");
        for (int i = 0; i < 10; i++) {
            int token = pytorchTopK[i];
            String tokenStr = tokenizer.decode(new int[]{token}).replace("\n", "\\n");
            float ptLogit = pytorchLogits[token];
            float arLogit = arLogits[token];
            int arRank = getRank(arLogits, token);

            System.err.println(String.format("%-6d %-10d %-15.4f %-15.4f %-10.4f (AR rank: %d) \"%s\"",
                i+1, token, ptLogit, arLogit, ptLogit - arLogit, arRank, tokenStr));
        }

        // Show AR top 10
        System.err.println("\nAR Top 10:");
        for (int i = 0; i < 10; i++) {
            int token = arTopK[i];
            String tokenStr = tokenizer.decode(new int[]{token}).replace("\n", "\\n");
            float ptLogit = pytorchLogits[token];
            float arLogit = arLogits[token];
            int ptRank = getRank(pytorchLogits, token);

            System.err.println(String.format("%-6d %-10d %-15.4f %-15.4f %-10.4f (PT rank: %d) \"%s\"",
                i+1, token, ptLogit, arLogit, ptLogit - arLogit, ptRank, tokenStr));
        }

        // Show specific tokens of interest
        System.err.println("\n=== Specific Tokens Analysis ===");
        int[] tokensOfInterest = {271, 198, 49, 27};  // \n\n, \n, R, <
        for (int token : tokensOfInterest) {
            String tokenStr = tokenizer.decode(new int[]{token}).replace("\n", "\\n");
            float ptLogit = pytorchLogits[token];
            float arLogit = arLogits[token];
            int ptRank = getRank(pytorchLogits, token);
            int arRank = getRank(arLogits, token);

            System.err.println(String.format("Token %d \"%s\":", token, tokenStr));
            System.err.println(String.format("  PyTorch: logit=%.4f, rank=%d", ptLogit, ptRank));
            System.err.println(String.format("  AR:      logit=%.4f, rank=%d", arLogit, arRank));
            System.err.println(String.format("  Diff:    %.4f", ptLogit - arLogit));
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
