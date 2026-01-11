package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Directly inspect cache contents to verify writes happen at correct positions.
 */
public class DirectCacheInspectionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	/**
	 * Test that cache writes happen at the correct position using into().
	 */
	@Test
	public void testCacheWritesAtCorrectPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/direct_cache_inspection.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Direct Cache Inspection Test ===");

		int dim = 8;
		int seqLen = 10;

		// Create cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Simple model: just write input to cache at position
		var inputShape = shape(dim);

		Model model = new Model(inputShape);
		SequentialBlock main = new SequentialBlock(inputShape);
		main.add(layer("identity", inputShape, inputShape, input -> input));
		main.andThen(into(cache, dynamicPosition));
		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		log("\nWriting different values at each position...");

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);

			// Create input with position-specific values
			PackedCollection input = new PackedCollection(inputShape);
			for (int i = 0; i < dim; i++) {
				input.setMem(i, pos + 0.1 * i);  // e.g., pos=2: [2.0, 2.1, 2.2, ...]
			}

			compiled.forward(input);
			log("Wrote at position " + pos + ": input[0]=" + input.toDouble(0));
		}

		log("\nInspecting cache contents:");
		for (int pos = 0; pos < 5; pos++) {
			double val0 = cache.toDouble(pos * dim);
			double val1 = cache.toDouble(pos * dim + 1);
			log(String.format("  Cache[%d]: [%.1f, %.1f, ...]", pos, val0, val1));

			// Verify the value matches what we wrote
			double expected = pos;
			Assert.assertEquals("Cache position " + pos + " should have correct value",
				expected, val0, 0.01);
		}

		// Verify positions 5-9 are still zero (we didn't write to them)
		log("\nVerifying unwritten positions are zero:");
		for (int pos = 5; pos < seqLen; pos++) {
			double val = cache.toDouble(pos * dim);
			log(String.format("  Cache[%d]: %.1f", pos, val));
			Assert.assertEquals("Unwritten position should be zero", 0.0, val, 0.01);
		}

		log("\n[PASS] Cache writes happen at correct positions!");
	}

	/**
	 * Test that reading from cache after multiple writes sees accumulated state.
	 */
	@Test
	public void testCacheAccumulation() throws Exception {
		log("\n=== Cache Accumulation Test ===");

		int dim = 8;
		int seqLen = 10;
		int heads = 2;
		int headSize = dim / heads;

		// Create caches (same shape as in attention)
		PackedCollection keyCache = new PackedCollection(shape(seqLen, heads, headSize));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, heads, headSize));
		keyCache.clear();
		valueCache.clear();

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Model that writes keys and reads them
		var inputShape = shape(dim);
		var kvDim = heads * headSize;
		var headShape = shape(heads, headSize);

		Model model = new Model(inputShape);
		SequentialBlock main = new SequentialBlock(inputShape);

		// Write to key cache
		main.add(reshape(inputShape, headShape));
		main.andThen(into(keyCache.reshape(shape(seqLen, kvDim)), dynamicPosition));

		// Main path: just output the input
		main.add(reshape(headShape, inputShape));

		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		log("\nWriting at positions 0, 1, 2...");
		for (int pos = 0; pos < 3; pos++) {
			position.setMem(0, (double) pos);

			// Different input at each position
			PackedCollection input = new PackedCollection(inputShape);
			for (int i = 0; i < dim; i++) {
				input.setMem(i, pos * 10 + i);  // 0,1,2... then 10,11,12... then 20,21,22...
			}

			compiled.forward(input);
		}

		log("\nInspecting key cache:");
		for (int pos = 0; pos < 5; pos++) {
			int offset = pos * kvDim;
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("  KeyCache[%d]: [", pos));
			for (int i = 0; i < Math.min(4, kvDim); i++) {
				if (i > 0) sb.append(", ");
				sb.append(String.format("%.0f", keyCache.toDouble(offset + i)));
			}
			sb.append(", ...]");
			log(sb.toString());
		}

		// Verify positions 0, 1, 2 have correct values
		Assert.assertEquals(0.0, keyCache.toDouble(0), 0.01);  // Position 0 first element
		Assert.assertEquals(10.0, keyCache.toDouble(kvDim), 0.01);  // Position 1 first element
		Assert.assertEquals(20.0, keyCache.toDouble(2 * kvDim), 0.01);  // Position 2 first element

		// Verify position 3 and beyond are still zero
		Assert.assertEquals(0.0, keyCache.toDouble(3 * kvDim), 0.01);

		log("\n[PASS] Cache accumulates values correctly!");
	}

	/**
	 * Test full attention with different inputs at each position -
	 * simulating real autoregressive generation.
	 */
	@Test
	public void testAutoRegressiveStyle() throws Exception {
		log("\n=== Autoregressive-Style Test ===");

		int dim = 8;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;
		int seqLen = 10;

		PackedCollection rmsAttWeight = ones(dim);
		PackedCollection wq = createIdentity(dim, dim);
		PackedCollection wk = createIdentity(dim, dim);
		PackedCollection wv = createIdentity(dim, dim);
		PackedCollection wo = createIdentity(dim, dim);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Build attention block
		var block = attention(heads, kvHeads,
			rmsAttWeight, wk, wv, wq, wo,
			null, null, null, null, null,
			freqCis, dynamicPosition, 1e-6);

		Model model = new Model(shape(1, dim));
		model.add(block);
		CompiledModel compiled = model.compile();

		log("Simulating autoregressive generation with different inputs...");

		double[][] outputs = new double[5][dim];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);

			// Different input at each position (simulating different token embeddings)
			PackedCollection input = new PackedCollection(shape(1, dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, Math.sin(pos + i * 0.5) + 1.0);  // Varying values
			}

			PackedCollection result = compiled.forward(input);
			for (int i = 0; i < dim; i++) {
				outputs[pos][i] = result.toDouble(i);
			}
			log("Position " + pos + ": " + formatArray(result, 4));
		}

		// Check that outputs differ
		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < dim; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) allSame = false;
			}
			log(String.format("Pos 0 vs %d: max diff = %.6f", pos, maxDiff));
		}

		if (allSame) {
			log("[FAIL] Different inputs should produce different outputs!");
		} else {
			log("[PASS] Autoregressive-style generation produces different outputs!");
		}

		Assert.assertFalse("Autoregressive generation should produce different outputs", allSame);
	}

	private PackedCollection ones(int size) {
		PackedCollection c = new PackedCollection(size);
		for (int i = 0; i < size; i++) c.setMem(i, 1.0);
		return c;
	}

	private PackedCollection createIdentity(int rows, int cols) {
		PackedCollection c = new PackedCollection(shape(rows, cols));
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < cols; j++) {
				c.setMem(i * cols + j, (i == j) ? 0.5 : 0.01);
			}
		}
		return c;
	}

	private PackedCollection createFreqCis(int seqLen, int headSize) {
		double theta = 1000000.0;
		int freqDim = headSize / 2;
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
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

	private String formatArray(PackedCollection c, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(count, c.getShape().getTotalSize()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append(", ...]");
		return sb.toString();
	}
}
