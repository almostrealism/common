package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Verify layer 23 weights are valid and sensible.
 *
 * <p>The theory: if both pure Java and AR produce the same wrong output,
 * and layers 0-22 work correctly, then either:
 * 1. Layer 23 weights are incorrect/corrupted
 * 2. The PyTorch reference data is wrong
 * 3. There's something fundamentally different about layer 23</p>
 */
public class VerifyLayer23WeightsTest implements ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final int DIM = 896;
    private static final int HIDDEN_DIM = 4864;

    /**
     * Compare weight statistics across all layers to find anomalies.
     */
    @Test
    public void compareAllLayerWeights() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/all_layer_weights.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Weight Statistics Across All 24 Layers");
        log("===================================================\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        String[] weightNames = {
            "input_layernorm.weight",
            "post_attention_layernorm.weight",
            "mlp.gate_proj.weight",
            "mlp.up_proj.weight",
            "mlp.down_proj.weight",
            "self_attn.q_proj.weight",
            "self_attn.k_proj.weight",
            "self_attn.v_proj.weight",
            "self_attn.o_proj.weight"
        };

        for (String weightName : weightNames) {
            log(String.format("\n=== %s ===", weightName));
            log("Layer | Mean | Std | Min | Max | Frobenius");
            log("------|------|-----|-----|-----|----------");

            double[] frobNorms = new double[24];
            double[] means = new double[24];
            double[] stds = new double[24];

            for (int layer = 0; layer < 24; layer++) {
                String key = String.format("model.layers.%d.%s", layer, weightName);
                PackedCollection w = stateDict.get(key);

                if (w == null) {
                    log(String.format("%5d | ERROR: weight not found", layer));
                    continue;
                }

                int size = (int) w.getShape().getSize();
                double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                for (int i = 0; i < size; i++) {
                    double v = w.toDouble(i);
                    sum += v;
                    sumSq += v * v;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }

                double mean = sum / size;
                double variance = sumSq / size - mean * mean;
                double std = Math.sqrt(Math.max(0, variance));
                double frob = Math.sqrt(sumSq);

                means[layer] = mean;
                stds[layer] = std;
                frobNorms[layer] = frob;

                // Highlight layer 23
                String marker = (layer == 23) ? " ***" : "";
                log(String.format("%5d | %.4f | %.4f | %.4f | %.4f | %.2f%s",
                    layer, mean, std, min, max, frob, marker));
            }

            // Check if layer 23 is anomalous
            double avgFrob = 0, avgStd = 0;
            for (int i = 0; i < 22; i++) {  // Exclude layers 22, 23
                avgFrob += frobNorms[i];
                avgStd += stds[i];
            }
            avgFrob /= 22;
            avgStd /= 22;

            double layer23FrobRatio = frobNorms[23] / avgFrob;
            double layer23StdRatio = stds[23] / avgStd;

            if (Math.abs(layer23FrobRatio - 1.0) > 0.2) {
                log(String.format("!!! Layer 23 Frobenius norm is %.2fx average !!!", layer23FrobRatio));
            }
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    /**
     * Test if layer 23 FFN can produce extreme values (-64, 51.5) that PyTorch produces.
     */
    @Test
    public void testExtremeValuePossibility() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/extreme_value_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Testing if Layer 23 FFN Can Produce Extreme Values");
        log("===================================================\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // For FFN to produce output value of -64, we need:
        // output[i] = sum_j(down_proj[i,j] * hidden[j])
        // If max|hidden| ≈ 15 and we need output = -64, then we need
        // down_proj row norm * hidden magnitude ≈ 64

        String prefix = "model.layers.23";
        PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");

        // Check row norms of down_proj
        log("Down_proj row norm analysis:");
        double maxRowNorm = 0;
        int maxRowIdx = 0;
        double[] rowNorms = new double[DIM];

        for (int i = 0; i < DIM; i++) {
            double rowNorm = 0;
            for (int j = 0; j < HIDDEN_DIM; j++) {
                double v = w2.toDouble(i * HIDDEN_DIM + j);
                rowNorm += v * v;
            }
            rowNorm = Math.sqrt(rowNorm);
            rowNorms[i] = rowNorm;
            if (rowNorm > maxRowNorm) {
                maxRowNorm = rowNorm;
                maxRowIdx = i;
            }
        }

        log(String.format("Max row norm: %.4f at idx %d", maxRowNorm, maxRowIdx));
        log(String.format("Row norm range: [%.4f, %.4f]", min(rowNorms), max(rowNorms)));

        // Check if idx 241 (which had error 49) has special row norm
        log(String.format("\nRow norms at problematic indices:"));
        int[] problemIndices = {241, 190, 58, 783, 53};
        for (int idx : problemIndices) {
            log(String.format("  idx=%d: row_norm=%.4f", idx, rowNorms[idx]));
        }

        // To produce -64 at idx 241 with multiply output max ~15:
        // We need: row_norm * max_hidden ≈ 64
        // If max_hidden = 15, then row_norm ≈ 64/15 ≈ 4.3
        // But our row_norm at idx 241 is much smaller!

        log("\nFor PyTorch to produce -64 at idx 241:");
        log("  Required: row_norm * max_hidden ≈ 64");
        log("  If max_hidden ≈ 15: row_norm ≈ 4.3");
        log(String.format("  Actual row_norm at idx 241: %.4f", rowNorms[241]));
        log(String.format("  Gap: PyTorch needs %.1fx more amplification!", 4.3 / rowNorms[241]));

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    private double min(double[] arr) {
        double m = arr[0];
        for (double v : arr) if (v < m) m = v;
        return m;
    }

    private double max(double[] arr) {
        double m = arr[0];
        for (double v : arr) if (v > m) m = v;
        return m;
    }
}
