package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Demo to see actual generation output from Qwen3 with the fixed tokenizer.
 */
public class Qwen3GenerationDemo extends TestSuiteBase {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
    private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

    @Test
    public void testGeneration() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("QWEN3 GENERATION TEST WITH FIXED TOKENIZER");
        System.out.println("=".repeat(70) + "\n");

        // Load model
        Qwen3Config config = new Qwen3Config(
            896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
        );

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        System.out.println("[Model loaded successfully]\n");

        // Test different prompts
        String[] prompts = {
            "Hello",
            "Tell me a story",
            "The quick brown fox"
        };

        for (String prompt : prompts) {
            System.out.println("-".repeat(70));
            System.out.println("PROMPT: \"" + prompt + "\"");
            System.out.println("-".repeat(70));

            // Show tokenization
            int[] tokens = tokenizer.encode(prompt, false, false);
            System.out.println("Tokens: " + java.util.Arrays.toString(tokens));
            System.out.println("\nGENERATED OUTPUT:");
            System.out.println(">>> ");

            model.setTemperature(0.8);

            StringBuilder output = new StringBuilder();
            model.run(30, prompt, token -> {
                System.out.print(token);
                System.out.flush();
                output.append(token);
            });

            System.out.println("\n");
            System.out.println("FULL OUTPUT: " + output.toString());
            System.out.println();
        }

        stateDict.destroy();

        System.out.println("=".repeat(70));
        System.out.println("TEST COMPLETE");
        System.out.println("=".repeat(70) + "\n");
    }
}
