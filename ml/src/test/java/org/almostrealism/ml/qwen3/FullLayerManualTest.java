package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Tests the full transformer layer (attention + FFN) step by step.
 *
 * <p>The previous manual FFN test showed that FFN-only computation fails.
 * This test includes attention to verify if the combined output is correct.</p>
 */
public class FullLayerManualTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test attention contribution to understand if FFN input is wrong.
     */
    @Test
    public void testAttentionContribution() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/attention_contribution.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Attention Contribution Analysis");
        log("===================================================\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Test layer 23
        int layerIdx = 23;

        // Load PyTorch reference
        float[] pytorchInput = loadReferenceOutput("after_layer_22.bin");
        float[] pytorchOutput = loadReferenceOutput("after_layer_23.bin");

        if (pytorchInput == null || pytorchOutput == null) {
            log("ERROR: Cannot load reference files");
            stateDict.destroy();
            return;
        }

        PackedCollection arInput = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            arInput.setMem(i, pytorchInput[i]);
        }

        log(String.format("=== Layer %d Analysis ===\n", layerIdx));
        log("Input: " + statsFromFloat(pytorchInput));

        // Step 1: Run attention only (with residual)
        log("\n--- Step 1: Attention (with residual) ---");
        SequentialBlock attentionOnly = buildAttentionOnly(config, stateDict, layerIdx, freqCis, position);
        Model attnModel = new Model(shape(config.dim));
        attnModel.add(attentionOnly);
        PackedCollection attnOutput = attnModel.compile().forward(arInput);
        log("After attention (input + attn_contrib): " + stats(attnOutput, config.dim));

        // Compute attention contribution (delta)
        double[] attnDelta = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            attnDelta[i] = attnOutput.toDouble(i) - arInput.toDouble(i);
        }
        log("Attention contribution (delta): " + statsFromDouble(attnDelta));

        // Step 2: Run FFN on attention output (with residual)
        log("\n--- Step 2: FFN (with residual) ---");
        SequentialBlock ffnOnly = buildFFNOnly(config, stateDict, layerIdx);
        Model ffnModel = new Model(shape(config.dim));
        ffnModel.add(ffnOnly);
        PackedCollection ffnOutput = ffnModel.compile().forward(attnOutput);
        log("After FFN (attn_out + ffn_contrib): " + stats(ffnOutput, config.dim));

        // Compute FFN contribution (delta)
        double[] ffnDelta = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            ffnDelta[i] = ffnOutput.toDouble(i) - attnOutput.toDouble(i);
        }
        log("FFN contribution (delta): " + statsFromDouble(ffnDelta));

        // Total output comparison
        log("\n--- Final Comparison ---");
        log("AR final output: " + stats(ffnOutput, config.dim));
        log("PyTorch expected: " + statsFromFloat(pytorchOutput));

        // Total delta comparison
        double[] totalDelta = new double[config.dim];
        double[] expectedDelta = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            totalDelta[i] = ffnOutput.toDouble(i) - arInput.toDouble(i);
            expectedDelta[i] = pytorchOutput[i] - pytorchInput[i];
        }
        log("\nTotal delta (AR): " + statsFromDouble(totalDelta));
        log("Total delta (Expected): " + statsFromDouble(expectedDelta));

        // Error analysis
        double sumError = 0, maxError = 0;
        int maxErrorIdx = 0;
        for (int i = 0; i < config.dim; i++) {
            double error = Math.abs(ffnOutput.toDouble(i) - pytorchOutput[i]);
            sumError += error;
            if (error > maxError) {
                maxError = error;
                maxErrorIdx = i;
            }
        }
        log(String.format("\nFinal error: mean=%.6f, max=%.6f (idx=%d)", sumError / config.dim, maxError, maxErrorIdx));

        // Show top 5 discrepant indices
        log("\n--- Top 5 Discrepant Values ---");
        showTopErrors(ffnOutput, pytorchOutput, config.dim, 5);

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    /**
     * Test if FFN alone can produce correct output when given correct (attention) input.
     */
    @Test
    public void compareFFNInputs() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/ffn_input_comparison.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  FFN Input Comparison: Raw Input vs After-Attention");
        log("===================================================\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Test layer 23
        int layerIdx = 23;
        float[] pytorchInput = loadReferenceOutput("after_layer_22.bin");
        float[] pytorchOutput = loadReferenceOutput("after_layer_23.bin");

        if (pytorchInput == null || pytorchOutput == null) {
            log("ERROR: Cannot load reference files");
            stateDict.destroy();
            return;
        }

        PackedCollection arInput = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            arInput.setMem(i, pytorchInput[i]);
        }

        log("PyTorch layer input: " + statsFromFloat(pytorchInput));

        // Get attention output first
        SequentialBlock attentionOnly = buildAttentionOnly(config, stateDict, layerIdx, freqCis, position);
        Model attnModel = new Model(shape(config.dim));
        attnModel.add(attentionOnly);
        PackedCollection attnOutput = attnModel.compile().forward(arInput);
        log("After attention: " + stats(attnOutput, config.dim));

        // Compare two FFN scenarios:
        log("\n--- Scenario A: FFN with RAW input (after_layer_22) ---");
        SequentialBlock ffnOnlyA = buildFFNOnly(config, stateDict, layerIdx);
        Model ffnModelA = new Model(shape(config.dim));
        ffnModelA.add(ffnOnlyA);
        PackedCollection ffnOutputA = ffnModelA.compile().forward(arInput);
        log("FFN output (raw input): " + stats(ffnOutputA, config.dim));

        log("\n--- Scenario B: FFN with ATTENTION output ---");
        SequentialBlock ffnOnlyB = buildFFNOnly(config, stateDict, layerIdx);
        Model ffnModelB = new Model(shape(config.dim));
        ffnModelB.add(ffnOnlyB);
        PackedCollection ffnOutputB = ffnModelB.compile().forward(attnOutput);
        log("FFN output (attn output): " + stats(ffnOutputB, config.dim));

        log("\n--- PyTorch Expected ---");
        log("Expected output: " + statsFromFloat(pytorchOutput));

        // Errors
        double errorA = computeMeanError(ffnOutputA, pytorchOutput, config.dim);
        double errorB = computeMeanError(ffnOutputB, pytorchOutput, config.dim);
        log(String.format("\nError with raw input: %.6f", errorA));
        log(String.format("Error with attn output: %.6f", errorB));

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    private SequentialBlock buildAttentionOnly(Qwen3Config config, StateDictionary stateDict,
                                               int layerIdx, PackedCollection freqCis,
                                               PackedCollection position) {
        SequentialBlock model = new SequentialBlock(shape(1, config.dim));
        String prefix = String.format("model.layers.%d", layerIdx);

        PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
        PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
        PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
        PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
        PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
        PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
        PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
        PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");
        PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
        PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

        model.accum(attention(
            config.headCount, config.kvHeadCount,
            layerRmsAtt,
            layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq,
            layerQkNormQ, layerQkNormK,
            freqCis,
            p(position)));

        return model;
    }

    private SequentialBlock buildFFNOnly(Qwen3Config config, StateDictionary stateDict, int layerIdx) {
        SequentialBlock model = new SequentialBlock(shape(1, config.dim));
        String prefix = String.format("model.layers.%d", layerIdx);

        PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        model.accum(feedForward(layerRmsFfn, layerW1, layerW2, layerW3));

        return model;
    }

    private double computeMeanError(PackedCollection output, float[] expected, int dim) {
        double sum = 0;
        for (int i = 0; i < dim; i++) {
            sum += Math.abs(output.toDouble(i) - expected[i]);
        }
        return sum / dim;
    }

    private void showTopErrors(PackedCollection output, float[] expected, int dim, int count) {
        // Simple: find max error repeatedly
        boolean[] shown = new boolean[dim];
        for (int n = 0; n < count; n++) {
            double maxE = 0;
            int maxI = 0;
            for (int i = 0; i < dim; i++) {
                if (shown[i]) continue;
                double e = Math.abs(output.toDouble(i) - expected[i]);
                if (e > maxE) {
                    maxE = e;
                    maxI = i;
                }
            }
            shown[maxI] = true;
            log(String.format("  idx=%d: AR=%.4f, Expected=%.4f, Error=%.4f",
                maxI, output.toDouble(maxI), expected[maxI], maxE));
        }
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

    private PackedCollection computeRopeFreqs(Qwen3Config config) {
        int headSize = config.headSize;
        int seqLen = 10;
        double theta = config.ropeTheta;

        int freqDim = headSize / 2;
        PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));

        for (int pos = 0; pos < seqLen; pos++) {
            for (int i = 0; i < freqDim; i++) {
                double freq = 1.0 / Math.pow(theta, (double) (2 * i) / headSize);
                double val = pos * freq;
                freqCis.setMem(pos * freqDim * 2 + i * 2, Math.cos(val));
                freqCis.setMem(pos * freqDim * 2 + i * 2 + 1, Math.sin(val));
            }
        }

        return freqCis;
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
