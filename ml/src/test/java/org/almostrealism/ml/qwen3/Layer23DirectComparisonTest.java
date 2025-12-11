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
 * Direct comparison test to verify layer 23 delta (output - input) matches PyTorch.
 *
 * This test computes the transformation delta for each layer and compares against
 * PyTorch's delta, which helps isolate the exact source of divergence.
 */
public class Layer23DirectComparisonTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void compareLayerDeltas() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer_delta_comparison.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer Delta Comparison Test ===\n");
        log("Testing layers 20-23 by comparing (AR_output - input) against (PyTorch_output - input)\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        log("| Layer | Input Std | Delta Std (Expected) | Delta Std (AR) | Delta Error | Full Error |");
        log("|-------|-----------|---------------------|----------------|-------------|------------|");

        for (int layerIdx = 20; layerIdx <= 23; layerIdx++) {
            // Load input (output from previous layer)
            String inputFile = String.format("after_layer_%d.bin", layerIdx - 1);
            float[] input = loadReferenceOutput(inputFile);
            if (input == null) {
                log(String.format("| %d | SKIP - Missing input file |", layerIdx));
                continue;
            }

            // Load expected output
            String outputFile = String.format("after_layer_%d.bin", layerIdx);
            float[] expectedOutput = loadReferenceOutput(outputFile);
            if (expectedOutput == null) {
                log(String.format("| %d | SKIP - Missing output file |", layerIdx));
                continue;
            }

            // Compute expected delta (PyTorch output - input)
            float[] expectedDelta = new float[config.dim];
            double expectedDeltaStd = computeDelta(input, expectedOutput, expectedDelta);

            // Run AR layer
            PackedCollection arInput = new PackedCollection(shape(config.dim));
            for (int i = 0; i < config.dim; i++) {
                arInput.setMem(i, input[i]);
            }

            Model layer = buildFullLayer(config, stateDict, layerIdx, freqCis, position);
            PackedCollection arOutput = layer.compile().forward(arInput);

            // Compute AR delta (AR output - input)
            float[] arDelta = new float[config.dim];
            double arDeltaStd = computeDeltaAR(input, arOutput, arDelta);

            // Compare deltas and full outputs
            double deltaError = computeError(expectedDelta, arDelta);
            double fullError = computeErrorAR(expectedOutput, arOutput);
            double inputStd = computeStd(input);

            log(String.format("| %d | %.4f | %.4f | %.4f | %.6f | %.6f |",
                layerIdx, inputStd, expectedDeltaStd, arDeltaStd, deltaError, fullError));
        }

        // Detailed analysis for layer 23
        log("\n=== Detailed Layer 23 Analysis ===\n");

        float[] input22 = loadReferenceOutput("after_layer_22.bin");
        float[] output23 = loadReferenceOutput("after_layer_23.bin");

        if (input22 != null && output23 != null) {
            PackedCollection arInput = new PackedCollection(shape(config.dim));
            for (int i = 0; i < config.dim; i++) {
                arInput.setMem(i, input22[i]);
            }

            Model layer23 = buildFullLayer(config, stateDict, 23, freqCis, position);
            PackedCollection arOutput = layer23.compile().forward(arInput);

            log("First 10 values comparison:");
            log("| Idx | Input | Expected | AR Output | Exp Delta | AR Delta | Abs Error |");
            log("|-----|-------|----------|-----------|-----------|----------|-----------|");

            for (int i = 0; i < 10; i++) {
                double expDelta = output23[i] - input22[i];
                double arDelta = arOutput.toDouble(i) - input22[i];
                double error = Math.abs(output23[i] - arOutput.toDouble(i));

                log(String.format("| %d | %.4f | %.4f | %.4f | %.4f | %.4f | %.4f |",
                    i, input22[i], output23[i], arOutput.toDouble(i), expDelta, arDelta, error));
            }

            // Find indices with largest errors
            log("\nTop 10 largest errors:");
            log("| Idx | Expected | AR Output | Error |");
            log("|-----|----------|-----------|-------|");

            int[] topErrorIdx = findTopErrors(output23, arOutput, 10);
            for (int idx : topErrorIdx) {
                double error = Math.abs(output23[idx] - arOutput.toDouble(idx));
                log(String.format("| %d | %.4f | %.4f | %.4f |",
                    idx, output23[idx], arOutput.toDouble(idx), error));
            }
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    private Model buildFullLayer(Qwen3Config config, StateDictionary stateDict,
                                  int layerIdx, PackedCollection freqCis,
                                  PackedCollection position) {
        Model model = new Model(shape(config.dim));
        String prefix = String.format("model.layers.%d", layerIdx);

        PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
        PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
        PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
        PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
        PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
        PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
        PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
        PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");
        PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        model.add(transformer(
            config.headCount, config.kvHeadCount,
            layerRmsAtt,
            layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq,
            null, null,  // No QK-Norm
            freqCis,
            layerRmsFfn,
            layerW1, layerW2, layerW3,
            p(position)));

        return model;
    }

    private double computeDelta(float[] input, float[] output, float[] delta) {
        double sum = 0, sumSq = 0;
        for (int i = 0; i < delta.length; i++) {
            delta[i] = output[i] - input[i];
            sum += delta[i];
            sumSq += delta[i] * delta[i];
        }
        double mean = sum / delta.length;
        return Math.sqrt(sumSq / delta.length - mean * mean);
    }

    private double computeDeltaAR(float[] input, PackedCollection output, float[] delta) {
        double sum = 0, sumSq = 0;
        for (int i = 0; i < delta.length; i++) {
            delta[i] = (float)(output.toDouble(i) - input[i]);
            sum += delta[i];
            sumSq += delta[i] * delta[i];
        }
        double mean = sum / delta.length;
        return Math.sqrt(sumSq / delta.length - mean * mean);
    }

    private double computeError(float[] expected, float[] actual) {
        double sum = 0;
        for (int i = 0; i < expected.length; i++) {
            sum += Math.abs(expected[i] - actual[i]);
        }
        return sum / expected.length;
    }

    private double computeErrorAR(float[] expected, PackedCollection actual) {
        double sum = 0;
        for (int i = 0; i < expected.length; i++) {
            sum += Math.abs(expected[i] - actual.toDouble(i));
        }
        return sum / expected.length;
    }

    private double computeStd(float[] arr) {
        double sum = 0, sumSq = 0;
        for (float v : arr) {
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / arr.length;
        return Math.sqrt(sumSq / arr.length - mean * mean);
    }

    private int[] findTopErrors(float[] expected, PackedCollection actual, int count) {
        double[] errors = new double[expected.length];
        for (int i = 0; i < expected.length; i++) {
            errors[i] = Math.abs(expected[i] - actual.toDouble(i));
        }

        int[] result = new int[count];
        for (int c = 0; c < count; c++) {
            int maxIdx = 0;
            for (int i = 1; i < errors.length; i++) {
                if (errors[i] > errors[maxIdx]) maxIdx = i;
            }
            result[c] = maxIdx;
            errors[maxIdx] = -1;
        }
        return result;
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
}
