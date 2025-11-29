package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes layer configurations to identify what makes layers 2, 22, and 23 special.
 * These layers show anomalous error growth in the error accumulation analysis.
 */
public class LayerConfigurationTest implements ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REPORT_FILE = "/workspace/project/common/ml/test_output/layer_config_analysis.md";

    // Problematic layers identified from error accumulation analysis
    private static final int[] PROBLEMATIC_LAYERS = {2, 22, 23};

    static class LayerConfig {
        int layerIndex;
        boolean hasQKNorm;
        boolean hasQBias;
        boolean hasKBias;
        boolean hasVBias;
        double qWeightMean;
        double qWeightStd;
        double kWeightMean;
        double kWeightStd;
        double vWeightMean;
        double vWeightStd;
        double oWeightMean;
        double oWeightStd;
        double gateWeightMean;
        double gateWeightStd;
        double upWeightMean;
        double upWeightStd;
        double downWeightMean;
        double downWeightStd;
        double inputNormMean;
        double inputNormStd;
        double postAttentionNormMean;
        double postAttentionNormStd;
        long totalParameters;
        boolean isProblematic;

        LayerConfig(int index) {
            this.layerIndex = index;
            this.isProblematic = isProblematicLayer(index);
        }

        private boolean isProblematicLayer(int index) {
            for (int prob : PROBLEMATIC_LAYERS) {
                if (index == prob) return true;
            }
            return false;
        }
    }

    @Test
    public void analyzeLayerConfigurations() throws Exception {
        // Setup file logging
        String logFile = "/workspace/project/common/ml/test_output/layer_config_analysis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer Configuration Analysis ===\n");
        log("Analyzing all 24 layers to identify anomalies in problematic layers (2, 22, 23)\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        List<LayerConfig> configs = new ArrayList<>();

        // Analyze each layer
        for (int i = 0; i < 24; i++) {
            log(String.format("\n--- Analyzing Layer %d %s---", i,
                isProblematicLayer(i) ? "[PROBLEMATIC] " : ""));

            LayerConfig config = analyzeLayer(i, stateDict);
            configs.add(config);

            // Print summary for problematic layers
            if (config.isProblematic) {
                log(String.format("  [WARNING] Layer %d is problematic with %.1fx error growth", i,
                    i == 2 ? 15.010 : i == 22 ? 3.813 : 20.187));
                log(String.format("  Has QK-Norm: %s", config.hasQKNorm));
                log(String.format("  Has Q/K/V biases: Q=%s, K=%s, V=%s",
                    config.hasQBias, config.hasKBias, config.hasVBias));
                log(String.format("  Q weight stats: mean=%.6f, std=%.6f",
                    config.qWeightMean, config.qWeightStd));
            }
        }

        // Generate comparison report
        generateComparisonReport(configs);

        // Check for patterns
        analyzePatterns(configs);

        stateDict.destroy();

        log("\n=== Analysis Complete ===");
        log("Report written to: " + REPORT_FILE);
    }

    private LayerConfig analyzeLayer(int layerIndex, StateDictionary stateDict) {
        LayerConfig config = new LayerConfig(layerIndex);
        String prefix = "model.layers." + layerIndex;

        // Check for QK-Norm (special normalization)
        config.hasQKNorm = checkExists(stateDict, prefix + ".self_attn.q_norm.weight");

        // Check for biases
        config.hasQBias = checkExists(stateDict, prefix + ".self_attn.q_proj.bias");
        config.hasKBias = checkExists(stateDict, prefix + ".self_attn.k_proj.bias");
        config.hasVBias = checkExists(stateDict, prefix + ".self_attn.v_proj.bias");

        // Analyze attention weights
        config.qWeightMean = computeMean(stateDict.get(prefix + ".self_attn.q_proj.weight"));
        config.qWeightStd = computeStd(stateDict.get(prefix + ".self_attn.q_proj.weight"));
        config.kWeightMean = computeMean(stateDict.get(prefix + ".self_attn.k_proj.weight"));
        config.kWeightStd = computeStd(stateDict.get(prefix + ".self_attn.k_proj.weight"));
        config.vWeightMean = computeMean(stateDict.get(prefix + ".self_attn.v_proj.weight"));
        config.vWeightStd = computeStd(stateDict.get(prefix + ".self_attn.v_proj.weight"));
        config.oWeightMean = computeMean(stateDict.get(prefix + ".self_attn.o_proj.weight"));
        config.oWeightStd = computeStd(stateDict.get(prefix + ".self_attn.o_proj.weight"));

        // Analyze FFN weights
        config.gateWeightMean = computeMean(stateDict.get(prefix + ".mlp.gate_proj.weight"));
        config.gateWeightStd = computeStd(stateDict.get(prefix + ".mlp.gate_proj.weight"));
        config.upWeightMean = computeMean(stateDict.get(prefix + ".mlp.up_proj.weight"));
        config.upWeightStd = computeStd(stateDict.get(prefix + ".mlp.up_proj.weight"));
        config.downWeightMean = computeMean(stateDict.get(prefix + ".mlp.down_proj.weight"));
        config.downWeightStd = computeStd(stateDict.get(prefix + ".mlp.down_proj.weight"));

        // Analyze normalization weights
        config.inputNormMean = computeMean(stateDict.get(prefix + ".input_layernorm.weight"));
        config.inputNormStd = computeStd(stateDict.get(prefix + ".input_layernorm.weight"));
        config.postAttentionNormMean = computeMean(stateDict.get(prefix + ".post_attention_layernorm.weight"));
        config.postAttentionNormStd = computeStd(stateDict.get(prefix + ".post_attention_layernorm.weight"));

        // Count total parameters
        config.totalParameters = countParameters(stateDict, prefix);

        return config;
    }

    private boolean checkExists(StateDictionary stateDict, String key) {
        try {
            PackedCollection tensor = stateDict.get(key);
            return tensor != null;
        } catch (Exception e) {
            return false;
        }
    }

    private double computeMean(PackedCollection tensor) {
        if (tensor == null) return 0.0;
        double sum = 0.0;
        int count = tensor.getShape().getSize();
        for (int i = 0; i < count; i++) {
            sum += tensor.toDouble(i);
        }
        return sum / count;
    }

    private double computeStd(PackedCollection tensor) {
        if (tensor == null) return 0.0;
        double mean = computeMean(tensor);
        double sumSq = 0.0;
        int count = tensor.getShape().getSize();
        for (int i = 0; i < count; i++) {
            double diff = tensor.toDouble(i) - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / count);
    }

    private long countParameters(StateDictionary stateDict, String prefix) {
        long count = 0;
        String[] weightKeys = {
            ".input_layernorm.weight",
            ".self_attn.q_proj.weight", ".self_attn.q_proj.bias",
            ".self_attn.k_proj.weight", ".self_attn.k_proj.bias",
            ".self_attn.v_proj.weight", ".self_attn.v_proj.bias",
            ".self_attn.o_proj.weight",
            ".self_attn.q_norm.weight", ".self_attn.k_norm.weight",
            ".post_attention_layernorm.weight",
            ".mlp.gate_proj.weight",
            ".mlp.up_proj.weight",
            ".mlp.down_proj.weight"
        };

        for (String key : weightKeys) {
            try {
                PackedCollection tensor = stateDict.get(prefix + key);
                if (tensor != null) {
                    count += tensor.getShape().getSize();
                }
            } catch (Exception e) {
                // Weight doesn't exist
            }
        }
        return count;
    }

    private boolean isProblematicLayer(int index) {
        for (int prob : PROBLEMATIC_LAYERS) {
            if (index == prob) return true;
        }
        return false;
    }

    private void analyzePatterns(List<LayerConfig> configs) {
        log("\n=== Pattern Analysis ===\n");

        // Check if problematic layers have unique configurations
        boolean problematicHaveQKNorm = true;
        boolean normalHaveQKNorm = true;
        boolean problematicHaveBias = true;
        boolean normalHaveBias = true;

        for (LayerConfig config : configs) {
            if (config.isProblematic) {
                problematicHaveQKNorm &= config.hasQKNorm;
                problematicHaveBias &= config.hasQBias;
            } else {
                normalHaveQKNorm &= config.hasQKNorm;
                normalHaveBias &= config.hasQBias;
            }
        }

        log("QK-Norm presence:");
        log("  Problematic layers all have QK-Norm: " + problematicHaveQKNorm);
        log("  Normal layers all have QK-Norm: " + normalHaveQKNorm);

        log("\nBias presence:");
        log("  Problematic layers all have Q bias: " + problematicHaveBias);
        log("  Normal layers all have Q bias: " + normalHaveBias);

        // Check weight magnitude differences
        double avgProblematicQStd = 0;
        double avgNormalQStd = 0;
        int problematicCount = 0;
        int normalCount = 0;

        for (LayerConfig config : configs) {
            if (config.isProblematic) {
                avgProblematicQStd += config.qWeightStd;
                problematicCount++;
            } else {
                avgNormalQStd += config.qWeightStd;
                normalCount++;
            }
        }

        avgProblematicQStd /= problematicCount;
        avgNormalQStd /= normalCount;

        log("\nWeight statistics:");
        log(String.format("  Average Q weight std (problematic layers): %.6f", avgProblematicQStd));
        log(String.format("  Average Q weight std (normal layers): %.6f", avgNormalQStd));
        log(String.format("  Ratio: %.2fx", avgProblematicQStd / avgNormalQStd));
    }

    private void generateComparisonReport(List<LayerConfig> configs) throws Exception {
        try (PrintWriter writer = new PrintWriter(REPORT_FILE)) {
            writer.println("# Layer Configuration Analysis Report");
            writer.println();
            writer.println("**Objective**: Identify what makes layers 2, 22, and 23 special");
            writer.println();

            writer.println("## Configuration Summary Table");
            writer.println();
            writer.println("| Layer | Status | QK-Norm | Q Bias | K Bias | V Bias | Q Std | Gate Std | Params |");
            writer.println("|-------|--------|---------|--------|--------|--------|-------|----------|--------|");

            for (LayerConfig config : configs) {
                String status = config.isProblematic ? "**PROBLEM**" : "Normal";
                writer.printf("| %d | %s | %s | %s | %s | %s | %.4f | %.4f | %d |%n",
                    config.layerIndex,
                    status,
                    config.hasQKNorm ? "Yes" : "No",
                    config.hasQBias ? "Yes" : "No",
                    config.hasKBias ? "Yes" : "No",
                    config.hasVBias ? "Yes" : "No",
                    config.qWeightStd,
                    config.gateWeightStd,
                    config.totalParameters);
            }

            writer.println();
            writer.println("## Weight Statistics Comparison");
            writer.println();
            writer.println("### Attention Weights (Mean +/- Std)");
            writer.println();
            writer.println("| Layer | Q Weight | K Weight | V Weight | O Weight |");
            writer.println("|-------|----------|----------|----------|----------|");

            for (LayerConfig config : configs) {
                if (config.isProblematic || config.layerIndex == 0 || config.layerIndex == 1) {
                    writer.printf("| **%d** | %.4f+/-%.4f | %.4f+/-%.4f | %.4f+/-%.4f | %.4f+/-%.4f |%n",
                        config.layerIndex,
                        config.qWeightMean, config.qWeightStd,
                        config.kWeightMean, config.kWeightStd,
                        config.vWeightMean, config.vWeightStd,
                        config.oWeightMean, config.oWeightStd);
                }
            }

            writer.println();
            writer.println("### FFN Weights (Mean +/- Std)");
            writer.println();
            writer.println("| Layer | Gate Weight | Up Weight | Down Weight |");
            writer.println("|-------|-------------|-----------|-------------|");

            for (LayerConfig config : configs) {
                if (config.isProblematic || config.layerIndex == 0 || config.layerIndex == 1) {
                    writer.printf("| **%d** | %.4f+/-%.4f | %.4f+/-%.4f | %.4f+/-%.4f |%n",
                        config.layerIndex,
                        config.gateWeightMean, config.gateWeightStd,
                        config.upWeightMean, config.upWeightStd,
                        config.downWeightMean, config.downWeightStd);
                }
            }

            writer.println();
            writer.println("## Key Findings");
            writer.println();

            // Check for unique patterns
            boolean allHaveQKNorm = true;
            boolean allHaveBias = true;
            for (LayerConfig config : configs) {
                allHaveQKNorm &= config.hasQKNorm;
                allHaveBias &= config.hasQBias;
            }

            if (!allHaveQKNorm) {
                writer.println("- **QK-Norm Distribution**: Not all layers have QK-Norm weights");
            } else {
                writer.println("- **QK-Norm Distribution**: All layers have QK-Norm weights (uniform)");
            }

            if (!allHaveBias) {
                writer.println("- **Bias Distribution**: Not all layers have biases");
            } else {
                writer.println("- **Bias Distribution**: All layers have Q/K/V biases (uniform)");
            }

            // Check weight magnitude differences
            double maxQStd = 0, minQStd = Double.MAX_VALUE;
            int maxLayer = 0, minLayer = 0;
            for (LayerConfig config : configs) {
                if (config.qWeightStd > maxQStd) {
                    maxQStd = config.qWeightStd;
                    maxLayer = config.layerIndex;
                }
                if (config.qWeightStd < minQStd) {
                    minQStd = config.qWeightStd;
                    minLayer = config.layerIndex;
                }
            }

            writer.println();
            writer.printf("- **Q Weight Variance Range**: %.4f (layer %d) to %.4f (layer %d)%n",
                minQStd, minLayer, maxQStd, maxLayer);

            writer.println();
            writer.println("## Recommendations");
            writer.println();
            writer.println("Based on this analysis:");
            writer.println("1. Check if weight initialization differs for problematic layers");
            writer.println("2. Verify normalization implementation for edge cases");
            writer.println("3. Test attention mechanism with extreme weight values");
            writer.println("4. Check for numerical overflow/underflow in FFN");

            writer.println();
            writer.println("---");
            writer.println("*Generated by LayerConfigurationTest*");
        }

        log("Configuration report generated: " + REPORT_FILE);
    }
}