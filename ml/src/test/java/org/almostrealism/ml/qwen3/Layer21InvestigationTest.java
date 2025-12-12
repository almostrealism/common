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
 * Investigates Layer 21 behavior since reference data shows massive variance drop at this layer.
 *
 * <p>From reference data analysis:
 * - Layer 20: output std=55.998
 * - Layer 21: output std=1.887 (massive 30x drop!)
 * - Layer 22: output std=2.482
 * - Layer 23: output std=7.828
 *
 * This test checks if AR produces the same variance drop at layer 21.</p>
 */
public class Layer21InvestigationTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    /**
     * Test layer 21 isolated behavior with PyTorch input.
     */
    @Test
    public void testLayer21IsolatedOutput() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/layer21_investigation.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Layer 21 Investigation");
        log("===================================================\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        PackedCollection freqCis = computeRopeFreqs(config);
        PackedCollection position = new PackedCollection(shape(1));
        position.setMem(0, 0.0);

        // Load PyTorch reference
        float[] layer20Output = loadReferenceOutput("after_layer_20.bin");
        float[] layer21Output = loadReferenceOutput("after_layer_21.bin");

        if (layer20Output == null || layer21Output == null) {
            log("ERROR: Cannot load reference files");
            stateDict.destroy();
            return;
        }

        log("PyTorch Reference Statistics:");
        log(String.format("  Layer 20 output: %s", statsFromFloat(layer20Output)));
        log(String.format("  Layer 21 output: %s", statsFromFloat(layer21Output)));

        // Compute PyTorch delta
        float[] expectedDelta = new float[config.dim];
        for (int i = 0; i < config.dim; i++) {
            expectedDelta[i] = layer21Output[i] - layer20Output[i];
        }
        log(String.format("  Expected delta: %s", statsFromFloat(expectedDelta)));

        // Run AR layer 21
        log("\n--- AR Layer 21 Output ---\n");

        PackedCollection arInput = new PackedCollection(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            arInput.setMem(i, layer20Output[i]);
        }

        // Build and run layer 21
        SequentialBlock layer21Block = buildTransformerLayer(config, stateDict, 21, freqCis, position);
        Model layer21Model = new Model(shape(config.dim));
        layer21Model.add(layer21Block);
        PackedCollection arOutput = layer21Model.compile().forward(arInput);

        log(String.format("AR Layer 21 output: %s", stats(arOutput, config.dim)));

        // Compute AR delta
        double[] arDelta = new double[config.dim];
        for (int i = 0; i < config.dim; i++) {
            arDelta[i] = arOutput.toDouble(i) - arInput.toDouble(i);
        }
        log(String.format("AR delta: %s", statsFromDouble(arDelta)));

        // Compare
        double sumError = 0, maxError = 0;
        int maxErrorIdx = 0;
        for (int i = 0; i < config.dim; i++) {
            double error = Math.abs(arOutput.toDouble(i) - layer21Output[i]);
            sumError += error;
            if (error > maxError) {
                maxError = error;
                maxErrorIdx = i;
            }
        }

        log(String.format("\nError: mean=%.6f, max=%.6f (idx=%d)", sumError / config.dim, maxError, maxErrorIdx));

        // Check if AR produces the same variance drop
        double arOutputStd = std(arOutput, config.dim);
        double expectedOutputStd = stdFromFloat(layer21Output);
        log(String.format("\nVariance drop check:"));
        log(String.format("  Expected output std: %.4f", expectedOutputStd));
        log(String.format("  AR output std: %.4f", arOutputStd));
        log(String.format("  Ratio (AR/Expected): %.4f", arOutputStd / expectedOutputStd));

        if (Math.abs(arOutputStd - expectedOutputStd) < 1.0) {
            log("\nCONCLUSION: AR produces similar variance drop - layer 21 behavior is correct");
        } else {
            log("\nCONCLUSION: AR does NOT produce the same variance drop - investigate layer 21");
        }

        stateDict.destroy();
        log("\n=== Test Complete ===");
    }

    /**
     * Check what PyTorch hidden_states actually represents.
     *
     * Key question: Does hidden_states[i] in HuggingFace come BEFORE or AFTER
     * the residual connection at the end of each layer?
     */
    @Test
    public void analyzeResidualPattern() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/residual_pattern_analysis.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n===================================================");
        log("  Residual Connection Pattern Analysis");
        log("===================================================\n");

        log("In a standard transformer layer:");
        log("  x' = x + Attention(LayerNorm(x))");
        log("  output = x' + FFN(LayerNorm(x'))");
        log("");
        log("The question: Does HuggingFace hidden_states[i] capture:");
        log("  A) Output AFTER both residuals");
        log("  B) Something else?");
        log("");

        // Load reference outputs
        float[] layer20 = loadReferenceOutput("after_layer_20.bin");
        float[] layer21 = loadReferenceOutput("after_layer_21.bin");
        float[] layer22 = loadReferenceOutput("after_layer_22.bin");
        float[] layer23 = loadReferenceOutput("after_layer_23.bin");

        if (layer20 == null || layer21 == null || layer22 == null || layer23 == null) {
            log("ERROR: Cannot load reference files");
            return;
        }

        log("Reference data statistics:");
        log(String.format("  Layer 20: %s", statsFromFloat(layer20)));
        log(String.format("  Layer 21: %s", statsFromFloat(layer21)));
        log(String.format("  Layer 22: %s", statsFromFloat(layer22)));
        log(String.format("  Layer 23: %s", statsFromFloat(layer23)));

        log("\nDelta (output - input) analysis:");
        float[] delta21 = subtract(layer21, layer20);
        float[] delta22 = subtract(layer22, layer21);
        float[] delta23 = subtract(layer23, layer22);

        log(String.format("  Layer 21 delta: %s", statsFromFloat(delta21)));
        log(String.format("  Layer 22 delta: %s", statsFromFloat(delta22)));
        log(String.format("  Layer 23 delta: %s", statsFromFloat(delta23)));

        // The key insight: In standard transformers, each layer adds a "contribution"
        // The delta magnitude reflects the layer's contribution
        // If layer 23 delta is 3.8x larger than layer 22 delta, that's unusual

        double delta22Std = stdFromFloat(delta22);
        double delta23Std = stdFromFloat(delta23);
        log(String.format("\nLayer 23/22 delta std ratio: %.2fx", delta23Std / delta22Std));

        if (delta23Std / delta22Std > 3.0) {
            log("\nWARNING: Layer 23 delta is significantly larger than layer 22!");
            log("This could indicate:");
            log("  1. Layer 23 has different weight magnitudes");
            log("  2. The final norm is included in hidden_states[24]");
            log("  3. Model architecture difference for final layer");
        }

        log("\n=== Analysis Complete ===");
    }

    private SequentialBlock buildTransformerLayer(Qwen3Config config, StateDictionary stateDict,
                                                  int layerIdx, PackedCollection freqCis,
                                                  PackedCollection position) {
        SequentialBlock model = new SequentialBlock(shape(1, config.dim));
        String prefix = String.format("model.layers.%d", layerIdx);

        // Load weights
        PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
        PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
        PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
        PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
        PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
        PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
        PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
        PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");
        PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
        PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");
        PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        // Attention block with residual
        model.accum(attention(
            config.headCount, config.kvHeadCount,
            layerRmsAtt,
            layerWk, layerWv, layerWq, layerWo,
            layerBk, layerBv, layerBq,
            layerQkNormQ, layerQkNormK,
            freqCis,
            p(position)));

        // FFN block with residual
        model.accum(feedForward(layerRmsFfn, layerW1, layerW2, layerW3));

        return model;
    }

    private float[] subtract(float[] a, float[] b) {
        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] - b[i];
        }
        return result;
    }

    private double std(PackedCollection c, int dim) {
        double sum = 0, sumSq = 0;
        for (int i = 0; i < dim; i++) {
            double v = c.toDouble(i);
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / dim;
        return Math.sqrt(sumSq / dim - mean * mean);
    }

    private double stdFromFloat(float[] arr) {
        double sum = 0, sumSq = 0;
        for (float v : arr) {
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / arr.length;
        return Math.sqrt(sumSq / arr.length - mean * mean);
    }

    private String stats(PackedCollection c, int dim) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < dim; i++) {
            double v = c.toDouble(i);
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / dim;
        double std = Math.sqrt(sumSq / dim - mean * mean);
        return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
    }

    private String statsFromFloat(float[] arr) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (float v : arr) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / arr.length;
        double std = Math.sqrt(sumSq / arr.length - mean * mean);
        return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
    }

    private String statsFromDouble(double[] arr) {
        double sum = 0, sumSq = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : arr) {
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / arr.length;
        double std = Math.sqrt(sumSq / arr.length - mean * mean);
        return String.format("mean=%.4f, std=%.4f, min=%.2f, max=%.2f", mean, std, min, max);
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
