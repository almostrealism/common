package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test to verify KV cache accumulates correctly across multiple forward passes.
 *
 * This test creates a simplified attention block with EXTERNAL cache that we can
 * inspect after each forward pass.
 */
public class KVCacheAccumulationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testCacheAccumulationWithDifferentInputs() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/kv_cache_accumulation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  KV CACHE ACCUMULATION TEST");
		log("=".repeat(70) + "\n");

		int dim = 16;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;  // 8
		int seqLen = 10;
		int kvDim = dim;

		// Create EXTERNAL caches that we can inspect
		PackedCollection keyCache = new PackedCollection(shape(seqLen, kvDim));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, kvDim));
		keyCache.clear();
		valueCache.clear();

		// Create position
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// Create RoPE frequencies
		PackedCollection freqCis = createRopeFreqs(seqLen, headSize);

		// Create synthetic weights
		PackedCollection wk = createWeights2D(kvDim, dim, 0.5);
		PackedCollection wv = createWeights2D(kvDim, dim, 0.5);
		PackedCollection wq = createWeights2D(dim, dim, 0.5);
		PackedCollection wo = createWeights2D(dim, dim, 0.5);
		PackedCollection rmsWeight = createWeights(dim, 1.0);

		log("Building model with external KV cache...");

		// Build a simplified attention block with external cache
		Model model = new Model(shape(1, dim));
		SequentialBlock attention = new SequentialBlock(shape(1, dim));

		// RMSNorm
		attention.add(rmsnorm(shape(1, dim), rmsWeight, 1e-6));

		// Keys branch
		SequentialBlock keys = attention.branch();
		keys.add(dense(wk));
		keys.add(reshape(shape(kvDim), shape(kvHeads, headSize / 2, 2)));
		keys.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, p(position)));
		keys.andThen(into(keyCache, p(position)));

		// Values branch
		SequentialBlock values = attention.branch();
		values.add(dense(wv));
		values.andThen(into(valueCache, p(position)));

		// Query path
		attention.add(dense(wq));
		attention.add(reshape(shape(dim), shape(heads, headSize / 2, 2)));
		attention.add(ropeRotation(shape(heads, headSize / 2, 2), freqCis, p(position)));
		attention.add(reshape(shape(heads, headSize / 2, 2), shape(heads, headSize)));

		// Attention computation - read from external cache
		attention.add(attentionKeys(shape(heads, headSize), p(keyCache.reshape(shape(seqLen, kvHeads, headSize)))));

		// Causal mask
		attention.add(layer("causal_mask", shape(heads, seqLen).traverseEach(), shape(heads, seqLen).traverseEach(),
				input -> {
					var indices = integers(0, seqLen);
					var maskRow = greaterThan(indices, p(position), c(-10000.0), c(0.0), false);
					var causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
					return add(input, causalMask);
				}));

		attention.add(softmax(shape(heads, seqLen).traverseEach(), true));
		attention.add(attentionValues(shape(heads, seqLen).traverseEach(),
				p(valueCache.reshape(shape(seqLen, kvHeads, headSize)))));
		attention.add(dense(wo));
		attention.reshape(shape(1, dim));

		model.add(attention);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create 3 TRULY different inputs - NOT scalar multiples of each other!
		// IMPORTANT: RMSNorm(k*x) = RMSNorm(x), so scalar multiples produce identical output.
		// Use inputs with different PATTERNS, not just different magnitudes.
		PackedCollection[] inputs = new PackedCollection[3];
		for (int i = 0; i < 3; i++) {
			inputs[i] = new PackedCollection(shape(1, dim));
			for (int j = 0; j < dim; j++) {
				// Use varying patterns: base + step offset + position scale + non-linear component
				inputs[i].setMem(j, 1.0 + i * 0.5 + j * 0.1 + Math.sin(i * j * 0.3));
			}
		}

		log("\n" + "=".repeat(50));
		log("RUNNING 3 FORWARD PASSES WITH DIFFERENT INPUTS");
		log("=".repeat(50) + "\n");

		double[][] outputs = new double[3][dim];

		for (int step = 0; step < 3; step++) {
			log("-".repeat(40));
			log(String.format("STEP %d", step));
			log("-".repeat(40));

			// Update position
			position.setMem(0, (double) step);
			log("Position: " + step);

			// Log input
			log("Input (first 4): " + formatFirst(inputs[step], 4));

			// Log cache BEFORE
			log("\nKey cache BEFORE forward:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(keyCache, row, kvDim)));
			}
			log("\nValue cache BEFORE forward:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(valueCache, row, kvDim)));
			}

			// Forward pass
			PackedCollection output = compiled.forward(inputs[step]);

			// Log cache AFTER
			log("\nKey cache AFTER forward:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(keyCache, row, kvDim)));
			}
			log("\nValue cache AFTER forward:");
			for (int row = 0; row <= step; row++) {
				log(String.format("  Row %d: %s", row, formatCacheRow(valueCache, row, kvDim)));
			}

			// Store output
			for (int j = 0; j < dim; j++) {
				outputs[step][j] = output.toDouble(j);
			}
			log("\nOutput (first 4): " + formatFirst(output, 4));

			// Verify cache was written
			boolean cacheWritten = false;
			for (int j = 0; j < kvDim; j++) {
				if (Math.abs(keyCache.toDouble(step * kvDim + j)) > 1e-10) {
					cacheWritten = true;
					break;
				}
			}
			log("\nCache write check: " + (cacheWritten ? "[OK]" : "[FAILED - zeros]"));

			// Verify previous rows preserved
			if (step > 0) {
				boolean previousPreserved = true;
				for (int prevRow = 0; prevRow < step; prevRow++) {
					for (int j = 0; j < kvDim; j++) {
						if (Math.abs(keyCache.toDouble(prevRow * kvDim + j)) < 1e-10) {
							previousPreserved = false;
							break;
						}
					}
				}
				log("Previous rows preserved: " + (previousPreserved ? "[OK]" : "[FAILED - zeros]"));
			}

			log("");
		}

		// Check if outputs differ
		log("=".repeat(50));
		log("ANALYSIS");
		log("=".repeat(50) + "\n");

		double diff01 = 0, diff12 = 0;
		for (int i = 0; i < dim; i++) {
			diff01 = Math.max(diff01, Math.abs(outputs[0][i] - outputs[1][i]));
			diff12 = Math.max(diff12, Math.abs(outputs[1][i] - outputs[2][i]));
		}

		log(String.format("Max output diff step 0 vs 1: %.6f", diff01));
		log(String.format("Max output diff step 1 vs 2: %.6f", diff12));

		if (diff01 < 0.001 && diff12 < 0.001) {
			log("\n[CRITICAL] All outputs nearly identical despite different inputs!");
			log("This suggests the attention is NOT using the accumulated cache correctly.");
		} else {
			log("\n[GOOD] Outputs differ as expected.");
		}

		log("\n=== Full Key Cache State ===");
		for (int row = 0; row < seqLen; row++) {
			double sum = 0;
			for (int j = 0; j < kvDim; j++) {
				sum += Math.abs(keyCache.toDouble(row * kvDim + j));
			}
			if (sum > 1e-10) {
				log(String.format("Row %d: %s", row, formatCacheRow(keyCache, row, kvDim)));
			}
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

	private String formatFirst(PackedCollection c, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count && i < c.getShape().getTotalSize(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
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
