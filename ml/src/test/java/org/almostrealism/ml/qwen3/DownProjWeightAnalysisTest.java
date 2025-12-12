package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Analyze down_proj weights specifically to understand the amplification difference.
 *
 * <p>Previous tests show layer 23's FFN under-amplifies by ~4x. This test examines
 * whether the down_proj weight matrices differ in a way that could explain this.</p>
 */
public class DownProjWeightAnalysisTest implements ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final int DIM = 896;
    private static final int HIDDEN_DIM = 4864;

    /**
     * Compare down_proj (w2) weight statistics between layers.
     */
    @Test
    public void analyzeDownProjWeights() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/down_proj_analysis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Down Projection Weight Analysis");
        log("===================================================\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Analyze all layers' down_proj weights
        log("Layer | Frobenius Norm | Max Abs | Mean Abs | Row Norm Range");
        log("------|----------------|---------|----------|---------------");

        double[] frobNorms = new double[24];
        for (int layer = 0; layer < 24; layer++) {
            String key = String.format("model.layers.%d.mlp.down_proj.weight", layer);
            PackedCollection w2 = stateDict.get(key);

            if (w2 == null) {
                log(String.format("%5d | ERROR: weight not found", layer));
                continue;
            }

            // Weight shape: [dim, hidden_dim] = [896, 4864]
            double frobNorm = 0;
            double maxAbs = 0;
            double sumAbs = 0;
            double minRowNorm = Double.MAX_VALUE;
            double maxRowNorm = 0;

            for (int i = 0; i < DIM; i++) {
                double rowNorm = 0;
                for (int j = 0; j < HIDDEN_DIM; j++) {
                    double v = w2.toDouble(i * HIDDEN_DIM + j);
                    double absV = Math.abs(v);
                    frobNorm += v * v;
                    sumAbs += absV;
                    if (absV > maxAbs) maxAbs = absV;
                    rowNorm += v * v;
                }
                rowNorm = Math.sqrt(rowNorm);
                if (rowNorm < minRowNorm) minRowNorm = rowNorm;
                if (rowNorm > maxRowNorm) maxRowNorm = rowNorm;
            }

            frobNorm = Math.sqrt(frobNorm);
            frobNorms[layer] = frobNorm;
            double meanAbs = sumAbs / (DIM * HIDDEN_DIM);

            log(String.format("%5d | %14.4f | %7.4f | %8.6f | %.2f - %.2f",
                layer, frobNorm, maxAbs, meanAbs, minRowNorm, maxRowNorm));
        }

        log("\n===================================================");
        log("  Layer 22 vs Layer 23 Comparison");
        log("===================================================\n");

        PackedCollection w22 = stateDict.get("model.layers.22.mlp.down_proj.weight");
        PackedCollection w23 = stateDict.get("model.layers.23.mlp.down_proj.weight");

        // Detailed comparison
        log(String.format("Frobenius norm ratio (23/22): %.4f", frobNorms[23] / frobNorms[22]));

        // Compare row by row
        log("\nRow norms comparison (first 10 rows):");
        log("Row | Layer 22 | Layer 23 | Ratio");
        log("----|----------|----------|------");
        for (int i = 0; i < 10; i++) {
            double norm22 = 0, norm23 = 0;
            for (int j = 0; j < HIDDEN_DIM; j++) {
                double v22 = w22.toDouble(i * HIDDEN_DIM + j);
                double v23 = w23.toDouble(i * HIDDEN_DIM + j);
                norm22 += v22 * v22;
                norm23 += v23 * v23;
            }
            norm22 = Math.sqrt(norm22);
            norm23 = Math.sqrt(norm23);
            log(String.format("%3d | %8.4f | %8.4f | %.3f", i, norm22, norm23, norm23 / norm22));
        }

        // Test manual matmul with unit input
        log("\n===================================================");
        log("  Manual Matmul Test with Unit Input");
        log("===================================================\n");

        double[] unitInput = new double[HIDDEN_DIM];
        for (int i = 0; i < HIDDEN_DIM; i++) {
            unitInput[i] = 1.0 / Math.sqrt(HIDDEN_DIM);  // Normalized to unit variance
        }

        // Compute output = input * W^T (matmul)
        double[] output22 = new double[DIM];
        double[] output23 = new double[DIM];

        for (int i = 0; i < DIM; i++) {
            double sum22 = 0, sum23 = 0;
            for (int j = 0; j < HIDDEN_DIM; j++) {
                double v22 = w22.toDouble(i * HIDDEN_DIM + j);
                double v23 = w23.toDouble(i * HIDDEN_DIM + j);
                sum22 += unitInput[j] * v22;
                sum23 += unitInput[j] * v23;
            }
            output22[i] = sum22;
            output23[i] = sum23;
        }

        // Compute output statistics
        double mean22 = 0, mean23 = 0;
        double var22 = 0, var23 = 0;
        for (int i = 0; i < DIM; i++) {
            mean22 += output22[i];
            mean23 += output23[i];
        }
        mean22 /= DIM;
        mean23 /= DIM;
        for (int i = 0; i < DIM; i++) {
            var22 += (output22[i] - mean22) * (output22[i] - mean22);
            var23 += (output23[i] - mean23) * (output23[i] - mean23);
        }
        var22 /= DIM;
        var23 /= DIM;

        log("Unit input test (input std=1.0):");
        log(String.format("  Layer 22 output: mean=%.6f, std=%.6f", mean22, Math.sqrt(var22)));
        log(String.format("  Layer 23 output: mean=%.6f, std=%.6f", mean23, Math.sqrt(var23)));
        log(String.format("  Output std ratio (23/22): %.4f", Math.sqrt(var23) / Math.sqrt(var22)));

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }
}
