package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Tests if the final RMSNorm is included in the PyTorch reference data.
 *
 * <p>Key hypothesis: hidden_states[24] might include final_norm(layer_23_output)
 * instead of just layer_23_output.</p>
 */
public class FinalNormVerificationTest implements ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";
    private static final int DIM = 896;

    /**
     * Test if applying final norm to layer 22 output produces values closer to layer 23 reference.
     */
    @Test
    public void testFinalNormHypothesis() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/final_norm_hypothesis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Final Norm Hypothesis Test");
        log("===================================================\n");

        log("Hypothesis: hidden_states[24] = final_norm(layer_23_output)");
        log("instead of: hidden_states[24] = layer_23_output\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Load final norm weights
        PackedCollection finalNormWeight = stateDict.get("model.norm.weight");
        if (finalNormWeight == null) {
            log("ERROR: Cannot find model.norm.weight");
            stateDict.destroy();
            return;
        }

        log("Final norm weight statistics:");
        log(String.format("  Shape: %s", finalNormWeight.getShape()));
        log(String.format("  Stats: %s", stats(finalNormWeight, DIM)));

        // Load reference files
        float[] layer22Output = loadReferenceOutput("after_layer_22.bin");
        float[] layer23Output = loadReferenceOutput("after_layer_23.bin");

        if (layer22Output == null || layer23Output == null) {
            log("ERROR: Cannot load reference files");
            stateDict.destroy();
            return;
        }

        log("\nReference data:");
        log(String.format("  Layer 22 output: %s", statsFromFloat(layer22Output)));
        log(String.format("  Layer 23 output: %s", statsFromFloat(layer23Output)));

        // Test hypothesis: What if we apply RMSNorm to layer 22 output?
        // This simulates what would happen if layer 23 reference is actually:
        // final_norm(layer_22_output) instead of true layer 23 output
        log("\n--- Test 1: Apply final norm to layer 22 output ---");
        double[] normedLayer22 = applyRMSNorm(layer22Output, finalNormWeight);
        log(String.format("  final_norm(layer_22): %s", statsFromDouble(normedLayer22)));

        // Compare with layer 23 reference
        double errorNormedL22 = meanAbsError(normedLayer22, layer23Output);
        log(String.format("  Error vs layer23 ref: %.6f", errorNormedL22));

        // What if layer 23 reference is final_norm(true_layer_23)?
        // We can't test this directly since we don't have true_layer_23
        // But we can check if the amplification pattern matches

        log("\n--- Test 2: What amplification does final norm add? ---");

        // Final norm amplification check
        // RMSNorm: x * weight / rms(x)
        // If input has std=2.48 and final norm weights have mean~1.0:
        // output std should be approximately: 2.48 * mean_weight / sqrt(mean(x^2))

        double inputStd = stdFromFloat(layer22Output);
        double inputRMS = rmsFromFloat(layer22Output);
        double weightMean = mean(finalNormWeight, DIM);
        double weightStd = std(finalNormWeight, DIM);

        log(String.format("  Layer 22 input RMS: %.4f", inputRMS));
        log(String.format("  Final norm weight mean: %.4f", weightMean));
        log(String.format("  Final norm weight std: %.4f", weightStd));

        // Expected output std after norm: input * weight / RMS
        // Roughly: std(output) ~ mean(weight) since normalized std ~ 1
        double expectedNormOutputStd = stdFromDouble(normedLayer22);
        log(String.format("  Normed output std: %.4f", expectedNormOutputStd));
        log(String.format("  Layer 23 reference std: %.4f", stdFromFloat(layer23Output)));

        // Key test: Does final norm produce the extreme values?
        log("\n--- Test 3: Can final norm produce extreme values? ---");

        // Find max values in layer 23 reference
        int minIdx = 0, maxIdx = 0;
        float minVal = Float.MAX_VALUE, maxVal = -Float.MAX_VALUE;
        for (int i = 0; i < DIM; i++) {
            if (layer23Output[i] < minVal) {
                minVal = layer23Output[i];
                minIdx = i;
            }
            if (layer23Output[i] > maxVal) {
                maxVal = layer23Output[i];
                maxIdx = i;
            }
        }

        log(String.format("  Layer 23 ref min: %.4f at idx %d", minVal, minIdx));
        log(String.format("  Layer 23 ref max: %.4f at idx %d", maxVal, maxIdx));

        // What does final norm produce at these indices?
        log(String.format("  Normed L22 at idx %d: %.4f", minIdx, normedLayer22[minIdx]));
        log(String.format("  Normed L22 at idx %d: %.4f", maxIdx, normedLayer22[maxIdx]));

        // What would we need layer 23 pre-norm to be for final norm to produce -64?
        // -64 = layer23_prenorm[idx] * weight[idx] / RMS(layer23_prenorm)
        // If weight[idx] ~ 1 and RMS ~ 1, then prenorm[idx] ~ -64
        // But if RMS is smaller, prenorm could be smaller

        double finalNormWeightAt241 = finalNormWeight.toDouble(241);
        double finalNormWeightAt58 = finalNormWeight.toDouble(58);
        log(String.format("\n  Final norm weight at idx 241: %.4f", finalNormWeightAt241));
        log(String.format("  Final norm weight at idx 58: %.4f", finalNormWeightAt58));

        // Reverse engineer: what input would produce -64 at idx 241?
        // output = input * weight / rms
        // -64 = input * finalNormWeightAt241 / rms
        // input = -64 * rms / finalNormWeightAt241

        // If layer 23 pre-norm has std~2.48 similar to layer 22, RMS~2.48
        // Then: input at 241 = -64 * 2.48 / finalNormWeightAt241
        double hypotheticalRMS = 2.48;
        double requiredInput241 = -64 * hypotheticalRMS / finalNormWeightAt241;
        double requiredInput58 = 51.5 * hypotheticalRMS / finalNormWeightAt58;

        log(String.format("\n  To produce -64 at idx 241 (assuming RMS=%.2f):", hypotheticalRMS));
        log(String.format("    Required pre-norm value: %.4f", requiredInput241));
        log(String.format("  To produce 51.5 at idx 58 (assuming RMS=%.2f):", hypotheticalRMS));
        log(String.format("    Required pre-norm value: %.4f", requiredInput58));

        // Compare with layer 22 values at these indices
        log(String.format("\n  Layer 22 value at idx 241: %.4f", layer22Output[241]));
        log(String.format("  Layer 22 value at idx 58: %.4f", layer22Output[58]));

        // Conclusion
        log("\n===================================================");
        log("  Conclusions");
        log("===================================================\n");

        if (Math.abs(normedLayer22[minIdx] - minVal) < 10 ||
            Math.abs(normedLayer22[maxIdx] - maxVal) < 10) {
            log("FINDING: Final norm of layer 22 output is CLOSE to layer 23 reference!");
            log("This suggests hidden_states[24] might be final_norm(layer_22_output)");
            log("rather than layer_23_output.");
        } else {
            log("FINDING: Final norm of layer 22 doesn't match layer 23 reference.");
            log("The extreme values in layer 23 reference require different input.");
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    private double[] applyRMSNorm(float[] input, PackedCollection weights) {
        // RMSNorm: output = input * weight / sqrt(mean(input^2) + eps)
        double sumSq = 0;
        for (float v : input) sumSq += v * v;
        double rms = Math.sqrt(sumSq / input.length + 1e-6);

        double[] output = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = (input[i] / rms) * weights.toDouble(i);
        }
        return output;
    }

    private double meanAbsError(double[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum / a.length;
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

    private double stdFromFloat(float[] arr) {
        double sum = 0;
        for (float v : arr) sum += v;
        double mean = sum / arr.length;
        double sumSq = 0;
        for (float v : arr) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / arr.length);
    }

    private double rmsFromFloat(float[] arr) {
        double sumSq = 0;
        for (float v : arr) sumSq += v * v;
        return Math.sqrt(sumSq / arr.length);
    }

    private double stdFromDouble(double[] arr) {
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

    private String statsFromDouble(double[] arr) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : arr) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / arr.length;
        double std = Math.sqrt(sumSq / arr.length - mean * mean);
        return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
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
