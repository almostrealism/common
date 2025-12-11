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
 * Test individual transformer layers in isolation using PyTorch reference inputs.
 *
 * This test feeds the CORRECT PyTorch output from layer N-1 into AR layer N,
 * to determine if the layer itself is correct vs error accumulation from prior layers.
 */
public class IsolatedLayerTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void testIsolatedLayers() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/isolated_layer_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Isolated Layer Test ===\n");
        log("Testing each layer with PyTorch reference input (not accumulated AR output)\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Test layers 20, 21, 22, 23 individually
        int[] layersToTest = {20, 21, 22, 23};

        log("| Layer | Input Source | Mean Abs Error | Max Error | Status |");
        log("|-------|--------------|----------------|-----------|--------|");

        for (int layerIdx : layersToTest) {
            // Load PyTorch input (output from previous layer)
            String inputFile = layerIdx == 0 ? "after_embeddings.bin" :
                              String.format("after_layer_%d.bin", layerIdx - 1);
            float[] pytorchInput = loadReferenceOutput(inputFile);

            // Load PyTorch expected output (output from this layer)
            String outputFile = String.format("after_layer_%d.bin", layerIdx);
            float[] pytorchOutput = loadReferenceOutput(outputFile);

            if (pytorchInput == null || pytorchOutput == null) {
                log(String.format("| %d | SKIP | - | - | Missing reference file |", layerIdx));
                continue;
            }

            // Create AR input from PyTorch reference
            PackedCollection arInput = new PackedCollection(shape(config.dim));
            for (int i = 0; i < config.dim; i++) {
                arInput.setMem(i, pytorchInput[i]);
            }

            // Build single layer model
            Model singleLayer = buildSingleLayer(config, stateDict, layerIdx, freqCis, position);
            org.almostrealism.model.CompiledModel compiled = singleLayer.compile();

            // Run AR layer
            PackedCollection arOutput = compiled.forward(arInput);

            // Compare
            double sumAbsError = 0.0;
            double maxAbsError = 0.0;
            for (int i = 0; i < config.dim; i++) {
                double diff = Math.abs(pytorchOutput[i] - arOutput.toDouble(i));
                sumAbsError += diff;
                if (diff > maxAbsError) maxAbsError = diff;
            }
            double meanAbsError = sumAbsError / config.dim;

            String status = meanAbsError < 0.001 ? "EXCELLENT" :
                           meanAbsError < 0.01 ? "GOOD" :
                           meanAbsError < 0.1 ? "ACCEPTABLE" : "POOR";

            log(String.format("| %d | after_layer_%d | %.6f | %.6f | %s |",
                layerIdx, layerIdx - 1, meanAbsError, maxAbsError, status));
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
        log("Results saved to: " + logFile);
    }

    /**
     * Build a model containing only a single transformer layer.
     */
    private Model buildSingleLayer(Qwen3Config config, StateDictionary stateDict,
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

        PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
        PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

        PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        model.add(transformer(
            config.headCount, config.kvHeadCount,
            layerRmsAtt,
            layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq,
            layerQkNormQ, layerQkNormK,
            freqCis,
            layerRmsFfn,
            layerW1, layerW2, layerW3,
            p(position)));

        return model;
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
