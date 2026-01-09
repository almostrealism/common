/*
 * Copyright 2025 Michael Murray
 */
package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Compare layer 22 and layer 23 weights to identify differences.
 */
public class Layer23WeightTest implements ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

    @Test
    public void compareLayerWeights() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_weights.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 22 vs Layer 23 Weight Comparison ===\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        String[] weightNames = {
            "input_layernorm.weight",
            "post_attention_layernorm.weight",
            "self_attn.q_proj.weight",
            "self_attn.k_proj.weight",
            "self_attn.v_proj.weight",
            "self_attn.o_proj.weight",
            "self_attn.q_proj.bias",
            "self_attn.k_proj.bias",
            "self_attn.v_proj.bias",
            "self_attn.q_norm.weight",
            "self_attn.k_norm.weight",
            "mlp.gate_proj.weight",
            "mlp.down_proj.weight",
            "mlp.up_proj.weight"
        };

        log(String.format("%-35s %-15s %-15s %-15s %-15s",
            "Weight", "L22 Mean", "L23 Mean", "L22 Range", "L23 Range"));
        log("-".repeat(100));

        for (String name : weightNames) {
            PackedCollection w22 = stateDict.get("model.layers.22." + name);
            PackedCollection w23 = stateDict.get("model.layers.23." + name);

            if (w22 == null || w23 == null) {
                log(String.format("%-35s MISSING", name));
                continue;
            }

            double[] stats22 = computeStats(w22);
            double[] stats23 = computeStats(w23);

            log(String.format("%-35s %-15.6f %-15.6f [%.3f,%.3f] [%.3f,%.3f]",
                name, stats22[0], stats23[0], stats22[1], stats22[2], stats23[1], stats23[2]));
        }

        // Check if layer 23 has any weights that are significantly different
        log("\n=== Checking for Anomalies ===\n");

        // Compare a few weights element by element
        PackedCollection gate22 = stateDict.get("model.layers.22.mlp.gate_proj.weight");
        PackedCollection gate23 = stateDict.get("model.layers.23.mlp.gate_proj.weight");

        log("gate_proj.weight shapes: L22=" + gate22.getShape() + ", L23=" + gate23.getShape());

        // Check first few elements
        log("\nFirst 5 elements of gate_proj.weight:");
        for (int i = 0; i < 5; i++) {
            log(String.format("  [%d] L22=%.6f, L23=%.6f", i, gate22.toDouble(i), gate23.toDouble(i)));
        }

        // Check if any weights are zero or have unusual values
        log("\n=== Checking for Zero Weights ===\n");
        for (String name : weightNames) {
            PackedCollection w23 = stateDict.get("model.layers.23." + name);
            if (w23 == null) continue;

            int zeroCount = 0;
            int totalCount = (int) w23.getShape().getTotalSize();
            for (int i = 0; i < totalCount; i++) {
                if (Math.abs(w23.toDouble(i)) < 1e-10) {
                    zeroCount++;
                }
            }

            if (zeroCount > 0) {
                log(String.format("%-35s %d/%d zeros (%.2f%%)",
                    name, zeroCount, totalCount, 100.0 * zeroCount / totalCount));
            }
        }

        stateDict.destroy();
    }

    private double[] computeStats(PackedCollection c) {
        int size = (int) c.getShape().getTotalSize();
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        for (int i = 0; i < size; i++) {
            double v = c.toDouble(i);
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        return new double[]{sum / size, min, max};
    }
}
