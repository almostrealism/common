package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Minimal test that calls the actual attention() method directly
 * to identify where position handling breaks.
 */
public class MinimalAttentionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testMinimalAttention() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/minimal_attention_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  MINIMAL ATTENTION TEST");
		log("=".repeat(70) + "\n");

		// Smallest possible attention config
		int dim = 8;
		int heads = 2;
		int kvHeads = 2;  // Same as heads (no GQA)
		int headSize = dim / heads;  // 4
		int seqLen = 10;

		log("Config: dim=" + dim + ", heads=" + heads + ", kvHeads=" + kvHeads +
			", headSize=" + headSize + ", seqLen=" + seqLen);

		// Create minimal weights
		PackedCollection rmsAttWeight = ones(dim);
		PackedCollection wq = createIdentity(dim, dim);
		PackedCollection wk = createIdentity(dim, dim);
		PackedCollection wv = createIdentity(dim, dim);
		PackedCollection wo = createIdentity(dim, dim);

		// Create RoPE frequencies
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		// Create position
		PackedCollection position = new PackedCollection(1);

		// IMPORTANT: Create DynamicCollectionProducer
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		log("\nBuilding attention block...");
		Block attentionBlock = attention(heads, kvHeads,
			rmsAttWeight, wk, wv, wq, wo,
			null, null, null, null, null,
			freqCis, dynamicPosition, 1e-6);

		log("Creating model...");
		Model model = new Model(shape(1, dim));
		model.add(attentionBlock);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Test at different positions with DIFFERENT inputs at each position
		// This simulates real autoregressive generation where each token is different
		log("\n=== Testing Positions (with different inputs) ===");
		double[][] outputs = new double[5][dim];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);

			// Different input at each position (simulating different token embeddings)
			PackedCollection input = new PackedCollection(shape(1, dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, 0.1 * (i + 1) + pos * 0.01);  // Varies with position
			}

			PackedCollection result = compiled.forward(input);

			for (int i = 0; i < dim; i++) {
				outputs[pos][i] = result.toDouble(i);
			}
			log("Position " + pos + ": " + formatArray(result, 0, dim));
		}

		// Compare outputs
		log("\n=== Comparing Outputs ===");
		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < dim; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) allSame = false;
			}
			log(String.format("Position 0 vs %d: max diff = %.9f", pos, maxDiff));
		}

		if (allSame) {
			log("\n[FAIL] All outputs identical - this should not happen with different inputs!");
		} else {
			log("\n[PASS] Outputs differ - attention correctly handles different positions!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertFalse("Different inputs should produce different outputs", allSame);
	}

	private PackedCollection ones(int size) {
		PackedCollection c = new PackedCollection(size);
		for (int i = 0; i < size; i++) c.setMem(i, 1.0);
		return c;
	}

	private PackedCollection createIdentity(int rows, int cols) {
		// Create a near-identity matrix with small values
		PackedCollection c = new PackedCollection(shape(rows, cols));
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < cols; j++) {
				// Small non-zero values for identity-like behavior
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

	private String formatArray(PackedCollection c, int start, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = start; i < start + count && i < c.getShape().getTotalSize(); i++) {
			if (i > start) sb.append(", ");
			sb.append(String.format("%.6f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
