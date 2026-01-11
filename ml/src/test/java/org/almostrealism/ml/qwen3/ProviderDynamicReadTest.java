package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test to verify if p(collection) reads data dynamically at runtime
 * or if it captures data at compile time.
 *
 * This test creates a simple computation that reads from an external collection
 * via p(), then modifies the collection between forward passes to see if the
 * changes are reflected in the output.
 */
public class ProviderDynamicReadTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testProviderDynamicRead() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/provider_dynamic_read.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  Provider Dynamic Read Test");
		log("=".repeat(70) + "\n");

		// Create external data collection
		PackedCollection externalData = new PackedCollection(shape(4));
		externalData.setMem(0, 1.0);
		externalData.setMem(1, 2.0);
		externalData.setMem(2, 3.0);
		externalData.setMem(3, 4.0);

		log("Initial external data: " + formatArray(externalData));

		// Build a simple model that reads from external data via p()
		// Output = input + externalData (element-wise)
		Model model = new Model(shape(4));
		SequentialBlock block = new SequentialBlock(shape(4));

		// Add external data to input
		block.add(layer("add_external", shape(4), shape(4),
			input -> add(input, c(p(externalData)))));

		model.add(block);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create input
		PackedCollection input = new PackedCollection(shape(4));
		input.setMem(0, 10.0);
		input.setMem(1, 20.0);
		input.setMem(2, 30.0);
		input.setMem(3, 40.0);
		log("Input: " + formatArray(input));

		// Forward pass 1 - external data is [1,2,3,4]
		log("\n--- Forward Pass 1 ---");
		log("External data: " + formatArray(externalData));
		PackedCollection output1 = compiled.forward(input);
		log("Output: " + formatArray(output1));
		log("Expected: [11, 22, 33, 44]");

		boolean pass1Correct = Math.abs(output1.toDouble(0) - 11.0) < 0.001 &&
		                       Math.abs(output1.toDouble(1) - 22.0) < 0.001 &&
		                       Math.abs(output1.toDouble(2) - 33.0) < 0.001 &&
		                       Math.abs(output1.toDouble(3) - 44.0) < 0.001;
		log("Pass 1 correct: " + (pass1Correct ? "[PASS]" : "[FAIL]"));

		// Modify external data
		log("\n--- Modifying external data ---");
		externalData.setMem(0, 100.0);
		externalData.setMem(1, 200.0);
		externalData.setMem(2, 300.0);
		externalData.setMem(3, 400.0);
		log("New external data: " + formatArray(externalData));

		// Forward pass 2 - external data is now [100,200,300,400]
		log("\n--- Forward Pass 2 ---");
		PackedCollection output2 = compiled.forward(input);
		log("Output: " + formatArray(output2));
		log("Expected (if dynamic): [110, 220, 330, 440]");
		log("Expected (if baked): [11, 22, 33, 44]");

		boolean pass2Dynamic = Math.abs(output2.toDouble(0) - 110.0) < 0.001 &&
		                       Math.abs(output2.toDouble(1) - 220.0) < 0.001 &&
		                       Math.abs(output2.toDouble(2) - 330.0) < 0.001 &&
		                       Math.abs(output2.toDouble(3) - 440.0) < 0.001;

		boolean pass2Baked = Math.abs(output2.toDouble(0) - 11.0) < 0.001 &&
		                     Math.abs(output2.toDouble(1) - 22.0) < 0.001 &&
		                     Math.abs(output2.toDouble(2) - 33.0) < 0.001 &&
		                     Math.abs(output2.toDouble(3) - 44.0) < 0.001;

		log("\n=== ANALYSIS ===");
		if (pass2Dynamic) {
			log("[DYNAMIC] Provider reads data at RUNTIME - changes are reflected!");
			log("This is the EXPECTED behavior for KV cache.");
		} else if (pass2Baked) {
			log("[BAKED] Provider data was captured at COMPILE TIME!");
			log("This is the BUG - changes to external data are NOT reflected.");
			log("The KV cache writes happen, but attentionKeys reads stale data.");
		} else {
			log("[UNEXPECTED] Output doesn't match either expectation:");
			log("  Dynamic expected: [110, 220, 330, 440]");
			log("  Baked expected: [11, 22, 33, 44]");
			log("  Actual: " + formatArray(output2));
		}

		log("\n=== Test Complete ===");
	}

	@Test
	public void testMapWithDynamicProvider() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/map_dynamic_provider.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  Map with Dynamic Provider Test");
		log("=".repeat(70) + "\n");

		// Create external matrix (2x2)
		PackedCollection matrix = new PackedCollection(shape(2, 2));
		matrix.setMem(0, 1.0);  // [0,0]
		matrix.setMem(1, 2.0);  // [0,1]
		matrix.setMem(2, 3.0);  // [1,0]
		matrix.setMem(3, 4.0);  // [1,1]

		log("Initial matrix:");
		log("  [" + matrix.toDouble(0) + ", " + matrix.toDouble(1) + "]");
		log("  [" + matrix.toDouble(2) + ", " + matrix.toDouble(3) + "]");

		// Build model that uses traverse().map() pattern like attentionKeys
		// This is similar to: traverse(1, expandedKeys).map(v -> v.multiply(input))
		Model model = new Model(shape(2));
		SequentialBlock block = new SequentialBlock(shape(2));

		// Multiply input by matrix rows (like Q @ K^T)
		// traverse(1, matrix) gives us traversal over rows
		// map(row -> row.multiply(input)) multiplies each row element-wise with input
		// traverse(1).sum() sums along the row to get dot product
		// reshape flattens the result
		block.add(layer("matmul", shape(2), shape(2),
			input -> traverse(1, p(matrix)).map(row -> row.multiply(input)).traverse(1).sum().reshape(shape(2))));

		model.add(block);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create input vector [1, 1]
		PackedCollection input = new PackedCollection(shape(2));
		input.setMem(0, 1.0);
		input.setMem(1, 1.0);
		log("Input: [" + input.toDouble(0) + ", " + input.toDouble(1) + "]");

		// Forward pass 1
		// Output[0] = input[0]*mat[0,0] + input[1]*mat[0,1] = 1*1 + 1*2 = 3
		// Output[1] = input[0]*mat[1,0] + input[1]*mat[1,1] = 1*3 + 1*4 = 7
		log("\n--- Forward Pass 1 ---");
		PackedCollection output1 = compiled.forward(input);
		log("Output: [" + output1.toDouble(0) + ", " + output1.toDouble(1) + "]");
		log("Expected: [3, 7]");

		// Modify matrix
		log("\n--- Modifying matrix ---");
		matrix.setMem(0, 10.0);  // [0,0]
		matrix.setMem(1, 20.0);  // [0,1]
		matrix.setMem(2, 30.0);  // [1,0]
		matrix.setMem(3, 40.0);  // [1,1]
		log("New matrix:");
		log("  [" + matrix.toDouble(0) + ", " + matrix.toDouble(1) + "]");
		log("  [" + matrix.toDouble(2) + ", " + matrix.toDouble(3) + "]");

		// Forward pass 2
		// If dynamic: Output = [10+20, 30+40] = [30, 70]
		// If baked: Output = [3, 7]
		log("\n--- Forward Pass 2 ---");
		PackedCollection output2 = compiled.forward(input);
		log("Output: [" + output2.toDouble(0) + ", " + output2.toDouble(1) + "]");
		log("Expected (if dynamic): [30, 70]");
		log("Expected (if baked): [3, 7]");

		boolean isDynamic = Math.abs(output2.toDouble(0) - 30.0) < 0.001 &&
		                    Math.abs(output2.toDouble(1) - 70.0) < 0.001;
		boolean isBaked = Math.abs(output2.toDouble(0) - 3.0) < 0.001 &&
		                  Math.abs(output2.toDouble(1) - 7.0) < 0.001;

		log("\n=== ANALYSIS ===");
		if (isDynamic) {
			log("[DYNAMIC] traverse().map() reads data at RUNTIME!");
		} else if (isBaked) {
			log("[BAKED] traverse().map() captures data at COMPILE TIME!");
			log("This explains why attentionKeys doesn't see cache updates.");
		} else {
			log("[UNEXPECTED] Output doesn't match either expectation.");
		}

		log("\n=== Test Complete ===");
	}

	private String formatArray(PackedCollection c) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < c.getShape().getTotalSize(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.2f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
