package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test that isolates the value cache write behavior from KVCacheAccumulationTest.
 * Strips out attentionKeys/attentionValues to see if the branches are working.
 */
public class ValueCacheIsolationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testValueCacheWriteOnly() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/value_cache_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  Value Cache Isolation Test");
		log("=".repeat(70) + "\n");

		// EXACT parameters from KVCacheAccumulationTest
		int dim = 16;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;  // 8
		int seqLen = 10;
		int kvDim = dim;

		// Create EXTERNAL caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, kvDim));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, kvDim));
		keyCache.clear();
		valueCache.clear();

		// Position
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// RoPE frequencies
		PackedCollection freqCis = createRopeFreqs(seqLen, headSize);

		// Same weights as KVCacheAccumulationTest
		PackedCollection wk = createWeights2D(kvDim, dim, 0.5);
		PackedCollection wv = createWeights2D(kvDim, dim, 0.5);
		PackedCollection rmsWeight = createWeights(dim, 1.0);

		log("Building model with ONLY key/value branches (no attention)...\n");

		// Model structure - SAME as KVCacheAccumulationTest but without attention computation
		Model model = new Model(shape(1, dim));
		SequentialBlock main = new SequentialBlock(shape(1, dim));

		// RMSNorm
		main.add(rmsnorm(shape(1, dim), rmsWeight, 1e-6));

		// Keys branch - EXACT same as KVCacheAccumulationTest
		SequentialBlock keys = main.branch();
		keys.add(dense(wk));
		keys.add(reshape(shape(kvDim), shape(kvHeads, headSize / 2, 2)));
		keys.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, p(position)));
		keys.andThen(into(keyCache, p(position)));

		// Values branch - EXACT same as KVCacheAccumulationTest
		SequentialBlock values = main.branch();
		values.add(dense(wv));
		values.andThen(into(valueCache, p(position)));

		// Main path: just dense (simpler than full attention)
		PackedCollection mainWeights = createWeights2D(dim, dim, 0.5);
		main.add(dense(mainWeights));

		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// TRULY different inputs - NOT scalar multiples of each other!
		// Previous inputs were (i+1)*0.1*(j+1) which gives Input1 = 2*Input0, Input2 = 3*Input0
		// RMSNorm(2x) = RMSNorm(x), so all three gave SAME normalized output!
		PackedCollection[] inputs = new PackedCollection[3];
		for (int i = 0; i < 3; i++) {
			inputs[i] = new PackedCollection(shape(1, dim));
			for (int j = 0; j < dim; j++) {
				// Use different patterns, not scalar multiples
				inputs[i].setMem(j, 1.0 + i * 0.5 + j * 0.1 + Math.sin(i * j * 0.3));
			}
		}

		log("Inputs:");
		for (int i = 0; i < 3; i++) {
			log(String.format("  Step %d: first 4 = [%.4f, %.4f, %.4f, %.4f]",
				i, inputs[i].toDouble(0), inputs[i].toDouble(1), inputs[i].toDouble(2), inputs[i].toDouble(3)));
		}

		log("\n" + "=".repeat(50));
		log("Running 3 forward passes");
		log("=".repeat(50) + "\n");

		for (int step = 0; step < 3; step++) {
			position.setMem(0, (double) step);

			log(String.format("--- Step %d (position=%d) ---", step, step));

			log("Value cache BEFORE:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(valueCache, row, kvDim)));
			}

			compiled.forward(inputs[step]);

			log("Value cache AFTER:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(valueCache, row, kvDim)));
			}

			log("");
		}

		log("=".repeat(50));
		log("Final Analysis");
		log("=".repeat(50) + "\n");

		// Check if value rows are identical (the bug)
		boolean valuesAllSame = true;
		for (int row = 1; row < 3; row++) {
			for (int j = 0; j < kvDim; j++) {
				if (Math.abs(valueCache.toDouble(row * kvDim + j) - valueCache.toDouble(j)) > 0.001) {
					valuesAllSame = false;
					break;
				}
			}
		}

		// Check if key rows are different
		boolean keysDifferent = false;
		for (int row = 1; row < 3; row++) {
			for (int j = 0; j < kvDim; j++) {
				if (Math.abs(keyCache.toDouble(row * kvDim + j) - keyCache.toDouble(j)) > 0.001) {
					keysDifferent = true;
					break;
				}
			}
		}

		log("Key cache rows:");
		for (int row = 0; row < 3; row++) {
			log(String.format("  Row %d: %s", row, formatCacheRow(keyCache, row, kvDim)));
		}

		log("\nValue cache rows:");
		for (int row = 0; row < 3; row++) {
			log(String.format("  Row %d: %s", row, formatCacheRow(valueCache, row, kvDim)));
		}

		log("\n");
		if (keysDifferent) {
			log("[OK] Key cache rows DIFFER (expected due to RoPE)");
		} else {
			log("[UNEXPECTED] Key cache rows are identical");
		}

		if (valuesAllSame) {
			log("[BUG CONFIRMED] Value cache rows ALL IDENTICAL!");
			log("The values branch is NOT receiving updated input on each forward pass.");
		} else {
			log("[OK] Value cache rows DIFFER (expected since inputs differ)");
		}

		log("\n=== Test Complete ===");
	}

	private PackedCollection createWeights(int size, double value) {
		PackedCollection weights = new PackedCollection(size);
		for (int i = 0; i < size; i++) {
			weights.setMem(i, value);
		}
		return weights;
	}

	private PackedCollection createWeights2D(int rows, int cols, double scale) {
		PackedCollection weights = new PackedCollection(shape(rows, cols));
		int size = rows * cols;
		for (int i = 0; i < size; i++) {
			weights.setMem(i, scale * Math.sin(i * 0.3));
		}
		return weights;
	}

	private PackedCollection createRopeFreqs(int seqLen, int headSize) {
		double theta = 10000.0;
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

	private String formatCacheRow(PackedCollection cache, int row, int cols) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(cols, 4); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", cache.toDouble(row * cols + i)));
		}
		if (cols > 4) sb.append(", ...");
		sb.append("]");
		return sb.toString();
	}
}
