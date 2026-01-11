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
 * Test to inspect what values actually get written to caches during attention.
 */
public class CacheContentInspectionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	/**
	 * Test if values differ across positions when using different input at each position.
	 * This should PASS because different inputs produce different values.
	 */
	@Test
	public void testDifferentInputProducesDifferentValues() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/cache_inspection.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Test: Different input at each position ===");

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

		Block attentionBlock = attention(heads, kvHeads,
			rmsAttWeight, wk, wv, wq, wo,
			null, null, null, null, null,
			freqCis, dynamicPosition, 1e-6);

		Model model = new Model(shape(1, dim));
		model.add(attentionBlock);
		CompiledModel compiled = model.compile();

		double[][] outputs = new double[5][dim];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);

			// Different input at each position!
			PackedCollection input = new PackedCollection(shape(1, dim));
			for (int i = 0; i < dim; i++) {
				input.setMem(i, 0.1 * (i + 1) + pos * 0.01);  // Vary with position
			}

			PackedCollection result = compiled.forward(input);
			for (int i = 0; i < dim; i++) {
				outputs[pos][i] = result.toDouble(i);
			}
			log("Position " + pos + " (input varies): " + formatArray(result, dim));
		}

		// Check if outputs differ
		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < dim; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) allSame = false;
			}
			log(String.format("Pos 0 vs %d: max diff = %.9f", pos, maxDiff));
		}

		if (allSame) {
			log("[FAIL] Different inputs should produce different outputs!");
		} else {
			log("[PASS] Different inputs produce different outputs");
		}

		Assert.assertFalse("Different inputs should produce different outputs", allSame);
	}

	/**
	 * Test if same input at each position produces same output.
	 * This is EXPECTED to produce same output when values are uniform.
	 * The question is: should position ALONE cause different outputs?
	 */
	@Test
	public void testSameInputWithRoPE() throws Exception {
		log("\n=== Test: Same input, expecting RoPE to cause difference ===");

		int dim = 8;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;
		int seqLen = 10;

		// Create weights that are NOT identity - make them more interesting
		PackedCollection rmsAttWeight = ones(dim);
		PackedCollection wq = createRandomWeights(dim, dim);  // Random weights
		PackedCollection wk = createRandomWeights(dim, dim);
		PackedCollection wv = createRandomWeights(dim, dim);
		PackedCollection wo = createIdentity(dim, dim);  // Keep wo simple
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		Block attentionBlock = attention(heads, kvHeads,
			rmsAttWeight, wk, wv, wq, wo,
			null, null, null, null, null,
			freqCis, dynamicPosition, 1e-6);

		Model model = new Model(shape(1, dim));
		model.add(attentionBlock);
		CompiledModel compiled = model.compile();

		// Same input at all positions
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 0.1 * (i + 1));
		}

		double[][] outputs = new double[5][dim];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			PackedCollection result = compiled.forward(input);
			for (int i = 0; i < dim; i++) {
				outputs[pos][i] = result.toDouble(i);
			}
			log("Position " + pos + " (same input): " + formatArray(result, dim));
		}

		// Check if outputs differ
		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < dim; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) allSame = false;
			}
			log(String.format("Pos 0 vs %d: max diff = %.9f", pos, maxDiff));
		}

		// With RoPE and different attention patterns, outputs SHOULD differ
		// even with same input, because:
		// 1. RoPE rotates Q differently at each position
		// 2. Q @ K^T produces different attention scores
		// 3. Even if V is same, the attention weights change
		// BUT: if V is completely uniform (all same value), weighted sum is same

		if (allSame) {
			log("[INFO] Same input produces same output - checking if this is expected...");
			log("[INFO] This happens when value cache has uniform values across positions");
		} else {
			log("[PASS] Position affects output even with same input");
		}

		// The key insight: with random weights, even same input should produce
		// different attention patterns that yield different outputs
		// UNLESS the attention pattern doesn't matter because values are uniform
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

	private PackedCollection createRandomWeights(int rows, int cols) {
		PackedCollection c = new PackedCollection(shape(rows, cols));
		java.util.Random rand = new java.util.Random(42);  // Fixed seed for reproducibility
		for (int i = 0; i < rows * cols; i++) {
			c.setMem(i, rand.nextGaussian() * 0.1);
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
			sb.append(String.format("%.6f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
