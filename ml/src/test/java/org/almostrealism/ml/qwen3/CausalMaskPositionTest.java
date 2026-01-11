package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test to verify causal mask with dynamic position.
 */
public class CausalMaskPositionTest extends TestSuiteBase implements AttentionFeatures, LayerFeatures, ConsoleFeatures {

	@Test
	public void testCausalMaskWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/causal_mask_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  CAUSAL MASK WITH DYNAMIC POSITION TEST");
		log("=".repeat(70) + "\n");

		int seqLen = 5;

		// Create position collection
		PackedCollection position = new PackedCollection(1);

		// Create dynamic position producer
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create the causal mask computation (same as in attention method)
		// mask[i] = -10000 if i > position, else 0
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow = greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);

		Evaluable<PackedCollection> eval = maskRow.get();

		// Test at position 0 - only index 0 should be visible (mask = [0, -10000, -10000, -10000, -10000])
		position.setMem(0, 0.0);
		PackedCollection mask0 = eval.evaluate();
		log("Position = 0:");
		log("  Mask: [" + mask0.toDouble(0) + ", " + mask0.toDouble(1) + ", " +
			mask0.toDouble(2) + ", " + mask0.toDouble(3) + ", " + mask0.toDouble(4) + "]");
		log("  Expected: [0, -10000, -10000, -10000, -10000]");

		// Test at position 2 - indices 0,1,2 should be visible (mask = [0, 0, 0, -10000, -10000])
		position.setMem(0, 2.0);
		PackedCollection mask2 = eval.evaluate();
		log("\nPosition = 2:");
		log("  Mask: [" + mask2.toDouble(0) + ", " + mask2.toDouble(1) + ", " +
			mask2.toDouble(2) + ", " + mask2.toDouble(3) + ", " + mask2.toDouble(4) + "]");
		log("  Expected: [0, 0, 0, -10000, -10000]");

		// Verify
		boolean pos0Correct = (mask0.toDouble(0) == 0 && mask0.toDouble(1) == -10000);
		boolean pos2Correct = (mask2.toDouble(0) == 0 && mask2.toDouble(2) == 0 && mask2.toDouble(3) == -10000);

		if (pos0Correct && pos2Correct) {
			log("\nSUCCESS: Causal mask respects dynamic position!");
		} else {
			log("\nFAILURE: Causal mask does NOT respect dynamic position!");
			log("  Position 0 correct: " + pos0Correct);
			log("  Position 2 correct: " + pos2Correct);
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("Mask at position 0 should be [0, -10000, ...]", pos0Correct);
		Assert.assertTrue("Mask at position 2 should be [0, 0, 0, -10000, ...]", pos2Correct);
	}
}
