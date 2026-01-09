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
			CollectionProducer cacheSum = cp(cache).traverse(0).sum();
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
	 * Test: Compile a model that reads from a PRE-FILLED cache.
	 * This isolates whether the issue is at compile time or runtime.
	 */
	@Test
	public void testPreFilledCacheRead() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/prefilled_cache_read.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Pre-Filled Cache Read Test");
		log("===================================================\n");

		int dim = 4;
		PackedCollection cache = new PackedCollection(shape(dim));

		// Fill cache BEFORE creating model
		cache.setMem(0, 1.0);
		cache.setMem(1, 2.0);
		cache.setMem(2, 3.0);
		cache.setMem(3, 4.0);
		log("Cache filled with [1,2,3,4] before model creation");
		log(String.format("Cache contents: [%.0f, %.0f, %.0f, %.0f]",
				cache.toDouble(0), cache.toDouble(1), cache.toDouble(2), cache.toDouble(3)));

		// Create model that reads cache and passes through input
		Model model = new Model(shape(dim));
		model.add(layer("readCache", shape(dim), shape(dim), input -> {
			// Return cache sum repeated dim times + input (to force input dependency)
			CollectionProducer cacheSum = cp(cache).sum();
			CollectionProducer repeated = cacheSum.repeat(dim).reshape(shape(dim));
			return add(repeated, input);  // input + [sum, sum, sum, sum]
		}));

		log("\nCompiling model...");
		CompiledModel compiled = model.compile(false);

		// Create test input
		PackedCollection input = new PackedCollection(shape(dim));
		input.setMem(0, 100.0);
		input.setMem(1, 100.0);
		input.setMem(2, 100.0);
		input.setMem(3, 100.0);

		log("Input: [100, 100, 100, 100]");
		log("Expected output: [110, 110, 110, 110] (100 + sum(1,2,3,4)=10)");

		PackedCollection output = compiled.forward(input);
		log(String.format("Actual output: [%.0f, %.0f, %.0f, %.0f]",
				output.toDouble(0), output.toDouble(1), output.toDouble(2), output.toDouble(3)));

		boolean success = Math.abs(output.toDouble(0) - 110.0) < 0.01;
		log(success ? "[PASS] Cache read correctly in compiled model" : "[FAIL] Cache not read correctly");

		// Test 2: Modify cache and run again
		log("\n--- Test 2: Modify cache after compilation ---");
		cache.setMem(0, 10.0);  // Change sum from 10 to 19
		log("Modified cache[0] to 10.0, new sum should be 19");
		log("Expected output: [119, 119, 119, 119]");

		output = compiled.forward(input);
		log(String.format("Actual output: [%.0f, %.0f, %.0f, %.0f]",
				output.toDouble(0), output.toDouble(1), output.toDouble(2), output.toDouble(3)));

		success = Math.abs(output.toDouble(0) - 119.0) < 0.01;
		log(success ? "[PASS] Dynamic cache update detected" : "[FAIL] Cache update not detected");

		// Test 3: Layer that IGNORES input (like H3)
		log("\n--- Test 3: Layer that ignores input ---");
		cache.setMem(0, 1.0);
		cache.setMem(1, 2.0);
		cache.setMem(2, 3.0);
		cache.setMem(3, 4.0);
		log("Reset cache to [1,2,3,4]");

		Model model2 = new Model(shape(dim));
		model2.add(layer("readCacheOnly", shape(dim), shape(dim), ignoredInput -> {
			// IGNORES input, returns only cache sum repeated
			CollectionProducer cacheSum = cp(cache).sum();
			return cacheSum.repeat(dim).reshape(shape(dim));
		}));

		log("Compiling model2 (ignores input)...");
		CompiledModel compiled2 = model2.compile(false);

		log("Expected output: [10, 10, 10, 10] (sum=10 repeated)");
		output = compiled2.forward(input);
		log(String.format("Actual output: [%.0f, %.0f, %.0f, %.0f]",
				output.toDouble(0), output.toDouble(1), output.toDouble(2), output.toDouble(3)));

		success = Math.abs(output.toDouble(0) - 10.0) < 0.01;
		log(success ? "[PASS] Cache read works even when input ignored" : "[FAIL] Cache not read when input ignored");

		// Test 4: Modify cache after compilation (input-ignoring model)
		log("\n--- Test 4: Modify cache (input-ignoring model) ---");
		cache.setMem(0, 100.0);
		log("Modified cache[0] to 100.0, new sum should be 109");
		log("Expected output: [109, 109, 109, 109]");

		output = compiled2.forward(input);
		log(String.format("Actual output: [%.0f, %.0f, %.0f, %.0f]",
				output.toDouble(0), output.toDouble(1), output.toDouble(2), output.toDouble(3)));

		success = Math.abs(output.toDouble(0) - 109.0) < 0.01;
		log(success ? "[PASS] Dynamic update works in input-ignoring model" : "[FAIL] Dynamic update fails in input-ignoring model");

		// Test 5: Two-layer model like H3 (write + read)
		log("\n--- Test 5: Two-layer model (write then read) ---");
		PackedCollection cache2 = new PackedCollection(shape(dim));
		cache2.clear();
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);
		log("Created fresh cache (all zeros)");

		Model model3 = new Model(shape(dim));

		// Layer 1: pass through and write to cache
		CellularLayer writeLayer = layer("write", shape(dim), shape(dim), x -> x);
		model3.add(writeLayer);

		// Log receptor state after add
		log("After add(writeLayer): writeLayer.getForward().getReceptor() = " +
			(writeLayer.getForward().getReceptor() == null ? "null" : writeLayer.getForward().getReceptor().getClass().getSimpleName()));

		// Call andThen AFTER add to ensure receptor chaining works
		writeLayer.andThen(into(cache2));  // No position, just write to start

		// Log receptor state after andThen
		log("After andThen(into): writeLayer.getForward().getReceptor() = " +
			(writeLayer.getForward().getReceptor() == null ? "null" : writeLayer.getForward().getReceptor().getClass().getSimpleName()));

		// Layer 2: read from cache and return sum
		CellularLayer readLayer = layer("read", shape(dim), shape(dim), ignoredInput -> {
			CollectionProducer cacheSum = cp(cache2).sum();
			return cacheSum.repeat(dim).reshape(shape(dim));
		});
		model3.add(readLayer);

		// Log receptor state after second add
		log("After add(readLayer): writeLayer.getForward().getReceptor() = " +
			(writeLayer.getForward().getReceptor() == null ? "null" : writeLayer.getForward().getReceptor().getClass().getSimpleName()));
		log("After add(readLayer): readLayer.getForward().getReceptor() = " +
			(readLayer.getForward().getReceptor() == null ? "null" : readLayer.getForward().getReceptor().getClass().getSimpleName()));

		log("Compiling two-layer model...");
		CompiledModel compiled3 = model3.compile(false);

		input.setMem(0, 1.0);
		input.setMem(1, 2.0);
		input.setMem(2, 3.0);
		input.setMem(3, 4.0);
		log("Input: [1, 2, 3, 4]");
		log("Expected: write layer writes [1,2,3,4] to cache, read layer sums to 10");
		log("Expected output: [10, 10, 10, 10]");

		output = compiled3.forward(input);
		log(String.format("Actual output: [%.0f, %.0f, %.0f, %.0f]",
				output.toDouble(0), output.toDouble(1), output.toDouble(2), output.toDouble(3)));
		log(String.format("Cache after forward: [%.0f, %.0f, %.0f, %.0f]",
				cache2.toDouble(0), cache2.toDouble(1), cache2.toDouble(2), cache2.toDouble(3)));

		success = Math.abs(output.toDouble(0) - 10.0) < 0.01;
		log(success ? "[PASS] Two-layer model works" : "[FAIL] Two-layer model fails (cache read returns wrong value)");

		// Test 6: Single layer with andThen (no second layer)
		log("\n--- Test 6: Single layer with andThen (no second layer) ---");
		PackedCollection cache3 = new PackedCollection(shape(dim));
		cache3.clear();
		log("Created fresh cache3 (all zeros)");

		Model model4 = new Model(shape(dim));
		CellularLayer passLayer = layer("pass", shape(dim), shape(dim), x -> x);
		model4.add(passLayer);
		passLayer.andThen(into(cache3));

		log("Compiling single-layer model...");
		CompiledModel compiled4 = model4.compile(false);

		input.setMem(0, 1.0);
		input.setMem(1, 2.0);
		input.setMem(2, 3.0);
		input.setMem(3, 4.0);
		log("Input: [1, 2, 3, 4]");
		log("Expected: cache3 should have [1, 2, 3, 4] after forward");

		output = compiled4.forward(input);
		log(String.format("Cache3 after forward: [%.0f, %.0f, %.0f, %.0f]",
				cache3.toDouble(0), cache3.toDouble(1), cache3.toDouble(2), cache3.toDouble(3)));

		success = Math.abs(cache3.toDouble(0) - 1.0) < 0.01;
		log(success ? "[PASS] Single layer andThen works" : "[FAIL] Single layer andThen fails");

		log("\n=== Test Complete ===");
	}

	/**
	 * Direct test of cp(cache).sum() without going through a model.
	 * This isolates whether the issue is in cp() or in how models/layers use it.
	 */
	@Test
	public void testDirectCacheRead() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/direct_cache_read.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Direct Cache Read Test (cp vs c)");
		log("===================================================\n");

		int dim = 4;
		PackedCollection cache = new PackedCollection(shape(dim));
		cache.clear();

		// Test 1: Read empty cache
		log("Test 1: Reading empty cache");
		CollectionProducer sumProducer = cp(cache).sum();
		PackedCollection result = sumProducer.get().evaluate();
		log(String.format("  Empty cache sum: %.2f (expected: 0.0)", result.toDouble(0)));

		// Test 2: Fill cache and read again (same producer)
		log("\nTest 2: Fill cache with [1,2,3,4] and read with SAME producer");
		cache.setMem(0, 1.0);
		cache.setMem(1, 2.0);
		cache.setMem(2, 3.0);
		cache.setMem(3, 4.0);
		result = sumProducer.get().evaluate();
		log(String.format("  Filled cache sum: %.2f (expected: 10.0)", result.toDouble(0)));

		// Test 3: Create NEW producer after filling
		log("\nTest 3: Create NEW producer after cache is filled");
		CollectionProducer newSumProducer = cp(cache).sum();
		result = newSumProducer.get().evaluate();
		log(String.format("  New producer sum: %.2f (expected: 10.0)", result.toDouble(0)));

		// Test 4: Try with c() instead of cp() for comparison
		log("\nTest 4: Using c() instead of cp() (should capture value at creation time)");
		cache.setMem(0, 100.0);  // Change first value
		CollectionProducer cProducer = c(cache).sum();
		result = cProducer.get().evaluate();
		log(String.format("  c(cache).sum(): %.2f", result.toDouble(0)));
		log("  If this is 10.0, c() captured old value. If 109.0, it read current value.");

		// Test 5: Read actual cache contents directly
		log("\nTest 5: Direct cache values (verification)");
		log(String.format("  cache[0..3]: %.2f, %.2f, %.2f, %.2f",
				cache.toDouble(0), cache.toDouble(1), cache.toDouble(2), cache.toDouble(3)));
		double manualSum = cache.toDouble(0) + cache.toDouble(1) + cache.toDouble(2) + cache.toDouble(3);
		log(String.format("  Manual sum: %.2f", manualSum));

		// Test 6: Test compiled kernel evaluation
		log("\nTest 6: Compiled kernel evaluation");
		cache.setMem(0, 1.0);
		cache.setMem(1, 2.0);
		cache.setMem(2, 3.0);
		cache.setMem(3, 4.0);
		log(String.format("  Reset cache to [1,2,3,4]"));

		// Create and compile a producer
		CollectionProducer compiledProducer = cp(cache).sum();
		io.almostrealism.relation.Evaluable<PackedCollection> evaluable = compiledProducer.get();
		log(String.format("  Evaluable type: %s", evaluable.getClass().getSimpleName()));

		// First evaluation
		result = evaluable.evaluate();
		log(String.format("  First evaluation: %.2f (expected: 10.0)", result.toDouble(0)));

		// Modify cache
		cache.setMem(0, 100.0);
		log(String.format("  Modified cache[0] to 100.0"));

		// Second evaluation with same evaluable
		result = evaluable.evaluate();
		log(String.format("  Second evaluation: %.2f (expected: 109.0 if dynamic, 10.0 if static)", result.toDouble(0)));

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

	/**
	 * Direct test of CollectionReceptor without any model compilation.
	 * This is the most narrow test to verify CollectionReceptor works in isolation.
	 *
	 * Tests:
	 * 1. CollectionReceptor.push() returns a valid Supplier
	 * 2. Calling .get() on the Supplier returns a Runnable
	 * 3. Running the Runnable writes data to the destination
	 * 4. Position-based writes work correctly
	 */
	@Test
	public void testCollectionReceptor_DirectInvocation() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/receptor_direct_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  CollectionReceptor Direct Invocation Test");
		log("===================================================\n");

		int seqLen = 10;
		int dim = 4;

		// Create destination cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Create position producer
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Create CollectionReceptor directly
		org.almostrealism.graph.CollectionReceptor receptor =
				new org.almostrealism.graph.CollectionReceptor(cache, p(position));

		log("Cache before any writes:");
		logCacheContents(cache, seqLen, dim, 3);

		log("\n--- Test 1: Direct push() invocation ---");

		for (int step = 0; step < 5; step++) {
			// Update position
			position.setMem(0, (double) step);

			// Create input data
			PackedCollection input = new PackedCollection(shape(dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, (step + 1) * 10 + i);  // e.g., [10,11,12,13]
			}

			log(String.format("\nStep %d: position=%.0f, input=[%.0f,%.0f,%.0f,%.0f]",
					step, position.toDouble(0),
					input.toDouble(0), input.toDouble(1), input.toDouble(2), input.toDouble(3)));

			// Create a producer that returns the input
			io.almostrealism.relation.Producer<PackedCollection> inputProducer = p(input);

			// Call push() - this returns Supplier<Runnable>
			java.util.function.Supplier<Runnable> supplier = receptor.push(inputProducer);
			log("  push() returned: " + supplier.getClass().getSimpleName());

			// Get the Runnable
			Runnable runnable = supplier.get();
			log("  get() returned: " + runnable.getClass().getSimpleName());

			// Execute the Runnable
			runnable.run();
			log("  run() executed");

			// Check cache contents
			log("  Cache contents after step " + step + ":");
			for (int row = 0; row <= step; row++) {
				StringBuilder sb = new StringBuilder(String.format("    Row %d: [", row));
				for (int col = 0; col < dim; col++) {
					double v = cache.toDouble(row * dim + col);
					sb.append(String.format("%.0f", v));
					if (col < dim - 1) sb.append(", ");
				}
				sb.append("]");
				double expected = (row + 1) * 10;
				double actual = cache.toDouble(row * dim);
				if (Math.abs(expected - actual) < 0.01) {
					sb.append(" [OK]");
				} else {
					sb.append(" [EXPECTED ").append(String.format("%.0f", expected)).append("]");
				}
				log(sb.toString());
			}
		}

		// Final verification
		log("\n--- Verification ---");
		boolean success = true;
		for (int row = 0; row < 5; row++) {
			double expected = (row + 1) * 10;
			double actual = cache.toDouble(row * dim);
			if (Math.abs(expected - actual) > 0.01) {
				log(String.format("[FAIL] Row %d: expected %.0f, got %.2f", row, expected, actual));
				success = false;
			}
		}

		if (success) {
			log("[SUCCESS] CollectionReceptor works correctly when invoked directly!");
			log("This confirms the receptor's push/get/run chain is functional.");
		} else {
			log("[FAILURE] CollectionReceptor failed even with direct invocation.");
		}

		log("\n=== Test Complete ===");
	}

	/**
	 * Test CollectionReceptor via Block.andThen() but WITHOUT model compilation.
	 * This tests whether setReceptor() properly wires up the receptor.
	 */
	@Test
	public void testCollectionReceptor_ViaBlockAndThen() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/receptor_block_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  CollectionReceptor via Block.andThen() Test");
		log("===================================================\n");

		int seqLen = 10;
		int dim = 4;

		// Create destination cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Create position producer
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Create a simple Block using SequentialBlock
		org.almostrealism.model.SequentialBlock block =
				new org.almostrealism.model.SequentialBlock(shape(dim));

		// Add a pass-through layer
		block.add(layer("passthrough", shape(dim), shape(dim), input -> input));

		// Add receptor via andThen
		block.andThen(into(cache, p(position)));

		log("Block created with andThen(CollectionReceptor)");
		log("Cache before any operations:");
		logCacheContents(cache, seqLen, dim, 3);

		log("\n--- Running forward passes (uncompiled) ---");

		for (int step = 0; step < 5; step++) {
			// Update position
			position.setMem(0, (double) step);

			// Create input data
			PackedCollection input = new PackedCollection(shape(dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, (step + 1) * 10 + i);
			}

			log(String.format("\nStep %d: position=%.0f, input=[%.0f,%.0f,%.0f,%.0f]",
					step, position.toDouble(0),
					input.toDouble(0), input.toDouble(1), input.toDouble(2), input.toDouble(3)));

			// Call forward() directly (not compiled!)
			Runnable op = block.forward(input);
			log("  forward() returned: " + op.getClass().getSimpleName());

			// Execute
			op.run();
			log("  run() executed");

			// Check cache
			log("  Cache after step " + step + ":");
			for (int row = 0; row <= step; row++) {
				StringBuilder sb = new StringBuilder(String.format("    Row %d: [", row));
				for (int col = 0; col < dim; col++) {
					sb.append(String.format("%.0f", cache.toDouble(row * dim + col)));
					if (col < dim - 1) sb.append(", ");
				}
				sb.append("]");
				log(sb.toString());
			}
		}

		// Verify
		log("\n--- Verification ---");
		boolean success = true;
		for (int row = 0; row < 5; row++) {
			double expected = (row + 1) * 10;
			double actual = cache.toDouble(row * dim);
			if (Math.abs(expected - actual) > 0.01) {
				log(String.format("[FAIL] Row %d: expected %.0f, got %.2f", row, expected, actual));
				success = false;
			}
		}

		if (success) {
			log("[SUCCESS] CollectionReceptor works via Block.andThen() without compilation!");
		} else {
			log("[FAILURE] CollectionReceptor fails even without compilation.");
			log("This suggests the issue is in how Block.andThen() wires up the receptor.");
		}

		log("\n=== Test Complete ===");
	}

	/**
	 * Test Cell.of() with an explicit receptor (like attention uses internally).
	 * This tests whether Cell-based operations work differently.
	 */
	@Test
	public void testCollectionReceptor_ViaCellOf() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/receptor_cell_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  CollectionReceptor via Cell.of() Test");
		log("===================================================\n");

		int seqLen = 10;
		int dim = 4;

		// Create destination cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Create position producer
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Create a Cell that passes through and has a receptor
		org.almostrealism.graph.CollectionReceptor receptor =
				new org.almostrealism.graph.CollectionReceptor(cache, p(position));

		Cell<PackedCollection> cell = Cell.of((input, next) -> {
			org.almostrealism.hardware.OperationList ops = new org.almostrealism.hardware.OperationList();
			// Add the receptor write
			ops.add(receptor.push(input));
			// Continue to next (if any)
			if (next != null) {
				ops.add(next.push(input));
			}
			return ops;
		});

		log("Cell created with CollectionReceptor in OperationList");
		log("Cache before any operations:");
		logCacheContents(cache, seqLen, dim, 3);

		log("\n--- Running cell.push() operations ---");

		for (int step = 0; step < 5; step++) {
			// Update position
			position.setMem(0, (double) step);

			// Create input data
			PackedCollection input = new PackedCollection(shape(dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, (step + 1) * 10 + i);
			}

			log(String.format("\nStep %d: position=%.0f, input=[%.0f,%.0f,%.0f,%.0f]",
					step, position.toDouble(0),
					input.toDouble(0), input.toDouble(1), input.toDouble(2), input.toDouble(3)));

			// Push to cell
			java.util.function.Supplier<Runnable> supplier = cell.push(p(input));
			Runnable op = supplier.get();
			op.run();
			log("  cell.push() -> get() -> run() completed");

			// Check cache
			log("  Cache after step " + step + ":");
			for (int row = 0; row <= step; row++) {
				StringBuilder sb = new StringBuilder(String.format("    Row %d: [", row));
				for (int col = 0; col < dim; col++) {
					sb.append(String.format("%.0f", cache.toDouble(row * dim + col)));
					if (col < dim - 1) sb.append(", ");
				}
				sb.append("]");
				log(sb.toString());
			}
		}

		// Verify
		log("\n--- Verification ---");
		boolean success = true;
		for (int row = 0; row < 5; row++) {
			double expected = (row + 1) * 10;
			double actual = cache.toDouble(row * dim);
			if (Math.abs(expected - actual) > 0.01) {
				log(String.format("[FAIL] Row %d: expected %.0f, got %.2f", row, expected, actual));
				success = false;
			}
		}

		if (success) {
			log("[SUCCESS] CollectionReceptor works via Cell.of() with OperationList!");
			log("This is the pattern used in AttentionFeatures.attention()");
		} else {
			log("[FAILURE] CollectionReceptor fails via Cell.of().");
		}

		log("\n=== Test Complete ===");
	}

	/**
	 * HYPOTHESIS 5: Split operation with branches does not route data correctly.
	 *
	 * Test: Create a simple split like qkvSplitOperation but with extensive logging
	 * to understand exactly where data flows.
	 */
	@Test
	public void testH5_SplitBranching() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/h5_split_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  H5: Split/Branch Data Flow Test");
		log("===================================================\n");

		int batchSize = 1;
		int seqLen = 4;
		int embedDim = 16;

		// Create input with sequential values for easy verification
		PackedCollection input = new PackedCollection(shape(batchSize, seqLen, embedDim * 3));
		for (int b = 0; b < batchSize; b++) {
			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < embedDim * 3; d++) {
					int idx = b * seqLen * embedDim * 3 + s * embedDim * 3 + d;
					input.setMem(idx, (double) idx);
				}
			}
		}
		log("Input shape: " + input.getShape());
		log("Input[0,0,0..2]: " + input.toDouble(0) + ", " + input.toDouble(1) + ", " + input.toDouble(2));
		log("Input[0,0,16..18]: " + input.toDouble(16) + ", " + input.toDouble(17) + ", " + input.toDouble(18));

		// Create model
		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(batchSize, seqLen, 3 * embedDim));
		org.almostrealism.model.SequentialBlock main = model.sequential();

		log("\nModel created with shape: " + model.getInputShape());

		// Reshape to (1, 4, 3, 16)
		main.reshape(batchSize, seqLen, 3, embedDim);
		log("After reshape: " + main.getOutputShape());

		// Split into 3 parts
		java.util.List<org.almostrealism.model.Block> qkv = main.split(shape(batchSize, seqLen, 1, embedDim), 0);
		log("Split into " + qkv.size() + " parts");

		// Get Q, K, V blocks
		org.almostrealism.model.Block qBlock = qkv.get(0);
		org.almostrealism.model.Block kBlock = qkv.get(1);
		org.almostrealism.model.Block vBlock = qkv.get(2);

		log("Q block input shape: " + qBlock.getInputShape());
		log("Q block output shape: " + qBlock.getOutputShape());
		log("K block output shape: " + kBlock.getOutputShape());
		log("V block output shape: " + vBlock.getOutputShape());

		// Reshape each
		org.almostrealism.model.Block qReshaped = qBlock.reshape(batchSize, seqLen, embedDim);
		org.almostrealism.model.Block kReshaped = kBlock.reshape(batchSize, seqLen, embedDim);
		org.almostrealism.model.Block vReshaped = vBlock.reshape(batchSize, seqLen, embedDim);

		log("After reshape - Q output: " + qReshaped.getOutputShape());

		// Create output collections
		PackedCollection qOut = new PackedCollection(shape(batchSize, seqLen, embedDim));
		PackedCollection kOut = new PackedCollection(shape(batchSize, seqLen, embedDim));
		PackedCollection vOut = new PackedCollection(shape(batchSize, seqLen, embedDim));

		// Set receptors - but NOW we cast back to SequentialBlock for andThen
		// Note: reshape returns 'this' for SequentialBlock due to override
		log("\nSetting up receptors...");
		((org.almostrealism.model.SequentialBlock) qReshaped).andThen(into(qOut));
		((org.almostrealism.model.SequentialBlock) kReshaped).andThen(into(kOut));
		((org.almostrealism.model.SequentialBlock) vReshaped).andThen(into(vOut));

		// Compile model
		log("Compiling model (inference only)...");
		CompiledModel compiled = model.compile(false);

		// Run forward pass
		log("\nRunning forward pass...");
		compiled.forward(input);

		// Check outputs
		log("\n--- Output Verification ---");
		boolean qMatch = true, kMatch = true, vMatch = true;

		for (int b = 0; b < batchSize; b++) {
			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < embedDim; d++) {
					int baseIdx = b * seqLen * embedDim * 3 + s * embedDim * 3;

					double expectedQ = baseIdx + d;
					double expectedK = baseIdx + embedDim + d;
					double expectedV = baseIdx + 2 * embedDim + d;

					double actualQ = qOut.valueAt(b, s, d);
					double actualK = kOut.valueAt(b, s, d);
					double actualV = vOut.valueAt(b, s, d);

					if (Math.abs(expectedQ - actualQ) > 0.01) {
						if (qMatch) {
							log(String.format("Q MISMATCH at [%d,%d,%d]: expected %.0f, got %.2f",
									b, s, d, expectedQ, actualQ));
						}
						qMatch = false;
					}
					if (Math.abs(expectedK - actualK) > 0.01) {
						if (kMatch) {
							log(String.format("K MISMATCH at [%d,%d,%d]: expected %.0f, got %.2f",
									b, s, d, expectedK, actualK));
						}
						kMatch = false;
					}
					if (Math.abs(expectedV - actualV) > 0.01) {
						if (vMatch) {
							log(String.format("V MISMATCH at [%d,%d,%d]: expected %.0f, got %.2f",
									b, s, d, expectedV, actualV));
						}
						vMatch = false;
					}
				}
			}
		}

		log("\n--- Summary ---");
		log("Q values: " + (qMatch ? "[PASS]" : "[FAIL]"));
		log("K values: " + (kMatch ? "[PASS]" : "[FAIL]"));
		log("V values: " + (vMatch ? "[PASS]" : "[FAIL]"));

		// Additional diagnostics - print first few values
		log("\n--- Sample Values ---");
		log(String.format("qOut[0,0,0..2]: %.2f, %.2f, %.2f",
				qOut.valueAt(0,0,0), qOut.valueAt(0,0,1), qOut.valueAt(0,0,2)));
		log(String.format("kOut[0,0,0..2]: %.2f, %.2f, %.2f",
				kOut.valueAt(0,0,0), kOut.valueAt(0,0,1), kOut.valueAt(0,0,2)));
		log(String.format("vOut[0,0,0..2]: %.2f, %.2f, %.2f",
				vOut.valueAt(0,0,0), vOut.valueAt(0,0,1), vOut.valueAt(0,0,2)));

		log("\n=== Test Complete ===");
	}

	/**
	 * HYPOTHESIS 6: Simple subset test without split complexity.
	 *
	 * Test: Create a model with just reshape + subset + output receptor
	 * to verify subset works correctly in isolation.
	 */
	@Test
	public void testH6_SimpleSubset() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/h6_subset_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  H6: Simple Subset Test");
		log("===================================================\n");

		// Simple test: input (1,4,12) -> reshape (1,4,3,4) -> subset (1,4,1,4) at [0,0,0,0]

		int batchSize = 1;
		int seqLen = 4;
		int dim = 4;
		int parts = 3;

		// Create input with sequential values
		PackedCollection input = new PackedCollection(shape(batchSize, seqLen, dim * parts));
		for (int i = 0; i < batchSize * seqLen * dim * parts; i++) {
			input.setMem(i, i);
		}
		log("Input shape: " + input.getShape());
		log("Input[0..11]: " + input.toDouble(0) + ", " + input.toDouble(1) + ", ..., " + input.toDouble(11));

		// Create model with reshape + subset
		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(batchSize, seqLen, dim * parts));
		org.almostrealism.model.SequentialBlock main = model.sequential();

		// Reshape to (1,4,3,4)
		main.reshape(batchSize, seqLen, parts, dim);
		log("After reshape: " + main.getOutputShape());

		// Add subset layer to extract first part at [0,0,0,0]
		main.add(subset(shape(batchSize, seqLen, parts, dim), shape(batchSize, seqLen, 1, dim), 0, 0, 0, 0));
		log("After subset: " + main.getOutputShape());

		// Reshape to (1,4,4) to flatten the third dimension
		main.reshape(batchSize, seqLen, dim);
		log("After final reshape: " + main.getOutputShape());

		// Create output collection
		PackedCollection outData = new PackedCollection(shape(batchSize, seqLen, dim));

		// Set receptor
		log("\nSetting up receptor...");
		main.andThen(into(outData));

		// Compile model
		log("Compiling model (inference only)...");
		CompiledModel compiled = model.compile(false);

		// Run forward pass
		log("\nRunning forward pass...");
		compiled.forward(input);

		// Check outputs
		log("\n--- Output Verification ---");
		boolean match = true;
		for (int b = 0; b < batchSize; b++) {
			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < dim; d++) {
					// Expected: the first 4 values of each seq position (before reshape)
					// Input layout after reshape (1,4,3,4):
					// [0,s,0,d] = s*12 + d (first part)
					// [0,s,1,d] = s*12 + 4 + d (second part)
					// [0,s,2,d] = s*12 + 8 + d (third part)
					double expected = s * 12 + d;  // First part values
					double actual = outData.valueAt(b, s, d);

					if (Math.abs(expected - actual) > 0.01) {
						if (match) {
							log(String.format("MISMATCH at [%d,%d,%d]: expected %.0f, got %.2f",
									b, s, d, expected, actual));
						}
						match = false;
					}
				}
			}
		}

		log("\n--- Summary ---");
		log("Subset extraction: " + (match ? "[PASS]" : "[FAIL]"));
		log("\n--- Sample Values ---");
		log(String.format("outData[0,0,0..3]: %.2f, %.2f, %.2f, %.2f",
				outData.valueAt(0,0,0), outData.valueAt(0,0,1),
				outData.valueAt(0,0,2), outData.valueAt(0,0,3)));
		log(String.format("Expected: 0, 1, 2, 3"));
		log(String.format("outData[0,1,0..3]: %.2f, %.2f, %.2f, %.2f",
				outData.valueAt(0,1,0), outData.valueAt(0,1,1),
				outData.valueAt(0,1,2), outData.valueAt(0,1,3)));
		log(String.format("Expected: 12, 13, 14, 15"));

		log("\n=== Test Complete ===");
	}
}
