package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.junit.Test;

/**
 * Inspect logits values to understand why model predicts token 198 instead of 271.
 */
public class InspectLogitsTest implements AttentionFeatures {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void inspectLogitsAtPosition0() throws Exception {
        System.err.println("\n=== Inspecting Logits at Position 0 ===\n");

        // Load model
        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        // Get components for manual forward pass
        CompiledModel compiledModel = model.getCompiledModel();
        PackedCollection tokenEmbeddings = model.getTokenEmbeddings();

        // Create input: embedding for token 9707 ("Hello") at position 0
        PackedCollection input = tokenEmbeddings.range(shape(896), 9707 * 896);

        System.err.println("Input token: 9707 (\"Hello\")");
        System.err.println("Position: 0");
        System.err.println("Input shape: " + input.getShape());
        System.err.println("Input sum: " + input.doubleStream().sum());

        // TODO: Need to set position to 0
        // The model expects position to be set via the position PackedCollection
        // But we don't have direct access to it from here
        // This is a limitation of the current test approach

        System.err.println("\n[WARN] Cannot set position directly - position placeholder is internal to model");
        System.err.println("[INFO] Using AutoregressiveModel is the correct way to test");
        System.err.println("[INFO] The issue is that AutoregressiveModel doesn't expose raw logits");

        stateDict.destroy();
    }
}
