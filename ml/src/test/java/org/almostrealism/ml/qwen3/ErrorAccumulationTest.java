/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
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
 * Test to track error accumulation across all 24 transformer layers.
 *
 * This test compares AR output at each layer against PyTorch reference
 * to understand how errors compound through the model.
 */
public class ErrorAccumulationTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test the full 24-layer model and compare output after the final layer.
     */
    @Test
    public void testFullModelOutput() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/full_model_comparison.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Full 24-Layer Model Output Comparison ===\n");

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

        // Build full model
        log("Building 24-layer model...");
        Model model = buildFullModel(config, stateDict);

        // Get embeddings for token 9707 ("Hello")
        int tokenId = 9707;
        PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
        PackedCollection input = embeddings.range(
            shape(config.dim),
            tokenId * config.dim
        );

        log("Running forward pass through 24 layers...");
        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection output = compiled.forward(input);

        // Compare with PyTorch reference after layer 23
        float[] pytorchOutput = loadReferenceOutput("after_layer_23.bin");

        float[] arOutput = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            arOutput[i] = (float) output.toDouble(i);
        }

        compareOutputs("After 24 layers", pytorchOutput, arOutput);

        // Also show what PyTorch and AR would predict as next token
        log("\n=== Token Prediction Analysis ===\n");

        // Load final logits reference
        float[] pytorchLogits = loadReferenceOutput("final_logits.bin");
        log("PyTorch logits size: " + pytorchLogits.length);

        // Find PyTorch top tokens
        int[] pytorchTopTokens = findTopTokens(pytorchLogits, 5);
        log("\nPyTorch top 5 predicted tokens:");
        for (int i = 0; i < 5; i++) {
            log(String.format("  #%d: token %d (logit %.4f)",
                i + 1, pytorchTopTokens[i], pytorchLogits[pytorchTopTokens[i]]));
        }

        stateDict.destroy();
    }

    /**
     * Test error at specific layer checkpoints (0, 5, 10, 15, 20, 23).
     */
    @Test
    public void testLayerCheckpoints() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer_checkpoints.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer Checkpoint Error Tracking ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        int[] checkpoints = {1, 6, 12, 18, 24};

        log(String.format("%-10s %-15s %-15s %-15s", "Layers", "MAE", "RMSE", "Max Error"));
        log("-".repeat(60));

        for (int numLayers : checkpoints) {
            Model model = buildPartialModel(config, stateDict, numLayers);

            int tokenId = 9707;
            PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
            PackedCollection input = embeddings.range(
                shape(config.dim),
                tokenId * config.dim
            );

            PackedCollection output = model.compile().forward(input);

            // Load corresponding PyTorch reference
            String refFile = "after_layer_" + (numLayers - 1) + ".bin";
            float[] pytorchOutput = loadReferenceOutput(refFile);

            float[] arOutput = new float[config.dim];
            for (int i = 0; i < config.dim; i++) {
                arOutput[i] = (float) output.toDouble(i);
            }

            double[] stats = computeStats(pytorchOutput, arOutput);
            log(String.format("%-10d %-15.6f %-15.6f %-15.6f",
                numLayers, stats[0], stats[1], stats[2]));
        }

        stateDict.destroy();

        log("\n[INFO] If MAE grows exponentially, there's a systematic error.");
        log("[INFO] If MAE stays low, the issue is elsewhere (lm_head, token selection).");
    }

    private Model buildFullModel(Qwen3Config config, StateDictionary stateDict) {
        return buildPartialModel(config, stateDict, 24);
    }

    private Model buildPartialModel(Qwen3Config config, StateDictionary stateDict, int numLayers) {
        Model model = new Model(shape(config.dim));

        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        for (int i = 0; i < numLayers; i++) {
            String prefix = String.format("model.layers.%d", i);

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
                layerRmsAtt, layerWk, layerWv, layerWq, layerWo,
                layerBk, layerBv, layerBq, layerQkNormQ, layerQkNormK,
                freqCis, layerRmsFfn, layerW1, layerW2, layerW3,
                p(position), 1e-6));
        }

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

    private void compareOutputs(String stageName, float[] pytorchOutput, float[] arOutput) {
        log("=== " + stageName + " Comparison ===");

        if (pytorchOutput.length != arOutput.length) {
            log("ERROR: Size mismatch! PyTorch: " + pytorchOutput.length + ", AR: " + arOutput.length);
            return;
        }

        double[] stats = computeStats(pytorchOutput, arOutput);

        log(String.format("Mean Absolute Difference: %.6f", stats[0]));
        log(String.format("RMSE: %.6f", stats[1]));
        log(String.format("Max Absolute Difference: %.6f", stats[2]));
        log("");

        log("First 10 values comparison:");
        log(String.format("%-5s %-15s %-15s %-15s", "Idx", "PyTorch", "AR", "Diff"));
        log("-".repeat(55));
        for (int i = 0; i < Math.min(10, pytorchOutput.length); i++) {
            log(String.format("%-5d %-15.6f %-15.6f %-15.6f",
                i, pytorchOutput[i], arOutput[i], pytorchOutput[i] - arOutput[i]));
        }
        log("");

        if (stats[0] < 1e-4) {
            log("[EXCELLENT] Outputs match within 1e-4 tolerance");
        } else if (stats[0] < 1e-2) {
            log("[GOOD] Outputs match within 1e-2 tolerance");
        } else if (stats[0] < 0.1) {
            log("[WARNING] Small divergence detected (< 0.1)");
        } else {
            log("[CRITICAL] Large divergence detected (>= 0.1)");
        }
    }

    private double[] computeStats(float[] expected, float[] actual) {
        double sumAbsDiff = 0;
        double sumSqDiff = 0;
        double maxAbsDiff = 0;

        for (int i = 0; i < expected.length; i++) {
            double diff = Math.abs(expected[i] - actual[i]);
            sumAbsDiff += diff;
            sumSqDiff += diff * diff;
            maxAbsDiff = Math.max(maxAbsDiff, diff);
        }

        double mae = sumAbsDiff / expected.length;
        double rmse = Math.sqrt(sumSqDiff / expected.length);
        return new double[]{mae, rmse, maxAbsDiff};
    }

    private int[] findTopTokens(float[] logits, int k) {
        int[] topIndices = new int[k];
        float[] topValues = new float[k];

        for (int i = 0; i < k; i++) {
            topValues[i] = Float.NEGATIVE_INFINITY;
        }

        for (int i = 0; i < logits.length; i++) {
            for (int j = 0; j < k; j++) {
                if (logits[i] > topValues[j]) {
                    // Shift down
                    for (int m = k - 1; m > j; m--) {
                        topValues[m] = topValues[m - 1];
                        topIndices[m] = topIndices[m - 1];
                    }
                    topValues[j] = logits[i];
                    topIndices[j] = i;
                    break;
                }
            }
        }
        return topIndices;
    }
}
