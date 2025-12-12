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
 * Isolate the dense layer operation for layer 23 to verify matrix multiplication.
 *
 * Tests:
 * 1. Weight loading verification - are layer 22 and 23 weights actually different?
 * 2. Manual matrix multiplication vs dense() layer
 * 3. Individual projection layers (gate_proj, up_proj, down_proj)
 */
public class Layer23DenseIsolationTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void verifyWeightsAreDifferent() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_weight_verify.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Weight Loading Verification Test ===\n");
        log("Verifying layer 22 and 23 weights are actually different\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        String[] weights = {
            "post_attention_layernorm.weight",
            "mlp.gate_proj.weight",
            "mlp.up_proj.weight",
            "mlp.down_proj.weight"
        };

        for (String weightName : weights) {
            PackedCollection w22 = stateDict.get("model.layers.22." + weightName);
            PackedCollection w23 = stateDict.get("model.layers.23." + weightName);

            if (w22 == null || w23 == null) {
                log(String.format("%s: MISSING", weightName));
                continue;
            }

            // Check if they're the same object
            boolean sameObject = (w22 == w23);

            // Check first 10 values
            boolean firstValuesSame = true;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("\n%s:\n", weightName));
            sb.append("  Layer 22 first 5: [");
            for (int i = 0; i < 5; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("%.6f", w22.toDouble(i)));
            }
            sb.append("]\n");
            sb.append("  Layer 23 first 5: [");
            for (int i = 0; i < 5; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("%.6f", w23.toDouble(i)));
                if (Math.abs(w22.toDouble(i) - w23.toDouble(i)) > 1e-6) {
                    firstValuesSame = false;
                }
            }
            sb.append("]");
            log(sb.toString());

            // Count differing values
            int size = (int) w22.getShape().getSize();
            int diffCount = 0;
            double maxDiff = 0;
            for (int i = 0; i < size; i++) {
                double diff = Math.abs(w22.toDouble(i) - w23.toDouble(i));
                if (diff > 1e-6) diffCount++;
                if (diff > maxDiff) maxDiff = diff;
            }

            log(String.format("  Same object: %s", sameObject));
            log(String.format("  Differing values: %d/%d (%.1f%%)", diffCount, size, 100.0 * diffCount / size));
            log(String.format("  Max difference: %.6f", maxDiff));

            if (diffCount == 0) {
                log("  *** WARNING: Weights are IDENTICAL! ***");
            }
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    @Test
    public void testDenseLayerDirectly() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_dense_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Dense Layer Direct Test ===\n");
        log("Testing dense() layer output vs manual matrix multiplication\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Create test input
        PackedCollection testInput = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            testInput.setMem(i, Math.sin(i * 0.01) * 2.0);
        }

        // Test gate_proj for both layers
        for (int layerIdx : new int[]{22, 23}) {
            log(String.format("\n=== Layer %d gate_proj ===\n", layerIdx));

            String prefix = String.format("model.layers.%d", layerIdx);
            PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");

            // Verify weight shape
            log(String.format("Weight shape: %s", w1.getShape()));
            log(String.format("Expected shape: [%d, %d]", config.hiddenDim, config.dim));

            // Use dense() layer
            Model denseModel = new Model(shape(config.dim));
            denseModel.add(dense(w1));
            PackedCollection denseOutput = denseModel.compile().forward(testInput);

            // Manual matrix multiplication (simple reference)
            double[] manualOutput = new double[config.hiddenDim];
            for (int i = 0; i < config.hiddenDim; i++) {
                double sum = 0;
                for (int j = 0; j < config.dim; j++) {
                    // Weight matrix is [hiddenDim, dim], so w[i,j] = w[i*dim + j]
                    double weight = w1.toDouble(i * config.dim + j);
                    double input = testInput.toDouble(j);
                    sum += weight * input;
                }
                manualOutput[i] = sum;
            }

            // Compare outputs
            double maxError = 0;
            int maxErrorIdx = 0;
            double sumError = 0;
            for (int i = 0; i < config.hiddenDim; i++) {
                double error = Math.abs(denseOutput.toDouble(i) - manualOutput[i]);
                sumError += error;
                if (error > maxError) {
                    maxError = error;
                    maxErrorIdx = i;
                }
            }

            log(String.format("Dense output first 5: [%.4f, %.4f, %.4f, %.4f, %.4f]",
                denseOutput.toDouble(0), denseOutput.toDouble(1), denseOutput.toDouble(2),
                denseOutput.toDouble(3), denseOutput.toDouble(4)));
            log(String.format("Manual output first 5: [%.4f, %.4f, %.4f, %.4f, %.4f]",
                manualOutput[0], manualOutput[1], manualOutput[2], manualOutput[3], manualOutput[4]));
            log(String.format("Mean error: %.10f", sumError / config.hiddenDim));
            log(String.format("Max error: %.10f at index %d", maxError, maxErrorIdx));

            if (maxError < 1e-4) {
                log("STATUS: dense() matches manual computation - GOOD");
            } else {
                log("STATUS: dense() DIFFERS from manual computation - INVESTIGATE");
            }
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    @Test
    public void testFFNWithPyTorchInput() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_ffn_pytorch_input.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== FFN Test with PyTorch Input ===\n");
        log("Using after_layer_22 as input, comparing FFN output\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Load PyTorch reference
        float[] pytorchInput = loadReferenceOutput("after_layer_22.bin");
        float[] pytorchOutput = loadReferenceOutput("after_layer_23.bin");

        if (pytorchInput == null || pytorchOutput == null) {
            log("ERROR: Cannot load reference files");
            return;
        }

        PackedCollection input = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, pytorchInput[i]);
        }

        // Test layer 23 FFN only (no attention, no residual)
        log("\n=== Layer 23 FFN Only (no residual) ===\n");

        String prefix = "model.layers.23";
        PackedCollection rmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        // Test step by step
        log("Step 1: RMSNorm");
        Model rmsModel = new Model(shape(config.dim));
        rmsModel.add(rmsnorm(shape(1, config.dim), rmsFfn));
        PackedCollection rmsOut = rmsModel.compile().forward(input);
        logStats("  RMSNorm output", rmsOut, config.dim);

        log("\nStep 2: Gate projection (w1)");
        Model gateModel = new Model(shape(config.dim));
        gateModel.add(dense(w1));
        PackedCollection gateOut = gateModel.compile().forward(rmsOut);
        logStats("  gate_proj output", gateOut, config.hiddenDim);

        log("\nStep 3: SiLU activation");
        Model siluModel = new Model(shape(config.hiddenDim));
        siluModel.add(silu());
        PackedCollection siluOut = siluModel.compile().forward(gateOut);
        logStats("  SiLU output", siluOut, config.hiddenDim);

        log("\nStep 4: Up projection (w3)");
        Model upModel = new Model(shape(config.dim));
        upModel.add(dense(w3));
        PackedCollection upOut = upModel.compile().forward(rmsOut);
        logStats("  up_proj output", upOut, config.hiddenDim);

        log("\nStep 5: Element-wise multiply (SiLU * up)");
        double[] mulOut = new double[config.hiddenDim];
        for (int i = 0; i < config.hiddenDim; i++) {
            mulOut[i] = siluOut.toDouble(i) * upOut.toDouble(i);
        }
        logStats("  multiply output", mulOut, config.hiddenDim);

        log("\nStep 6: Down projection (w2)");
        PackedCollection mulInput = new PackedCollection(shape(config.hiddenDim));
        for (int i = 0; i < config.hiddenDim; i++) {
            mulInput.setMem(i, mulOut[i]);
        }
        Model downModel = new Model(shape(config.hiddenDim));
        downModel.add(dense(w2));
        PackedCollection downOut = downModel.compile().forward(mulInput);
        logStats("  down_proj output (FFN result)", downOut, config.dim);

        log("\nStep 7: Add residual");
        double[] finalOut = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            finalOut[i] = input.toDouble(i) + downOut.toDouble(i);
        }
        logStats("  Final output (with residual)", finalOut, config.dim);

        log("\nExpected PyTorch output:");
        logStats("  PyTorch after_layer_23", pytorchOutput);

        // Compute error
        double sumError = 0;
        double maxError = 0;
        for (int i = 0; i < config.dim; i++) {
            double error = Math.abs(finalOut[i] - pytorchOutput[i]);
            sumError += error;
            if (error > maxError) maxError = error;
        }
        log(String.format("\nError: mean=%.6f, max=%.6f", sumError / config.dim, maxError));

        // Also compute the delta (contribution from this layer)
        log("\n=== Delta Analysis ===");
        double[] expectedDelta = new double[config.dim];
        double[] arDelta = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            expectedDelta[i] = pytorchOutput[i] - pytorchInput[i];
            arDelta[i] = finalOut[i] - pytorchInput[i];
        }
        logStatsDouble("Expected delta (PyTorch)", expectedDelta);
        logStatsDouble("AR delta", arDelta);

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    private void logStats(String name, PackedCollection c, int size) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double v = c.toDouble(i);
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / size;
        double std = Math.sqrt(sumSq / size - mean * mean);
        log(String.format("%s: mean=%.6f, std=%.6f, min=%.6f, max=%.6f", name, mean, std, min, max));
    }

    private void logStats(String name, double[] arr, int size) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double v = arr[i];
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / size;
        double std = Math.sqrt(sumSq / size - mean * mean);
        log(String.format("%s: mean=%.6f, std=%.6f, min=%.6f, max=%.6f", name, mean, std, min, max));
    }

    private void logStats(String name, float[] arr) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (float v : arr) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / arr.length;
        double std = Math.sqrt(sumSq / arr.length - mean * mean);
        log(String.format("%s: mean=%.6f, std=%.6f, min=%.6f, max=%.6f", name, mean, std, min, max));
    }

    private void logStatsDouble(String name, double[] arr) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : arr) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / arr.length;
        double std = Math.sqrt(sumSq / arr.length - mean * mean);
        log(String.format("%s: mean=%.6f, std=%.6f, min=%.6f, max=%.6f", name, mean, std, min, max));
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
