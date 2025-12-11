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
 * Debug layer 23 by testing attention and FFN components separately.
 *
 * This test compares layer 22 (which works) against layer 23 (which fails)
 * to isolate which component is causing the issue.
 */
public class Layer23ComponentTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void compareAttentionAndFFN() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_component_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 Component Debug Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Test both layer 22 (works) and layer 23 (broken) for comparison
        int[] layersToTest = {22, 23};

        log("Testing attention-only and FFN-only models for layers 22 and 23\n");
        log("Using PyTorch reference inputs to isolate errors\n");

        for (int layerIdx : layersToTest) {
            log(String.format("\n=== Layer %d ===\n", layerIdx));

            // Load PyTorch reference input (output from previous layer)
            String inputFile = String.format("after_layer_%d.bin", layerIdx - 1);
            float[] pytorchInput = loadReferenceOutput(inputFile);
            if (pytorchInput == null) {
                log("SKIP: Missing reference input file");
                continue;
            }

            // Load PyTorch expected output
            String outputFile = String.format("after_layer_%d.bin", layerIdx);
            float[] pytorchOutput = loadReferenceOutput(outputFile);
            if (pytorchOutput == null) {
                log("SKIP: Missing reference output file");
                continue;
            }

            // Create AR input from PyTorch reference
            PackedCollection arInput = new PackedCollection(shape(config.dim));
            for (int i = 0; i < config.dim; i++) {
                arInput.setMem(i, pytorchInput[i]);
            }

            // Test full layer
            log("\n--- Full Transformer Layer ---");
            Model fullLayer = buildFullLayer(config, stateDict, layerIdx, freqCis, position);
            org.almostrealism.model.CompiledModel compiledFull = fullLayer.compile();
            PackedCollection fullOutput = compiledFull.forward(arInput);
            compareOutputs("Full Layer", pytorchOutput, fullOutput, config.dim);

            // Test attention-only (with residual)
            log("\n--- Attention Only (with residual) ---");
            SequentialBlock attentionOnly = buildAttentionOnly(config, stateDict, layerIdx, freqCis, position);
            Model attnModel = new Model(shape(config.dim));
            attnModel.add(attentionOnly);
            org.almostrealism.model.CompiledModel compiledAttn = attnModel.compile();
            PackedCollection attnOutput = compiledAttn.forward(arInput);
            logOutputStats("Attention output", attnOutput, config.dim);

            // Test FFN-only (with residual) - using attention output as input
            log("\n--- FFN Only (with residual) ---");
            SequentialBlock ffnOnly = buildFFNOnly(config, stateDict, layerIdx);
            Model ffnModel = new Model(shape(config.dim));
            ffnModel.add(ffnOnly);
            org.almostrealism.model.CompiledModel compiledFFN = ffnModel.compile();
            PackedCollection ffnOutput = compiledFFN.forward(attnOutput);
            compareOutputs("FFN output", pytorchOutput, ffnOutput, config.dim);

            // Log intermediate value statistics
            log("\n--- Intermediate Statistics ---");
            logInputStats("Layer input", arInput, config.dim);
            logOutputStats("After attention", attnOutput, config.dim);
            logOutputStats("After FFN (final)", ffnOutput, config.dim);
            logExpectedStats("Expected output", pytorchOutput);
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
        log("Results saved to: " + logFile);
    }

    @Test
    public void testRMSNormComponent() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_rmsnorm_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 RMSNorm Component Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Test RMSNorm behavior for layers 22 and 23
        for (int layerIdx : new int[]{22, 23}) {
            log(String.format("\n=== Layer %d RMSNorm ===\n", layerIdx));

            String inputFile = String.format("after_layer_%d.bin", layerIdx - 1);
            float[] pytorchInput = loadReferenceOutput(inputFile);
            if (pytorchInput == null) {
                log("SKIP: Missing reference input");
                continue;
            }

            PackedCollection arInput = new PackedCollection(shape(config.dim));
            for (int i = 0; i < config.dim; i++) {
                arInput.setMem(i, pytorchInput[i]);
            }

            // Get RMSNorm weights
            String prefix = String.format("model.layers.%d", layerIdx);
            PackedCollection rmsAttWeight = stateDict.get(prefix + ".input_layernorm.weight");
            PackedCollection rmsFfnWeight = stateDict.get(prefix + ".post_attention_layernorm.weight");

            // Test input layernorm
            Model rmsModel = new Model(shape(config.dim));
            rmsModel.add(rmsnorm(shape(1, config.dim), rmsAttWeight));
            org.almostrealism.model.CompiledModel compiled = rmsModel.compile();
            PackedCollection rmsOutput = compiled.forward(arInput);

            log("Input LayerNorm (input_layernorm):");
            logOutputStats("  RMSNorm output", rmsOutput, config.dim);
            logWeightStats("  RMSNorm weights", rmsAttWeight);

            // Check for unusual values
            double maxVal = 0;
            double minVal = Double.MAX_VALUE;
            for (int i = 0; i < config.dim; i++) {
                double v = Math.abs(rmsOutput.toDouble(i));
                if (v > maxVal) maxVal = v;
                if (v < minVal) minVal = v;
            }
            log(String.format("  Max abs value: %.6f, Min abs value: %.6f", maxVal, minVal));
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

        // Build attention block with residual (accum)
        model.accum(attention(
            config.headCount, config.kvHeadCount,
            layerRmsAtt,
            layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq,
            null, null,  // No QK-Norm
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

        // Build FFN block with residual (accum)
        model.accum(feedForward(layerRmsFfn, layerW1, layerW2, layerW3));

        return model;
    }

    private void compareOutputs(String name, float[] expected, PackedCollection actual, int dim) {
        double sumAbsError = 0.0;
        double maxAbsError = 0.0;
        int maxErrorIdx = 0;

        for (int i = 0; i < dim; i++) {
            double diff = Math.abs(expected[i] - actual.toDouble(i));
            sumAbsError += diff;
            if (diff > maxAbsError) {
                maxAbsError = diff;
                maxErrorIdx = i;
            }
        }

        double meanAbsError = sumAbsError / dim;
        String status = meanAbsError < 0.001 ? "EXCELLENT" :
                       meanAbsError < 0.01 ? "GOOD" :
                       meanAbsError < 0.1 ? "ACCEPTABLE" : "POOR";

        log(String.format("%s: MeanErr=%.6f, MaxErr=%.6f (idx=%d), Status=%s",
            name, meanAbsError, maxAbsError, maxErrorIdx, status));
    }

    private void logOutputStats(String name, PackedCollection output, int dim) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < dim; i++) {
            double v = output.toDouble(i);
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / dim;
        double std = Math.sqrt(sumSq / dim - mean * mean);
        log(String.format("%s: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
            name, mean, std, min, max));
    }

    private void logInputStats(String name, PackedCollection input, int dim) {
        logOutputStats(name, input, dim);
    }

    private void logExpectedStats(String name, float[] expected) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (float v : expected) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / expected.length;
        double std = Math.sqrt(sumSq / expected.length - mean * mean);
        log(String.format("%s: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
            name, mean, std, min, max));
    }

    private void logWeightStats(String name, PackedCollection weight) {
        int size = (int) weight.getShape().getSize();
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double v = weight.toDouble(i);
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / size;
        double std = Math.sqrt(sumSq / size - mean * mean);
        log(String.format("%s: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
            name, mean, std, min, max));
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
