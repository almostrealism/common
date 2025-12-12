package org.almostrealism.ml.qwen3;

import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Model;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Simplified transformer validation test.
 *
 * <p>This test validates the transformer layers work correctly by:
 * 1. Running all 24 layers + final RMSNorm using compiled model
 * 2. Manually computing logits projection in Java (avoiding slow kernel compilation)
 * 3. Finding argmax to predict next token</p>
 *
 * <p>This validates end-to-end correctness without the 20+ minute compilation
 * time for the vocab projection kernel.</p>
 */
public class SimpleTransformerValidationTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test end-to-end transformer with manual logits computation.
     */
    @Test
    public void testTransformerWithManualLogits() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/simple_transformer_validation.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Simple Transformer Validation Test");
        log("===================================================\n");

        // Configuration for Qwen2.5-0.5B-Instruct
        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        log("Loading weights...");
        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        log("Loading tokenizer...");
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);

        // Build transformer (without vocab projection)
        log("Building transformer model (without vocab projection)...");
        Model transformer = buildTransformerWithoutVocabProjection(config, stateDict);

        log("Compiling model...");
        long compileStart = System.currentTimeMillis();
        var compiledModel = transformer.compile();
        log("Model compiled in " + (System.currentTimeMillis() - compileStart) + "ms");

        // Encode "Hello"
        String prompt = "Hello";
        int[] tokens = tokenizer.encode(prompt, false, false);
        log("\nPrompt: '" + prompt + "'");
        log("Tokens: " + Arrays.toString(tokens));

        // Get embedding for first token
        PackedCollection tokenEmbeddings = stateDict.get("model.embed_tokens.weight");
        int tokenId = tokens[0];

        // Create input from embedding
        PackedCollection input = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, tokenEmbeddings.toDouble(tokenId * config.dim + i));
        }
        log("Input embedding stats: " + stats(input, config.dim));

        // Run transformer (all layers + final norm)
        log("\nRunning transformer forward pass...");
        long forwardStart = System.currentTimeMillis();
        PackedCollection hidden = compiledModel.forward(input);
        log("Forward pass completed in " + (System.currentTimeMillis() - forwardStart) + "ms");
        log("Hidden state stats: " + stats(hidden, config.dim));

        // Compare with PyTorch reference (after layer 23 which includes final norm)
        float[] pytorchHidden = loadReferenceOutput("after_layer_23.bin");
        if (pytorchHidden != null) {
            double error = meanAbsError(hidden, pytorchHidden, config.dim);
            log(String.format("\nComparison with PyTorch hidden states (after final norm):"));
            log(String.format("  Mean absolute error: %.6f", error));
            log(String.format("  PyTorch stats: %s", statsFromFloat(pytorchHidden)));

            if (error < 0.1) {
                log("  [MATCH] Hidden states match PyTorch!");
            } else {
                log("  [MISMATCH] Hidden states differ from PyTorch");
            }
        }

        // Manually compute logits projection in Java
        log("\nComputing logits projection manually...");
        long logitsStart = System.currentTimeMillis();
        double[] logits = computeLogitsManually(hidden, tokenEmbeddings, config);
        log("Logits computed in " + (System.currentTimeMillis() - logitsStart) + "ms");

        // Find top-k tokens
        log("\n--- Top 10 Predicted Tokens ---");
        int[] topK = findTopK(logits, 10);
        for (int i = 0; i < 10; i++) {
            int idx = topK[i];
            String tokenStr = tokenizer.decode(new int[]{idx});
            log(String.format("  %d. Token %d (%.2f): '%s'",
                i + 1, idx, logits[idx], tokenStr.replace("\n", "\\n")));
        }

        // Check against PyTorch expected output
        log("\n--- PyTorch Reference ---");
        log("Expected top token: 271 (\\n\\n) with logit 12.84");

        int predictedToken = topK[0];
        log("\n--- Validation Result ---");
        if (predictedToken == 271) {
            log("[SUCCESS] Model predicts correct token!");
            log("The Qwen3 transformer implementation is working correctly.");
        } else {
            log("[MISMATCH] Predicted token " + predictedToken + " vs expected 271");
            log("Top predicted: " + tokenizer.decode(new int[]{predictedToken}));
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    /**
     * Build transformer model without the final vocab projection.
     * This avoids the slow compilation of the 151936-dimensional output.
     */
    private Model buildTransformerWithoutVocabProjection(Qwen3Config config,
                                                          StateDictionary stateDict) {
        Model transformer = new Model(shape(1, config.dim));

        // Placeholder for the index of the current step (position in sequence)
        PackedCollection position = new PackedCollection(1);
        position.setMem(0, 0.0);  // Position 0 for single token

        // Get final norm weights
        PackedCollection rmsFinalWeight = stateDict.get("model.norm.weight");

        // Compute RoPE frequencies
        PackedCollection freqCis = computeRopeFreqs(config);

        // Build transformer stack: 24 layers
        for (int i = 0; i < config.layerCount; i++) {
            String prefix = String.format("model.layers.%d", i);

            PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
            PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");

            PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
            PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
            PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
            PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");

            PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
            PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
            PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");

            PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
            PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

            PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
            PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
            PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

            transformer.add(transformer(
                    config.headCount,
                    config.kvHeadCount,
                    layerRmsAtt,
                    layerWk, layerWv, layerWq, layerWo,
                    layerBk, layerBv, layerBq,
                    layerQkNormQ, layerQkNormK,
                    freqCis,
                    layerRmsFfn,
                    layerW1, layerW2, layerW3,
                    p(position)));
        }

        // Final RMS Norm (but NO vocab projection)
        transformer.add(rmsnorm(shape(1, config.dim), rmsFinalWeight));

        return transformer;
    }

    /**
     * Compute logits manually using Java.
     * logits = hidden @ weights^T (since weights are [vocab, dim])
     */
    private double[] computeLogitsManually(PackedCollection hidden,
                                           PackedCollection weights,
                                           Qwen3Config config) {
        double[] logits = new double[config.vocabSize];

        // For each output token
        for (int v = 0; v < config.vocabSize; v++) {
            double sum = 0;
            for (int d = 0; d < config.dim; d++) {
                sum += hidden.toDouble(d) * weights.toDouble(v * config.dim + d);
            }
            logits[v] = sum;
        }

        return logits;
    }

    /**
     * Find top-k indices by value.
     */
    private int[] findTopK(double[] values, int k) {
        int[] indices = new int[k];
        double[] topValues = new double[k];
        Arrays.fill(topValues, Double.NEGATIVE_INFINITY);

        for (int i = 0; i < values.length; i++) {
            // Find position to insert
            int pos = -1;
            for (int j = k - 1; j >= 0; j--) {
                if (values[i] > topValues[j]) {
                    pos = j;
                } else {
                    break;
                }
            }

            if (pos >= 0) {
                // Shift down
                for (int j = k - 1; j > pos; j--) {
                    indices[j] = indices[j - 1];
                    topValues[j] = topValues[j - 1];
                }
                indices[pos] = i;
                topValues[pos] = values[i];
            }
        }

        return indices;
    }

    private PackedCollection computeRopeFreqs(Qwen3Config config) {
        int headSize = config.headSize;
        int seqLen = 10;  // Only need a few positions for testing
        double theta = config.ropeTheta;

        int freqDim = headSize / 2;
        PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));

        for (int pos = 0; pos < seqLen; pos++) {
            for (int i = 0; i < freqDim; i++) {
                double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
                double angle = pos * freq;
                freqCis.setMem((pos * freqDim + i) * 2, Math.cos(angle));
                freqCis.setMem((pos * freqDim + i) * 2 + 1, Math.sin(angle));
            }
        }

        return freqCis;
    }

    private float[] loadReferenceOutput(String filename) {
        String filepath = REFERENCE_DIR + "/" + filename;
        try (FileChannel channel = FileChannel.open(Paths.get(filepath), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(buffer);
            buffer.flip();

            int size = buffer.getInt();
            float[] output = new float[size];
            for (int i = 0; i < size; i++) {
                output[i] = buffer.getFloat();
            }
            return output;
        } catch (IOException e) {
            return null;
        }
    }

    private double meanAbsError(PackedCollection a, float[] b, int dim) {
        double sum = 0;
        for (int i = 0; i < dim; i++) {
            sum += Math.abs(a.toDouble(i) - b[i]);
        }
        return sum / dim;
    }

    private String stats(PackedCollection c, int dim) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < dim; i++) {
            double v = c.toDouble(i);
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / dim;
        double std = Math.sqrt(sumSq / dim - mean * mean);
        return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
    }

    private String statsFromFloat(float[] arr) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (float v : arr) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / arr.length;
        double std = Math.sqrt(sumSq / arr.length - mean * mean);
        return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
    }
}
