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
 * Compares FFN step-by-step output between layers 22 and 23 to find where
 * layer 23's amplification pattern diverges.
 *
 * <p>Key finding from previous tests: Layer 22 works correctly but layer 23
 * under-amplifies by ~4x. This test compares intermediate values at each step.</p>
 */
public class FFNLayerComparisonTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Compare FFN intermediate values between layer 22 and layer 23.
     *
     * <p>Layer 22 input: after_layer_21.bin (output of layer 21)</p>
     * <p>Layer 23 input: after_layer_22.bin (output of layer 22)</p>
     */
    @Test
    public void compareFFNStepByStep() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/ffn_layer_comparison.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  FFN Layer 22 vs Layer 23 Step-by-Step Comparison");
        log("===================================================\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Load PyTorch reference inputs
        float[] layer22Input = loadReferenceOutput("after_layer_21.bin");
        float[] layer22Expected = loadReferenceOutput("after_layer_22.bin");
        float[] layer23Input = loadReferenceOutput("after_layer_22.bin");
        float[] layer23Expected = loadReferenceOutput("after_layer_23.bin");

        if (layer22Input == null || layer22Expected == null ||
            layer23Input == null || layer23Expected == null) {
            log("ERROR: Cannot load reference files");
            stateDict.destroy();
            return;
        }

        log("Layer 22: after_layer_21 -> after_layer_22");
        log("Layer 23: after_layer_22 -> after_layer_23\n");

        // Run both layers step by step
        FFNResult layer22Result = runFFNStepByStep(stateDict, config, 22, layer22Input, layer22Expected);
        log("\n---------------------------------------------------\n");
        FFNResult layer23Result = runFFNStepByStep(stateDict, config, 23, layer23Input, layer23Expected);

        // Side-by-side comparison
        log("\n===================================================");
        log("  SIDE-BY-SIDE COMPARISON");
        log("===================================================\n");

        log("Step                    | Layer 22 std | Layer 23 std | Ratio (23/22)");
        log("------------------------|--------------|--------------|---------------");
        log(String.format("Input                   | %12.6f | %12.6f | %13.3f",
            layer22Result.inputStd, layer23Result.inputStd, layer23Result.inputStd / layer22Result.inputStd));
        log(String.format("After RMSNorm           | %12.6f | %12.6f | %13.3f",
            layer22Result.rmsNormStd, layer23Result.rmsNormStd, layer23Result.rmsNormStd / layer22Result.rmsNormStd));
        log(String.format("After gate_proj (w1)    | %12.6f | %12.6f | %13.3f",
            layer22Result.gateStd, layer23Result.gateStd, layer23Result.gateStd / layer22Result.gateStd));
        log(String.format("After SiLU              | %12.6f | %12.6f | %13.3f",
            layer22Result.siluStd, layer23Result.siluStd, layer23Result.siluStd / layer22Result.siluStd));
        log(String.format("After up_proj (w3)      | %12.6f | %12.6f | %13.3f",
            layer22Result.upStd, layer23Result.upStd, layer23Result.upStd / layer22Result.upStd));
        log(String.format("After multiply          | %12.6f | %12.6f | %13.3f",
            layer22Result.multiplyStd, layer23Result.multiplyStd, layer23Result.multiplyStd / layer22Result.multiplyStd));
        log(String.format("After down_proj (w2)    | %12.6f | %12.6f | %13.3f",
            layer22Result.downStd, layer23Result.downStd, layer23Result.downStd / layer22Result.downStd));
        log(String.format("Final (with residual)   | %12.6f | %12.6f | %13.3f",
            layer22Result.finalStd, layer23Result.finalStd, layer23Result.finalStd / layer22Result.finalStd));
        log(String.format("Expected (PyTorch)      | %12.6f | %12.6f | %13.3f",
            layer22Result.expectedStd, layer23Result.expectedStd, layer23Result.expectedStd / layer22Result.expectedStd));

        log("\n===================================================");
        log("  AMPLIFICATION RATIOS (output_std / input_std)");
        log("===================================================\n");

        log("Step                    | Layer 22 amp | Layer 23 amp | Match?");
        log("------------------------|--------------|--------------|--------");
        log(String.format("RMSNorm (input->norm)   | %12.3fx | %12.3fx | %s",
            layer22Result.rmsNormStd / layer22Result.inputStd,
            layer23Result.rmsNormStd / layer23Result.inputStd,
            Math.abs((layer22Result.rmsNormStd / layer22Result.inputStd) -
                     (layer23Result.rmsNormStd / layer23Result.inputStd)) < 0.5 ? "OK" : "DIFFERS"));
        log(String.format("gate_proj (norm->gate)  | %12.3fx | %12.3fx | %s",
            layer22Result.gateStd / layer22Result.rmsNormStd,
            layer23Result.gateStd / layer23Result.rmsNormStd,
            Math.abs((layer22Result.gateStd / layer22Result.rmsNormStd) -
                     (layer23Result.gateStd / layer23Result.rmsNormStd)) < 0.5 ? "OK" : "DIFFERS"));
        log(String.format("SiLU (gate->silu)       | %12.3fx | %12.3fx | %s",
            layer22Result.siluStd / layer22Result.gateStd,
            layer23Result.siluStd / layer23Result.gateStd,
            Math.abs((layer22Result.siluStd / layer22Result.gateStd) -
                     (layer23Result.siluStd / layer23Result.gateStd)) < 0.5 ? "OK" : "DIFFERS"));
        log(String.format("up_proj (norm->up)      | %12.3fx | %12.3fx | %s",
            layer22Result.upStd / layer22Result.rmsNormStd,
            layer23Result.upStd / layer23Result.rmsNormStd,
            Math.abs((layer22Result.upStd / layer22Result.rmsNormStd) -
                     (layer23Result.upStd / layer23Result.rmsNormStd)) < 0.5 ? "OK" : "DIFFERS"));
        log(String.format("multiply (silu*up)      | %12.3fx | %12.3fx | %s",
            layer22Result.multiplyStd / (layer22Result.siluStd * layer22Result.upStd),
            layer23Result.multiplyStd / (layer23Result.siluStd * layer23Result.upStd),
            "N/A"));
        log(String.format("down_proj (mul->down)   | %12.3fx | %12.3fx | %s",
            layer22Result.downStd / layer22Result.multiplyStd,
            layer23Result.downStd / layer23Result.multiplyStd,
            Math.abs((layer22Result.downStd / layer22Result.multiplyStd) -
                     (layer23Result.downStd / layer23Result.multiplyStd)) < 0.5 ? "OK" : "DIFFERS"));

        log("\n===================================================");
        log("  DELTA ANALYSIS (layer_output - layer_input)");
        log("===================================================\n");

        log(String.format("Layer 22: Expected delta std=%.6f, AR delta std=%.6f, Ratio=%.3fx",
            layer22Result.expectedDeltaStd, layer22Result.arDeltaStd,
            layer22Result.arDeltaStd / layer22Result.expectedDeltaStd));
        log(String.format("Layer 23: Expected delta std=%.6f, AR delta std=%.6f, Ratio=%.3fx",
            layer23Result.expectedDeltaStd, layer23Result.arDeltaStd,
            layer23Result.arDeltaStd / layer23Result.expectedDeltaStd));

        log("\n===================================================");
        log("  ERROR SUMMARY");
        log("===================================================\n");

        log(String.format("Layer 22: Mean error=%.6f, Max error=%.6f",
            layer22Result.meanError, layer22Result.maxError));
        log(String.format("Layer 23: Mean error=%.6f, Max error=%.6f",
            layer23Result.meanError, layer23Result.maxError));

        if (layer23Result.meanError > layer22Result.meanError * 10) {
            log("\n*** CONCLUSION: Layer 23 error is significantly higher ***");
            log("*** Look at the amplification ratios to see which step diverges ***");
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    private FFNResult runFFNStepByStep(StateDictionary stateDict, Qwen3Config config,
                                       int layerIdx, float[] inputArr, float[] expectedArr) throws Exception {
        FFNResult result = new FFNResult();

        log(String.format("=== Layer %d FFN Step-by-Step ===\n", layerIdx));

        // Convert input
        PackedCollection input = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, inputArr[i]);
        }
        result.inputStd = computeStd(inputArr);
        log(String.format("Input std: %.6f", result.inputStd));

        // Load weights
        String prefix = String.format("model.layers.%d", layerIdx);
        PackedCollection rmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        // Step 1: RMSNorm
        Model rmsModel = new Model(shape(config.dim));
        rmsModel.add(rmsnorm(shape(1, config.dim), rmsFfn));
        PackedCollection rmsOut = rmsModel.compile().forward(input);
        result.rmsNormStd = computeStd(rmsOut, config.dim);
        log(String.format("After RMSNorm std: %.6f", result.rmsNormStd));

        // Step 2: Gate projection
        Model gateModel = new Model(shape(config.dim));
        gateModel.add(dense(w1));
        PackedCollection gateOut = gateModel.compile().forward(rmsOut);
        result.gateStd = computeStd(gateOut, config.hiddenDim);
        log(String.format("After gate_proj std: %.6f", result.gateStd));

        // Step 3: SiLU
        Model siluModel = new Model(shape(config.hiddenDim));
        siluModel.add(silu());
        PackedCollection siluOut = siluModel.compile().forward(gateOut);
        result.siluStd = computeStd(siluOut, config.hiddenDim);
        log(String.format("After SiLU std: %.6f", result.siluStd));

        // Step 4: Up projection
        Model upModel = new Model(shape(config.dim));
        upModel.add(dense(w3));
        PackedCollection upOut = upModel.compile().forward(rmsOut);
        result.upStd = computeStd(upOut, config.hiddenDim);
        log(String.format("After up_proj std: %.6f", result.upStd));

        // Step 5: Multiply
        double[] mulOut = new double[config.hiddenDim];
        for (int i = 0; i < config.hiddenDim; i++) {
            mulOut[i] = siluOut.toDouble(i) * upOut.toDouble(i);
        }
        result.multiplyStd = computeStd(mulOut);
        log(String.format("After multiply std: %.6f", result.multiplyStd));

        // Step 6: Down projection
        PackedCollection mulInput = new PackedCollection(shape(config.hiddenDim));
        for (int i = 0; i < config.hiddenDim; i++) {
            mulInput.setMem(i, mulOut[i]);
        }
        Model downModel = new Model(shape(config.hiddenDim));
        downModel.add(dense(w2));
        PackedCollection downOut = downModel.compile().forward(mulInput);
        result.downStd = computeStd(downOut, config.dim);
        log(String.format("After down_proj std: %.6f", result.downStd));

        // Step 7: Add residual
        double[] finalOut = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            finalOut[i] = input.toDouble(i) + downOut.toDouble(i);
        }
        result.finalStd = computeStd(finalOut);
        log(String.format("Final (with residual) std: %.6f", result.finalStd));

        // Expected
        result.expectedStd = computeStd(expectedArr);
        log(String.format("Expected (PyTorch) std: %.6f", result.expectedStd));

        // Delta analysis
        double[] expectedDelta = new double[config.dim];
        double[] arDelta = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            expectedDelta[i] = expectedArr[i] - inputArr[i];
            arDelta[i] = finalOut[i] - inputArr[i];
        }
        result.expectedDeltaStd = computeStd(expectedDelta);
        result.arDeltaStd = computeStd(arDelta);
        log(String.format("Expected delta std: %.6f", result.expectedDeltaStd));
        log(String.format("AR delta std: %.6f", result.arDeltaStd));

        // Error
        double sumError = 0, maxError = 0;
        for (int i = 0; i < config.dim; i++) {
            double error = Math.abs(finalOut[i] - expectedArr[i]);
            sumError += error;
            if (error > maxError) maxError = error;
        }
        result.meanError = sumError / config.dim;
        result.maxError = maxError;
        log(String.format("Error: mean=%.6f, max=%.6f", result.meanError, result.maxError));

        return result;
    }

    private double computeStd(float[] arr) {
        double sum = 0, sumSq = 0;
        for (float v : arr) {
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / arr.length;
        return Math.sqrt(sumSq / arr.length - mean * mean);
    }

    private double computeStd(double[] arr) {
        double sum = 0, sumSq = 0;
        for (double v : arr) {
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / arr.length;
        return Math.sqrt(sumSq / arr.length - mean * mean);
    }

    private double computeStd(PackedCollection c, int size) {
        double sum = 0, sumSq = 0;
        for (int i = 0; i < size; i++) {
            double v = c.toDouble(i);
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / size;
        return Math.sqrt(sumSq / size - mean * mean);
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

    private static class FFNResult {
        double inputStd;
        double rmsNormStd;
        double gateStd;
        double siluStd;
        double upStd;
        double multiplyStd;
        double downStd;
        double finalStd;
        double expectedStd;
        double expectedDeltaStd;
        double arDeltaStd;
        double meanError;
        double maxError;
    }
}
