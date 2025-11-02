package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.shape.TraversalPolicy;
import org.almostrealism.graph.Model;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import static org.almostrealism.collect.shape.TraversalPolicy.shape;
import static org.almostrealism.graph.Receptor.p;

/**
 * Diagnoses the shape transformation issue causing errors at layer boundaries.
 * Tests show output shape is (1, 896) instead of expected (896).
 */
public class ShapeDiagnosticTest implements ConsoleFeatures, LayerFeatures, AttentionFeatures {

    @Test
    public void testSingleLayerShape() throws Exception {
        // Setup file logging
        String logFile = "/workspace/project/common/ml/test_output/shape_diagnostic.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Shape Diagnostic Test ===\n");

        // Load weights
        String weightsDir = "/workspace/project/common/ml/qwen3_weights";
        StateDictionary stateDict = new StateDictionary(weightsDir);

        // Create minimal config
        int dim = 896;
        int headCount = 14;
        int kvHeadCount = 2;

        log(String.format("Testing with dim=%d, heads=%d, kvHeads=%d", dim, headCount, kvHeadCount));

        // Test 1: Model with single RMSNorm
        log("\n--- Test 1: Single RMSNorm ---");
        Model model1 = new Model(shape(dim));
        PackedCollection<?> rmsWeight = stateDict.get("model.layers.0.input_layernorm.weight");
        model1.add(rmsnorm(rmsWeight));

        PackedCollection<?> input1 = new PackedCollection<>(shape(dim));
        for (int i = 0; i < dim; i++) {
            input1.setMem(i, Math.random());
        }

        org.almostrealism.model.CompiledModel compiled1 = model1.compile();
        PackedCollection<?> output1 = compiled1.forward(input1);

        log(String.format("Input shape: %s", input1.getShape()));
        log(String.format("Output shape: %s", output1.getShape()));
        log(String.format("Shape match: %s", output1.getShape().equals(input1.getShape())));

        // Test 2: Model with two RMSNorms
        log("\n--- Test 2: Two RMSNorms ---");
        Model model2 = new Model(shape(dim));
        model2.add(rmsnorm(rmsWeight));
        model2.add(rmsnorm(rmsWeight));

        org.almostrealism.model.CompiledModel compiled2 = model2.compile();
        PackedCollection<?> output2 = compiled2.forward(input1);

        log(String.format("Input shape: %s", input1.getShape()));
        log(String.format("Output shape: %s", output2.getShape()));
        log(String.format("Shape match: %s", output2.getShape().equals(input1.getShape())));

        // Test 3: Model with dense layer
        log("\n--- Test 3: Dense Layer ---");
        Model model3 = new Model(shape(dim));
        PackedCollection<?> denseWeight = stateDict.get("model.layers.0.self_attn.q_proj.weight");
        model3.add(dense(denseWeight));

        org.almostrealism.model.CompiledModel compiled3 = model3.compile();
        PackedCollection<?> output3 = compiled3.forward(input1);

        log(String.format("Input shape: %s", input1.getShape()));
        log(String.format("Output shape: %s", output3.getShape()));

        // Test 4: Model with simplified transformer (no attention, just norms)
        log("\n--- Test 4: Simplified Transformer Block ---");
        Model model4 = new Model(shape(dim));

        // First norm + identity (simulating attention residual)
        model4.add(rmsnorm(rmsWeight));
        // Second norm + identity (simulating FFN residual)
        model4.add(rmsnorm(rmsWeight));

        org.almostrealism.model.CompiledModel compiled4 = model4.compile();
        PackedCollection<?> output4 = compiled4.forward(input1);

        log(String.format("Input shape: %s", input1.getShape()));
        log(String.format("Output shape: %s", output4.getShape()));
        log(String.format("Shape match: %s", output4.getShape().equals(input1.getShape())));

        // Test 5: Check if the problem is with the attention mechanism
        log("\n--- Test 5: Minimal Attention Block ---");
        testMinimalAttention(stateDict, dim, headCount, kvHeadCount);

        log("\n=== Test Complete ===");
        log("Results saved to: " + logFile);
    }

    private void testMinimalAttention(StateDictionary stateDict, int dim, int headCount, int kvHeadCount) {
        try {
            Model model = new Model(shape(dim));

            // Load weights for layer 0
            String prefix = "model.layers.0";
            PackedCollection<?> rmsAttWeight = stateDict.get(prefix + ".input_layernorm.weight");
            PackedCollection<?> wq = stateDict.get(prefix + ".self_attn.q_proj.weight");
            PackedCollection<?> wk = stateDict.get(prefix + ".self_attn.k_proj.weight");
            PackedCollection<?> wv = stateDict.get(prefix + ".self_attn.v_proj.weight");
            PackedCollection<?> wo = stateDict.get(prefix + ".self_attn.o_proj.weight");

            // Create minimal RoPE frequencies
            int headSize = dim / headCount;
            PackedCollection<?> freqCis = new PackedCollection<>(shape(10, headSize/2, 2));
            for (int i = 0; i < freqCis.getShape().getSize(); i++) {
                freqCis.setMem(i, 1.0);
            }

            PackedCollection<?> position = new PackedCollection<>(shape(1));
            position.setMem(0, 0.0);

            // Add just the attention block (no FFN)
            model.add(attention(headCount, kvHeadCount, rmsAttWeight,
                               wk, wv, wq, wo,
                               null, null, null,  // No biases
                               null, null,        // No QK-Norm
                               freqCis, p(position)));

            // Test with input
            PackedCollection<?> input = new PackedCollection<>(shape(dim));
            for (int i = 0; i < dim; i++) {
                input.setMem(i, Math.random());
            }

            org.almostrealism.model.CompiledModel compiled = model.compile();
            PackedCollection<?> output = compiled.forward(input);

            log(String.format("Attention Input shape: %s", input.getShape()));
            log(String.format("Attention Output shape: %s", output.getShape()));
            log(String.format("Shape preserved: %s", output.getShape().equals(input.getShape())));

        } catch (Exception e) {
            log("Error in minimal attention test: " + e.getMessage());
            e.printStackTrace();
        }
    }
}