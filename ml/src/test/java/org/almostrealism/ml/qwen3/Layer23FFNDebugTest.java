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
 * Debug layer 23's FFN block by comparing it to layer 22's FFN.
 *
 * Since the component test showed the issue is in FFN, not attention,
 * this test focuses on understanding what's different about layer 23's FFN.
 */
public class Layer23FFNDebugTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void compareFFNWeights() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_ffn_weights.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 FFN Weight Comparison ===\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        String[] ffnWeights = {
            "post_attention_layernorm.weight",
            "mlp.gate_proj.weight",
            "mlp.up_proj.weight",
            "mlp.down_proj.weight"
        };

        log("| Weight | Layer | Mean | Std | Min | Max | Norm |");
        log("|--------|-------|------|-----|-----|-----|------|");

        for (String weightName : ffnWeights) {
            for (int layer : new int[]{22, 23}) {
                String key = String.format("model.layers.%d.%s", layer, weightName);
                PackedCollection weight = stateDict.get(key);

                if (weight == null) {
                    log(String.format("| %s | %d | MISSING |", weightName, layer));
                    continue;
                }

                int size = (int) weight.getShape().getSize();
                double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;

                for (int i = 0; i < size; i++) {
                    double v = weight.toDouble(i);
                    sum += v;
                    sumSq += v * v;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }

                double mean = sum / size;
                double std = Math.sqrt(sumSq / size - mean * mean);
                double norm = Math.sqrt(sumSq);

                log(String.format("| %s | %d | %.6f | %.6f | %.6f | %.6f | %.2f |",
                    shortName(weightName), layer, mean, std, min, max, norm));
            }
        }

        // Check for significant differences
        log("\n=== Weight Difference Analysis ===\n");

        for (String weightName : ffnWeights) {
            PackedCollection w22 = stateDict.get(String.format("model.layers.22.%s", weightName));
            PackedCollection w23 = stateDict.get(String.format("model.layers.23.%s", weightName));

            if (w22 == null || w23 == null) continue;

            int size = (int) w22.getShape().getSize();
            double maxDiff = 0;
            double sumAbsDiff = 0;

            for (int i = 0; i < size; i++) {
                double diff = Math.abs(w22.toDouble(i) - w23.toDouble(i));
                sumAbsDiff += diff;
                if (diff > maxDiff) maxDiff = diff;
            }

            double meanAbsDiff = sumAbsDiff / size;
            log(String.format("%s: meanDiff=%.6f, maxDiff=%.6f",
                weightName, meanAbsDiff, maxDiff));
        }

        stateDict.destroy();
        log("\n=== Analysis Complete ===");
    }

    @Test
    public void testFFNOnlyWithSameInput() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_ffn_same_input.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== FFN-Only Test with Same Input ===\n");
        log("Testing FFN blocks with identical synthetic input to isolate weight behavior\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Create a synthetic input (random values in reasonable range)
        PackedCollection testInput = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            // Use a simple pattern: values between -2 and 2
            testInput.setMem(i, Math.sin(i * 0.01) * 2.0);
        }

        log(String.format("Test input: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
            computeMean(testInput, config.dim),
            computeStd(testInput, config.dim),
            computeMin(testInput, config.dim),
            computeMax(testInput, config.dim)));

        // Test FFN for layers 22 and 23 with the SAME input
        for (int layerIdx : new int[]{22, 23}) {
            log(String.format("\n=== Layer %d FFN ===\n", layerIdx));

            SequentialBlock ffn = buildFFNOnly(config, stateDict, layerIdx);
            Model ffnModel = new Model(shape(config.dim));
            ffnModel.add(ffn);
            org.almostrealism.model.CompiledModel compiled = ffnModel.compile();

            PackedCollection output = compiled.forward(testInput);

            log(String.format("Layer %d FFN output:", layerIdx));
            log(String.format("  mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
                computeMean(output, config.dim),
                computeStd(output, config.dim),
                computeMin(output, config.dim),
                computeMax(output, config.dim)));

            // Print first 10 values
            StringBuilder sb = new StringBuilder("  First 10: [");
            for (int i = 0; i < 10; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("%.4f", output.toDouble(i)));
            }
            sb.append("]");
            log(sb.toString());
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    @Test
    public void testFFNSubcomponents() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer23_ffn_subcomponents.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== FFN Subcomponent Analysis ===\n");
        log("Testing each FFN subcomponent separately\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Create test input (use PyTorch reference output from layer 22)
        String inputFile = "after_layer_22.bin";
        float[] pytorchInput = loadReferenceOutput(inputFile);
        if (pytorchInput == null) {
            log("ERROR: Cannot load reference input");
            return;
        }

        PackedCollection testInput = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            testInput.setMem(i, pytorchInput[i]);
        }

        log(String.format("Input (after_layer_22): mean=%.6f, std=%.6f",
            computeMean(testInput, config.dim),
            computeStd(testInput, config.dim)));

        for (int layerIdx : new int[]{22, 23}) {
            log(String.format("\n=== Layer %d FFN Subcomponents ===\n", layerIdx));

            String prefix = String.format("model.layers.%d", layerIdx);
            PackedCollection rmsFfnWeight = stateDict.get(prefix + ".post_attention_layernorm.weight");
            PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
            PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
            PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

            // Test 1: RMSNorm only
            Model rmsModel = new Model(shape(config.dim));
            rmsModel.add(rmsnorm(shape(1, config.dim), rmsFfnWeight));
            PackedCollection rmsOutput = rmsModel.compile().forward(testInput);
            log(String.format("After RMSNorm: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
                computeMean(rmsOutput, config.dim),
                computeStd(rmsOutput, config.dim),
                computeMin(rmsOutput, config.dim),
                computeMax(rmsOutput, config.dim)));

            // Test 2: RMSNorm + gate_proj (w1)
            Model gateModel = new Model(shape(config.dim));
            gateModel.add(rmsnorm(shape(1, config.dim), rmsFfnWeight));
            gateModel.add(dense(w1));
            PackedCollection gateOutput = gateModel.compile().forward(testInput);
            log(String.format("After gate_proj: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
                computeMean(gateOutput, config.hiddenDim),
                computeStd(gateOutput, config.hiddenDim),
                computeMin(gateOutput, config.hiddenDim),
                computeMax(gateOutput, config.hiddenDim)));

            // Test 3: RMSNorm + up_proj (w3)
            Model upModel = new Model(shape(config.dim));
            upModel.add(rmsnorm(shape(1, config.dim), rmsFfnWeight));
            upModel.add(dense(w3));
            PackedCollection upOutput = upModel.compile().forward(testInput);
            log(String.format("After up_proj: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
                computeMean(upOutput, config.hiddenDim),
                computeStd(upOutput, config.hiddenDim),
                computeMin(upOutput, config.hiddenDim),
                computeMax(upOutput, config.hiddenDim)));

            // Test 4: Full FFN (without residual for now)
            Model ffnModel = new Model(shape(config.dim));
            ffnModel.add(feedForwardNoResidual(rmsFfnWeight, w1, w2, w3));
            PackedCollection ffnOutput = ffnModel.compile().forward(testInput);
            log(String.format("After full FFN: mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
                computeMean(ffnOutput, config.dim),
                computeStd(ffnOutput, config.dim),
                computeMin(ffnOutput, config.dim),
                computeMax(ffnOutput, config.dim)));
        }

        stateDict.destroy();
        log("\n=== Analysis Complete ===");
    }

    private SequentialBlock buildFFNOnly(Qwen3Config config, StateDictionary stateDict, int layerIdx) {
        SequentialBlock model = new SequentialBlock(shape(1, config.dim));
        String prefix = String.format("model.layers.%d", layerIdx);

        PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        model.accum(feedForward(layerRmsFfn, layerW1, layerW2, layerW3));

        return model;
    }

    private SequentialBlock feedForwardNoResidual(PackedCollection rms,
                                                   PackedCollection w1,
                                                   PackedCollection w2,
                                                   PackedCollection w3) {
        int dim = w2.getShape().length(0);
        SequentialBlock feedForward = new SequentialBlock(shape(1, dim));
        feedForward.add(rmsnorm(shape(1, dim), rms));

        SequentialBlock hidden = new SequentialBlock(shape(1, dim));
        hidden.add(dense(w1));
        hidden.add(silu());

        feedForward.product(dense(w3), hidden);
        feedForward.add(dense(w2));
        return feedForward;
    }

    private String shortName(String name) {
        if (name.length() > 25) {
            return name.substring(0, 22) + "...";
        }
        return name;
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

    private double computeMean(PackedCollection c, int size) {
        double sum = 0;
        for (int i = 0; i < size; i++) sum += c.toDouble(i);
        return sum / size;
    }

    private double computeStd(PackedCollection c, int size) {
        double mean = computeMean(c, size);
        double sumSq = 0;
        for (int i = 0; i < size; i++) {
            double v = c.toDouble(i);
            sumSq += v * v;
        }
        return Math.sqrt(sumSq / size - mean * mean);
    }

    private double computeMin(PackedCollection c, int size) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double v = c.toDouble(i);
            if (v < min) min = v;
        }
        return min;
    }

    private double computeMax(PackedCollection c, int size) {
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double v = c.toDouble(i);
            if (v > max) max = v;
        }
        return max;
    }
}
