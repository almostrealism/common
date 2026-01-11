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
 * Test to verify that position affects the attention block output.
 *
 * This test uses synthetic weights and verifies that:
 * 1. Different positions produce different attention outputs
 * 2. Position is being read dynamically, not baked in at compile time
 */
public class AttentionPositionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testAttentionWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/attention_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Attention Block Position Test");
		log("===================================================\n");

		// Small attention configuration
		int dim = 32;       // Model dimension
		int heads = 4;      // Number of attention heads
		int kvHeads = 2;    // Number of KV heads (GQA)
		int headSize = dim / heads;  // 8
		int seqLen = 10;

		// Create synthetic weights (small random values)
		int kvDim = dim * kvHeads / heads;  // 16
		PackedCollection rmsAttWeight = createSyntheticWeights(dim);
		PackedCollection wk = createSyntheticWeights2D(kvDim, dim);   // (16, 32) for K projection
		PackedCollection wv = createSyntheticWeights2D(kvDim, dim);   // (16, 32) for V projection
		PackedCollection wq = createSyntheticWeights2D(dim, dim);     // (32, 32) for Q projection
		PackedCollection wo = createSyntheticWeights2D(dim, dim);     // (32, 32) for O projection

		// Create RoPE frequency table
		PackedCollection freqCis = createRopeFreqs(seqLen, headSize);

		// Create position collection
		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		// CRITICAL: Use DynamicCollectionProducer instead of p(position)!
		// p(position) creates a static reference that's evaluated at compile time
		// DynamicCollectionProducer reads the position value at runtime
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		log("Configuration:");
		log(String.format("  dim=%d, heads=%d, kvHeads=%d, headSize=%d, seqLen=%d",
				dim, heads, kvHeads, headSize, seqLen));
		log("");

		// Build attention block
		log("Building attention block...");
		Block attentionBlock = attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				null, null, null, null, null,
				freqCis, dynamicPosition, 1e-6);

		// Build model
		Model model = new Model(shape(1, dim));
		model.add(attentionBlock);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create input
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 0.1 * (i + 1));  // Values: 0.1, 0.2, 0.3, ...
		}

		log("Input (first 8 values): " + formatValues(input, 0, 8));

		// Test at different positions
		log("\n=== Testing Attention at Different Positions ===\n");

		double[][] outputs = new double[5][8];  // Store first 8 output values

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			log("Position " + pos + ":");

			PackedCollection output = compiled.forward(input);

			for (int i = 0; i < 8; i++) {
				outputs[pos][i] = output.toDouble(i);
			}

			log("  Output (first 8 values): " + formatValues(output, 0, 8));
		}

		// Compare outputs
		log("\n=== Comparing Outputs ===\n");

		boolean allIdentical = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < 8; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) {
					allIdentical = false;
				}
			}
			log(String.format("  Position 0 vs Position %d: max diff = %.6f", pos, maxDiff));
		}

		log("");
		if (allIdentical) {
			log("[FAIL] All attention outputs are identical - position is NOT affecting computation!");
			log("This indicates the position producer is being evaluated at compile time.");
		} else {
			log("[PASS] Attention outputs differ between positions - position IS working!");
		}

		log("\n=== Test Complete ===");

		Assert.assertFalse("Position should affect attention output", allIdentical);
	}

	private PackedCollection createSyntheticWeights(int size) {
		PackedCollection weights = new PackedCollection(size);
		// Larger values for RMSNorm weights (should be around 1.0)
		for (int i = 0; i < size; i++) {
			weights.setMem(i, 1.0);  // RMSNorm weights are typically 1.0
		}
		return weights;
	}

	private PackedCollection createSyntheticWeights2D(int rows, int cols) {
		PackedCollection weights = new PackedCollection(shape(rows, cols));
		// Use Xavier-like initialization: scale by sqrt(2 / (rows + cols))
		double scale = Math.sqrt(2.0 / (rows + cols));
		int size = rows * cols;
		for (int i = 0; i < size; i++) {
			// Deterministic pseudo-random values
			double val = Math.sin(i * 0.1) * scale;
			weights.setMem(i, val);
		}
		return weights;
	}

	private PackedCollection createRopeFreqs(int seqLen, int headSize) {
		double theta = 1000000.0;  // Qwen3 theta
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

	private String formatValues(PackedCollection c, int start, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = start; i < start + count && i < c.getShape().getTotalSize(); i++) {
			if (i > start) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
