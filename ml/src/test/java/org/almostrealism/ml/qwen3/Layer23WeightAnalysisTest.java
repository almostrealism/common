package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Analyze layer 23 weights to find anomalies compared to other layers.
 */
public class Layer23WeightAnalysisTest implements ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

    @Test
    public void analyzeLayer23Weights() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_weight_analysis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 Weight Analysis ===\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Compare layers 22 (works) and 23 (broken)
        int[] layersToCompare = {0, 22, 23};

        String[] weightNames = {
            "input_layernorm.weight",
            "self_attn.q_proj.weight",
            "self_attn.k_proj.weight",
            "self_attn.v_proj.weight",
            "self_attn.o_proj.weight",
            "self_attn.q_proj.bias",
            "self_attn.k_proj.bias",
            "self_attn.v_proj.bias",
            "self_attn.q_norm.weight",
            "self_attn.k_norm.weight",
            "post_attention_layernorm.weight",
            "mlp.gate_proj.weight",
            "mlp.up_proj.weight",
            "mlp.down_proj.weight"
        };

        log("| Weight | Layer | Shape | Mean | Std | Min | Max | HasNaN | HasInf |");
        log("|--------|-------|-------|------|-----|-----|-----|--------|--------|");

        for (String weightName : weightNames) {
            for (int layer : layersToCompare) {
                String key = String.format("model.layers.%d.%s", layer, weightName);
                PackedCollection weight = stateDict.get(key);

                if (weight == null) {
                    log(String.format("| %s | %d | MISSING | - | - | - | - | - | - |",
                        weightName, layer));
                    continue;
                }

                // Compute statistics
                int size = (int) weight.getShape().getSize();
                double sum = 0;
                double sumSq = 0;
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                boolean hasNaN = false;
                boolean hasInf = false;

                for (int i = 0; i < size; i++) {
                    double val = weight.toDouble(i);
                    if (Double.isNaN(val)) hasNaN = true;
                    if (Double.isInfinite(val)) hasInf = true;
                    sum += val;
                    sumSq += val * val;
                    if (val < min) min = val;
                    if (val > max) max = val;
                }

                double mean = sum / size;
                double variance = (sumSq / size) - (mean * mean);
                double std = Math.sqrt(Math.max(0, variance));

                log(String.format("| %s | %d | %s | %.6f | %.6f | %.6f | %.6f | %s | %s |",
                    shortName(weightName), layer, weight.getShape(),
                    mean, std, min, max,
                    hasNaN ? "YES" : "no",
                    hasInf ? "YES" : "no"));
            }
        }

        // Check for significant differences between layer 22 and 23
        log("\n=== Comparing Layer 22 vs 23 ===\n");

        for (String weightName : weightNames) {
            PackedCollection w22 = stateDict.get(String.format("model.layers.22.%s", weightName));
            PackedCollection w23 = stateDict.get(String.format("model.layers.23.%s", weightName));

            if (w22 == null || w23 == null) continue;

            // Compare distributions
            int size = (int) w22.getShape().getSize();
            double maxDiff = 0;
            double sumAbsDiff = 0;

            for (int i = 0; i < size; i++) {
                double diff = Math.abs(w22.toDouble(i) - w23.toDouble(i));
                sumAbsDiff += diff;
                if (diff > maxDiff) maxDiff = diff;
            }

            double meanAbsDiff = sumAbsDiff / size;

            if (maxDiff > 0.1 || meanAbsDiff > 0.01) {
                log(String.format("[DIFF] %s: meanDiff=%.6f, maxDiff=%.6f",
                    weightName, meanAbsDiff, maxDiff));
            }
        }

        // Specifically check if weights are identical (potential loading bug)
        log("\n=== Checking for Identical Weights (loading bug) ===\n");

        PackedCollection q22 = stateDict.get("model.layers.22.self_attn.q_proj.weight");
        PackedCollection q23 = stateDict.get("model.layers.23.self_attn.q_proj.weight");

        if (q22 != null && q23 != null) {
            boolean identical = true;
            int size = (int) q22.getShape().getSize();
            for (int i = 0; i < Math.min(100, size); i++) {
                if (Math.abs(q22.toDouble(i) - q23.toDouble(i)) > 1e-10) {
                    identical = false;
                    break;
                }
            }
            log("Q weights identical: " + (identical ? "YES (BUG!)" : "No (correct)"));
        }

        stateDict.destroy();
        log("\n=== Analysis Complete ===");
    }

    private String shortName(String name) {
        if (name.length() > 25) {
            return name.substring(0, 22) + "...";
        }
        return name;
    }
}
