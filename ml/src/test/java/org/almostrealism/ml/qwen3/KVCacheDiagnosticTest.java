package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Diagnostic tests to isolate the root cause of Qwen3 multi-token generation failure.
 *
 * <h2>Observed Behavior</h2>
 * <ul>
 *   <li>Step 0: correct output (271)</li>
 *   <li>Step 1: wrong output (220)</li>
 *   <li>Steps 2, 3, 4: identical wrong output (220)</li>
 * </ul>
 *
 * <h2>Top 4 Hypotheses</h2>
 * <ol>
 *   <li><b>H1: Position producer evaluated once at compile time</b>
 *       - p(position) captures VALUE not REFERENCE, so position is stuck</li>
 *   <li><b>H2: KV cache writes not persisting</b>
 *       - CollectionReceptor writes may not persist between forward passes</li>
 *   <li><b>H3: Cache write order (write after read)</b>
 *       - andThen() may execute writes AFTER reads within same forward pass</li>
 *   <li><b>H4: GQA-specific issue</b>
 *       - GQA expansion may interact incorrectly with caching</li>
 * </ol>
 */
public class KVCacheDiagnosticTest extends TestSuiteBase implements AttentionFeatures, LayerFeatures, ConsoleFeatures {

	/**
	 * HYPOTHESIS 1: Position producer is evaluated once at compile time.
	 * <p>
	 * Test: Create a minimal model that reads from a position PackedCollection
	 * and adds it to the input. Run multiple forward passes with different
	 * position values and verify outputs differ.
	 * <p>
	 * Expected if H1 is TRUE: All outputs identical (position stuck at initial value)
	 * Expected if H1 is FALSE: Outputs differ for each position
	 */
	@Test
	public void testH1_PositionProducerUpdates() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/h1_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  H1: Position Producer Update Test");
		log("===================================================\n");

		// Create a position placeholder
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		int dim = 4;

		// Build model: input -> add position value -> output
		// This is a VERY simple test: input[i] + position[0] for all i
		Model model = new Model(shape(dim));

		// Add a layer that multiplies input by (1 + position value)
		// This tests whether p(position) is being re-evaluated each forward pass
		model.add(layer("positionMultiply", shape(dim), shape(dim), input -> {
			// p(position) creates a producer that reads position[0]
			// Add 1 to position, then multiply by input
			// c(p(position)) wraps the producer into a CollectionProducer
			CollectionProducer positionVal = c(p(position));
			CollectionProducer multiplier = add(positionVal, c(1.0));
			return multiply(input, multiplier);
		}));

		log("Building and compiling model...");
		CompiledModel compiled = model.compile();

		// Create test input: all ones
		PackedCollection input = new PackedCollection(shape(dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 1.0);
		}

		log("Input: [1.0, 1.0, 1.0, 1.0]");
		log("\n--- Running Forward Passes ---");
		log("Test: output = input * (1 + position)");
		log("If position updates correctly:");
		log("  Step 0: position=0, mult=1, output=[1,1,1,1], sum=4");
		log("  Step 1: position=1, mult=2, output=[2,2,2,2], sum=8");
		log("  Step 2: position=2, mult=3, output=[3,3,3,3], sum=12");
		log("If position is stuck at 0, all sums would be 4\n");

		double[] outputSums = new double[5];

		for (int step = 0; step < 5; step++) {
			// Update position BEFORE forward pass
			position.setMem(0, (double) step);
			log(String.format("Step %d: position.toDouble(0) = %.1f", step, position.toDouble(0)));

			// Run forward pass
			PackedCollection output = compiled.forward(input);

			// Log output
			double sum = 0;
			StringBuilder sb = new StringBuilder("  Output: [");
			for (int i = 0; i < dim; i++) {
				double v = output.toDouble(i);
				sum += v;
				sb.append(String.format("%.2f", v));
				if (i < dim - 1) sb.append(", ");
			}
			sb.append("]");
			log(sb.toString());

			double expectedSum = dim * (1.0 + step);  // 4*(1+step)
			log(String.format("  Output sum: %.2f (expected: %.2f)", sum, expectedSum));

			outputSums[step] = sum;
		}

