/*
 * Copyright 2025 Michael Murray
 */
package org.almostrealism.ml.qwen3;

import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Compare PyTorch reference value ranges at each layer.
 */
public class ReferenceRangeTest implements ConsoleFeatures {

    private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

    @Test
    public void showPyTorchRanges() throws Exception {
        String logFile = "/workspace/project/common/ml/test_output/pytorch_ranges.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("\n=== PyTorch Reference Value Ranges ===\n");
        log(String.format("%-15s %-15s %-15s %-15s", "Layer", "Min", "Max", "Mean"));
        log("-".repeat(60));

        // Check embeddings
        float[] emb = loadReferenceOutput("after_embeddings.bin");
        printStats("Embeddings", emb);

        // Check each layer
        for (int i = 0; i < 24; i++) {
            float[] data = loadReferenceOutput("after_layer_" + i + ".bin");
            printStats("Layer " + i, data);
        }

        // Check final logits
        float[] logits = loadReferenceOutput("final_logits.bin");
        printStats("Final Logits", logits);
    }

    private void printStats(String name, float[] data) {
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        double sum = 0;

        for (float v : data) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }

        log(String.format("%-15s %-15.4f %-15.4f %-15.4f", name, min, max, sum / data.length));
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
}
