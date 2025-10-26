package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Quick sanity check on generation output quality.
 */
public class QuickGenerationCheck {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void quickGenerationCheck() throws Exception {
        System.err.println("\n=== QUICK GENERATION SANITY CHECK ===\n");

        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        model.setTemperature(0.0);  // Greedy decoding for reproducibility

        System.err.println("PROMPT: \"Hello\"");
        System.err.println("\nGENERATED OUTPUT:");
        System.err.print("<|im_start|>\n");

        StringBuilder output = new StringBuilder();
        model.run(20, "Hello", token -> {
            System.err.print(token);
            output.append(token);
        });

        System.err.println("\n\n=== OUTPUT ANALYSIS ===");
        System.err.println("Length: " + output.length() + " characters");
        System.err.println("Contains Chinese/garbage chars: " + output.toString().matches(".*[\\p{IsHan}\\?].*"));
        System.err.println("Looks like English: " + output.toString().matches(".*[a-zA-Z\\s.,!?]+.*"));

        String outStr = output.toString();
        if (outStr.length() > 10) {
            System.err.println("\nFirst 50 chars: \"" + outStr.substring(0, Math.min(50, outStr.length())) + "\"");
        }

        stateDict.destroy();
    }
}
