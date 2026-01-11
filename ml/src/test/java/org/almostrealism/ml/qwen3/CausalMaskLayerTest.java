package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test to verify that causal mask layer respects dynamic position
 * when used inside a compiled model.
 */
public class CausalMaskLayerTest extends TestSuiteBase implements AttentionFeatures, LayerFeatures, ConsoleFeatures {

	@Test
	public void testCausalMaskLayerWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/causal_mask_layer_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  CAUSAL MASK LAYER WITH DYNAMIC POSITION TEST");
		log("=".repeat(70) + "\n");

		int heads = 2;
		int seqLen = 5;

		// Create position collection
		PackedCollection position = new PackedCollection(1);

		// Create dynamic position producer
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create causal mask computation - same as in attention()
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		log("Created causal mask producer");

		// Create a simple model that just adds the causal mask to input
		var inputShape = shape(heads, 1, seqLen);
		Model model = new Model(inputShape);
		// Use the actual attention shape that matches what attention() uses
		var attentionShape = shape(heads, seqLen).traverseEach();  // (heads, 1, seqLen)
		model.add(layer("causal_mask", attentionShape, attentionShape,
			input -> add(input, causalMask).reshape(attentionShape)));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create input (all zeros)
		PackedCollection input = new PackedCollection(inputShape);
		input.clear();

		log("Input (all zeros): [" + input.toDouble(0) + ", " + input.toDouble(1) + ", ...]");

		// Test at position 0 - mask should be [0, -10000, -10000, -10000, -10000]
		position.setMem(0, 0.0);
		PackedCollection result0 = compiled.forward(input);
		log("\nPosition = 0:");
		log("  Output head 0: [" + result0.toDouble(0) + ", " + result0.toDouble(1) + ", " +
			result0.toDouble(2) + ", " + result0.toDouble(3) + ", " + result0.toDouble(4) + "]");
		log("  Expected: [0, -10000, -10000, -10000, -10000]");

		// Save values
		double r0_0 = result0.toDouble(0);
		double r0_1 = result0.toDouble(1);

		// Test at position 2 - mask should be [0, 0, 0, -10000, -10000]
		position.setMem(0, 2.0);
		PackedCollection result2 = compiled.forward(input);
		log("\nPosition = 2:");
		log("  Output head 0: [" + result2.toDouble(0) + ", " + result2.toDouble(1) + ", " +
			result2.toDouble(2) + ", " + result2.toDouble(3) + ", " + result2.toDouble(4) + "]");
		log("  Expected: [0, 0, 0, -10000, -10000]");

		// Verify
		boolean pos0Correct = (r0_0 == 0 && r0_1 == -10000);
		boolean pos2Correct = (result2.toDouble(0) == 0 && result2.toDouble(2) == 0 && result2.toDouble(3) == -10000);
		boolean different = result2.toDouble(1) != r0_1;

		log("\nResults:");
		log("  Position 0 mask correct: " + pos0Correct);
		log("  Position 2 mask correct: " + pos2Correct);
		log("  Outputs differ between positions: " + different);

		if (different && pos0Correct && pos2Correct) {
			log("\nSUCCESS: Causal mask layer respects dynamic position!");
		} else {
			log("\nFAILURE: Causal mask layer does NOT respect dynamic position!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("Position should affect causal mask output", different);
	}
}
