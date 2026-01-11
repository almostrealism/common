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

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Step-by-step attention diagnostic test.
 *
 * This test traces through each operation in the attention mechanism
 * using simple known values to verify correctness.
 */
public class AttentionStepByStepTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test dense layer weight orientation.
     *
     * PyTorch: output = input @ weight.T + bias
     * AR: output = matmul(weight, input) + bias
     *
     * These should be equivalent if done correctly.
     */
    @Test
    public void testDenseWeightOrientation() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/dense_orientation_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Dense Layer Weight Orientation Test ===\n");

        // Simple test: input [1, 2], weight [[1, 2], [3, 4]], no bias
        // PyTorch: [1, 2] @ [[1, 3], [2, 4]] = [1*1+2*2, 1*3+2*4] = [5, 11]
        // Or equivalently: [[1, 2], [3, 4]] @ [1, 2]^T = [[1+4], [3+8]] = [[5], [11]]

        int inputDim = 2;
        int outputDim = 2;

        PackedCollection input = new PackedCollection(shape(inputDim));
        input.setMem(0, 1.0);
        input.setMem(1, 2.0);

        // Weight in PyTorch convention: (output_dim, input_dim)
        // Row 0: [1, 2] -> output[0] = 1*1 + 2*2 = 5
        // Row 1: [3, 4] -> output[1] = 3*1 + 4*2 = 11
        PackedCollection weight = new PackedCollection(shape(outputDim, inputDim));
        weight.setMem(0, 1.0);  // weight[0][0]
        weight.setMem(1, 2.0);  // weight[0][1]
        weight.setMem(2, 3.0);  // weight[1][0]
        weight.setMem(3, 4.0);  // weight[1][1]

        log("Input: [" + input.toDouble(0) + ", " + input.toDouble(1) + "]");
        log("Weight:");
        log("  [" + weight.toDouble(0) + ", " + weight.toDouble(1) + "]");
        log("  [" + weight.toDouble(2) + ", " + weight.toDouble(3) + "]");

        // Expected: input @ weight.T = [1,2] @ [[1,3],[2,4]] = [5, 11]
        double[] expected = {5.0, 11.0};
        log("Expected (PyTorch): [" + expected[0] + ", " + expected[1] + "]");

        // Apply AR's dense layer
        CellularLayer denseLayer = dense(weight).apply(shape(inputDim));
        Model model = new Model(shape(inputDim));
        model.add(denseLayer);
        PackedCollection output = model.compile().forward(input);

        log("AR output: [" + output.toDouble(0) + ", " + output.toDouble(1) + "]");

        double error0 = Math.abs(output.toDouble(0) - expected[0]);
        double error1 = Math.abs(output.toDouble(1) - expected[1]);
        log("Errors: [" + error0 + ", " + error1 + "]");

        if (error0 < 1e-5 && error1 < 1e-5) {
            log("[PASS] Dense layer matches PyTorch convention");
        } else {
            log("[FAIL] Dense layer DOES NOT match PyTorch convention!");
            log("This indicates weight matrix orientation issue.");
        }
    }

    /**
     * Test RMSNorm on actual model weights.
     */
    @Test
    public void testRMSNormWithRealWeights() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/rmsnorm_real_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== RMSNorm with Real Weights Test ===\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Get layer 0 RMSNorm weights
        PackedCollection rmsWeight = stateDict.get("model.layers.0.input_layernorm.weight");
        log("RMSNorm weight shape: " + rmsWeight.getShape());
        log("First 5 weights: " + rmsWeight.toDouble(0) + ", " + rmsWeight.toDouble(1) +
            ", " + rmsWeight.toDouble(2) + ", " + rmsWeight.toDouble(3) + ", " + rmsWeight.toDouble(4));

        // Get embedding for token 9707 ("Hello")
        PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
        int tokenId = 9707;
        int dim = rmsWeight.getShape().length(0);

        PackedCollection tokenEmbed = new PackedCollection(shape(dim));
        for (int i = 0; i < dim; i++) {
            tokenEmbed.setMem(i, embeddings.toDouble(tokenId * dim + i));
        }

        log("Token embedding (first 5): " + tokenEmbed.toDouble(0) + ", " + tokenEmbed.toDouble(1) +
            ", " + tokenEmbed.toDouble(2) + ", " + tokenEmbed.toDouble(3) + ", " + tokenEmbed.toDouble(4));

        // Compute expected RMSNorm output manually
        double sumSquares = 0;
        for (int i = 0; i < dim; i++) {
            sumSquares += tokenEmbed.toDouble(i) * tokenEmbed.toDouble(i);
        }
        double rms = Math.sqrt(sumSquares / dim + 1e-6);
        log("Computed RMS: " + rms);

        double[] expected = new double[dim];
        for (int i = 0; i < dim; i++) {
            expected[i] = (tokenEmbed.toDouble(i) / rms) * rmsWeight.toDouble(i);
        }
        log("Expected (first 5): " + expected[0] + ", " + expected[1] + ", " + expected[2] +
            ", " + expected[3] + ", " + expected[4]);

        // Apply AR's RMSNorm
        CellularLayer rmsnormLayer = rmsnorm(shape(1, dim), rmsWeight, 1e-6);
        Model model = new Model(shape(1, dim));
        model.add(rmsnormLayer);

        PackedCollection input2D = new PackedCollection(shape(1, dim));
        for (int i = 0; i < dim; i++) {
            input2D.setMem(i, tokenEmbed.toDouble(i));
        }

        PackedCollection output = model.compile().forward(input2D);

        log("AR output (first 5): " + output.toDouble(0) + ", " + output.toDouble(1) + ", " +
            output.toDouble(2) + ", " + output.toDouble(3) + ", " + output.toDouble(4));

        // Compare
        double maxError = 0;
        for (int i = 0; i < dim; i++) {
            double error = Math.abs(output.toDouble(i) - expected[i]);
            maxError = Math.max(maxError, error);
        }
        log("Max error: " + maxError);

        if (maxError < 1e-5) {
            log("[PASS] RMSNorm matches expected values");
        } else {
            log("[FAIL] RMSNorm diverges from expected values");
        }

        // Load PyTorch reference if available
        try {
            float[] pytorchRef = loadReferenceOutput("after_rmsnorm_pre_attn.bin");
            if (pytorchRef != null) {
                log("\nComparison with PyTorch reference:");
                double pytorchMaxError = 0;
                for (int i = 0; i < Math.min(dim, pytorchRef.length); i++) {
                    double error = Math.abs(output.toDouble(i) - pytorchRef[i]);
                    pytorchMaxError = Math.max(pytorchMaxError, error);
                }
                log("Max error vs PyTorch: " + pytorchMaxError);
            }
        } catch (Exception e) {
            log("PyTorch reference not available: " + e.getMessage());
        }

        stateDict.destroy();
    }

    /**
     * Test Q projection step.
     */
    @Test
    public void testQProjection() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/q_projection_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Q Projection Test ===\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Get layer 0 weights
        PackedCollection wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
        PackedCollection bq = stateDict.get("model.layers.0.self_attn.q_proj.bias");

        log("Q weight shape: " + wq.getShape());
        log("Q bias shape: " + (bq != null ? bq.getShape().toString() : "null"));

        int dim = wq.getShape().length(1);  // input dim
        int qDim = wq.getShape().length(0); // output dim

        log("Input dim: " + dim + ", Q output dim: " + qDim);

        // Create simple test input
        PackedCollection input = new PackedCollection(shape(1, dim));
        for (int i = 0; i < dim; i++) {
            input.setMem(i, (i % 10) * 0.1);  // Simple pattern
        }

        // Compute expected output: input @ weight.T + bias
        double[] expected = new double[qDim];
        for (int i = 0; i < qDim; i++) {
            expected[i] = 0;
            for (int j = 0; j < dim; j++) {
                expected[i] += input.toDouble(j) * wq.toDouble(i * dim + j);
            }
            if (bq != null) {
                expected[i] += bq.toDouble(i);
            }
        }

        log("Expected output (first 10): ");
        for (int i = 0; i < 10; i++) {
            log("  [" + i + "] = " + expected[i]);
        }

        // Apply AR's dense layer
        CellularLayer qProjLayer = bq != null ? dense(wq, bq).apply(shape(1, dim)) : dense(wq).apply(shape(1, dim));
        Model model = new Model(shape(1, dim));
        model.add(qProjLayer);
        PackedCollection output = model.compile().forward(input);

        log("AR output (first 10): ");
        for (int i = 0; i < 10; i++) {
            log("  [" + i + "] = " + output.toDouble(i));
        }

        // Compare
        double maxError = 0;
        int maxErrorIdx = 0;
        for (int i = 0; i < qDim; i++) {
            double error = Math.abs(output.toDouble(i) - expected[i]);
            if (error > maxError) {
                maxError = error;
                maxErrorIdx = i;
            }
        }
        log("Max error: " + maxError + " at index " + maxErrorIdx);

        if (maxError < 1e-4) {
            log("[PASS] Q projection matches expected values");
        } else {
            log("[FAIL] Q projection diverges from expected values");
            log("Expected[" + maxErrorIdx + "] = " + expected[maxErrorIdx]);
            log("Got[" + maxErrorIdx + "] = " + output.toDouble(maxErrorIdx));
        }

        stateDict.destroy();
    }

    /**
     * Load reference output from binary file.
     */
    private float[] loadReferenceOutput(String filename) throws Exception {
        try (FileChannel channel = FileChannel.open(
                Paths.get(REFERENCE_DIR, filename), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(buffer);
            buffer.flip();

            int size = buffer.getInt();
            float[] data = new float[size];
            for (int i = 0; i < size; i++) {
                data[i] = buffer.getFloat();
            }
            return data;
        }
    }
}
