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

/**
 * Component-level comparison tests to identify divergence sources.
 * Each test verifies a single component against mathematically computed expected values.
 */
public class ComponentComparisonTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

    private static final double TOLERANCE = 1e-5;

    /**
     * Test RMSNorm with epsilon=1e-6 (Qwen3 default).
     *
     * RMSNorm formula: output = (x / sqrt(mean(x^2) + epsilon)) * weight
     */
    @Test
    public void testRMSNorm_Epsilon1e6() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/rmsnorm_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== RMSNorm Test (epsilon=1e-6) ===\n");

        // Simple input: [1.0, 2.0, 3.0, 4.0]
        double[] inputData = {1.0, 2.0, 3.0, 4.0};
        int dim = inputData.length;

        PackedCollection input = new PackedCollection(shape(dim));
        for (int i = 0; i < dim; i++) {
            input.setMem(i, inputData[i]);
        }

        // Weights: all ones (so we can verify just the normalization)
        PackedCollection weights = new PackedCollection(shape(dim));
        for (int i = 0; i < dim; i++) {
            weights.setMem(i, 1.0);
        }

        double epsilon = 1e-6;

        // Compute expected output mathematically:
        // mean(x^2) = (1 + 4 + 9 + 16) / 4 = 30 / 4 = 7.5
        // rms = sqrt(7.5 + 1e-6) = sqrt(7.500001) = 2.7386128...
        // output[i] = input[i] / rms * weight[i] = input[i] / 2.7386128
        double sumSquares = 0;
        for (double v : inputData) {
            sumSquares += v * v;
        }
        double meanSquares = sumSquares / dim;
        double rms = Math.sqrt(meanSquares + epsilon);

        log(String.format("Input: [%.1f, %.1f, %.1f, %.1f]", inputData[0], inputData[1], inputData[2], inputData[3]));
        log(String.format("Sum of squares: %.1f", sumSquares));
        log(String.format("Mean of squares: %.6f", meanSquares));
        log(String.format("RMS (with epsilon=1e-6): %.10f", rms));

        double[] expected = new double[dim];
        for (int i = 0; i < dim; i++) {
            expected[i] = inputData[i] / rms;
        }
        log(String.format("Expected output: [%.10f, %.10f, %.10f, %.10f]",
                expected[0], expected[1], expected[2], expected[3]));

        // Apply AR's RMSNorm
        CellularLayer rmsnormLayer = rmsnorm(shape(dim), weights, epsilon);
        Model model = new Model(shape(dim));
        model.add(rmsnormLayer);
        PackedCollection output = model.compile().forward(input);

        log(String.format("AR output: [%.10f, %.10f, %.10f, %.10f]",
                output.toDouble(0), output.toDouble(1), output.toDouble(2), output.toDouble(3)));

        // Compare
        double maxError = 0;
        for (int i = 0; i < dim; i++) {
            double error = Math.abs(output.toDouble(i) - expected[i]);
            maxError = Math.max(maxError, error);
            log(String.format("  [%d] Expected: %.10f, Got: %.10f, Error: %.2e",
                    i, expected[i], output.toDouble(i), error));
        }

        log(String.format("\nMax error: %.2e", maxError));
        if (maxError < TOLERANCE) {
            log("[PASS] RMSNorm with epsilon=1e-6 matches expected values");
        } else {
            log("[FAIL] RMSNorm diverges from expected values");
        }

        assert maxError < TOLERANCE : "RMSNorm error exceeds tolerance";
    }

    /**
     * Test RMSNorm with epsilon=1e-5 (default) vs 1e-6 to show the difference.
     */
    @Test
    public void testRMSNorm_EpsilonComparison() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/rmsnorm_epsilon_compare.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== RMSNorm Epsilon Comparison (1e-5 vs 1e-6) ===\n");

        // Use small values where epsilon makes a bigger difference
        double[] inputData = {0.001, 0.002, 0.001, 0.002};
        int dim = inputData.length;

        PackedCollection input = new PackedCollection(shape(dim));
        for (int i = 0; i < dim; i++) {
            input.setMem(i, inputData[i]);
        }

        PackedCollection weights = new PackedCollection(shape(dim));
        for (int i = 0; i < dim; i++) {
            weights.setMem(i, 1.0);
        }

        // Compute with epsilon=1e-5
        double sumSquares = 0;
        for (double v : inputData) {
            sumSquares += v * v;
        }
        double meanSquares = sumSquares / dim;

        double rms_1e5 = Math.sqrt(meanSquares + 1e-5);
        double rms_1e6 = Math.sqrt(meanSquares + 1e-6);

        log(String.format("Mean of squares: %.10e", meanSquares));
        log(String.format("RMS with 1e-5: %.10f", rms_1e5));
        log(String.format("RMS with 1e-6: %.10f", rms_1e6));
        log(String.format("Difference in RMS: %.10e", Math.abs(rms_1e5 - rms_1e6)));

        // Apply AR's RMSNorm with both epsilon values
        CellularLayer rmsnorm_1e5 = rmsnorm(shape(dim), weights, 1e-5);
        CellularLayer rmsnorm_1e6 = rmsnorm(shape(dim), weights, 1e-6);

        Model model_1e5 = new Model(shape(dim));
        model_1e5.add(rmsnorm_1e5);
        PackedCollection output_1e5 = model_1e5.compile().forward(input);

        Model model_1e6 = new Model(shape(dim));
        model_1e6.add(rmsnorm_1e6);
        PackedCollection output_1e6 = model_1e6.compile().forward(input);

        log("\nOutput comparison:");
        double totalDiff = 0;
        for (int i = 0; i < dim; i++) {
            double diff = Math.abs(output_1e5.toDouble(i) - output_1e6.toDouble(i));
            totalDiff += diff;
            log(String.format("  [%d] 1e-5: %.10f, 1e-6: %.10f, diff: %.10e",
                    i, output_1e5.toDouble(i), output_1e6.toDouble(i), diff));
        }
        log(String.format("\nTotal absolute difference: %.10e", totalDiff));
        log(String.format("Mean absolute difference: %.10e", totalDiff / dim));

        log("\n[INFO] This shows that epsilon choice matters for small values");
    }

    /**
     * Test SiLU activation: silu(x) = x * sigmoid(x)
     */
    @Test
    public void testSiLU() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/silu_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== SiLU Activation Test ===\n");

        // Test various input values
        double[] inputData = {-2.0, -1.0, 0.0, 1.0, 2.0, 3.0};
        int dim = inputData.length;

        PackedCollection input = new PackedCollection(shape(dim));
        for (int i = 0; i < dim; i++) {
            input.setMem(i, inputData[i]);
        }

        // Compute expected: silu(x) = x * sigmoid(x) = x / (1 + exp(-x))
        double[] expected = new double[dim];
        for (int i = 0; i < dim; i++) {
            double x = inputData[i];
            double sigmoid = 1.0 / (1.0 + Math.exp(-x));
            expected[i] = x * sigmoid;
        }

        log("Input and expected SiLU output:");
        for (int i = 0; i < dim; i++) {
            log(String.format("  x=%.1f: sigmoid=%.10f, silu=%.10f",
                    inputData[i], 1.0 / (1.0 + Math.exp(-inputData[i])), expected[i]));
        }

        // Apply AR's SiLU
        CellularLayer siluLayer = silu(shape(dim));
        Model model = new Model(shape(dim));
        model.add(siluLayer);
        PackedCollection output = model.compile().forward(input);

        log("\nComparison:");
        double maxError = 0;
        for (int i = 0; i < dim; i++) {
            double error = Math.abs(output.toDouble(i) - expected[i]);
            maxError = Math.max(maxError, error);
            log(String.format("  x=%.1f: expected=%.10f, got=%.10f, error=%.2e",
                    inputData[i], expected[i], output.toDouble(i), error));
        }

        log(String.format("\nMax error: %.2e", maxError));
        if (maxError < TOLERANCE) {
            log("[PASS] SiLU matches expected values");
        } else {
            log("[FAIL] SiLU diverges from expected values");
        }

        assert maxError < TOLERANCE : "SiLU error exceeds tolerance";
    }

    /**
     * Test Dense/Linear layer: output = input @ weight.T + bias
     */
    @Test
    public void testDense() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/dense_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Dense Layer Test ===\n");

        // Input: [1, 2, 3] (1x3)
        double[] inputData = {1.0, 2.0, 3.0};
        int inputDim = inputData.length;
        int outputDim = 2;

        PackedCollection input = new PackedCollection(shape(inputDim));
        for (int i = 0; i < inputDim; i++) {
            input.setMem(i, inputData[i]);
        }

        // Weight: 2x3 matrix (outputDim x inputDim)
        // [1, 2, 3]
        // [4, 5, 6]
        double[][] weightData = {{1, 2, 3}, {4, 5, 6}};
        PackedCollection weight = new PackedCollection(shape(outputDim, inputDim));
        for (int i = 0; i < outputDim; i++) {
            for (int j = 0; j < inputDim; j++) {
                weight.setMem(i * inputDim + j, weightData[i][j]);
            }
        }

        // No bias for simplicity
        // Expected output: input @ weight.T
        // [1,2,3] @ [[1,4],[2,5],[3,6]] = [1*1+2*2+3*3, 1*4+2*5+3*6] = [14, 32]
        double[] expected = new double[outputDim];
        for (int i = 0; i < outputDim; i++) {
            for (int j = 0; j < inputDim; j++) {
                expected[i] += inputData[j] * weightData[i][j];
            }
        }

        log(String.format("Input: [%.1f, %.1f, %.1f]", inputData[0], inputData[1], inputData[2]));
        log("Weight:");
        log(String.format("  [%.1f, %.1f, %.1f]", weightData[0][0], weightData[0][1], weightData[0][2]));
        log(String.format("  [%.1f, %.1f, %.1f]", weightData[1][0], weightData[1][1], weightData[1][2]));
        log(String.format("Expected output: [%.1f, %.1f]", expected[0], expected[1]));

        // Apply AR's dense layer
        CellularLayer denseLayer = dense(weight).apply(shape(inputDim));
        Model model = new Model(shape(inputDim));
        model.add(denseLayer);
        PackedCollection output = model.compile().forward(input);

        log(String.format("AR output: [%.10f, %.10f]", output.toDouble(0), output.toDouble(1)));

        // Compare
        double maxError = 0;
        for (int i = 0; i < outputDim; i++) {
            double error = Math.abs(output.toDouble(i) - expected[i]);
            maxError = Math.max(maxError, error);
            log(String.format("  [%d] Expected: %.1f, Got: %.10f, Error: %.2e",
                    i, expected[i], output.toDouble(i), error));
        }

        log(String.format("\nMax error: %.2e", maxError));
        if (maxError < TOLERANCE) {
            log("[PASS] Dense layer matches expected values");
        } else {
            log("[FAIL] Dense layer diverges from expected values");
        }

        assert maxError < TOLERANCE : "Dense layer error exceeds tolerance";
    }

    /**
     * Test softmax: softmax(x)_i = exp(x_i) / sum(exp(x_j))
     */
    @Test
    public void testSoftmax() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/softmax_test.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Softmax Test ===\n");

        // Input: [1, 2, 3, 4] as a (1, 4) shape
        double[] inputData = {1.0, 2.0, 3.0, 4.0};
        int dim = inputData.length;

        PackedCollection input = new PackedCollection(shape(1, dim));
        for (int i = 0; i < dim; i++) {
            input.setMem(i, inputData[i]);
        }

        // Compute expected softmax
        double maxVal = Double.NEGATIVE_INFINITY;
        for (double v : inputData) maxVal = Math.max(maxVal, v);

        double sumExp = 0;
        for (double v : inputData) {
            sumExp += Math.exp(v - maxVal);  // Numerical stability
        }

        double[] expected = new double[dim];
        for (int i = 0; i < dim; i++) {
            expected[i] = Math.exp(inputData[i] - maxVal) / sumExp;
        }

        log("Input: " + java.util.Arrays.toString(inputData));
        log("Expected softmax: " + java.util.Arrays.toString(expected));

        // Apply AR's softmax (requires 2D shape)
        CellularLayer softmaxLayer = softmax(shape(1, dim), true);
        Model model = new Model(shape(1, dim));
        model.add(softmaxLayer);
        PackedCollection output = model.compile().forward(input);

        log("AR output:");
        double sumOutput = 0;
        double maxError = 0;
        for (int i = 0; i < dim; i++) {
            double error = Math.abs(output.toDouble(i) - expected[i]);
            maxError = Math.max(maxError, error);
            sumOutput += output.toDouble(i);
            log(String.format("  [%d] Expected: %.10f, Got: %.10f, Error: %.2e",
                    i, expected[i], output.toDouble(i), error));
        }

        log(String.format("\nSum of softmax outputs: %.10f (should be 1.0)", sumOutput));
        log(String.format("Max error: %.2e", maxError));

        if (maxError < TOLERANCE) {
            log("[PASS] Softmax matches expected values");
        } else {
            log("[FAIL] Softmax diverges from expected values");
        }

        assert maxError < TOLERANCE : "Softmax error exceeds tolerance";
    }
}
