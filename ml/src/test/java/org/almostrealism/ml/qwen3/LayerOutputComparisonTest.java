package org.almostrealism.ml.qwen3;

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

/**
 * Test to compare AR intermediate layer outputs vs PyTorch.
 *
 * Purpose: Identify at which layer the outputs start diverging significantly.
 * This helps narrow down where the bug is - in individual layers or in how they chain together.
 *
 * Strategy:
 * - Build partial models (1 layer, 2 layers, etc.)
 * - Compare output after each layer with PyTorch reference
 * - Track divergence progression
 */
public class LayerOutputComparisonTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void compareAfterEmbeddings() throws Exception {
        // Setup file logging
        String logFile = "/workspace/project/common/ml/test_output/layer_comparison_embeddings.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Embeddings Output Comparison ===\n");

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

        // Get embeddings for token 9707 ("Hello")
        int tokenId = 9707;
        PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
        PackedCollection tokenEmbedding = embeddings.range(
            shape(config.dim),
            tokenId * config.dim
        );

        // Load PyTorch reference
        float[] pytorchOutput = loadReferenceOutput("after_embeddings.bin");

        // Extract AR embeddings
        float[] arOutput = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            arOutput[i] = (float) tokenEmbedding.toDouble(i);
        }

        // Compare
        compareOutputs("Embeddings", pytorchOutput, arOutput);

        stateDict.destroy();
    }

    @Test
    public void compareAfter1Layer() throws Exception {
        // Setup file logging
        String logFile = "/workspace/project/common/ml/test_output/layer_comparison_1layer.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== 1-Layer Model Output Comparison ===\n");

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

        // Build a model with just 1 layer
        log("Building 1-layer model...");
        Model partial = buildPartialModel(config, stateDict, 1);

        // Get embeddings for token 9707 ("Hello")
        int tokenId = 9707;
        PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
        PackedCollection input = embeddings.range(
            shape(config.dim),
            tokenId * config.dim
        );

        log("Running forward pass through 1 layer...");
        org.almostrealism.model.CompiledModel compiled = partial.compile();
        PackedCollection output = compiled.forward(input);

        // Load PyTorch reference (after layer 0)
        float[] pytorchOutput = loadReferenceOutput("after_layer_0.bin");

        // Extract AR output
        float[] arOutput = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            arOutput[i] = (float) output.toDouble(i);
        }

        // Compare
        compareOutputs("After 1 layer", pytorchOutput, arOutput);

        stateDict.destroy();
    }

    @Test
    public void compareAfter2Layers() throws Exception {
        // Setup file logging
        String logFile = "/workspace/project/common/ml/test_output/layer_comparison_2layers.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== 2-Layer Model Output Comparison ===\n");

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

        // Build a model with 2 layers
        log("Building 2-layer model...");
        Model partial = buildPartialModel(config, stateDict, 2);

        // Get embeddings for token 9707 ("Hello")
        int tokenId = 9707;
        PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
        PackedCollection input = embeddings.range(
            shape(config.dim),
            tokenId * config.dim
        );

        log("Running forward pass through 2 layers...");
        org.almostrealism.model.CompiledModel compiled = partial.compile();
        PackedCollection output = compiled.forward(input);

        // Load PyTorch reference (after layer 1)
        float[] pytorchOutput = loadReferenceOutput("after_layer_1.bin");

        // Extract AR output
        float[] arOutput = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            arOutput[i] = (float) output.toDouble(i);
        }

        // Compare
        compareOutputs("After 2 layers", pytorchOutput, arOutput);

        stateDict.destroy();
    }

    /**
     * Build a partial model with only the specified number of transformer layers.
     */
    private Model buildPartialModel(Qwen3Config config, StateDictionary stateDict, int numLayers) {
        Model model = new Model(shape(config.dim));

        // Compute RoPE frequencies
        PackedCollection freqCis = computeRopeFreqs(config);

        // Position (always 0 for this test)
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        for (int i = 0; i < numLayers; i++) {
            String prefix = String.format("model.layers.%d", i);

            // Load all weights for this layer (same as Qwen3 does)
            PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
            PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");

            // Attention weights
            PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
            PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
            PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
            PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");

            // Attention biases
            PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
            PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
            PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");

            // QK-Norm weights
            PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
            PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

            // FFN weights (SwiGLU: gate, up, down)
            PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
            PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
            PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

            // Add complete transformer layer using the same method as Qwen3
            model.add(transformer(
                config.headCount,     // 14 query heads
                config.kvHeadCount,   // 2 KV heads (GQA)
                layerRmsAtt,          // Pre-attention norm
                layerWk, layerWv, layerWq, layerWo,  // Attention projections
                layerBk, layerBv, layerBq,           // Attention biases
                layerQkNormQ, layerQkNormK,          // QK-Norm weights
                freqCis,                             // RoPE frequencies
                layerRmsFfn,                         // Pre-FFN norm
                layerW1, layerW2, layerW3,           // FFN projections (SwiGLU)
                p(position)));                       // Current position
        }

        return model;
    }

    /**
     * Compute RoPE frequency embeddings (copied from Qwen3).
     * For testing, we only need a small number of positions.
     */
    private PackedCollection computeRopeFreqs(Qwen3Config config) {
        int headSize = config.headSize;
        // For testing single token, we only need position 0-9
        int seqLen = 10;  // Reduced from config.seqLen to avoid integer overflow
        double theta = config.ropeTheta;

        int freqDim = headSize / 2;
        PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));

        for (int pos = 0; pos < seqLen; pos++) {
            for (int i = 0; i < freqDim; i++) {
                double freq = 1.0 / Math.pow(theta, (double) (2 * i) / headSize);
                double val = pos * freq;
                freqCis.setMem(pos * freqDim * 2 + i * 2, Math.cos(val));
                freqCis.setMem(pos * freqDim * 2 + i * 2 + 1, Math.sin(val));
            }
        }

        return freqCis;
    }

    /**
     * Load PyTorch reference output from binary file.
     */
    private float[] loadReferenceOutput(String filename) throws IOException {
        String filepath = REFERENCE_DIR + "/" + filename;
        try (FileChannel channel = FileChannel.open(Paths.get(filepath),
                StandardOpenOption.READ)) {
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
        }
    }

    /**
     * Compare AR output vs PyTorch output and print detailed statistics.
     */
    private void compareOutputs(String stageName, float[] pytorchOutput, float[] arOutput) {
        log("=== " + stageName + " Comparison ===");

        if (pytorchOutput.length != arOutput.length) {
            log("ERROR: Size mismatch!");
            log("  PyTorch: " + pytorchOutput.length);
            log("  AR: " + arOutput.length);
            return;
        }

        int size = pytorchOutput.length;

        // Compute statistics
        double sumAbsDiff = 0.0;
        double sumSqDiff = 0.0;
        double maxAbsDiff = 0.0;
        int maxDiffIdx = -1;

        double sumPytorch = 0.0;
        double sumAR = 0.0;

        for (int i = 0; i < size; i++) {
            double diff = Math.abs(pytorchOutput[i] - arOutput[i]);
            sumAbsDiff += diff;
            sumSqDiff += diff * diff;

            if (diff > maxAbsDiff) {
                maxAbsDiff = diff;
                maxDiffIdx = i;
            }

            sumPytorch += pytorchOutput[i];
            sumAR += arOutput[i];
        }

        double meanAbsDiff = sumAbsDiff / size;
        double rmse = Math.sqrt(sumSqDiff / size);
        double meanPytorch = sumPytorch / size;
        double meanAR = sumAR / size;

        log(String.format("Mean Absolute Difference: %.6f", meanAbsDiff));
        log(String.format("RMSE: %.6f", rmse));
        log(String.format("Max Absolute Difference: %.6f at index %d", maxAbsDiff, maxDiffIdx));
        log(String.format("PyTorch mean: %.6f", meanPytorch));
        log(String.format("AR mean: %.6f", meanAR));
        log("");

        // Show first 10 values
        log("First 10 values comparison:");
        log(String.format("%-5s %-15s %-15s %-15s", "Idx", "PyTorch", "AR", "Diff"));
        log("-".repeat(55));
        for (int i = 0; i < Math.min(10, size); i++) {
            log(String.format("%-5d %-15.6f %-15.6f %-15.6f",
                i, pytorchOutput[i], arOutput[i],
                pytorchOutput[i] - arOutput[i]));
        }
        log("");

        // Verdict
        if (meanAbsDiff < 1e-4) {
            log("[EXCELLENT] Outputs match within 1e-4 tolerance");
        } else if (meanAbsDiff < 1e-2) {
            log("[GOOD] Outputs match within 1e-2 tolerance");
        } else if (meanAbsDiff < 0.1) {
            log("[WARNING] Small divergence detected (< 0.1)");
        } else if (meanAbsDiff < 1.0) {
            log("[ALERT] Moderate divergence detected (< 1.0)");
        } else {
            log("[CRITICAL] Large divergence detected (>= 1.0)");
        }
    }
}
