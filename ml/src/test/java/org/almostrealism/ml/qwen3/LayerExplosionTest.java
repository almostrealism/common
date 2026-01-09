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
 * Test to identify exactly which layer causes the error explosion.
 * Based on ErrorAccumulationTest results showing massive jump between layers 18-24.
 */
public class LayerExplosionTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test each layer from 18 to 24 to find the exact explosion point.
     */
    @Test
    public void findExplosionPoint() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/explosion_point.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Finding Error Explosion Point (Layers 18-24) ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        log(String.format("%-10s %-15s %-15s %-15s %-15s", "Layers", "MAE", "RMSE", "Max Error", "Status"));
        log("-".repeat(75));

        double prevMae = 0;

        for (int numLayers = 18; numLayers <= 24; numLayers++) {
            Model model = buildPartialModel(config, stateDict, numLayers);

            int tokenId = 9707;
            PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
            PackedCollection input = embeddings.range(
                shape(config.dim),
                tokenId * config.dim
            );

            PackedCollection output = model.compile().forward(input);

            String refFile = "after_layer_" + (numLayers - 1) + ".bin";
            float[] pytorchOutput = loadReferenceOutput(refFile);

            float[] arOutput = new float[config.dim];
            for (int i = 0; i < config.dim; i++) {
                arOutput[i] = (float) output.toDouble(i);
            }

            double[] stats = computeStats(pytorchOutput, arOutput);
            double mae = stats[0];

            String status = "";
            if (prevMae > 0 && mae / prevMae > 10) {
                status = "<<< EXPLOSION!";
            } else if (prevMae > 0 && mae / prevMae > 2) {
                status = "<< significant jump";
            }

            log(String.format("%-10d %-15.6f %-15.6f %-15.6f %s",
                numLayers, mae, stats[1], stats[2], status));

            prevMae = mae;
        }

        log("\n=== Detailed Analysis of Problem Layer ===\n");

        // Check for NaN/Inf in outputs
        log("Checking for numerical issues in layer outputs...");
        for (int numLayers = 18; numLayers <= 24; numLayers++) {
            Model model = buildPartialModel(config, stateDict, numLayers);

            int tokenId = 9707;
            PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
            PackedCollection input = embeddings.range(
                shape(config.dim),
                tokenId * config.dim
            );

            PackedCollection output = model.compile().forward(input);

            int nanCount = 0;
            int infCount = 0;
            double minVal = Double.MAX_VALUE;
            double maxVal = Double.MIN_VALUE;

            for (int i = 0; i < config.dim; i++) {
                double val = output.toDouble(i);
                if (Double.isNaN(val)) nanCount++;
                if (Double.isInfinite(val)) infCount++;
                if (!Double.isNaN(val) && !Double.isInfinite(val)) {
                    minVal = Math.min(minVal, val);
                    maxVal = Math.max(maxVal, val);
                }
            }

            log(String.format("Layer %d: NaN=%d, Inf=%d, Range=[%.4f, %.4f]",
                numLayers, nanCount, infCount, minVal, maxVal));
        }

        stateDict.destroy();
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
}
