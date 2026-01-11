package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test to detect cache timing issues.
 * If cache write happens AFTER cache read within the same forward pass,
 * running position 1 twice should give different results.
 */
public class CacheTimingTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

	@Test
	public void testCacheTimingIssue() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/cache_timing.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Cache Timing Test");
		log("===================================================\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 qwen3 = new Qwen3(config, stateDict, tokenizer);

		org.almostrealism.model.CompiledModel compiledModel = qwen3.getCompiledModel();
		PackedCollection embeddings = qwen3.getTokenEmbeddings();
		PackedCollection position = qwen3.getPosition();

		log("Model loaded.\n");

		// Position 0 first to populate cache
		log("=== Run 1: Position 0 (Token 9707) ===");
		position.setMem(0, 0.0);
		PackedCollection input0 = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input0.setMem(i, embeddings.toDouble(9707 * config.dim + i));
		}
		PackedCollection logits0 = compiledModel.forward(input0);
		int top0 = findTop1(logits0);
		log("Top prediction: Token " + top0 + " (logit: " + logits0.toDouble(top0) + ")");

		// Position 1 - FIRST run
		log("\n=== Run 2: Position 1 (Token 271) - FIRST RUN ===");
		position.setMem(0, 1.0);
		PackedCollection input1 = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input1.setMem(i, embeddings.toDouble(271 * config.dim + i));
		}
		PackedCollection logits1_run1 = compiledModel.forward(input1);
		int top1_run1 = findTop1(logits1_run1);
		double topLogit1_run1 = logits1_run1.toDouble(top1_run1);
		log("Top prediction: Token " + top1_run1 + " (logit: " + topLogit1_run1 + ")");

		// Save some logit values for comparison
		double[] sample1 = new double[5];
		for (int i = 0; i < 5; i++) {
			sample1[i] = logits1_run1.toDouble(i);
		}

		// Position 1 - SECOND run (same position, same input)
		log("\n=== Run 3: Position 1 (Token 271) - SECOND RUN ===");
		position.setMem(0, 1.0);  // Still position 1
		PackedCollection logits1_run2 = compiledModel.forward(input1);
		int top1_run2 = findTop1(logits1_run2);
		double topLogit1_run2 = logits1_run2.toDouble(top1_run2);
		log("Top prediction: Token " + top1_run2 + " (logit: " + topLogit1_run2 + ")");

		// Compare the two runs
		log("\n=== Comparison ===");
		boolean sameTop = top1_run1 == top1_run2;
		double topDiff = Math.abs(topLogit1_run1 - topLogit1_run2);
		log("Same top prediction: " + sameTop);
		log("Top logit difference: " + topDiff);

		// Check sample logits
		double maxSampleDiff = 0;
		for (int i = 0; i < 5; i++) {
			double diff = Math.abs(sample1[i] - logits1_run2.toDouble(i));
			maxSampleDiff = Math.max(maxSampleDiff, diff);
		}
		log("Max sample difference (first 5 tokens): " + maxSampleDiff);

		if (topDiff > 0.001 || !sameTop) {
			log("\n[PROBLEM DETECTED] The two runs of position 1 produced different results!");
			log("This indicates the cache write is happening AFTER the cache read.");
			log("The first run sees only K0 in the cache, the second run sees K0 and K1.");
		} else {
			log("\n[OK] Both runs produced identical results.");
			log("Cache timing is not the issue - the cache write happens before the cache read.");
		}

		// Bonus: run position 2 to check if context accumulates
		log("\n=== Run 4: Position 2 (Token 40) ===");
		position.setMem(0, 2.0);
		PackedCollection input2 = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input2.setMem(i, embeddings.toDouble(40 * config.dim + i));
		}
		PackedCollection logits2 = compiledModel.forward(input2);
		int top2 = findTop1(logits2);
		log("Top prediction: Token " + top2 + " (logit: " + logits2.toDouble(top2) + ")");

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private int findTop1(PackedCollection logits) {
		int size = logits.getShape().getTotalSize();
		int maxIdx = 0;
		double maxVal = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < size; i++) {
			double val = logits.toDouble(i);
			if (val > maxVal) {
				maxVal = val;
				maxIdx = i;
			}
		}
		return maxIdx;
	}
}
