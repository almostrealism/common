package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Minimal test to debug the causal mask repeat behavior.
 * Tests whether repeat() correctly broadcasts mask to all heads.
 */
public class MaskRepeatDebugTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testMaskRepeatBehavior() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/mask_repeat_debug.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  MASK REPEAT DEBUG TEST");
		log("=".repeat(70) + "\n");

		int heads = 3;
		int seqLen = 5;

		// Create a simple mask row: [0, -10000, -10000, -10000, -10000]
		// (position 0 is unmasked, positions 1+ are masked)
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);  // We're at position 0

		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow = greaterThan(indices, p(position), c(-10000.0), c(0.0), false);

		// First, evaluate just the mask row
		log("=== Step 1: Evaluate mask row alone ===");
		Model maskRowModel = new Model(shape(1));
		maskRowModel.add(layer("mask_row", shape(1), shape(seqLen), in -> maskRow));
		PackedCollection maskRowOutput = maskRowModel.compile().forward(new PackedCollection(shape(1)));

		log("Mask row shape: " + maskRowOutput.getShape());
		StringBuilder sb = new StringBuilder("Mask row values: [");
		for (int i = 0; i < seqLen; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.0f", maskRowOutput.toDouble(i)));
		}
		sb.append("]");
		log(sb.toString());
		log("Expected: [0, -10000, -10000, -10000, -10000]");

		// Now test repeat
		log("\n=== Step 2: Test repeat(heads, maskRow) ===");
		CollectionProducer causalMask = repeat(heads, maskRow);

		Model repeatModel = new Model(shape(1));
		repeatModel.add(layer("repeat_mask", shape(1), shape(heads, seqLen), in -> causalMask));
		PackedCollection repeatOutput = repeatModel.compile().forward(new PackedCollection(shape(1)));

		log("Repeat output shape: " + repeatOutput.getShape());

		log("\nRepeat output (per head, per position):");
		for (int h = 0; h < heads; h++) {
			sb = new StringBuilder(String.format("  Head %d: [", h));
			for (int p = 0; p < seqLen; p++) {
				if (p > 0) sb.append(", ");
				sb.append(String.format("%.0f", repeatOutput.toDouble(h * seqLen + p)));
			}
			sb.append("]");
			log(sb.toString());
		}
		log("Expected: Each head should be [0, -10000, -10000, -10000, -10000]");

		// Now test the add operation with different shape combinations
		log("\n=== Step 3: Test add with regular shapes ===");

		// Create dummy attention scores: different value per head/position for debugging
		PackedCollection attnScores = new PackedCollection(shape(heads, seqLen));
		for (int h = 0; h < heads; h++) {
			for (int p = 0; p < seqLen; p++) {
				// Score = h * 10 + p (e.g., head 0: 0,1,2,3,4; head 1: 10,11,12,13,14)
				attnScores.setMem(h * seqLen + p, h * 10 + p);
			}
		}

		log("Attention scores:");
		for (int h = 0; h < heads; h++) {
			sb = new StringBuilder(String.format("  Head %d: [", h));
			for (int p = 0; p < seqLen; p++) {
				if (p > 0) sb.append(", ");
				sb.append(String.format("%.0f", attnScores.toDouble(h * seqLen + p)));
			}
			sb.append("]");
			log(sb.toString());
		}

		// Add mask to scores
		TraversalPolicy scoreShape = shape(heads, seqLen);
		Model addModel = new Model(scoreShape);
		addModel.add(layer("add_mask", scoreShape, scoreShape,
			in -> add(c(in), causalMask)));
		PackedCollection maskedOutput = addModel.compile().forward(attnScores);

		log("\nAfter adding mask (regular shape):");
		for (int h = 0; h < heads; h++) {
			sb = new StringBuilder(String.format("  Head %d: [", h));
			for (int p = 0; p < seqLen; p++) {
				if (p > 0) sb.append(", ");
				double val = maskedOutput.toDouble(h * seqLen + p);
				sb.append(String.format("%.0f", val));
			}
			sb.append("]");
			log(sb.toString());
		}
		log("Expected: Head 0: [0, -9999, -9998, -9997, -9996]");
		log("          Head 1: [10, -9989, -9988, -9987, -9986]");
		log("          Head 2: [20, -9979, -9978, -9977, -9976]");

		// Now test with traverseEach (the actual attention shape)
		log("\n=== Step 4: Test with traverseEach() shape ===");

		TraversalPolicy attentionShape = shape(heads, seqLen).traverseEach();
		log("attentionShape: " + attentionShape + ", traverseEach: " + attentionShape.getTraversalAxis());
		log("attentionShape.item(): " + attentionShape.item());

		// Recreate the exact pattern from attention code
		Model traverseAddModel = new Model(attentionShape);
		traverseAddModel.add(layer("add_mask_traverse", attentionShape, attentionShape,
			in -> add(c(in).reshape(shape(heads, seqLen)), causalMask).reshape(attentionShape)));
		PackedCollection traverseMaskedOutput = traverseAddModel.compile().forward(attnScores);

		log("\nAfter adding mask (traverseEach shape):");
		for (int h = 0; h < heads; h++) {
			sb = new StringBuilder(String.format("  Head %d: [", h));
			for (int p = 0; p < seqLen; p++) {
				if (p > 0) sb.append(", ");
				double val = traverseMaskedOutput.toDouble(h * seqLen + p);
				sb.append(String.format("%.0f", val));
			}
			sb.append("]");
			log(sb.toString());
		}
		log("Expected: Same as Step 3 - each head position 0 unmasked, positions 1+ masked");

		// Check for the specific bug pattern
		boolean bugDetected = false;
		for (int h = 1; h < heads; h++) {
			double pos0Value = traverseMaskedOutput.toDouble(h * seqLen);
			if (pos0Value < -1000) {
				log(String.format("\n*** BUG DETECTED: Head %d position 0 is masked (value=%.0f) when it should not be! ***", h, pos0Value));
				bugDetected = true;
			}
		}

		if (!bugDetected) {
			log("\n[PASS] All heads have correct mask pattern");
		}
	}
}
