package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;

/**
 * Demo to see actual generation output from Qwen3 with the fixed tokenizer.
 */
public class Qwen3GenerationDemo extends TestSuiteBase {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test(timeout = 300000)
    public void testGeneration() throws Exception {
        log("\n" + "=".repeat(70));
        log("QWEN3 GENERATION TEST WITH FIXED TOKENIZER");
        log("=".repeat(70) + "\n");

        // Load model
        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        log("[Model loaded successfully]\n");

        // Test different prompts
        String[] prompts = {
            "Hello",
            "Tell me a story",
            "The quick brown fox"
        };

        for (String prompt : prompts) {
            log("-".repeat(70));
            log("PROMPT: \"" + prompt + "\"");
            log("-".repeat(70));

            // Show tokenization
            int[] tokens = tokenizer.encode(prompt, false, false);
            log("Tokens: " + Arrays.toString(tokens));
            log("\nGENERATED OUTPUT:");
            log(">>> ");

            model.setTemperature(0.8);

            StringBuilder output = new StringBuilder();
            model.run(30, prompt, token -> {
                log(String.valueOf(token));

                output.append(token);
            });

            log("\n");
            log("FULL OUTPUT: " + output.toString());
            log("");
        }

        stateDict.destroy();

        log("=".repeat(70));
        log("TEST COMPLETE");
        log("=".repeat(70) + "\n");
    }
}
