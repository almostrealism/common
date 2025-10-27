package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Model;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive test to measure error accumulation across transformer layers.
 *
 * Generates a detailed markdown report showing how errors grow with each additional layer.
 * This helps determine if numerical precision is the core issue vs a bug in implementation.
 */
public class ErrorAccumulationAnalysisTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";
    private static final String REPORT_FILE = "/workspace/project/common/ml/test_output/error_accumulation_report.md";

    /**
     * Statistics for a single layer's output comparison.
     */
    static class LayerStats {
        int layerIndex;
        double meanAbsError;
        double rmse;
        double maxAbsError;
        int maxErrorIndex;
        double pytorchMean;
        double arMean;

        LayerStats(int layerIndex) {
            this.layerIndex = layerIndex;
        }
    }

    @Test
    public void analyzeErrorAccumulation() throws Exception {
        // Setup file logging
        String logFile = "/workspace/project/common/ml/test_output/error_accumulation_analysis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Error Accumulation Analysis ===\n");
        log("Testing all 24 layers to measure error growth per layer\n");

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
        PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");
        PackedCollection<?> input = embeddings.range(
            shape(config.dim),
            tokenId * config.dim
        );

        // Compute RoPE frequencies once
        PackedCollection<?> freqCis = computeRopeFreqs(config);

        // Position tracker (always 0 for first token)
        PackedCollection<?> position = new PackedCollection<>(shape(1));
        position.setMem(0, 0.0);

        // Storage for all layer statistics
        List<LayerStats> allStats = new ArrayList<>();

        // Test all 24 layers to get accurate per-layer growth measurements
        int[] layersToTest = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                              13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24};

        for (int numLayers : layersToTest) {
            log(String.format("\n--- Testing %d layer(s) ---", numLayers));

            // Build partial model
            Model partial = buildPartialModel(config, stateDict, numLayers, freqCis, position);

            // Compile and run
            org.almostrealism.model.CompiledModel compiled = partial.compile();
            PackedCollection<?> output = compiled.forward(input);

            // Load PyTorch reference for this layer
            String refFile = String.format("after_layer_%d.bin", numLayers - 1);
            float[] pytorchOutput = loadReferenceOutput(refFile);

            if (pytorchOutput == null) {
                log(String.format("WARNING: No reference file found for layer %d (%s)", numLayers, refFile));
                continue;
            }

            // Extract AR output
            float[] arOutput = new float[config.dim];
            for (int i = 0; i < config.dim; i++) {
                arOutput[i] = (float) output.toDouble(i);
            }

            // Compute statistics
            LayerStats stats = computeStats(numLayers - 1, pytorchOutput, arOutput);
            allStats.add(stats);

            log(String.format("  Mean Abs Error: %.6f", stats.meanAbsError));
            log(String.format("  RMSE: %.6f", stats.rmse));
            log(String.format("  Max Error: %.6f", stats.maxAbsError));
        }

        // Generate markdown report
        generateMarkdownReport(allStats, config);

        stateDict.destroy();

        log("\n=== Analysis Complete ===");
        log("Report written to: " + REPORT_FILE);
    }

    /**
     * Build a partial model with only the specified number of transformer layers.
     */
    private Model buildPartialModel(Qwen3Config config, StateDictionary stateDict,
                                   int numLayers, PackedCollection<?> freqCis,
                                   PackedCollection<?> position) {
        Model model = new Model(shape(config.dim));

        for (int i = 0; i < numLayers; i++) {
            String prefix = String.format("model.layers.%d", i);

            // Load all weights for this layer (same as Qwen3 does)
            PackedCollection<?> layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
            PackedCollection<?> layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");

            // Attention weights
            PackedCollection<?> layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
            PackedCollection<?> layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
            PackedCollection<?> layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
            PackedCollection<?> layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");

            // Attention biases
            PackedCollection<?> layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
            PackedCollection<?> layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
            PackedCollection<?> layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");

            // QK-Norm weights
            PackedCollection<?> layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
            PackedCollection<?> layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

            // FFN weights (SwiGLU: gate, up, down)
            PackedCollection<?> layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
            PackedCollection<?> layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
            PackedCollection<?> layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

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
     * Compute statistics comparing PyTorch and AR outputs.
     */
    private LayerStats computeStats(int layerIndex, float[] pytorch, float[] ar) {
        LayerStats stats = new LayerStats(layerIndex);

        double sumAbsError = 0.0;
        double sumSqError = 0.0;
        double maxAbsError = 0.0;
        int maxErrorIndex = 0;
        double pytorchSum = 0.0;
        double arSum = 0.0;

        for (int i = 0; i < pytorch.length; i++) {
            double diff = Math.abs(pytorch[i] - ar[i]);
            sumAbsError += diff;
            sumSqError += diff * diff;

            if (diff > maxAbsError) {
                maxAbsError = diff;
                maxErrorIndex = i;
            }

            pytorchSum += pytorch[i];
            arSum += ar[i];
        }

        stats.meanAbsError = sumAbsError / pytorch.length;
        stats.rmse = Math.sqrt(sumSqError / pytorch.length);
        stats.maxAbsError = maxAbsError;
        stats.maxErrorIndex = maxErrorIndex;
        stats.pytorchMean = pytorchSum / pytorch.length;
        stats.arMean = arSum / pytorch.length;

        return stats;
    }

    /**
     * Generate a detailed markdown report with error accumulation table.
     */
    private void generateMarkdownReport(List<LayerStats> allStats, Qwen3Config config) throws IOException {
        try (PrintWriter writer = new PrintWriter(REPORT_FILE)) {
            writer.println("# Error Accumulation Analysis Report");
            writer.println();
            writer.println("**Model**: Qwen3-Instruct-2507 4B");
            writer.println("**Test Token**: 9707 (\"Hello\")");
            writer.println("**Position**: 0 (first token)");
            writer.println();

            writer.println("## Configuration");
            writer.println();
            writer.println("```");
            writer.println("Dimension: " + config.dim);
            writer.println("Hidden Dimension: " + config.hiddenDim);
            writer.println("Heads: " + config.headCount);
            writer.println("KV Heads: " + config.kvHeadCount);
            writer.println("Layers Tested: " + allStats.size());
            writer.println("```");
            writer.println();

            writer.println("## Error Accumulation Table");
            writer.println();
            writer.println("| Layers | Mean Abs Error | RMSE | Max Error | Growth Rate | Status |");
            writer.println("|--------|----------------|------|-----------|-------------|--------|");

            double previousMeanError = 0.0;
            for (LayerStats stats : allStats) {
                double growthRate = (previousMeanError > 0)
                    ? (stats.meanAbsError / previousMeanError)
                    : 1.0;

                String status = stats.meanAbsError < 0.001 ? "[OK] Excellent" :
                               stats.meanAbsError < 0.01 ? "[OK] Good" :
                               stats.meanAbsError < 0.1 ? "[WARN] Acceptable" :
                               stats.meanAbsError < 1.0 ? "[WARN] Degraded" :
                               "[ERR] Poor";

                writer.printf("| %d | %.6f | %.6f | %.6f | %.3fx | %s |%n",
                    stats.layerIndex + 1,
                    stats.meanAbsError,
                    stats.rmse,
                    stats.maxAbsError,
                    growthRate,
                    status);

                previousMeanError = stats.meanAbsError;
            }

            writer.println();
            writer.println("## Analysis");
            writer.println();

            // Calculate average growth rate
            double totalGrowth = 0.0;
            int growthCount = 0;
            previousMeanError = 0.0;
            for (LayerStats stats : allStats) {
                if (previousMeanError > 0) {
                    totalGrowth += (stats.meanAbsError / previousMeanError);
                    growthCount++;
                }
                previousMeanError = stats.meanAbsError;
            }
            double avgGrowthRate = growthCount > 0 ? totalGrowth / growthCount : 1.0;

            writer.println("### Error Growth Pattern");
            writer.println();
            writer.printf("- **Average growth rate per layer**: %.3fx%n", avgGrowthRate);

            if (!allStats.isEmpty()) {
                LayerStats first = allStats.get(0);
                LayerStats last = allStats.get(allStats.size() - 1);
                double totalGrowthFactor = last.meanAbsError / first.meanAbsError;

                writer.printf("- **Total error growth (layer 1 -> %d)**: %.1fx%n",
                    last.layerIndex + 1, totalGrowthFactor);
                writer.printf("- **Initial error (1 layer)**: %.6f%n", first.meanAbsError);
                writer.printf("- **Final error (%d layers)**: %.6f%n",
                    last.layerIndex + 1, last.meanAbsError);
            }

            writer.println();
            writer.println("### Conclusion");
            writer.println();

            if (avgGrowthRate < 1.2) {
                writer.println("[OK] **Error accumulation is minimal** (< 1.2x per layer on average).");
                writer.println("The errors are likely due to minor numerical precision differences that compound slightly across layers.");
                writer.println("This level of error accumulation is typical and may not be worth extensive optimization effort.");
            } else if (avgGrowthRate < 2.0) {
                writer.println("[WARN] **Moderate error accumulation detected** (1.2x - 2.0x per layer).");
                writer.println("While not critical, there may be room for optimization in numerical precision.");
                writer.println("Consider investigating the most error-prone operations.");
            } else {
                writer.println("[ERROR] **Significant error accumulation detected** (> 2.0x per layer).");
                writer.println("This suggests a potential implementation issue beyond normal numerical precision.");
                writer.println("**Action Required**: Investigate operations with highest error contribution.");
            }

            writer.println();
            writer.println("## Next Steps");
            writer.println();

            if (avgGrowthRate >= 2.0) {
                writer.println("1. Profile which operations contribute most to error accumulation");
                writer.println("2. Check for numerical instabilities in attention mechanism");
                writer.println("3. Verify softmax and normalization implementations");
            } else {
                writer.println("1. Accept current numerical precision as platform-dependent limitation");
                writer.println("2. Focus on functional correctness rather than exact numerical matching");
                writer.println("3. Consider using tolerance-based validation instead of exact matching");
            }

            writer.println();
            writer.println("---");
            writer.println("*Generated by ErrorAccumulationAnalysisTest*");
        }

        log("Markdown report generated: " + REPORT_FILE);
    }

    /**
     * Load PyTorch reference output from binary file.
     */
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

    /**
     * Compute RoPE frequency embeddings (reduced seqLen to avoid overflow).
     */
    private PackedCollection<?> computeRopeFreqs(Qwen3Config config) {
        int headSize = config.headSize;
        // For testing single token, we only need position 0-9
        int seqLen = 10;  // Reduced from config.seqLen to avoid integer overflow
        double theta = config.ropeTheta;

        int freqDim = headSize / 2;
        PackedCollection<?> freqCis = new PackedCollection<>(shape(seqLen, freqDim, 2));

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
}
