package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertTrue;

/**
 * Breaks down problematic layers into components to identify the exact operation
 * that causes error amplification when layers are stacked.
 */
public class ComponentBreakdownTest implements AttentionFeatures, ConsoleFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    // Test components of layer 2 which shows first major error jump
    @Test
    public void testLayer2ComponentBreakdown() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/component_breakdown_layer2.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 2 Component Breakdown ===\n");
        testLayerComponents(2);
    }

    // Test components of layer 22 which shows moderate error jump
    @Test
    public void testLayer22ComponentBreakdown() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/component_breakdown_layer22.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 22 Component Breakdown ===\n");
        testLayerComponents(22);
    }

    // Test components of layer 23 which shows catastrophic error
    @Test
    public void testLayer23ComponentBreakdown() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/component_breakdown_layer23.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== Layer 23 Component Breakdown ===\n");
        testLayerComponents(23);
    }

    /**
     * Test all components of a single layer to find where divergence occurs
     */
    private void testLayerComponents(int layerIndex) throws Exception {
        log(String.format("Breaking down layer %d into components...\n", layerIndex));

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

        // Load input from previous layer
        String inputFile = layerIndex == 0 ? "after_embeddings.bin" :
                          String.format("after_layer_%d.bin", layerIndex - 1);
        float[] inputData = loadReferenceOutput(inputFile);

        if (inputData == null) {
            log(String.format("ERROR: No reference input found for layer %d", layerIndex));
            return;
        }

        PackedCollection<?> input = new PackedCollection<>(shape(config.dim));
        for (int i = 0; i < config.dim; i++) {
            input.setMem(i, inputData[i]);
        }

        String prefix = String.format("model.layers.%d", layerIndex);

        // Load all layer weights
        PackedCollection<?> layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
        PackedCollection<?> layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
        PackedCollection<?> layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
        PackedCollection<?> layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
        PackedCollection<?> layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
        PackedCollection<?> layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
        PackedCollection<?> layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
        PackedCollection<?> layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
        PackedCollection<?> layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");
        PackedCollection<?> layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
        PackedCollection<?> layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
        PackedCollection<?> layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

        // Test 1: Input normalization only
        log("\n--- Testing Input Normalization ---");
        testInputNorm(config, input, layerRmsAtt);

        // Test 2: Input norm + attention (no residual)
        log("\n--- Testing Attention Block (without residual) ---");
        testAttentionOnly(config, input, layerRmsAtt,
                         layerWq, layerWk, layerWv, layerWo,
                         layerBq, layerBk, layerBv);

        // Test 3: Input norm + attention + residual
        log("\n--- Testing Attention Block (with residual) ---");
        testAttentionWithResidual(config, input, layerRmsAtt,
                                 layerWq, layerWk, layerWv, layerWo,
                                 layerBq, layerBk, layerBv);

        // Test 4: Post-attention norm only
        log("\n--- Testing Post-Attention Normalization ---");
        // Use output from attention+residual as input
        PackedCollection<?> afterAttention = runAttentionWithResidual(config, input, layerRmsAtt,
                                                                      layerWq, layerWk, layerWv, layerWo,
                                                                      layerBq, layerBk, layerBv);
        testPostAttentionNorm(config, afterAttention, layerRmsFfn);

        // Test 5: FFN only (no residual)
        log("\n--- Testing FFN Block (without residual) ---");
        testFFNOnly(config, afterAttention, layerRmsFfn, layerW1, layerW2, layerW3);

        // Test 6: Complete layer
        log("\n--- Testing Complete Layer ---");
        testCompleteLayer(config, input, layerRmsAtt, layerRmsFfn,
                         layerWq, layerWk, layerWv, layerWo,
                         layerBq, layerBk, layerBv,
                         layerW1, layerW2, layerW3, layerIndex);

        stateDict.destroy();
    }

    private void testInputNorm(Qwen3Config config, PackedCollection<?> input,
                               PackedCollection<?> normWeight) {
        Model model = new Model(shape(config.dim));
        model.add(rmsnorm(normWeight));

        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection<?> output = compiled.forward(input);

        // Compare first few values
        log("Input norm output (first 5 values):");
        for (int i = 0; i < 5; i++) {
            log(String.format("  [%d] = %.6f", i, output.toDouble(i)));
        }

        // Check for NaN/Inf
        boolean hasNaN = false;
        boolean hasInf = false;
        for (int i = 0; i < config.dim; i++) {
            double val = output.toDouble(i);
            if (Double.isNaN(val)) hasNaN = true;
            if (Double.isInfinite(val)) hasInf = true;
        }
        log(String.format("  Has NaN: %s, Has Inf: %s", hasNaN, hasInf));
    }

    private void testAttentionOnly(Qwen3Config config, PackedCollection<?> input,
                                   PackedCollection<?> normWeight,
                                   PackedCollection<?> wq, PackedCollection<?> wk,
                                   PackedCollection<?> wv, PackedCollection<?> wo,
                                   PackedCollection<?> bq, PackedCollection<?> bk,
                                   PackedCollection<?> bv) {
        Model model = new Model(shape(config.dim));

        // Normalize input
        model.add(rmsnorm(normWeight));

        // Apply attention (without residual)
        PackedCollection<?> freqCis = computeRopeFreqs(config);
        PackedCollection<?> position = new PackedCollection<>(shape(1));
        position.setMem(0, 0.0);

        // Build attention without residual
        model.add(selfAttention(
            config.headCount, config.kvHeadCount,
            wq, wk, wv, wo,
            bq, bk, bv,
            null, null,  // No QK-Norm
            freqCis, p(position)
        ));

        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection<?> output = compiled.forward(input);

        log("Attention output (no residual, first 5 values):");
        for (int i = 0; i < 5; i++) {
            log(String.format("  [%d] = %.6f", i, output.toDouble(i)));
        }

        // Compute statistics
        double mean = 0, std = 0;
        for (int i = 0; i < config.dim; i++) {
            mean += output.toDouble(i);
        }
        mean /= config.dim;

        for (int i = 0; i < config.dim; i++) {
            double diff = output.toDouble(i) - mean;
            std += diff * diff;
        }
        std = Math.sqrt(std / config.dim);

        log(String.format("  Mean: %.6f, Std: %.6f", mean, std));
    }

    private void testAttentionWithResidual(Qwen3Config config, PackedCollection<?> input,
                                          PackedCollection<?> normWeight,
                                          PackedCollection<?> wq, PackedCollection<?> wk,
                                          PackedCollection<?> wv, PackedCollection<?> wo,
                                          PackedCollection<?> bq, PackedCollection<?> bk,
                                          PackedCollection<?> bv) {
        PackedCollection<?> output = runAttentionWithResidual(config, input, normWeight,
                                                              wq, wk, wv, wo, bq, bk, bv);

        log("Attention output (with residual, first 5 values):");
        for (int i = 0; i < 5; i++) {
            log(String.format("  [%d] = %.6f", i, output.toDouble(i)));
        }

        // Check difference from input (residual effect)
        double maxDiff = 0;
        for (int i = 0; i < config.dim; i++) {
            double diff = Math.abs(output.toDouble(i) - input.toDouble(i));
            maxDiff = Math.max(maxDiff, diff);
        }
        log(String.format("  Max change from input: %.6f", maxDiff));
    }

    private PackedCollection<?> runAttentionWithResidual(Qwen3Config config, PackedCollection<?> input,
                                                         PackedCollection<?> normWeight,
                                                         PackedCollection<?> wq, PackedCollection<?> wk,
                                                         PackedCollection<?> wv, PackedCollection<?> wo,
                                                         PackedCollection<?> bq, PackedCollection<?> bk,
                                                         PackedCollection<?> bv) {
        Model model = new Model(shape(config.dim));

        PackedCollection<?> freqCis = computeRopeFreqs(config);
        PackedCollection<?> position = new PackedCollection<>(shape(1));
        position.setMem(0, 0.0);

        // Attention with residual (standard transformer pattern)
        model.add(Block.compose(
            residual(
                serial(
                    rmsnorm(normWeight),
                    selfAttention(
                        config.headCount, config.kvHeadCount,
                        wq, wk, wv, wo,
                        bq, bk, bv,
                        null, null,  // No QK-Norm
                        freqCis, p(position)
                    )
                )
            )
        ));

        org.almostrealism.model.CompiledModel compiled = model.compile();
        return compiled.forward(input);
    }

    private void testPostAttentionNorm(Qwen3Config config, PackedCollection<?> input,
                                       PackedCollection<?> normWeight) {
        Model model = new Model(shape(config.dim));
        model.add(rmsnorm(normWeight));

        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection<?> output = compiled.forward(input);

        log("Post-attention norm output (first 5 values):");
        for (int i = 0; i < 5; i++) {
            log(String.format("  [%d] = %.6f", i, output.toDouble(i)));
        }
    }

    private void testFFNOnly(Qwen3Config config, PackedCollection<?> input,
                            PackedCollection<?> normWeight,
                            PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3) {
        Model model = new Model(shape(config.dim));

        // Normalize then FFN (without residual)
        model.add(feedForward(normWeight, w1, w2, w3));

        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection<?> output = compiled.forward(input);

        log("FFN output (no residual, first 5 values):");
        for (int i = 0; i < 5; i++) {
            log(String.format("  [%d] = %.6f", i, output.toDouble(i)));
        }

        // Check value ranges
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (int i = 0; i < config.dim; i++) {
            double val = output.toDouble(i);
            min = Math.min(min, val);
            max = Math.max(max, val);
        }
        log(String.format("  Value range: [%.6f, %.6f]", min, max));
    }

    private void testCompleteLayer(Qwen3Config config, PackedCollection<?> input,
                                  PackedCollection<?> rmsAtt, PackedCollection<?> rmsFfn,
                                  PackedCollection<?> wq, PackedCollection<?> wk,
                                  PackedCollection<?> wv, PackedCollection<?> wo,
                                  PackedCollection<?> bq, PackedCollection<?> bk,
                                  PackedCollection<?> bv,
                                  PackedCollection<?> w1, PackedCollection<?> w2,
                                  PackedCollection<?> w3, int layerIndex) {
        Model model = new Model(shape(config.dim));

        PackedCollection<?> freqCis = computeRopeFreqs(config);
        PackedCollection<?> position = new PackedCollection<>(shape(1));
        position.setMem(0, 0.0);

        // Complete transformer layer
        model.add(transformer(
            config.headCount, config.kvHeadCount,
            rmsAtt,
            wk, wv, wq, wo,
            bk, bv, bq,
            null, null,  // No QK-Norm
            freqCis,
            rmsFfn,
            w1, w2, w3,
            p(position)
        ));

        org.almostrealism.model.CompiledModel compiled = model.compile();
        PackedCollection<?> output = compiled.forward(input);

        // Load expected output
        String outputFile = String.format("after_layer_%d.bin", layerIndex);
        float[] expected = loadReferenceOutput(outputFile);

        if (expected != null) {
            // Compare with reference
            double meanError = 0;
            double maxError = 0;
            int maxErrorIdx = 0;

            for (int i = 0; i < config.dim; i++) {
                double diff = Math.abs(output.toDouble(i) - expected[i]);
                meanError += diff;
                if (diff > maxError) {
                    maxError = diff;
                    maxErrorIdx = i;
                }
            }
            meanError /= config.dim;

            log(String.format("\nComplete layer comparison:"));
            log(String.format("  Mean absolute error: %.6f", meanError));
            log(String.format("  Max error: %.6f at index %d", maxError, maxErrorIdx));
            log("  First 5 values:");
            for (int i = 0; i < 5; i++) {
                log(String.format("    [%d] AR: %.6f, PT: %.6f, diff: %.6f",
                    i, output.toDouble(i), expected[i],
                    Math.abs(output.toDouble(i) - expected[i])));
            }

            if (meanError > 0.1) {
                log("  [ERROR] Significant divergence detected!");
            } else if (meanError > 0.01) {
                log("  [WARNING] Notable error accumulation");
            } else {
                log("  [GOOD] Layer working correctly");
            }
        }
    }

    private PackedCollection<?> computeRopeFreqs(Qwen3Config config) {
        int headSize = config.headSize;
        int seqLen = 10;  // Small for testing
        double theta = config.ropeTheta;

        int freqDim = headSize / 2;
        PackedCollection<?> freqCis = new PackedCollection<>(shape(seqLen, freqDim, 2));

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