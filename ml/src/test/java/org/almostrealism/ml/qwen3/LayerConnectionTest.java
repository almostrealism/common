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
 * Tests how transformer blocks are connected, focusing on the problematic
 * layer boundaries (2->3, 22->23, 23->24) where error accumulation spikes.
 */
public class LayerConnectionTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void testLayerBoundary2to3() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer_boundary_2_3.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Testing Layer Boundary 2->3 (15x error spike) ===\n");
        testLayerBoundary(2, 3);
    }

    @Test
    public void testLayerBoundary22to23() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer_boundary_22_23.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Testing Layer Boundary 22->23 (3.8x error spike) ===\n");
        testLayerBoundary(22, 23);
    }

    @Test
    public void testControlBoundary3to4() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer_boundary_3_4.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Testing Control Layer Boundary 3->4 (should be stable) ===\n");
        testLayerBoundary(3, 4);
    }

    @Test
    public void testSequentialLayerStacking() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/sequential_stacking_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Testing Sequential Layer Stacking ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Test how layers 1, 2, 3 are stacked together
        testSequentialStack(config, stateDict, new int[]{1, 2, 3});

        // Test how layers 21, 22, 23 are stacked together
        testSequentialStack(config, stateDict, new int[]{21, 22, 23});

        stateDict.destroy();
    }

    private void testLayerBoundary(int layerA, int layerB) throws Exception {
        log(String.format("Testing boundary between layers %d and %d...\n", layerA, layerB));

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Test 1: Run layers separately
        log("\n--- Test 1: Running layers separately ---");
        testLayersSeparately(config, stateDict, layerA, layerB);

        // Test 2: Run layers connected
        log("\n--- Test 2: Running layers connected ---");
        testLayersConnected(config, stateDict, layerA, layerB);

        // Test 3: Examine the connection mechanism
        log("\n--- Test 3: Examining connection mechanism ---");
        examineConnectionMechanism(config, stateDict, layerA, layerB);

        stateDict.destroy();
    }

    private void testLayersSeparately(Qwen3Config config, StateDictionary stateDict,
                                      int layerA, int layerB) throws Exception {
        // Load input for layer A
        String inputFileA = layerA == 0 ? "after_embeddings.bin" :
                           String.format("after_layer_%d.bin", layerA - 1);
        float[] inputDataA = loadReferenceOutput(inputFileA);

        if (inputDataA == null) {
            log(String.format("ERROR: No reference input found for layer %d", layerA));
            return;
        }

        // Build and run layer A
        PackedCollection inputA = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            inputA.setMem(i, inputDataA[i]);
        }

        Model modelA = buildSingleLayer(config, stateDict, layerA);
        org.almostrealism.model.CompiledModel compiledA = modelA.compile();
        PackedCollection outputA = compiledA.forward(inputA);

        // Load expected output for layer A
        float[] expectedA = loadReferenceOutput(String.format("after_layer_%d.bin", layerA));
        double errorA = computeMeanError(outputA, expectedA, config.dim);
        log(String.format("Layer %d error when run separately: %.6f", layerA, errorA));

        // Now run layer B with the output from layer A
        Model modelB = buildSingleLayer(config, stateDict, layerB);
        org.almostrealism.model.CompiledModel compiledB = modelB.compile();
        PackedCollection outputB = compiledB.forward(outputA);

        // Load expected output for layer B
        float[] expectedB = loadReferenceOutput(String.format("after_layer_%d.bin", layerB));
        double errorB = computeMeanError(outputB, expectedB, config.dim);
        log(String.format("Layer %d error when run separately (using layer %d output): %.6f",
            layerB, layerA, errorB));

        // Check if we're feeding the correct input to layer B
        String inputFileB = String.format("after_layer_%d.bin", layerB - 1);
        float[] inputDataB = loadReferenceOutput(inputFileB);
        double inputDiff = computeMeanError(outputA, inputDataB, config.dim);
        log(String.format("Difference between layer %d output and expected layer %d input: %.6f",
            layerA, layerB, inputDiff));
    }

    private void testLayersConnected(Qwen3Config config, StateDictionary stateDict,
                                    int layerA, int layerB) throws Exception {
        // Load input for layer A
        String inputFile = layerA == 0 ? "after_embeddings.bin" :
                          String.format("after_layer_%d.bin", layerA - 1);
        float[] inputData = loadReferenceOutput(inputFile);

        if (inputData == null) {
            log(String.format("ERROR: No reference input found for layer %d", layerA));
            return;
        }

        PackedCollection input = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, inputData[i]);
        }

        // Build model with both layers connected
        Model model = new Model(shape(config.dim));

        // Add layer A
        addTransformerLayer(model, config, stateDict, layerA);

        // Add layer B
        addTransformerLayer(model, config, stateDict, layerB);

        // Compile and run
        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection output = compiled.forward(input);

        // Compare with expected output after layer B
        float[] expected = loadReferenceOutput(String.format("after_layer_%d.bin", layerB));
        double error = computeMeanError(output, expected, config.dim);
        log(String.format("Error when layers %d and %d are connected: %.6f", layerA, layerB, error));

        // Check intermediate values if possible
        log("\nOutput statistics:");
        double mean = 0, std = 0, max = 0, min = Double.MAX_VALUE;
        for (int i = 0; i < config.dim; i++) {
            double val = output.toDouble(i);
            mean += val;
            max = Math.max(max, Math.abs(val));
            min = Math.min(min, Math.abs(val));
        }
        mean /= config.dim;

        for (int i = 0; i < config.dim; i++) {
            double diff = output.toDouble(i) - mean;
            std += diff * diff;
        }
        std = Math.sqrt(std / config.dim);

        log(String.format("  Mean: %.6f, Std: %.6f, Max abs: %.6f, Min abs: %.6f",
            mean, std, max, min));

        // Check for NaN or Inf
        boolean hasNaN = false, hasInf = false;
        for (int i = 0; i < config.dim; i++) {
            if (Double.isNaN(output.toDouble(i))) hasNaN = true;
            if (Double.isInfinite(output.toDouble(i))) hasInf = true;
        }
        log(String.format("  Has NaN: %s, Has Inf: %s", hasNaN, hasInf));
    }

    private void examineConnectionMechanism(Qwen3Config config, StateDictionary stateDict,
                                           int layerA, int layerB) throws Exception {
        // This method examines HOW the layers are connected
        // Specifically looking at residual connections and data flow

        log("Examining how output from layer " + layerA + " flows into layer " + layerB);

        // Load the output from layer A-1 (input to layer A)
        String inputFile = layerA == 0 ? "after_embeddings.bin" :
                          String.format("after_layer_%d.bin", layerA - 1);
        float[] inputData = loadReferenceOutput(inputFile);

        if (inputData == null) {
            log("ERROR: No reference input found");
            return;
        }

        // Check if the model is properly maintaining residual connections
        Model model = new Model(shape(config.dim));

        // Build a test model that exposes intermediate values
        SequentialBlock testBlock = new SequentialBlock(shape(config.dim));

        // Add layer A
        addTransformerLayer(testBlock, config, stateDict, layerA);

        // Add layer B
        addTransformerLayer(testBlock, config, stateDict, layerB);

        model.add(testBlock);

        // Run the model
        PackedCollection input = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, inputData[i]);
        }

        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection output = compiled.forward(input);

        log("Connection mechanism test complete");

        // Compare shapes
        log(String.format("Input shape: %s", input.getShape()));
        log(String.format("Output shape: %s", output.getShape()));

        // Check if dimensions match
        if (output.getShape().getSize() != config.dim) {
            log(String.format("ERROR: Output size mismatch! Expected %d, got %d",
                config.dim, output.getShape().getSize()));
        }
    }

    private void testSequentialStack(Qwen3Config config, StateDictionary stateDict,
                                    int[] layers) throws Exception {
        log(String.format("\nTesting sequential stack of layers: %d, %d, %d",
            layers[0], layers[1], layers[2]));

        // Load input for first layer
        String inputFile = layers[0] == 0 ? "after_embeddings.bin" :
                          String.format("after_layer_%d.bin", layers[0] - 1);
        float[] inputData = loadReferenceOutput(inputFile);

        if (inputData == null) {
            log("ERROR: No reference input found");
            return;
        }

        PackedCollection input = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, inputData[i]);
        }

        // Build model with all three layers
        Model model = new Model(shape(config.dim));
        for (int layer : layers) {
            addTransformerLayer(model, config, stateDict, layer);
        }

        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection output = compiled.forward(input);

        // Compare with expected output after last layer
        float[] expected = loadReferenceOutput(String.format("after_layer_%d.bin", layers[2]));
        double error = computeMeanError(output, expected, config.dim);

        log(String.format("Error after stacking layers %d->%d->%d: %.6f",
            layers[0], layers[1], layers[2], error));

        // Now test each layer individually to see where error accumulates
        log("\nLayer-by-layer errors:");
        PackedCollection currentInput = input;

        for (int layer : layers) {
            Model singleLayer = buildSingleLayer(config, stateDict, layer);
            org.almostrealism.model.CompiledModel singleCompiled = singleLayer.compile();
            PackedCollection layerOutput = singleCompiled.forward(currentInput);

            float[] layerExpected = loadReferenceOutput(String.format("after_layer_%d.bin", layer));
            double layerError = computeMeanError(layerOutput, layerExpected, config.dim);

            log(String.format("  Layer %d: %.6f", layer, layerError));
            currentInput = layerOutput;
        }
    }

    private void addTransformerLayer(Model model, Qwen3Config config,
                                    StateDictionary stateDict, int layerIndex) {
        String prefix = String.format("model.layers.%d", layerIndex);

        // Load weights for this layer
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

        // QK-Norm weights for Qwen3
        PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
        PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

        // Compute RoPE frequencies
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Add transformer layer
        model.add(transformer(
            config.headCount, config.kvHeadCount,
            layerRmsAtt,
            layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq,
            layerQkNormQ, layerQkNormK,  // Include QK-Norm weights
            freqCis,
            layerRmsFfn,
            layerW1, layerW2, layerW3,
            p(position)));
    }

    private void addTransformerLayer(SequentialBlock block, Qwen3Config config,
                                    StateDictionary stateDict, int layerIndex) {
        String prefix = String.format("model.layers.%d", layerIndex);

        // Load weights (same as above)
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

        // QK-Norm weights for Qwen3
        PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
        PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        block.add(transformer(
            config.headCount, config.kvHeadCount,
            layerRmsAtt,
            layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq,
            layerQkNormQ, layerQkNormK,  // Include QK-Norm weights
            freqCis,
            layerRmsFfn,
            layerW1, layerW2, layerW3,
            p(position)));
    }

    private Model buildSingleLayer(Qwen3Config config, StateDictionary stateDict, int layerIndex) {
        Model model = new Model(shape(config.dim));
        addTransformerLayer(model, config, stateDict, layerIndex);
        return model;
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

    private double computeMeanError(PackedCollection output, float[] expected, int dim) {
        if (expected == null) return Double.MAX_VALUE;

        double sumError = 0.0;
        for (int i = 0; i < dim; i++) {
            sumError += Math.abs(output.toDouble(i) - expected[i]);
        }
        return sumError / dim;
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