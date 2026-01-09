/*
 * Copyright 2025 Michael Murray
 */
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
 * Diagnostic test for layer 23 specifically.
 * Tests layer 23 in isolation using layer 22 output as input.
 */
public class Layer23DiagnosticTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test layer 23 in isolation using PyTorch layer 22 output as input.
     * This isolates layer 23 from any accumulated errors.
     */
    @Test
    public void testLayer23Isolated() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_isolated.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 Isolated Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Load PyTorch output after layer 22 as input to layer 23
        float[] layer22Output = loadReferenceOutput("after_layer_22.bin");
        log("Using PyTorch layer 22 output as input (size: " + layer22Output.length + ")");
        log("Input range: [" + min(layer22Output) + ", " + max(layer22Output) + "]");

        PackedCollection input = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, layer22Output[i]);
        }

        // Build just layer 23
        Model layer23Model = new Model(shape(1, config.dim));
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        String prefix = "model.layers.23";
        PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
        PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
        PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
        PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
        PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
        PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
        PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
        PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");
        PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
        PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");
        PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        log("\nLayer 23 weight verification:");
        log("  input_layernorm.weight: first 3 = " +
            layerRmsAtt.toDouble(0) + ", " + layerRmsAtt.toDouble(1) + ", " + layerRmsAtt.toDouble(2));
        log("  q_proj.weight shape: " + layerWq.getShape());
        log("  q_proj.bias: first 3 = " +
            (layerBq != null ? layerBq.toDouble(0) + ", " + layerBq.toDouble(1) + ", " + layerBq.toDouble(2) : "null"));

        layer23Model.add(transformer(
            config.headCount, config.kvHeadCount,
            layerRmsAtt, layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq, layerQkNormQ, layerQkNormK,
            freqCis, layerRmsFfn, layerW1, layerW2, layerW3,
            p(position), 1e-6));

        log("\nRunning layer 23...");
        PackedCollection output = layer23Model.compile().forward(input);

        // Load PyTorch reference for layer 23
        float[] pytorchLayer23 = loadReferenceOutput("after_layer_23.bin");

        log("\nComparison:");
        log("  PyTorch layer 23 output range: [" + min(pytorchLayer23) + ", " + max(pytorchLayer23) + "]");

        float[] arOutput = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            arOutput[i] = (float) output.toDouble(i);
        }
        log("  AR layer 23 output range: [" + min(arOutput) + ", " + max(arOutput) + "]");

        // Compute error
        double sumAbsDiff = 0;
        double maxAbsDiff = 0;
        int maxDiffIdx = 0;

        for (int i = 0; i < config.dim; i++) {
            double diff = Math.abs(pytorchLayer23[i] - arOutput[i]);
            sumAbsDiff += diff;
            if (diff > maxAbsDiff) {
                maxAbsDiff = diff;
                maxDiffIdx = i;
            }
        }

        double mae = sumAbsDiff / config.dim;
        log("\nLayer 23 Isolated Error:");
        log("  MAE: " + mae);
        log("  Max Error: " + maxAbsDiff + " at index " + maxDiffIdx);
        log("  PyTorch[" + maxDiffIdx + "]: " + pytorchLayer23[maxDiffIdx]);
        log("  AR[" + maxDiffIdx + "]: " + arOutput[maxDiffIdx]);

        log("\nFirst 10 values:");
        log(String.format("%-5s %-15s %-15s %-15s", "Idx", "PyTorch", "AR", "Diff"));
        log("-".repeat(55));
        for (int i = 0; i < 10; i++) {
            log(String.format("%-5d %-15.6f %-15.6f %-15.6f",
                i, pytorchLayer23[i], arOutput[i], pytorchLayer23[i] - arOutput[i]));
        }

        if (mae < 0.01) {
            log("\n[PASS] Layer 23 isolated test passed - MAE < 0.01");
        } else if (mae < 0.1) {
            log("\n[WARNING] Layer 23 shows small divergence");
        } else {
            log("\n[FAIL] Layer 23 shows significant divergence!");
            log("This indicates the bug is IN layer 23, not in accumulated errors.");
        }

        stateDict.destroy();
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

    private float[] loadReferenceOutput(String filename) throws IOException {
        try (FileChannel channel = FileChannel.open(
                Paths.get(REFERENCE_DIR, filename), StandardOpenOption.READ)) {
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
        }
    }

    private float min(float[] arr) {
        float m = Float.MAX_VALUE;
        for (float v : arr) m = Math.min(m, v);
        return m;
    }

    private float max(float[] arr) {
        float m = Float.MIN_VALUE;
        for (float v : arr) m = Math.max(m, v);
        return m;
    }
}
