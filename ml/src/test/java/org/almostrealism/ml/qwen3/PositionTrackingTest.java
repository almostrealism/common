package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Track position values during autoregressive generation to verify correct incrementing.
 */
public class PositionTrackingTest implements AttentionFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void trackPositionDuringGeneration() throws Exception {
        System.err.println("\n=== Position Tracking Test ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        model.setTemperature(0.0);  // Greedy decoding

        // Get autoregressive model
        AutoregressiveModel arModel = model.getAutoregressiveModel();

        System.err.println("Tracking position across 5 generation steps:\n");

        // Manually drive generation loop to inspect position
        arModel.setCurrentToken(9707);  // "Hello"

        for (int step = 0; step < 5; step++) {
            System.err.println("=== Step " + step + " ===");
            System.err.println("Current step (before): " + arModel.getCurrentStep());
            System.err.println("Current token (before): " + arModel.getCurrentToken());

            int nextToken = arModel.next();

            System.err.println("Generated token: " + nextToken + " (\"" +
                tokenizer.decode(new int[]{nextToken}).replace("\n", "\\n") + "\")");
            System.err.println("Current step (after): " + arModel.getCurrentStep());
            System.err.println();
        }

        System.err.println("\n=== ANALYSIS ===");
        System.err.println("If position increments correctly, we should see:");
        System.err.println("  Step 0: position=0, token=9707 (Hello)");
        System.err.println("  Step 1: position=1, generated from Hello");
        System.err.println("  Step 2: position=2, generated from previous");
        System.err.println("  etc...");

        stateDict.destroy();
    }
}
