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

import java.util.ArrayList;
import java.util.List;

/**
 * Performance benchmark for Qwen3 Java implementation.
 *
 * <p>This test measures time per token for autoregressive generation to establish
 * performance metrics that can be compared with PyTorch reference implementation.</p>
 *
 * <p>Run the companion Python script to get PyTorch baseline:</p>
 * <pre>
 *   cd ml/scripts
 *   python benchmark_pytorch_generation.py --tokens 20 --warmup 5 --runs 3
 * </pre>
 *
 * <p>Results are written to ml/results/java_benchmark.txt for comparison.</p>
 */
public class Qwen3BenchmarkTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";
	private static final String RESULTS_FILE = "/workspace/project/common/ml/results/java_benchmark.txt";

	/**
	 * Run benchmark measuring time per token for autoregressive generation.
	 */
	@Test
	public void benchmarkGeneration() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		// Benchmark parameters (matching Python script defaults)
		int numTokens = 20;
		int warmupTokens = 5;
		int numRuns = 3;

		// Setup logging
		Console.root().addListener(OutputFeatures.fileOutput(RESULTS_FILE));

		log("======================================================================");
		log("  JAVA QWEN3 GENERATION BENCHMARK");
		log("======================================================================");
		log("");

		// Load model
		log("Loading model from: " + WEIGHTS_DIR);
		long loadStart = System.currentTimeMillis();

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 model = new Qwen3(config, stateDict, tokenizer);

		long loadTime = System.currentTimeMillis() - loadStart;
		log("Model loaded in " + loadTime + " ms");
		log("");

		// Get components for direct token generation
		org.almostrealism.model.CompiledModel compiledModel = model.getCompiledModel();
		PackedCollection embeddings = model.getTokenEmbeddings();
		PackedCollection position = model.getPosition();
		int dim = config.dim;
		int vocabSize = config.vocabSize;

		log("Benchmark parameters:");
		log("  Warmup tokens: " + warmupTokens);
		log("  Tokens per run: " + numTokens);
		log("  Number of runs: " + numRuns);
		log("");

		// Input: Start with "Hello" (token 9707)
		int currentToken = 9707;

		// Collect all timing data
		List<List<Double>> allRunTimes = new ArrayList<>();

		for (int run = 0; run < numRuns; run++) {
			log("--- Run " + (run + 1) + " ---");

			List<Double> tokenTimes = new ArrayList<>();
			currentToken = 9707;  // Reset to "Hello"
			int step = 0;

			// Warmup phase (not timed)
			for (int w = 0; w < warmupTokens; w++) {
				position.setMem(0, (double) step);
				PackedCollection input = createInput(embeddings, currentToken, dim);
				PackedCollection logits = compiledModel.forward(input);
				currentToken = findArgmax(logits, vocabSize);
				step++;
			}

			// Timed generation phase
			for (int t = 0; t < numTokens; t++) {
				long start = System.nanoTime();

				position.setMem(0, (double) step);
				PackedCollection input = createInput(embeddings, currentToken, dim);
				PackedCollection logits = compiledModel.forward(input);
				currentToken = findArgmax(logits, vocabSize);

				double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
				tokenTimes.add(elapsedMs);
				step++;
			}

			allRunTimes.add(tokenTimes);

			// Compute run statistics
			double mean = tokenTimes.stream().mapToDouble(d -> d).average().orElse(0);
			double min = tokenTimes.stream().mapToDouble(d -> d).min().orElse(0);
			double max = tokenTimes.stream().mapToDouble(d -> d).max().orElse(0);
			double total = tokenTimes.stream().mapToDouble(d -> d).sum();
			double tokensPerSecond = 1000.0 / mean;

			log(String.format("  Mean time per token: %.2f ms", mean));
			log(String.format("  Min time per token:  %.2f ms", min));
			log(String.format("  Max time per token:  %.2f ms", max));
			log(String.format("  Tokens per second:   %.1f", tokensPerSecond));
			log(String.format("  Total time:          %.1f ms", total));
			log("");
		}

		// Compute overall statistics
		List<Double> allTimes = new ArrayList<>();
		for (List<Double> runTimes : allRunTimes) {
			allTimes.addAll(runTimes);
		}

		double overallMean = allTimes.stream().mapToDouble(d -> d).average().orElse(0);
		double overallMin = allTimes.stream().mapToDouble(d -> d).min().orElse(0);
		double overallMax = allTimes.stream().mapToDouble(d -> d).max().orElse(0);
		double overallTokensPerSecond = 1000.0 / overallMean;

		log("======================================================================");
		log("  OVERALL STATISTICS");
		log("======================================================================");
		log(String.format("  Mean time per token: %.2f ms", overallMean));
		log(String.format("  Min time per token:  %.2f ms", overallMin));
		log(String.format("  Max time per token:  %.2f ms", overallMax));
		log(String.format("  Tokens per second:   %.1f", overallTokensPerSecond));
		log(String.format("  Total tokens:        %d", allTimes.size()));
		log("======================================================================");
		log("");

		// Print profile information if available
		if (model.getProfile() != null) {
			log("--- Operation Profile ---");
			model.getProfile().print();

			// Save profile as XML for detailed analysis
			String profilePath = "/workspace/project/common/ml/results/qwen3_benchmark_profile.xml";
			model.getProfile().save(profilePath);
			log("Profile XML saved to: " + profilePath);
		}

		log("");
		log("Results written to: " + RESULTS_FILE);

		stateDict.destroy();
	}

	/**
	 * Create input collection from token embedding.
	 */
	private PackedCollection createInput(PackedCollection embeddings, int token, int dim) {
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, embeddings.toDouble(token * dim + i));
		}
		return input;
	}

	/**
	 * Find the argmax (greedy decoding).
	 */
	private int findArgmax(PackedCollection logits, int vocabSize) {
		int maxIdx = 0;
		double maxVal = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < vocabSize; i++) {
			double val = logits.toDouble(i);
			if (val > maxVal) {
				maxVal = val;
				maxIdx = i;
			}
		}
		return maxIdx;
	}
}
