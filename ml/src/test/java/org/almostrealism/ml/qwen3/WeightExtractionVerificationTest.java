package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Verify weight extraction by comparing weight statistics that should
 * produce the observed amplification patterns.
 *
 * <p>Based on reference data analysis:
 * - Layer 22 delta std: 1.65 (AR matches this)
 * - Layer 23 delta std: 6.32 (AR produces 1.70 - 3.7x under)
 *
 * This test investigates if the weights can produce the expected amplification.</p>
 */
public class WeightExtractionVerificationTest implements ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final int DIM = 896;
    private static final int HIDDEN_DIM = 4864;

    /**
     * Analyze what amplification each layer's FFN should produce.
     *
     * <p>For SwiGLU FFN: output = W2 @ (SiLU(W1 @ x) * (W3 @ x))
     * The amplification depends on weight norms and correlations.</p>
     */
    @Test
    public void analyzeExpectedAmplification() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/weight_amplification_analysis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Weight Amplification Analysis");
        log("===================================================\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        log("Analyzing FFN weight characteristics for layers 20-23...\n");
        log("Layer | W1 Frob | W2 Frob | W3 Frob | RMS Norm | Theoretical Amp");
        log("------|---------|---------|---------|----------|----------------");

        for (int layer = 20; layer <= 23; layer++) {
            String prefix = String.format("model.layers.%d", layer);

            PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
            PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
            PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");
            PackedCollection rmsW = stateDict.get(prefix + ".post_attention_layernorm.weight");

            double w1Frob = frobenius(w1, HIDDEN_DIM * DIM);
            double w2Frob = frobenius(w2, DIM * HIDDEN_DIM);
            double w3Frob = frobenius(w3, HIDDEN_DIM * DIM);
            double rmsNorm = frobenius(rmsW, DIM);

            // Theoretical amplification: roughly (W2_frob * W1_frob * W3_frob * RMS_norm) / sqrt(dims)
            // This is a rough estimate - actual depends on input distribution
            double theoreticalAmp = (w2Frob * w1Frob * w3Frob * rmsNorm) /
                (Math.sqrt(DIM) * Math.sqrt(HIDDEN_DIM) * Math.sqrt(HIDDEN_DIM));

            log(String.format("%5d | %7.2f | %7.2f | %7.2f | %8.2f | %.4f",
                layer, w1Frob, w2Frob, w3Frob, rmsNorm, theoreticalAmp));
        }

        // Detailed comparison of layer 22 vs 23 down_proj
        log("\n===================================================");
        log("  Down Projection Detailed Comparison (L22 vs L23)");
        log("===================================================\n");

        PackedCollection w2_22 = stateDict.get("model.layers.22.mlp.down_proj.weight");
        PackedCollection w2_23 = stateDict.get("model.layers.23.mlp.down_proj.weight");

        // Compare at problematic output indices
        int[] problemIndices = {241, 190, 58, 783, 53};

        log("Row norm comparison at problematic output indices:");
        log("Idx  | L22 Row Norm | L23 Row Norm | Ratio");
        log("-----|--------------|--------------|------");

        for (int idx : problemIndices) {
            double norm22 = rowNorm(w2_22, idx, HIDDEN_DIM);
            double norm23 = rowNorm(w2_23, idx, HIDDEN_DIM);
            log(String.format("%4d | %12.4f | %12.4f | %.2f",
                idx, norm22, norm23, norm23 / norm22));
        }

        // For layer 23 to produce 15x larger delta at idx 241, what would we need?
        log("\n===================================================");
        log("  Required vs Actual Amplification");
        log("===================================================\n");

        // From reference data:
        // Layer 22 delta at idx 241: -3.59
        // Layer 23 delta at idx 241: -57.97
        // Ratio: 16.15x

        double expectedRatio = 57.97 / 3.59; // ~16x
        log(String.format("Expected delta ratio at idx 241: %.2fx", expectedRatio));

        double norm22_241 = rowNorm(w2_22, 241, HIDDEN_DIM);
        double norm23_241 = rowNorm(w2_23, 241, HIDDEN_DIM);
        log(String.format("Actual row norm ratio at idx 241: %.2fx", norm23_241 / norm22_241));

        log("\nFor layer 23 to produce 16x larger delta:");
        log("  Option 1: Layer 23 row norm should be 16x larger (it's not)");
        log("  Option 2: Layer 23 hidden values should be 16x larger");
        log("  Option 3: The reference data includes additional computation");

        // Check RMS norm weights
        log("\n===================================================");
        log("  RMS Norm Weight Comparison");
        log("===================================================\n");

        PackedCollection rms22 = stateDict.get("model.layers.22.post_attention_layernorm.weight");
        PackedCollection rms23 = stateDict.get("model.layers.23.post_attention_layernorm.weight");

        log("Post-attention layernorm weights at problematic indices:");
        log("Idx  | L22 RMS Weight | L23 RMS Weight | Ratio");
        log("-----|----------------|----------------|------");

        for (int idx : problemIndices) {
            double w22 = rms22.toDouble(idx);
            double w23 = rms23.toDouble(idx);
            log(String.format("%4d | %14.4f | %14.4f | %.2f",
                idx, w22, w23, w23 / w22));
        }

        // Check overall statistics
        log("\nOverall RMS norm statistics:");
        log(String.format("Layer 22: mean=%.4f, std=%.4f, max=%.4f",
            mean(rms22, DIM), std(rms22, DIM), max(rms22, DIM)));
        log(String.format("Layer 23: mean=%.4f, std=%.4f, max=%.4f",
            mean(rms23, DIM), std(rms23, DIM), max(rms23, DIM)));

        stateDict.destroy();
        log("\n=== Analysis Complete ===");
    }

    /**
     * Simulate layer output using weights to verify amplification capability.
     */
    @Test
    public void simulateLayerAmplification() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/amplification_simulation.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Layer Amplification Simulation");
        log("===================================================\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Simulate what delta std should be for a given input
        // Input: layer 22 output has std=2.48
        // After layer 23 FFN, delta std should be 6.32

        // For layer 22: input std=1.89, delta std=1.65
        // For layer 23: input std=2.48, delta std=6.32

        log("Reference data expectations:");
        log("  Layer 22: input std=1.89 -> delta std=1.65 (0.87x)");
        log("  Layer 23: input std=2.48 -> delta std=6.32 (2.55x)");
        log("\nFor layer 23 to produce 2.55x amplification, we need:");
        log("  W2 @ (SiLU(W1 @ norm_x) * (W3 @ norm_x)) to have 2.55x std of input");

        // Load weights
        String[] layers = {"22", "23"};
        for (String layerStr : layers) {
            String prefix = "model.layers." + layerStr;
            int layer = Integer.parseInt(layerStr);

            PackedCollection rmsW = stateDict.get(prefix + ".post_attention_layernorm.weight");
            PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
            PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
            PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

            log(String.format("\n--- Layer %s ---", layerStr));

            // Simulate with unit input std
            double[] input = new double[DIM];
            java.util.Random rnd = new java.util.Random(42);
            for (int i = 0; i < DIM; i++) {
                input[i] = rnd.nextGaussian();
            }

            // RMSNorm
            double[] normed = rmsNorm(input, toArray(rmsW, DIM));
            log(String.format("  After RMSNorm: std=%.4f", std(normed)));

            // Gate projection (W1)
            double[] gate = matmul(toMatrix(w1, HIDDEN_DIM, DIM), normed);
            log(String.format("  After gate_proj: std=%.4f", std(gate)));

            // SiLU
            double[] silu = silu(gate);
            log(String.format("  After SiLU: std=%.4f", std(silu)));

            // Up projection (W3)
            double[] up = matmul(toMatrix(w3, HIDDEN_DIM, DIM), normed);
            log(String.format("  After up_proj: std=%.4f", std(up)));

            // Multiply
            double[] mul = multiply(silu, up);
            log(String.format("  After multiply: std=%.4f", std(mul)));

            // Down projection (W2)
            double[] down = matmul(toMatrix(w2, DIM, HIDDEN_DIM), mul);
            log(String.format("  After down_proj: std=%.4f", std(down)));

            log(String.format("  Amplification ratio: %.4f", std(down) / std(input)));
        }

        stateDict.destroy();
        log("\n=== Simulation Complete ===");
    }

    // Helper methods
    private double frobenius(PackedCollection c, int size) {
        double sum = 0;
        for (int i = 0; i < size; i++) {
            double v = c.toDouble(i);
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    private double rowNorm(PackedCollection c, int row, int cols) {
        double sum = 0;
        for (int j = 0; j < cols; j++) {
            double v = c.toDouble(row * cols + j);
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    private double mean(PackedCollection c, int size) {
        double sum = 0;
        for (int i = 0; i < size; i++) sum += c.toDouble(i);
        return sum / size;
    }

    private double std(PackedCollection c, int size) {
        double mean = mean(c, size);
        double sumSq = 0;
        for (int i = 0; i < size; i++) {
            double diff = c.toDouble(i) - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / size);
    }

    private double max(PackedCollection c, int size) {
        double m = c.toDouble(0);
        for (int i = 1; i < size; i++) {
            if (c.toDouble(i) > m) m = c.toDouble(i);
        }
        return m;
    }

    private double[] toArray(PackedCollection c, int size) {
        double[] arr = new double[size];
        for (int i = 0; i < size; i++) arr[i] = c.toDouble(i);
        return arr;
    }

    private double[][] toMatrix(PackedCollection c, int rows, int cols) {
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                m[i][j] = c.toDouble(i * cols + j);
            }
        }
        return m;
    }

    private double[] rmsNorm(double[] input, double[] weights) {
        double sumSq = 0;
        for (double v : input) sumSq += v * v;
        double rms = Math.sqrt(sumSq / input.length + 1e-6);
        double[] out = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = (input[i] / rms) * weights[i];
        }
        return out;
    }

    private double[] matmul(double[][] w, double[] x) {
        double[] out = new double[w.length];
        for (int i = 0; i < w.length; i++) {
            double sum = 0;
            for (int j = 0; j < x.length; j++) {
                sum += w[i][j] * x[j];
            }
            out[i] = sum;
        }
        return out;
    }

    private double[] silu(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double sigmoid = 1.0 / (1.0 + Math.exp(-x[i]));
            out[i] = x[i] * sigmoid;
        }
        return out;
    }

    private double[] multiply(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] * b[i];
        }
        return out;
    }

    private double std(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v;
        double mean = sum / arr.length;
        double sumSq = 0;
        for (double v : arr) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / arr.length);
    }
}
