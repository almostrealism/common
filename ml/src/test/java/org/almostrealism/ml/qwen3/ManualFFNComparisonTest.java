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
 * Compares manual Java FFN computation against AR framework's dense() output.
 *
 * <p>This test manually implements the entire FFN pipeline using pure Java loops
 * and compares the result against the AR framework. This will isolate whether
 * the issue is in the AR dense() implementation or in the weight values.</p>
 */
public class ManualFFNComparisonTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";
    private static final int DIM = 896;
    private static final int HIDDEN_DIM = 4864;

    /**
     * Test complete FFN with pure Java computation vs PyTorch reference.
     */
    @Test
    public void testManualFFNVsPyTorch() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/manual_ffn_comparison.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Manual FFN (Pure Java) vs PyTorch Reference");
        log("===================================================\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Load PyTorch reference
        float[] layer22Input = loadReferenceOutput("after_layer_21.bin");
        float[] layer22Output = loadReferenceOutput("after_layer_22.bin");
        float[] layer23Input = loadReferenceOutput("after_layer_22.bin");
        float[] layer23Output = loadReferenceOutput("after_layer_23.bin");

        if (layer22Input == null || layer22Output == null ||
            layer23Input == null || layer23Output == null) {
            log("ERROR: Cannot load reference files");
            stateDict.destroy();
            return;
        }

        // Test both layers
        for (int layerIdx : new int[]{22, 23}) {
            float[] pytorchInput = layerIdx == 22 ? layer22Input : layer23Input;
            float[] pytorchOutput = layerIdx == 22 ? layer22Output : layer23Output;

            log(String.format("\n=== Layer %d ===\n", layerIdx));
            testLayerFFN(stateDict, layerIdx, pytorchInput, pytorchOutput);
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    private void testLayerFFN(StateDictionary stateDict, int layerIdx,
                              float[] pytorchInput, float[] pytorchOutput) {
        String prefix = String.format("model.layers.%d", layerIdx);

        // Load weights
        PackedCollection rmsFfnWeight = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        // Convert weights to double arrays for manual computation
        double[] rmsW = toDoubleArray(rmsFfnWeight, DIM);
        double[][] gateW = to2DArray(w1, HIDDEN_DIM, DIM);
        double[][] downW = to2DArray(w2, DIM, HIDDEN_DIM);
        double[][] upW = to2DArray(w3, HIDDEN_DIM, DIM);

        // Convert input
        double[] input = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            input[i] = pytorchInput[i];
        }

        log("Input stats: " + stats(input));

        // Step 1: RMSNorm (manual)
        double[] rmsNormOutput = manualRMSNorm(input, rmsW);
        log("RMSNorm output: " + stats(rmsNormOutput));

        // Step 2: Gate projection (manual matmul)
        double[] gateOutput = manualMatmul(gateW, rmsNormOutput);
        log("Gate projection: " + stats(gateOutput));

        // Step 3: SiLU (manual)
        double[] siluOutput = manualSiLU(gateOutput);
        log("SiLU output: " + stats(siluOutput));

        // Step 4: Up projection (manual matmul)
        double[] upOutput = manualMatmul(upW, rmsNormOutput);
        log("Up projection: " + stats(upOutput));

        // Step 5: Element-wise multiply
        double[] mulOutput = new double[HIDDEN_DIM];
        for (int i = 0; i < HIDDEN_DIM; i++) {
            mulOutput[i] = siluOutput[i] * upOutput[i];
        }
        log("Multiply output: " + stats(mulOutput));

        // Step 6: Down projection (manual matmul)
        double[] downOutput = manualMatmul(downW, mulOutput);
        log("Down projection: " + stats(downOutput));

        // Step 7: Add residual
        double[] finalOutput = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            finalOutput[i] = input[i] + downOutput[i];
        }
        log("Final (with residual): " + stats(finalOutput));

        // Expected output
        double[] expected = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            expected[i] = pytorchOutput[i];
        }
        log("PyTorch expected: " + stats(expected));

        // Compute delta analysis
        double[] expectedDelta = new double[DIM];
        double[] manualDelta = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            expectedDelta[i] = pytorchOutput[i] - pytorchInput[i];
            manualDelta[i] = finalOutput[i] - input[i];
        }
        log("\nDelta Analysis:");
        log("Expected delta: " + stats(expectedDelta));
        log("Manual delta: " + stats(manualDelta));

        // Compute error
        double sumError = 0, maxError = 0;
        int maxErrorIdx = 0;
        for (int i = 0; i < DIM; i++) {
            double error = Math.abs(finalOutput[i] - pytorchOutput[i]);
            sumError += error;
            if (error > maxError) {
                maxError = error;
                maxErrorIdx = i;
            }
        }
        double meanError = sumError / DIM;
        log(String.format("\nManual FFN Error: mean=%.6f, max=%.6f (idx=%d)", meanError, maxError, maxErrorIdx));

        if (meanError < 0.01) {
            log("STATUS: EXCELLENT - Manual FFN matches PyTorch");
        } else if (meanError < 0.1) {
            log("STATUS: GOOD - Small discrepancy");
        } else {
            log("STATUS: POOR - Significant discrepancy");
            // Show top 5 errors
            log("\nTop 5 errors:");
            for (int rank = 0; rank < 5; rank++) {
                double maxE = 0;
                int maxI = 0;
                for (int i = 0; i < DIM; i++) {
                    double e = Math.abs(finalOutput[i] - pytorchOutput[i]);
                    if (e > maxE) {
                        boolean alreadyShown = false;
                        for (int j = 0; j < rank; j++) {
                            // Skip already shown indices (crude but works)
                        }
                        if (!alreadyShown) {
                            maxE = e;
                            maxI = i;
                        }
                    }
                }
                log(String.format("  idx=%d: expected=%.4f, manual=%.4f, error=%.4f",
                    maxI, pytorchOutput[maxI], finalOutput[maxI], maxE));
            }
        }
    }

    private double[] manualRMSNorm(double[] input, double[] weights) {
        // Compute RMS
        double sumSq = 0;
        for (double v : input) {
            sumSq += v * v;
        }
        double rms = Math.sqrt(sumSq / input.length + 1e-6);

        // Normalize and scale
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = (input[i] / rms) * weights[i];
        }
        return output;
    }

    private double[] manualMatmul(double[][] weight, double[] input) {
        // weight shape: [out_dim, in_dim]
        // output = weight @ input
        int outDim = weight.length;
        int inDim = weight[0].length;
        double[] output = new double[outDim];

        for (int i = 0; i < outDim; i++) {
            double sum = 0;
            for (int j = 0; j < inDim; j++) {
                sum += weight[i][j] * input[j];
            }
            output[i] = sum;
        }
        return output;
    }

    private double[] manualSiLU(double[] input) {
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            double x = input[i];
            double sigmoid = 1.0 / (1.0 + Math.exp(-x));
            output[i] = x * sigmoid;
        }
        return output;
    }

    private double[] toDoubleArray(PackedCollection c, int size) {
        double[] arr = new double[size];
        for (int i = 0; i < size; i++) {
            arr[i] = c.toDouble(i);
        }
        return arr;
    }

    private double[][] to2DArray(PackedCollection c, int rows, int cols) {
        double[][] arr = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                arr[i][j] = c.toDouble(i * cols + j);
            }
        }
        return arr;
    }

    private String stats(double[] arr) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : arr) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / arr.length;
        double std = Math.sqrt(sumSq / arr.length - mean * mean);
        return String.format("mean=%.6f, std=%.6f, min=%.4f, max=%.4f", mean, std, min, max);
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
}
