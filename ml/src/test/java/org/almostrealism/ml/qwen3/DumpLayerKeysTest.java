/*
 * Copyright 2025 Michael Murray
 */
package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Dump all keys in StateDictionary to find missing weights.
 */
public class DumpLayerKeysTest {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

    @Test
    public void dumpLayer23Keys() throws Exception {
        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        System.out.println("=== All keys containing 'layers.0' ===");
        for (String key : stateDict.keySet()) {
            if (key.contains("layers.0.")) {
                System.out.println("  " + key);
            }
        }

        System.out.println("\n=== Keys containing 'q_norm' or 'k_norm' anywhere ===");
        for (String key : stateDict.keySet()) {
            if (key.contains("q_norm") || key.contains("k_norm")) {
                System.out.println("  " + key);
            }
        }

        System.out.println("\n=== Total keys in StateDictionary: " + stateDict.keySet().size() + " ===");

        System.out.println("\n=== All keys not containing 'layers' ===");
        for (String key : stateDict.keySet()) {
            if (!key.contains("layers")) {
                System.out.println("  " + key);
            }
        }

        stateDict.destroy();
    }
}
