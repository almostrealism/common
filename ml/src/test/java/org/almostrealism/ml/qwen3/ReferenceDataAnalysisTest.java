package org.almostrealism.ml.qwen3;

import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Analyzes the PyTorch reference data to verify consistency.
 *
 * <p>The theory: if layer 23 reference shows 6.3 std delta while layers 0-22 show ~1-2 std,
 * this would indicate either a model architecture difference or post-processing
 * (like final norm) being included in the reference data.</p>
 */
public class ReferenceDataAnalysisTest implements ConsoleFeatures {

    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";
    private static final int DIM = 896;

    /**
     * Analyze delta statistics across all layers to find anomalies.
     */
    @Test
    public void analyzeLayerDeltas() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/reference_delta_analysis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Layer Delta Analysis (PyTorch Reference Data)");
        log("===================================================\n");

        log("Analyzing output - input deltas for all layers...\n");
        log("Layer | Input Std  | Output Std | Delta Std  | Delta/Input | Notes");
        log("------|------------|------------|------------|-------------|------");

        float[] embeddings = loadReferenceOutput("after_embeddings.bin");
        if (embeddings == null) {
            log("ERROR: Cannot load embeddings reference");
            return;
        }

        float[] prevOutput = embeddings;
        double avgDeltaStd = 0;
        int layerCount = 0;

        for (int layer = 0; layer < 24; layer++) {
            String filename = String.format("after_layer_%d.bin", layer);
            float[] layerOutput = loadReferenceOutput(filename);

            if (layerOutput == null) {
                log(String.format("%5d | ERROR: Cannot load %s", layer, filename));
                continue;
            }

            // Compute delta
            float[] delta = new float[DIM];
            for (int i = 0; i < DIM; i++) {
                delta[i] = layerOutput[i] - prevOutput[i];
            }

            double inputStd = std(prevOutput);
            double outputStd = std(layerOutput);
            double deltaStd = std(delta);
            double ratio = deltaStd / inputStd;

            // Mark anomalies
            String notes = "";
            if (layer < 22) {
                avgDeltaStd += deltaStd;
                layerCount++;
            } else if (layer == 23) {
                double expectedDelta = avgDeltaStd / Math.max(1, layerCount);
                if (deltaStd > expectedDelta * 2) {
                    notes = String.format("ANOMALY! %.1fx avg", deltaStd / expectedDelta);
                }
            }

            log(String.format("%5d | %10.4f | %10.4f | %10.4f | %11.4f | %s",
                layer, inputStd, outputStd, deltaStd, ratio, notes));

            prevOutput = layerOutput;
        }

        log("\n===================================================");
        log("  Top Discrepancy Analysis");
        log("===================================================\n");

        // Focus on layer 23
        float[] layer22Output = loadReferenceOutput("after_layer_22.bin");
        float[] layer23Output = loadReferenceOutput("after_layer_23.bin");

        if (layer22Output != null && layer23Output != null) {
            log("Layer 23 extreme values analysis:");
            log("--------------------------------\n");

            // Find extreme values in layer 23 output
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

            log(String.format("Min value: %.4f at idx %d (input was %.4f, delta=%.4f)",
                minVal, minIdx, layer22Output[minIdx], layer23Output[minIdx] - layer22Output[minIdx]));
            log(String.format("Max value: %.4f at idx %d (input was %.4f, delta=%.4f)",
                maxVal, maxIdx, layer22Output[maxIdx], layer23Output[maxIdx] - layer22Output[maxIdx]));

            // Compare layer 22 and 23 delta magnitudes at problematic indices
            log("\nDelta at problematic indices (from previous tests):");
            int[] problemIndices = {241, 190, 58, 783, 53};
            log("Idx  | Layer22 In | Layer23 Out | Delta    | Abs Delta");
            log("-----|------------|-------------|----------|----------");
            for (int idx : problemIndices) {
                float input = layer22Output[idx];
                float output = layer23Output[idx];
                float delta = output - input;
                log(String.format("%4d | %10.4f | %11.4f | %8.4f | %8.4f",
                    idx, input, output, delta, Math.abs(delta)));
            }

            // Compare with layer 22's delta at same indices
            float[] layer21Output = loadReferenceOutput("after_layer_21.bin");
            if (layer21Output != null) {
                log("\nFor comparison - Layer 22 deltas at same indices:");
                log("Idx  | Layer21 In | Layer22 Out | Delta    | Abs Delta");
                log("-----|------------|-------------|----------|----------");
                for (int idx : problemIndices) {
                    float input = layer21Output[idx];
                    float output = layer22Output[idx];
                    float delta = output - input;
                    log(String.format("%4d | %10.4f | %11.4f | %8.4f | %8.4f",
                        idx, input, output, delta, Math.abs(delta)));
                }
            }
        }

