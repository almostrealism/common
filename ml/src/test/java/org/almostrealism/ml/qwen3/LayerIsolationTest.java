package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertTrue;

/**
 * Tests problematic layers (2, 22, 23) in complete isolation to determine if the
 * error is in the layer implementation or in how layers are stacked.
 */
public class LayerIsolationTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void testLayer2InIsolation() throws Exception {
        testLayerInIsolation(2);
    }

    @Test
    public void testLayer22InIsolation() throws Exception {
        testLayerInIsolation(22);
    }

    @Test
    public void testLayer23InIsolation() throws Exception {
        testLayerInIsolation(23);
    }

    @Test
    public void testAllProblematicLayersSequentially() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer_isolation_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Testing Problematic Layers in Isolation ===\n");

        // Test all problematic layers and some control layers
        int[] layersToTest = {0, 1, 2, 3, 21, 22, 23};  // Include good layers for comparison

        for (int layer : layersToTest) {
            log(String.format("\n--- Testing Layer %d ---", layer));
            try {
                double error = testLayerInIsolation(layer);
                log(String.format("Layer %d: Mean absolute error = %.6f", layer, error));

                // Flag problematic behavior
                if (layer == 2 || layer == 22 || layer == 23) {
                    if (error > 0.1) {
                        log(String.format("[ERROR] Layer %d has high error (%.6f) in isolation!", layer, error));
                    } else {
                        log(String.format("[INTERESTING] Layer %d works well in isolation (%.6f) but fails in stack", layer, error));
                    }
                }
            } catch (Exception e) {
                log("Failed to test layer " + layer + ": " + e.getMessage());
            }
        }

        log("\n=== Isolation Test Complete ===");
    }

    private double testLayerInIsolation(int layerIndex) throws Exception {
        log(String.format("\nTesting layer %d in complete isolation...", layerIndex));

        Qwen3Config config = new Qwen3Config(
            896,      // dim
            4864,     // hiddenDim
            24,       // layerCount
            14,       // headCount
            2,        // kvHeadCount
            151936,   // vocabSize
            32768,    // seqLen
            true,     // sharedWeights
            1000000.0 // ropeTheta
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Load input from previous layer
        String inputFile = layerIndex == 0 ? "after_embeddings.bin" :
                          String.format("after_layer_%d.bin", layerIndex - 1);
        float[] inputData = loadReferenceOutput(inputFile);

        if (inputData == null) {
            log(String.format("WARNING: No reference input found for layer %d (%s)", layerIndex, inputFile));
            return Double.MAX_VALUE;
        }

        PackedCollection<?> input = new PackedCollection<>(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, inputData[i]);
        }

        // Build ONLY this single layer
        Model singleLayer = buildSingleLayer(config, stateDict, layerIndex);

        // Compile and run
        org.almostrealism.model.CompiledModel compiled = singleLayer.compile();
        PackedCollection<?> output = compiled.forward(input);

        // Load expected output
        String outputFile = String.format("after_layer_%d.bin", layerIndex);
        float[] expectedData = loadReferenceOutput(outputFile);

        if (expectedData == null) {
            log(String.format("WARNING: No reference output found for layer %d (%s)", layerIndex, outputFile));
            return Double.MAX_VALUE;
        }

        // Compare outputs
        double sumAbsError = 0.0;
        double sumSqError = 0.0;
        double maxError = 0.0;
        int maxErrorIdx = 0;

        for (int i = 0; i < config.dim; i++) {
            double actual = output.toDouble(i);
            double expected = expectedData[i];
            double diff = Math.abs(actual - expected);

            sumAbsError += diff;
            sumSqError += diff * diff;

            if (diff > maxError) {
                maxError = diff;
                maxErrorIdx = i;
            }
        }

        double meanAbsError = sumAbsError / config.dim;
        double rmse = Math.sqrt(sumSqError / config.dim);

        log(String.format("Layer %d Isolation Test Results:", layerIndex));
        log(String.format("  Mean Absolute Error: %.6f", meanAbsError));
        log(String.format("  RMSE: %.6f", rmse));
        log(String.format("  Max Error: %.6f at index %d", maxError, maxErrorIdx));

        // Check first few values for sanity
        log("  First 5 values comparison:");
        for (int i = 0; i < Math.min(5, config.dim); i++) {
            log(String.format("    [%d] AR: %.6f, PT: %.6f, diff: %.6f",
                i, output.toDouble(i), expectedData[i],
                Math.abs(output.toDouble(i) - expectedData[i])));
        }

        // Check if this is acceptable error
        if (meanAbsError < 0.001) {
            log("  [EXCELLENT] Layer works correctly in isolation");
        } else if (meanAbsError < 0.01) {
            log("  [GOOD] Layer has minor numerical differences");
        } else if (meanAbsError < 0.1) {
            log("  [WARNING] Layer has significant error even in isolation");
        } else {
            log("  [ERROR] Layer is completely broken!");
        }

        stateDict.destroy();

        return meanAbsError;
    }

    private Model buildSingleLayer(Qwen3Config config, StateDictionary stateDict, int layerIndex) {
        Model model = new Model(shape(config.dim));

        String prefix = String.format("model.layers.%d", layerIndex);

        // Load all weights for this layer
        PackedCollection<?> layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
        PackedCollection<?> layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");

        // Attention weights
        PackedCollection<?> layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
        PackedCollection<?> layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
        PackedCollection<?> layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
        PackedCollection<?> layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");

        // Attention biases
        PackedCollection<?> layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
        PackedCollection<?> layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
        PackedCollection<?> layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");

        // QK-Norm weights
        PackedCollection<?> layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
        PackedCollection<?> layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

        // FFN weights
        PackedCollection<?> layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection<?> layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection<?> layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        // Compute RoPE frequencies
        PackedCollection<?> freqCis = computeRopeFreqs(config);

        // Position at 0 (first token)
        PackedCollection<?> position = new PackedCollection<>(shape(1));
        position.setMem(0, 0.0);

        // Add the transformer layer
        model.add(transformer(
            config.headCount,     // 14 query heads
            config.kvHeadCount,   // 2 KV heads
            layerRmsAtt,          // Pre-attention norm
            layerWk, layerWv, layerWq, layerWo,  // Attention projections
            layerBk, layerBv, layerBq,           // Attention biases
            layerQkNormQ, layerQkNormK,          // QK-Norm weights
            freqCis,                              // RoPE frequencies
            layerRmsFfn,                          // Pre-FFN norm
            layerW1, layerW2, layerW3,           // FFN projections
            p(position)));                        // Current position

        return model;
    }

    private PackedCollection<?> computeRopeFreqs(Qwen3Config config) {
        int headSize = config.headSize;
        int seqLen = 10;  // Small for testing single token
        double theta = config.ropeTheta;

        int freqDim = headSize / 2;
        PackedCollection<?> freqCis = new PackedCollection<>(shape(seqLen, freqDim, 2));

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