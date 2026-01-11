/*
 * Copyright 2025 Michael Murray
 */
package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
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
 * Test attention and FFN separately for layer 23.
 */
public class Layer23ComponentTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test RMSNorm alone for layer 23.
     */
    @Test
    public void testLayer23RMSNorm() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_rmsnorm.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 RMSNorm Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Load layer 22 output as input
        float[] inputData = loadReferenceOutput("after_layer_22.bin");
        PackedCollection input = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, inputData[i]);
        }

        log("Input range: [" + min(inputData) + ", " + max(inputData) + "]");

        // Get layer 23 RMSNorm weights
        PackedCollection rmsWeight = stateDict.get("model.layers.23.input_layernorm.weight");
        log("RMSNorm weight range: [" + rmsWeight.toDouble(0) + " ... " +
            rmsWeight.toDouble(config.dim - 1) + "]");
        log("RMSNorm weight max: " + maxAbs(rmsWeight));

        // Apply RMSNorm
        CellularLayer rmsnormLayer = rmsnorm(shape(1, config.dim), rmsWeight, 1e-6);
        Model model = new Model(shape(1, config.dim));
        model.add(rmsnormLayer);
        PackedCollection output = model.compile().forward(input);

        // Compute expected RMSNorm manually
        double sumSquares = 0;
        for (int i = 0; i < config.dim; i++) {
            sumSquares += inputData[i] * inputData[i];
        }
        double rms = Math.sqrt(sumSquares / config.dim + 1e-6);
        log("Computed RMS: " + rms);

        double[] expected = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            expected[i] = (inputData[i] / rms) * rmsWeight.toDouble(i);
        }

        log("Expected first 5: " + expected[0] + ", " + expected[1] + ", " +
            expected[2] + ", " + expected[3] + ", " + expected[4]);
        log("AR output first 5: " + output.toDouble(0) + ", " + output.toDouble(1) + ", " +
            output.toDouble(2) + ", " + output.toDouble(3) + ", " + output.toDouble(4));

        // Compare
        double maxError = 0;
        for (int i = 0; i < config.dim; i++) {
            double error = Math.abs(output.toDouble(i) - expected[i]);
            maxError = Math.max(maxError, error);
        }
        log("Max error vs expected: " + maxError);

        // Get output range
        float[] arOutput = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            arOutput[i] = (float) output.toDouble(i);
        }
        log("AR RMSNorm output range: [" + min(arOutput) + ", " + max(arOutput) + "]");

        stateDict.destroy();
    }

    /**
     * Test FFN-only for layer 22 vs layer 23.
     */
    @Test
    public void testFFNOnly() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_ffn_only.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 22 vs 23 FFN-Only Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Test Layer 22 FFN with layer 21 output as input
        float[] input22 = loadReferenceOutput("after_layer_21.bin");
        PackedCollection in22 = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) in22.setMem(i, input22[i]);

        Block ffn22 = buildFFNOnly(config, stateDict, 22);
        Model model22 = new Model(shape(1, config.dim));
        model22.add(ffn22);
        PackedCollection out22 = model22.compile().forward(in22);

        log("Layer 22 FFN:");
        log("  Input range: [" + min(input22) + ", " + max(input22) + "]");
        float[] ar22 = toFloatArray(out22, config.dim);
        log("  Output range: [" + min(ar22) + ", " + max(ar22) + "]");

        // Test Layer 23 FFN with layer 22 output as input
        float[] input23 = loadReferenceOutput("after_layer_22.bin");
        PackedCollection in23 = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) in23.setMem(i, input23[i]);

        Block ffn23 = buildFFNOnly(config, stateDict, 23);
        Model model23 = new Model(shape(1, config.dim));
        model23.add(ffn23);
        PackedCollection out23 = model23.compile().forward(in23);

        log("\nLayer 23 FFN:");
        log("  Input range: [" + min(input23) + ", " + max(input23) + "]");
        float[] ar23 = toFloatArray(out23, config.dim);
        log("  Output range: [" + min(ar23) + ", " + max(ar23) + "]");

        // Check weight statistics for each layer's FFN
        log("\n=== FFN Weight Comparison ===");
        String[] ffnWeights = {"post_attention_layernorm.weight",
                               "mlp.gate_proj.weight", "mlp.up_proj.weight", "mlp.down_proj.weight"};
        for (String name : ffnWeights) {
            PackedCollection w22 = stateDict.get("model.layers.22." + name);
            PackedCollection w23 = stateDict.get("model.layers.23." + name);
            if (w22 != null && w23 != null) {
                log(String.format("%s: L22 max=%.4f, L23 max=%.4f, ratio=%.2f",
                    name.replace("mlp.", ""), maxAbs(w22), maxAbs(w23), maxAbs(w23)/maxAbs(w22)));
            }
        }

        stateDict.destroy();
    }

    /**
     * Compare weight shapes and values between layer 22 and 23 to find anomalies.
     */
    @Test
    public void testWeightComparison() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_weight_shapes.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 22 vs 23 Weight Shape/Value Comparison ===\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        String[] weightKeys = {
            "input_layernorm.weight",
            "self_attn.q_proj.weight", "self_attn.q_proj.bias",
            "self_attn.k_proj.weight", "self_attn.k_proj.bias",
            "self_attn.v_proj.weight", "self_attn.v_proj.bias",
            "self_attn.o_proj.weight",
            "post_attention_layernorm.weight",
            "mlp.gate_proj.weight",
            "mlp.up_proj.weight",
            "mlp.down_proj.weight"
        };

        for (String key : weightKeys) {
            PackedCollection w22 = stateDict.get("model.layers.22." + key);
            PackedCollection w23 = stateDict.get("model.layers.23." + key);

            if (w22 == null || w23 == null) {
                log(String.format("%-30s MISSING (22=%s, 23=%s)", key, w22 != null, w23 != null));
                continue;
            }

            String shape22 = w22.getShape().toString();
            String shape23 = w23.getShape().toString();

            // Compute stats
            double mean22 = 0, mean23 = 0;
            double max22 = Double.MIN_VALUE, max23 = Double.MIN_VALUE;
            double min22 = Double.MAX_VALUE, min23 = Double.MAX_VALUE;
            int size22 = (int) w22.getShape().getTotalSize();
            int size23 = (int) w23.getShape().getTotalSize();

            for (int i = 0; i < size22; i++) {
                double v = w22.toDouble(i);
                mean22 += v;
                max22 = Math.max(max22, v);
                min22 = Math.min(min22, v);
            }
            mean22 /= size22;

            for (int i = 0; i < size23; i++) {
                double v = w23.toDouble(i);
                mean23 += v;
                max23 = Math.max(max23, v);
                min23 = Math.min(min23, v);
            }
            mean23 /= size23;

            log(String.format("%-30s shapes: %s vs %s", key, shape22, shape23));
            log(String.format("  L22: mean=%.4f, range=[%.4f, %.4f]", mean22, min22, max22));
            log(String.format("  L23: mean=%.4f, range=[%.4f, %.4f]", mean23, min23, max23));
            log(String.format("  Range ratio (L23/L22): %.2f", (max23-min23)/(max22-min22)));
            log("");
        }

        // Also check first few elements of o_proj (which should be 3x larger for L23)
        PackedCollection o22 = stateDict.get("model.layers.22.self_attn.o_proj.weight");
        PackedCollection o23 = stateDict.get("model.layers.23.self_attn.o_proj.weight");

        log("=== o_proj.weight first 10 elements ===");
        for (int i = 0; i < 10; i++) {
            log(String.format("[%d] L22=%.6f, L23=%.6f, ratio=%.2f",
                i, o22.toDouble(i), o23.toDouble(i),
                Math.abs(o23.toDouble(i)) / (Math.abs(o22.toDouble(i)) + 1e-10)));
        }

        // Also check layer 21 vs 22 vs 23 to see the pattern
        log("\n=== Layer 21/22/23 o_proj max values ===");
        for (int layer = 20; layer <= 23; layer++) {
            PackedCollection w = stateDict.get("model.layers." + layer + ".self_attn.o_proj.weight");
            if (w != null) {
                double max = 0;
                for (int i = 0; i < w.getShape().getTotalSize(); i++) {
                    max = Math.max(max, Math.abs(w.toDouble(i)));
                }
                log(String.format("Layer %d o_proj.weight max abs: %.4f", layer, max));
            }
        }

        stateDict.destroy();
    }

    private Block buildFFNOnly(Qwen3Config config, StateDictionary stateDict, int layerIdx) {
        String prefix = "model.layers." + layerIdx;
        PackedCollection rmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        return feedForward(rmsFfn, w1, w2, w3, 1e-6);
    }

    /**
     * Test attention + residual to verify the residual connection works.
     */
    @Test
    public void testAttentionPlusResidual() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_attn_residual.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 Attention + Residual Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        float[] input23 = loadReferenceOutput("after_layer_22.bin");
        PackedCollection in23 = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) in23.setMem(i, input23[i]);

        log("Input range: [" + min(input23) + ", " + max(input23) + "]");

        // Step 1: Attention only (no residual)
        Block attn23 = buildAttentionOnly(config, stateDict, 23, freqCis, p(position));
        PackedCollection attnOnly = runAttention(attn23, in23);
        float[] attnOnlyArr = toFloatArray(attnOnly, config.dim);
        log("Attention-only output range: [" + min(attnOnlyArr) + ", " + max(attnOnlyArr) + "]");

        // Step 2: Manually compute input + attention
        float[] manualResidual = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            manualResidual[i] = input23[i] + attnOnlyArr[i];
        }
        log("Manual residual (input + attn): [" + min(manualResidual) + ", " + max(manualResidual) + "]");

        // Step 3: Attention with accum (should equal manual residual)
        Block attn23b = buildAttentionOnly(config, stateDict, 23, freqCis, p(position));
        SequentialBlock attnWithResidual = new SequentialBlock(shape(1, config.dim));
        attnWithResidual.accum(attn23b);

        Model attnResModel = new Model(shape(1, config.dim));
        attnResModel.add(attnWithResidual);
        PackedCollection attnRes = attnResModel.compile().forward(in23);
        float[] attnResArr = toFloatArray(attnRes, config.dim);
        log("Accum output range: [" + min(attnResArr) + ", " + max(attnResArr) + "]");

        // Compare
        double maxDiff = 0;
        for (int i = 0; i < config.dim; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(manualResidual[i] - attnResArr[i]));
        }
        log("Max diff between manual and accum: " + maxDiff);

        // Show first few values
        log("\nFirst 5 values:");
        log("Input: " + input23[0] + ", " + input23[1] + ", " + input23[2] + ", " + input23[3] + ", " + input23[4]);
        log("Attn: " + attnOnlyArr[0] + ", " + attnOnlyArr[1] + ", " + attnOnlyArr[2] + ", " + attnOnlyArr[3] + ", " + attnOnlyArr[4]);
        log("Manual: " + manualResidual[0] + ", " + manualResidual[1] + ", " + manualResidual[2] + ", " + manualResidual[3] + ", " + manualResidual[4]);
        log("Accum: " + attnResArr[0] + ", " + attnResArr[1] + ", " + attnResArr[2] + ", " + attnResArr[3] + ", " + attnResArr[4]);

        stateDict.destroy();
    }

    /**
     * Test full layer step-by-step: attention+residual then FFN+residual.
     */
    @Test
    public void testFullLayerStepByStep() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_stepbystep.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 Full Layer Step-by-Step Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Load input
        float[] inputArr = loadReferenceOutput("after_layer_22.bin");
        PackedCollection input = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) input.setMem(i, inputArr[i]);

        // Load expected output
        float[] expectedArr = loadReferenceOutput("after_layer_23.bin");

        log("Step 0 - Input: [" + min(inputArr) + ", " + max(inputArr) + "]");
        log("Expected output: [" + min(expectedArr) + ", " + max(expectedArr) + "]");

        // Step 1: Attention only
        Block attn = buildAttentionOnly(config, stateDict, 23, freqCis, p(position));
        PackedCollection attnOut = runAttention(attn, input);
        float[] attnArr = toFloatArray(attnOut, config.dim);
        log("\nStep 1 - Attention only: [" + min(attnArr) + ", " + max(attnArr) + "]");

        // Step 2: Attention + Residual (manual)
        float[] afterAttnResidual = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            afterAttnResidual[i] = inputArr[i] + attnArr[i];
        }
        log("Step 2 - Input + Attention: [" + min(afterAttnResidual) + ", " + max(afterAttnResidual) + "]");

        // Step 3: FFN on (Input + Attention)
        PackedCollection ffnInput = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) ffnInput.setMem(i, afterAttnResidual[i]);

        Block ffn = buildFFNOnly(config, stateDict, 23);
        Model ffnModel = new Model(shape(1, config.dim));
        ffnModel.add(ffn);
        PackedCollection ffnOut = ffnModel.compile().forward(ffnInput);
        float[] ffnArr = toFloatArray(ffnOut, config.dim);
        log("Step 3 - FFN output: [" + min(ffnArr) + ", " + max(ffnArr) + "]");

        // Step 4: FFN + Residual = final output
        float[] finalOutput = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            finalOutput[i] = afterAttnResidual[i] + ffnArr[i];
        }
        log("Step 4 - (Input + Attn) + FFN: [" + min(finalOutput) + ", " + max(finalOutput) + "]");

        // Compare with expected
        double mae = 0;
        for (int i = 0; i < config.dim; i++) {
            mae += Math.abs(expectedArr[i] - finalOutput[i]);
        }
        mae /= config.dim;
        log("\nFinal MAE vs PyTorch: " + mae);

        // What's the ratio?
        double ratio = (Math.abs(max(expectedArr)) + Math.abs(min(expectedArr))) /
                       (Math.abs(max(finalOutput)) + Math.abs(min(finalOutput)) + 1e-10);
        log("Range ratio (PyTorch/AR): " + ratio);

        // Now test using the transformer method (accum-based)
        log("\n--- Comparing with transformer() method ---");
        String prefix = "model.layers.23";
        Block fullLayer = transformer(config.headCount, config.kvHeadCount,
            stateDict.get(prefix + ".input_layernorm.weight"),
            stateDict.get(prefix + ".self_attn.k_proj.weight"),
            stateDict.get(prefix + ".self_attn.v_proj.weight"),
            stateDict.get(prefix + ".self_attn.q_proj.weight"),
            stateDict.get(prefix + ".self_attn.o_proj.weight"),
            stateDict.get(prefix + ".self_attn.k_proj.bias"),
            stateDict.get(prefix + ".self_attn.v_proj.bias"),
            stateDict.get(prefix + ".self_attn.q_proj.bias"),
            null, null,  // No QK-Norm
            freqCis,
            stateDict.get(prefix + ".post_attention_layernorm.weight"),
            stateDict.get(prefix + ".mlp.gate_proj.weight"),
            stateDict.get(prefix + ".mlp.down_proj.weight"),
            stateDict.get(prefix + ".mlp.up_proj.weight"),
            p(position), 1e-6);

        Model transformerModel = new Model(shape(1, config.dim));
        transformerModel.add(fullLayer);
        PackedCollection transformerOut = transformerModel.compile().forward(input);
        float[] transformerArr = toFloatArray(transformerOut, config.dim);
        log("Transformer method output: [" + min(transformerArr) + ", " + max(transformerArr) + "]");

        double manualVsTransformerDiff = 0;
        for (int i = 0; i < config.dim; i++) {
            manualVsTransformerDiff = Math.max(manualVsTransformerDiff,
                Math.abs(finalOutput[i] - transformerArr[i]));
        }
        log("Max diff (manual vs transformer): " + manualVsTransformerDiff);

        // Detailed element comparison
        log("\n=== Element-by-element comparison (first 10) ===");
        log(String.format("%-5s %-15s %-15s %-15s", "Idx", "Input", "AR Output", "PyTorch Expected"));
        for (int i = 0; i < 10; i++) {
            log(String.format("%-5d %-15.4f %-15.4f %-15.4f",
                i, inputArr[i], finalOutput[i], expectedArr[i]));
        }

        // Check if applying final norm makes a difference
        PackedCollection finalNormWeight = stateDict.get("model.norm.weight");
        if (finalNormWeight != null) {
            log("\n=== Testing if final norm is included ===");
            log("model.norm.weight shape: " + finalNormWeight.getShape());

            // Apply RMSNorm to our output
            double sumSquares = 0;
            for (int i = 0; i < config.dim; i++) {
                sumSquares += finalOutput[i] * finalOutput[i];
            }
            double rms = Math.sqrt(sumSquares / config.dim + 1e-6);
            float[] normOutput = new float[config.dim];
            for (int i = 0; i < config.dim; i++) {
                normOutput[i] = (float) ((finalOutput[i] / rms) * finalNormWeight.toDouble(i));
            }
            log("After applying final norm: [" + min(normOutput) + ", " + max(normOutput) + "]");

            // Compare with PyTorch
            double normMae = 0;
            for (int i = 0; i < config.dim; i++) {
                normMae += Math.abs(expectedArr[i] - normOutput[i]);
            }
            normMae /= config.dim;
            log("MAE after final norm: " + normMae);
        }

        stateDict.destroy();
    }

    /**
     * Compare layer 22 vs layer 23 attention output (without FFN).
     */
    @Test
    public void testAttentionOnly() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_attn_only.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 22 vs 23 Attention-Only Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Test layer 22 attention
        float[] input22 = loadReferenceOutput("after_layer_21.bin");
        PackedCollection in22 = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) in22.setMem(i, input22[i]);

        Block attn22 = buildAttentionOnly(config, stateDict, 22, freqCis, p(position));
        PackedCollection out22 = runAttention(attn22, in22);

        // Test layer 23 attention
        float[] input23 = loadReferenceOutput("after_layer_22.bin");
        PackedCollection in23 = new PackedCollection(shape(1, config.dim));
        for (int i = 0; i < config.dim; i++) in23.setMem(i, input23[i]);

        Block attn23 = buildAttentionOnly(config, stateDict, 23, freqCis, p(position));
        PackedCollection out23 = runAttention(attn23, in23);

        // Report
        log("Layer 22 Attention:");
        log("  Input range: [" + min(input22) + ", " + max(input22) + "]");
        float[] ar22 = toFloatArray(out22, config.dim);
        log("  Output range: [" + min(ar22) + ", " + max(ar22) + "]");
        log("  Amplification: " + (max(ar22) - min(ar22)) / (max(input22) - min(input22)));

        log("\nLayer 23 Attention:");
        log("  Input range: [" + min(input23) + ", " + max(input23) + "]");
        float[] ar23 = toFloatArray(out23, config.dim);
        log("  Output range: [" + min(ar23) + ", " + max(ar23) + "]");
        log("  Amplification: " + (max(ar23) - min(ar23)) / (max(input23) - min(input23)));

        stateDict.destroy();
    }

    private Block buildAttentionOnly(Qwen3Config config, StateDictionary stateDict,
                                      int layerIdx, PackedCollection freqCis,
                                      Producer<PackedCollection> position) {
        String prefix = "model.layers." + layerIdx;

        PackedCollection rmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
        PackedCollection wq = stateDict.get(prefix + ".self_attn.q_proj.weight");
        PackedCollection wk = stateDict.get(prefix + ".self_attn.k_proj.weight");
        PackedCollection wv = stateDict.get(prefix + ".self_attn.v_proj.weight");
        PackedCollection wo = stateDict.get(prefix + ".self_attn.o_proj.weight");
        PackedCollection bq = stateDict.get(prefix + ".self_attn.q_proj.bias");
        PackedCollection bk = stateDict.get(prefix + ".self_attn.k_proj.bias");
        PackedCollection bv = stateDict.get(prefix + ".self_attn.v_proj.bias");

        // Return attention block directly (without residual wrapper)
        return attention(config.headCount, config.kvHeadCount,
                rmsAtt, wk, wv, wq, wo, bk, bv, bq, null, null, freqCis, position, 1e-6);
    }

    private PackedCollection runAttention(Block block, PackedCollection input) {
        // Input shape is (1, dim), so use getTotalSize() or length(1) for dim
        int dim = (int) input.getShape().getTotalSize();
        Model model = new Model(shape(1, dim));
        model.add(block);
        return model.compile().forward(input);
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

    private float[] toFloatArray(PackedCollection c, int size) {
        float[] arr = new float[size];
        for (int i = 0; i < size; i++) arr[i] = (float) c.toDouble(i);
        return arr;
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

    private double maxAbs(PackedCollection c) {
        double m = 0;
        for (int i = 0; i < c.getShape().getTotalSize(); i++) {
            m = Math.max(m, Math.abs(c.toDouble(i)));
        }
        return m;
    }
}