        // Check if final layer output looks like post-norm
        log("\n===================================================");
        log("  Post-Norm Detection Test");
        log("===================================================\n");

        if (layer23Output != null) {
            // If layer 23 output has been through RMSNorm, its std would be close to 1.0
            // after dividing by the weight mean (~2.0 for Qwen)
            double outputStd = std(layer23Output);
            double outputMean = mean(layer23Output);

            log(String.format("Layer 23 output mean: %.6f", outputMean));
            log(String.format("Layer 23 output std: %.6f", outputStd));

            // RMSNorm typically produces output with std close to weight magnitude
            // For Qwen, final norm weights have mean ~1.0, so normalized output std ~1.0
            if (outputStd > 5.0) {
                log("\nCONCLUSION: Output std is high (>5), likely NOT post-normalized");
            } else if (outputStd < 3.0) {
                log("\nCONCLUSION: Output std is low (<3), possibly post-normalized?");
            } else {
                log("\nCONCLUSION: Output std is moderate, unclear if post-normalized");
            }
        }

        log("\n=== Analysis Complete ===");
    }

    /**
     * Compare reference file sizes to detect corruption.
     */
    @Test
    public void checkReferenceFileIntegrity() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/reference_file_check.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Reference File Integrity Check");
        log("===================================================\n");

        Path dir = Paths.get(REFERENCE_DIR);
        if (!Files.exists(dir)) {
            log("ERROR: Reference directory does not exist: " + REFERENCE_DIR);
            return;
        }

        log("File                    | Size (bytes) | Values | First 3 Values");
        log("------------------------|--------------|--------|---------------");

        String[] files = {"after_embeddings.bin"};
        for (int i = 0; i < 24; i++) {
            files = appendFile(files, String.format("after_layer_%d.bin", i));
        }
        files = appendFile(files, "final_logits.bin");

        for (String filename : files) {
            Path filepath = dir.resolve(filename);
            if (!Files.exists(filepath)) {
                log(String.format("%-23s | NOT FOUND", filename));
                continue;
            }

            long size = Files.size(filepath);
            float[] data = loadReferenceOutput(filename);
            if (data != null) {
                String first3 = String.format("[%.2f, %.2f, %.2f]",
                    data[0], data[1], data[2]);
                log(String.format("%-23s | %12d | %6d | %s",
                    filename, size, data.length, first3));
            } else {
                log(String.format("%-23s | %12d | ERROR", filename, size));
            }
        }

        log("\n=== Check Complete ===");
    }

    private String[] appendFile(String[] arr, String file) {
        String[] newArr = new String[arr.length + 1];
        System.arraycopy(arr, 0, newArr, 0, arr.length);
        newArr[arr.length] = file;
        return newArr;
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

    private double mean(float[] arr) {
        double sum = 0;
        for (float v : arr) sum += v;
        return sum / arr.length;
    }

    private double std(float[] arr) {
        double mean = mean(arr);
        double sumSq = 0;
        for (float v : arr) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / arr.length);
    }
}
