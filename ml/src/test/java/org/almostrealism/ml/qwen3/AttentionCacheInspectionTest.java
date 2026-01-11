package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.lang.reflect.Field;

/**
 * Test to inspect the KV cache inside the attention block after each forward pass.
 * This helps verify that the cache is being written correctly with position-dependent data.
 */
public class AttentionCacheInspectionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testCacheAccumulationInAttention() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/attention_cache_inspection.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Attention Cache Inspection Test");
		log("===================================================\n");

		// Small attention configuration
		int dim = 8;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;  // 4
		int seqLen = 5;

		// Create synthetic weights with LARGER values to ensure non-trivial computation
		int kvDim = dim * kvHeads / heads;  // 8
		PackedCollection rmsAttWeight = createWeights(dim, 1.0);
		PackedCollection wk = createWeights2D(kvDim, dim, 0.5);
		PackedCollection wv = createWeights2D(kvDim, dim, 0.5);
		PackedCollection wq = createWeights2D(dim, dim, 0.5);
		PackedCollection wo = createWeights2D(dim, dim, 0.5);

		// Create RoPE frequency table
		PackedCollection freqCis = createRopeFreqs(seqLen, headSize);

		// Create position collection
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		log("Configuration:");
		log(String.format("  dim=%d, heads=%d, kvHeads=%d, headSize=%d, seqLen=%d",
				dim, heads, kvHeads, headSize, seqLen));

		// Build attention block - we'll use a simplified version with external cache
		// to inspect it
		PackedCollection keyCache = new PackedCollection(seqLen, kvHeads, headSize);
		PackedCollection valueCache = new PackedCollection(seqLen, kvHeads, headSize);
		keyCache.clear();
		valueCache.clear();

		Model model = new Model(shape(1, dim));
		SequentialBlock attention = new SequentialBlock(shape(1, dim));

		// RMSNorm
		attention.add(rmsnorm(shape(1, dim), rmsAttWeight, 1e-6));

		// Keys branch - writes to our external cache
		SequentialBlock keys = attention.branch();
		keys.add(dense(wk));
		keys.add(reshape(shape(kvDim), shape(kvHeads, headSize / 2, 2)));
		keys.add(ropeRotation(shape(kvHeads, headSize / 2, 2), freqCis, p(position)));
		keys.andThen(into(keyCache.reshape(shape(seqLen, kvDim)), p(position)));

		// Values branch
		SequentialBlock values = attention.branch();
		values.add(dense(wv));
		values.andThen(into(valueCache.reshape(shape(seqLen, kvDim)), p(position)));

		// Query path
		attention.add(dense(wq));
		attention.add(reshape(shape(dim), shape(heads, headSize / 2, 2)));
		attention.add(ropeRotation(shape(heads, headSize / 2, 2), freqCis, p(position)));
		attention.add(reshape(shape(heads, headSize / 2, 2), shape(heads, headSize)));

		// Attention computation
		attention.add(attentionKeys(shape(heads, headSize), p(keyCache)));

		// Causal mask
		attention.add(layer("causal_mask", shape(heads, seqLen).traverseEach(), shape(heads, seqLen).traverseEach(),
				input -> {
					var indices = integers(0, seqLen);
					var maskRow = greaterThan(indices, p(position), c(-10000.0), c(0.0), false);
					var causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
					return add(input, causalMask);
				}));

		attention.add(softmax(shape(heads, seqLen).traverseEach(), true));
		attention.add(attentionValues(shape(heads, seqLen).traverseEach(), p(valueCache)));
		attention.add(dense(wo));
		attention.reshape(shape(1, dim));

		model.add(attention);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create input
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 1.0 + 0.1 * i);
		}

		log("\nInput: " + formatArray(input, 0, dim));

		log("\n=== Running Forward Passes and Inspecting Cache ===\n");

		double[][] outputs = new double[4][dim];

		for (int pos = 0; pos < 4; pos++) {
			position.setMem(0, (double) pos);

			log("--- Position " + pos + " ---");

			// Show cache BEFORE forward pass
			log("Key cache BEFORE forward (row " + pos + "):");
			log("  " + formatCacheRow(keyCache, pos, kvDim));

			PackedCollection output = compiled.forward(input);

			// Show cache AFTER forward pass
			log("Key cache AFTER forward (row " + pos + "):");
			log("  " + formatCacheRow(keyCache, pos, kvDim));

			// Check if cache was written
			boolean hasNonZero = false;
			for (int i = 0; i < kvDim; i++) {
				if (Math.abs(keyCache.toDouble(pos * kvDim + i)) > 1e-10) {
					hasNonZero = true;
					break;
				}
			}
			log("  Cache write: " + (hasNonZero ? "[OK] Data written" : "[FAIL] Still zeros"));

			// Store output
			for (int i = 0; i < dim; i++) {
				outputs[pos][i] = output.toDouble(i);
			}
			log("Output: " + formatArray(output, 0, dim));
			log("");
		}

		log("=== Comparing Outputs ===\n");

		boolean allIdentical = true;
		for (int pos = 1; pos < 4; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < dim; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) {
					allIdentical = false;
				}
			}
			log(String.format("Position 0 vs Position %d: max diff = %.6f", pos, maxDiff));
		}

		log("");
		if (allIdentical) {
			log("[FAIL] All outputs identical - position NOT affecting computation!");
		} else {
			log("[PASS] Outputs differ between positions!");
		}

		log("\n=== Full Cache State ===\n");
		log("Key Cache (each row should be DIFFERENT due to RoPE):");
		for (int row = 0; row < seqLen; row++) {
			log("  Row " + row + ": " + formatCacheRow(keyCache, row, kvDim));
		}

		log("\nValue Cache (each row should be SAME since no RoPE on values):");
		for (int row = 0; row < seqLen; row++) {
			log("  Row " + row + ": " + formatCacheRow(valueCache, row, kvDim));
		}

		// Check if all value cache rows are identical
		boolean allValuesIdentical = true;
		for (int row = 1; row < 4; row++) {
			for (int i = 0; i < kvDim; i++) {
				if (Math.abs(valueCache.toDouble(row * kvDim + i) - valueCache.toDouble(i)) > 1e-6) {
					allValuesIdentical = false;
					break;
				}
			}
		}

		log("\nValue cache rows identical: " + allValuesIdentical);
		if (allValuesIdentical) {
			log("NOTE: Since V entries are all the same (same input, no RoPE),");
			log("      attention output = sum(weights)*V = V regardless of weights!");
			log("      This explains identical outputs despite different attention patterns.");
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

	private String formatArray(PackedCollection c, int start, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = start; i < start + count && i < c.getShape().getTotalSize(); i++) {
			if (i > start) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatCacheRow(PackedCollection cache, int row, int cols) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < cols; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", cache.toDouble(row * cols + i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