		// Verify results
		log("\n--- Analysis ---");
		boolean allIdentical = true;
		for (int i = 1; i < 5; i++) {
			if (Math.abs(outputSums[i] - outputSums[0]) > 0.001) {
				allIdentical = false;
				break;
			}
		}

		if (allIdentical) {
			log("[HYPOTHESIS 1 CONFIRMED] All outputs identical - position is NOT updating in compiled model");
			log("This would explain why multi-token generation fails!");
		} else {
			log("[HYPOTHESIS 1 REFUTED] Outputs differ - position IS updating correctly");
			for (int i = 0; i < 5; i++) {
				log(String.format("  Step %d sum: %.2f (expected: %.2f)", i, outputSums[i], dim * (1.0 + i)));
			}
		}

		log("\n=== Test Complete ===");
	}

	/**
	 * HYPOTHESIS 2: KV cache writes not persisting between forward passes.
	 * <p>
	 * Test: Create a model with a cache that is written to via into().
	 * After each forward pass, inspect the cache contents.
	 * <p>
	 * Expected if H2 is TRUE: Cache is empty/reset after each pass
	 * Expected if H2 is FALSE: Cache accumulates entries
	 */
	@Test
	public void testH2_CacheWritePersistence() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/h2_cache_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  H2: Cache Write Persistence Test");
		log("===================================================\n");

		// Create position placeholder
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		int seqLen = 10;
		int dim = 4;

		// Create a cache that will be written to
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();  // Zero-initialize

		log("Initial cache state (should be all zeros):");
		logCacheContents(cache, seqLen, dim, 3);

		// Build model: input -> write to cache at position -> pass through
		Model model = new Model(shape(dim));

		// Add a layer that writes input to cache at current position
		CellularLayer writeLayer = layer("cacheWrite", shape(dim), shape(dim),
				input -> input);  // Pass through

		// Use andThen to schedule the cache write
		writeLayer.andThen(into(cache, p(position)));

		model.add(writeLayer);

		log("\nBuilding and compiling model (inference-only mode)...");
		CompiledModel compiled = model.compile(false);  // false = no backpropagation

		log("\n--- Running Forward Passes ---");

		for (int step = 0; step < 5; step++) {
			// Update position
			position.setMem(0, (double) step);

			// Create input with distinctive values for this step
			PackedCollection input = new PackedCollection(shape(dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, (step + 1) * 10 + i);  // e.g., step 0: [10,11,12,13]
			}

			log(String.format("\nStep %d: position=%.0f, input=[%.0f,%.0f,%.0f,%.0f]",
					step, position.toDouble(0),
					input.toDouble(0), input.toDouble(1), input.toDouble(2), input.toDouble(3)));

			// Run forward pass (this should write to cache)
			compiled.forward(input);

			// Inspect cache contents AFTER forward pass
			log("Cache contents after step " + step + ":");
			logCacheContents(cache, seqLen, dim, step + 2);
		}

		// Verify results
		log("\n--- Analysis ---");
		boolean cachePopulated = false;
		for (int pos = 0; pos < 5; pos++) {
			double sum = 0;
			for (int i = 0; i < dim; i++) {
				sum += Math.abs(cache.toDouble(pos * dim + i));
			}
			if (sum > 0.001) {
				cachePopulated = true;
				break;
			}
		}

		if (!cachePopulated) {
			log("[HYPOTHESIS 2 CONFIRMED] Cache is empty - writes are NOT persisting");
		} else {
			log("[HYPOTHESIS 2 REFUTED] Cache has data - writes ARE persisting");
			// Check if writes are at correct positions
			boolean correctPositions = true;
			for (int pos = 0; pos < 5; pos++) {
				double expected = (pos + 1) * 10;  // First element of each row
				double actual = cache.toDouble(pos * dim);
				if (Math.abs(expected - actual) > 0.001) {
					log(String.format("  Position %d: expected %.0f, got %.2f", pos, expected, actual));
					correctPositions = false;
				}
			}
			if (correctPositions) {
				log("  All writes at correct positions!");
			}
		}

		log("\n=== Test Complete ===");
	}

	/**
	 * HYPOTHESIS 3: Cache write happens after cache read (ordering issue).
	 * <p>
	 * Test: Create a model that both reads from and writes to a cache.
	 * Verify the order of operations.
	 * <p>
	 * Expected if H3 is TRUE: Read sees stale data (previous step's write)
	 * Expected if H3 is FALSE: Read sees current step's write
	 */
	@Test
	public void testH3_CacheReadWriteOrder() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/h3_order_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  H3: Cache Read/Write Order Test");
		log("===================================================\n");

		// Create position placeholder
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		int seqLen = 10;
		int dim = 4;

		// Create a cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Build model that:
		// 1. Writes input to cache at position (side effect)
		// 2. Reads ALL cache entries and sums them
		// 3. Returns the sum

		// This tests whether we see data from ALL previous positions
		// (i.e., whether cache accumulates correctly)

		Model model = new Model(shape(dim));

		// First layer: write to cache AND pass through
		CellularLayer writeLayer = layer("write", shape(dim), shape(dim), input -> input);
		writeLayer.andThen(into(cache, p(position)));
		model.add(writeLayer);

		// Second layer: sum all cached values (up to current position)
		// For simplicity, sum entire cache (zeros for unused positions)
		model.add(layer("readSum", shape(dim), shape(dim), input -> {
			// Sum all entries in cache
			CollectionProducer cacheSum = c(cache).traverse(0).sum();
			// Return as dim-sized output: repeat produces (dim, 1), reshape to (dim)
			return cacheSum.reshape(shape(1)).repeat(dim).reshape(shape(dim));
		}));

		log("Building and compiling model (inference-only mode)...");
		CompiledModel compiled = model.compile(false);  // false = no backpropagation

		log("\n--- Running Forward Passes ---");
		log("Each step writes [step*10, step*10+1, step*10+2, step*10+3] to cache");
		log("Output should be cumulative sum of all cached values");

		double[] expectedCumulativeSum = new double[5];
		double runningSum = 0;

		for (int step = 0; step < 5; step++) {
			// Calculate expected cumulative sum
			for (int i = 0; i < dim; i++) {
				runningSum += step * 10 + i;
			}
			expectedCumulativeSum[step] = runningSum;

			// Update position
			position.setMem(0, (double) step);

			// Create input
			PackedCollection input = new PackedCollection(shape(dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, step * 10 + i);
			}

			// Run forward pass
			PackedCollection output = compiled.forward(input);

			double actualSum = output.toDouble(0);
			log(String.format("Step %d: expected sum=%.0f, actual=%.2f %s",
					step, expectedCumulativeSum[step], actualSum,
					Math.abs(expectedCumulativeSum[step] - actualSum) < 0.01 ? "[MATCH]" : "[MISMATCH]"));
		}

		log("\n--- Analysis ---");
		log("If cache read sees ONLY current step: sums would be small (not cumulative)");
		log("If cache read sees ALL steps: sums would be cumulative (correct behavior)");

		log("\n=== Test Complete ===");
	}

	/**
	 * HYPOTHESIS 4: GQA-specific interaction with caching.
	 * <p>
	 * Test: Compare behavior of attention with heads==kvHeads (no GQA)
	 * vs heads!=kvHeads (GQA) using the same cache mechanism.
	 * <p>
	 * Expected if H4 is TRUE: GQA produces identical outputs, non-GQA differs
	 * Expected if H4 is FALSE: Both behave similarly (both work or both fail)
	 */
	@Test
	public void testH4_GQAvsNonGQA() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/h4_gqa_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  H4: GQA vs Non-GQA Cache Behavior");
		log("===================================================\n");

		// This test uses the actual attention mechanism to compare
		// GQA (heads != kvHeads) vs standard MHA (heads == kvHeads)

		// Test parameters
		int seqLen = 10;
		int headSize = 8;

		log("--- Test 1: Standard MHA (heads = kvHeads = 4) ---");
		testAttentionCaching(4, 4, seqLen, headSize);

		log("\n--- Test 2: GQA (heads = 4, kvHeads = 2) ---");
		testAttentionCaching(4, 2, seqLen, headSize);

		log("\n=== Test Complete ===");
	}

	private void testAttentionCaching(int heads, int kvHeads, int seqLen, int headSize) {
		int dim = heads * headSize;
		int kvDim = kvHeads * headSize;

		log(String.format("Configuration: heads=%d, kvHeads=%d, dim=%d, kvDim=%d",
				heads, kvHeads, dim, kvDim));

		// Create position
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Create caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		keyCache.clear();
		valueCache.clear();

		// Create random weights for attention
		PackedCollection wq = randomWeights(dim, dim);
		PackedCollection wk = randomWeights(kvDim, dim);
		PackedCollection wv = randomWeights(kvDim, dim);
		PackedCollection wo = randomWeights(dim, dim);
		PackedCollection rmsWeight = onesWeights(dim);
		PackedCollection freqCis = computeFreqCis(seqLen, headSize);

		// Build attention block (without QK-Norm for simplicity)
		Model model = new Model(shape(1, dim));
		model.add(attention(heads, kvHeads, rmsWeight, wk, wv, wq, wo,
				null, null, null, null, null, freqCis, p(position)));

		log("Compiling attention model (inference-only mode)...");
		CompiledModel compiled = model.compile(false);  // false = no backpropagation

		// Run multiple forward passes
		log("Running forward passes...");
		double[] outputSums = new double[5];

		for (int step = 0; step < 5; step++) {
			position.setMem(0, (double) step);

			PackedCollection input = new PackedCollection(shape(1, dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, Math.sin(step * 0.5 + i * 0.1));  // Different input each step
			}

			PackedCollection output = compiled.forward(input);

			double sum = 0;
			for (int i = 0; i < dim; i++) {
				sum += output.toDouble(i);
			}
			outputSums[step] = sum;

			log(String.format("  Step %d: output sum = %.4f", step, sum));
		}

		// Check if outputs are identical
		boolean allIdentical = true;
		for (int i = 2; i < 5; i++) {
			if (Math.abs(outputSums[i] - outputSums[1]) > 0.0001) {
				allIdentical = false;
				break;
			}
		}

		if (allIdentical) {
			log("  [ISSUE] Steps 1-4 produce identical outputs!");
		} else {
			log("  [OK] Outputs differ across steps");
		}
	}

	// Helper methods

	private void logCacheContents(PackedCollection cache, int seqLen, int dim, int maxRows) {
		for (int row = 0; row < Math.min(seqLen, maxRows); row++) {
			StringBuilder sb = new StringBuilder(String.format("  Row %d: [", row));
			boolean allZero = true;
			for (int col = 0; col < dim; col++) {
				double v = cache.toDouble(row * dim + col);
				if (Math.abs(v) > 0.001) allZero = false;
				sb.append(String.format("%.1f", v));
				if (col < dim - 1) sb.append(", ");
			}
			sb.append("]");
			if (allZero) sb.append(" (zeros)");
			log(sb.toString());
		}
		if (maxRows < seqLen) {
			log("  ... (remaining rows)");
		}
	}

	private PackedCollection randomWeights(int rows, int cols) {
		PackedCollection w = new PackedCollection(shape(rows, cols));
		for (int i = 0; i < rows * cols; i++) {
			w.setMem(i, (Math.random() - 0.5) * 0.1);
		}
		return w;
	}

	private PackedCollection onesWeights(int size) {
		PackedCollection w = new PackedCollection(shape(size));
		for (int i = 0; i < size; i++) {
			w.setMem(i, 1.0);
		}
		return w;
	}

	private PackedCollection computeFreqCis(int seqLen, int headSize) {
		int freqDim = headSize / 2;
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));

		double theta = 10000.0;
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
				double angle = pos * freq;
				freqCis.setMem((pos * freqDim + i) * 2, Math.cos(angle));
				freqCis.setMem((pos * freqDim + i) * 2 + 1, Math.sin(angle));
			}
		}

		return freqCis;
	}
}
