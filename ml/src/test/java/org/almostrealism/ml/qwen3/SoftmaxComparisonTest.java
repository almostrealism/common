package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Test softmax computation to verify it matches PyTorch.
 */
public class SoftmaxComparisonTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void testSoftmaxWithPyTorchScores() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/softmax_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Softmax Comparison: Java vs PyTorch");
		log("===================================================\n");

		// Load PyTorch reference data
		double[] pytorchScoresBeforeMask = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_before_mask.bin");
		double[] pytorchScoresAfterMask = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_after_mask.bin");
		double[] pytorchSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");

		int heads = 14;
		int seqLen = 2;  // Only positions 0 and 1 for this test

		log("Loaded PyTorch reference data:");
		log("  scores_before_mask: " + pytorchScoresBeforeMask.length + " values");
		log("  scores_after_mask: " + pytorchScoresAfterMask.length + " values");
		log("  softmax_weights: " + pytorchSoftmax.length + " values");

		// Extract position 1 query attention scores (indices [h, 1, :] for each head)
		// Shape is [1, 14, 2, 2] = 56 values
		// Position 1 query is at indices h*4+2 and h*4+3
		log("\n=== PyTorch Attention Scores (Position 1 Query) ===");
		for (int h = 0; h < heads; h++) {
			int idx0 = h * 4 + 2;
			int idx1 = h * 4 + 3;
			log(String.format("  Head %2d: score(k0)=%.4f, score(k1)=%.4f",
					h, pytorchScoresAfterMask[idx0], pytorchScoresAfterMask[idx1]));
		}

		log("\n=== PyTorch Softmax Weights (Position 1 Query) ===");
		for (int h = 0; h < heads; h++) {
			int idx0 = h * 4 + 2;
			int idx1 = h * 4 + 3;
			log(String.format("  Head %2d: w0=%.6f, w1=%.6f",
					h, pytorchSoftmax[idx0], pytorchSoftmax[idx1]));
		}

		// Create a simple test: apply Java softmax to the PyTorch scores and compare
		log("\n=== Testing Java Softmax ===");

		// Build a simple model that applies softmax
		TraversalPolicy inputShape = shape(heads, seqLen);
		TraversalPolicy outputShape = inputShape;

		// Create input from PyTorch scores (position 1 query only)
		PackedCollection scoresInput = new PackedCollection(inputShape);
		for (int h = 0; h < heads; h++) {
			int idx0 = h * 4 + 2;
			int idx1 = h * 4 + 3;
			scoresInput.setMem(h * seqLen + 0, pytorchScoresAfterMask[idx0]);
			scoresInput.setMem(h * seqLen + 1, pytorchScoresAfterMask[idx1]);
		}

		log("\nInput scores (first 4 values):");
		log(String.format("  [%.4f, %.4f, %.4f, %.4f, ...]",
				scoresInput.toDouble(0), scoresInput.toDouble(1),
				scoresInput.toDouble(2), scoresInput.toDouble(3)));

		// Build softmax model
		Model softmaxModel = new Model(inputShape);
		softmaxModel.add(softmax(inputShape, true));
		CompiledModel compiledSoftmax = softmaxModel.compile();

		// Run softmax
		PackedCollection javaSoftmax = compiledSoftmax.forward(scoresInput);

		log("\nJava softmax output (first 4 values):");
		log(String.format("  [%.6f, %.6f, %.6f, %.6f, ...]",
				javaSoftmax.toDouble(0), javaSoftmax.toDouble(1),
				javaSoftmax.toDouble(2), javaSoftmax.toDouble(3)));

		// Compare with PyTorch
		log("\n=== Comparison ===");
		double maxDiff = 0;
		double sumDiff = 0;
		for (int h = 0; h < heads; h++) {
			int pyIdx0 = h * 4 + 2;
			int pyIdx1 = h * 4 + 3;
			int javaIdx0 = h * seqLen + 0;
			int javaIdx1 = h * seqLen + 1;

			double diff0 = Math.abs(javaSoftmax.toDouble(javaIdx0) - pytorchSoftmax[pyIdx0]);
			double diff1 = Math.abs(javaSoftmax.toDouble(javaIdx1) - pytorchSoftmax[pyIdx1]);

			maxDiff = Math.max(maxDiff, Math.max(diff0, diff1));
			sumDiff += diff0 + diff1;

			if (h < 5) {  // Print first 5 heads
				log(String.format("  Head %2d: Java=(%.6f, %.6f), PyTorch=(%.6f, %.6f), diff=(%.6f, %.6f)",
						h,
						javaSoftmax.toDouble(javaIdx0), javaSoftmax.toDouble(javaIdx1),
						pytorchSoftmax[pyIdx0], pytorchSoftmax[pyIdx1],
						diff0, diff1));
			}
		}

		double meanDiff = sumDiff / (heads * seqLen);
		log(String.format("\nMean Absolute Difference: %.8f", meanDiff));
		log(String.format("Max Absolute Difference: %.8f", maxDiff));

		if (maxDiff < 1e-5) {
			log("\n[OK] Java softmax matches PyTorch!");
		} else {
			log("\n[MISMATCH] Java softmax differs from PyTorch.");
		}

		log("\n=== Test Complete ===");
	}

	private double[] loadBinaryLogits(String path) throws Exception {
		try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
			byte[] sizeBytes = new byte[4];
			dis.readFully(sizeBytes);
			int size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

			double[] logits = new double[size];
			byte[] floatBytes = new byte[4];
			for (int i = 0; i < size; i++) {
				dis.readFully(floatBytes);
				logits[i] = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
			}
			return logits;
		}
	}
}
