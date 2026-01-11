package org.almostrealism.ml.qwen3;

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
import org.junit.Test;

/**
 * Minimal test to verify position-dependent computation in compiled model.
 *
 * This test isolates the position issue by testing:
 * 1. A simple subset operation that depends on position
 * 2. Without all the complexity of attention, RoPE, etc.
 */
public class MinimalPositionTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testSubsetWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/minimal_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Minimal Position Test: Subset with Dynamic Position");
		log("===================================================\n");

		// Create a simple data collection with known values
		// Shape: (10, 4) - 10 rows of 4 values each
		PackedCollection data = new PackedCollection(shape(10, 4));
		for (int row = 0; row < 10; row++) {
			for (int col = 0; col < 4; col++) {
				data.setMem(row * 4 + col, row * 10 + col);  // Values: 0-3, 10-13, 20-23, ...
			}
		}

		log("Data collection:");
		for (int row = 0; row < 10; row++) {
			log(String.format("  Row %d: [%.0f, %.0f, %.0f, %.0f]",
					row,
					data.toDouble(row * 4),
					data.toDouble(row * 4 + 1),
					data.toDouble(row * 4 + 2),
					data.toDouble(row * 4 + 3)));
		}

		// Create position collection (needs 2 values for 2D data: row, col)
		PackedCollection position = new PackedCollection(2);
		position.setMem(0, 0.0);  // row
		position.setMem(1, 0.0);  // col (always 0, we want full row)

		// Create a simple model that:
		// 1. Takes input shape (4)
		// 2. Uses subset to read from data at position
		// 3. Adds the subset to the input
		log("\nCreating model with position-dependent subset...");

		// Build a model that uses position to select a row from data
		Model model = new Model(shape(4));

		// Create a layer that adds a position-dependent row from data
		CellularLayer subsetLayer = layer("positionSubset", shape(4), shape(4),
				input -> {
					// Read row at position from data (shape (1,4) -> flattened to (4))
					CollectionProducer row = subset(shape(1, 4), c(p(data)), p(position)).reshape(4);
					return add(input, row);
				});

		model.add(subsetLayer);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create simple input
		PackedCollection input = new PackedCollection(shape(4));
		input.setMem(0, 1.0);
		input.setMem(1, 1.0);
		input.setMem(2, 1.0);
		input.setMem(3, 1.0);

		log("\nInput: [1.0, 1.0, 1.0, 1.0]");

		// Test at different positions
		log("\n=== Testing at Different Positions ===\n");

		double[][] outputs = new double[5][4];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);  // Row index
			position.setMem(1, 0.0);           // Col always 0
			log("Position " + pos + ": position.toDouble(0) = " + position.toDouble(0));

			PackedCollection output = compiled.forward(input);

			log(String.format("  Output: [%.1f, %.1f, %.1f, %.1f]",
					output.toDouble(0), output.toDouble(1),
					output.toDouble(2), output.toDouble(3)));

			// Expected: input + data[pos] = [1,1,1,1] + [pos*10, pos*10+1, pos*10+2, pos*10+3]
			double[] expected = {1 + pos*10, 1 + pos*10+1, 1 + pos*10+2, 1 + pos*10+3};
			log(String.format("  Expected: [%.1f, %.1f, %.1f, %.1f]",
					expected[0], expected[1], expected[2], expected[3]));

			// Store for comparison
			for (int i = 0; i < 4; i++) {
				outputs[pos][i] = output.toDouble(i);
			}

			boolean match = true;
			for (int i = 0; i < 4; i++) {
				if (Math.abs(output.toDouble(i) - expected[i]) > 0.001) {
					match = false;
					break;
				}
			}

			if (match) {
				log("  [PASS] Output matches expected\n");
			} else {
				log("  [FAIL] Output does NOT match expected\n");
			}
		}

		// Check if outputs are different
		log("=== Checking if Position Affects Output ===\n");

		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			for (int i = 0; i < 4; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 0.001) {
					allSame = false;
					break;
				}
			}
		}

		if (allSame) {
			log("[FAIL] All outputs are identical - position is NOT affecting the compiled model!");
			log("This confirms the bug: position values are baked in at compile time.");
		} else {
			log("[PASS] Outputs differ between positions - position IS working correctly!");
		}

		log("\n=== Test Complete ===");
	}
}
