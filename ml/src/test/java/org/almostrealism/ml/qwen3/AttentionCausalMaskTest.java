package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test the EXACT causal mask pattern used in AttentionFeatures.attention()
 * to verify if position is being evaluated dynamically.
 */
public class AttentionCausalMaskTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testAttentionCausalMaskPattern() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/attention_causal_mask_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Attention Causal Mask Pattern Test");
		log("===================================================\n");

		int seqLen = 10;
		int inputSize = 4;

		PackedCollection position = new PackedCollection(1);

		// Create model using EXACT same pattern as AttentionFeatures.attention() lines 484-493
		Model model = new Model(shape(inputSize));
		model.add(layer("causal_mask", shape(inputSize), shape(seqLen),
				input -> {
					// This is EXACTLY what attention() does:
					CollectionProducer indices = integers(0, seqLen);
					CollectionProducer maskRow =
							greaterThan(indices, p(position), c(-10000.0), c(0.0), false);
					return maskRow;
				}));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		PackedCollection input = new PackedCollection(shape(inputSize));
		for (int i = 0; i < inputSize; i++) {
			input.setMem(i, 1.0);
		}

		log("\n=== Testing Attention Causal Mask Pattern ===\n");

		double[][] outputs = new double[5][seqLen];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);

			PackedCollection output = compiled.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Position %d: [", pos));
			for (int i = 0; i < seqLen; i++) {
				outputs[pos][i] = output.toDouble(i);
				if (i > 0) sb.append(", ");
				sb.append(String.format("%.0f", outputs[pos][i]));
			}
			sb.append("]");
			log(sb.toString());
		}

		// Check if outputs differ between positions
		log("\n=== Analysis ===\n");

		boolean allIdentical = true;
		for (int pos = 1; pos < 5; pos++) {
			for (int i = 0; i < seqLen; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 1e-6) {
					allIdentical = false;
					break;
				}
			}
		}

		// Check expected mask pattern:
		// Position 0: mask[i] = -10000 if i > 0, else 0 -> [0, -10000, -10000, ...]
		// Position 1: mask[i] = -10000 if i > 1, else 0 -> [0, 0, -10000, ...]
		log("Expected mask pattern:");
		for (int pos = 0; pos < 5; pos++) {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Position %d: [", pos));
			for (int i = 0; i < seqLen; i++) {
				if (i > 0) sb.append(", ");
				sb.append(i > pos ? "-10000" : "0");
			}
			sb.append("]");
			log(sb.toString());
		}

		log("");
		if (allIdentical) {
			log("[FAIL] All mask outputs identical - position NOT affecting computation!");
		} else {
			log("[PASS] Mask outputs differ between positions - position IS working!");
		}

		log("\n=== Test Complete ===");
	}
}
