package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.graph.Cell;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test to verify DynamicCollectionProducer values are read at runtime.
 */
public class DynamicPositionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testDynamicProducerReadsAtRuntime() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/dynamic_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  DYNAMIC PRODUCER TEST");
		log("=".repeat(70) + "\n");

		// Create a simple test: x * position
		// If position is read dynamically, changing it should change the output
		PackedCollection position = new PackedCollection(1);
		PackedCollection input = new PackedCollection(shape(4));
		input.setMem(0, 1.0);
		input.setMem(1, 2.0);
		input.setMem(2, 3.0);
		input.setMem(3, 4.0);

		// Create a dynamic producer for position
		DynamicCollectionProducer dynamicPos =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create computation: input * position (broadcast)
		CollectionProducer computation = c(p(input)).multiply(c(dynamicPos).repeat(4));
		Evaluable<PackedCollection> eval = computation.get();

		// Test with position = 2.0
		position.setMem(0, 2.0);
		PackedCollection result1 = eval.evaluate();
		log("Position = 2.0:");
		log("  Result: [" + result1.toDouble(0) + ", " + result1.toDouble(1) +
			", " + result1.toDouble(2) + ", " + result1.toDouble(3) + "]");
		log("  Expected: [2, 4, 6, 8]");

		// Test with position = 3.0
		position.setMem(0, 3.0);
		PackedCollection result2 = eval.evaluate();
		log("\nPosition = 3.0:");
		log("  Result: [" + result2.toDouble(0) + ", " + result2.toDouble(1) +
			", " + result2.toDouble(2) + ", " + result2.toDouble(3) + "]");
		log("  Expected: [3, 6, 9, 12]");

		// Check if results are different
		boolean different = Math.abs(result1.toDouble(0) - result2.toDouble(0)) > 0.001;

		if (different) {
			log("\nSUCCESS: DynamicCollectionProducer values are read at runtime!");
		} else {
			log("\nFAILURE: DynamicCollectionProducer values are NOT read at runtime!");
			log("This means the value was embedded at compile time.");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("DynamicCollectionProducer should read values at runtime", different);
	}

	@Test
	public void testSubsetWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/dynamic_subset_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  DYNAMIC SUBSET TEST");
		log("=".repeat(70) + "\n");

		// Create a 2D array: [[1,2,3], [4,5,6], [7,8,9], [10,11,12]]
		PackedCollection data = new PackedCollection(shape(4, 3));
		for (int i = 0; i < 12; i++) {
			data.setMem(i, i + 1);
		}
		log("Data matrix:");
		for (int r = 0; r < 4; r++) {
			log("  Row " + r + ": [" + data.toDouble(r*3) + ", " +
				data.toDouble(r*3+1) + ", " + data.toDouble(r*3+2) + "]");
		}

		// Create position that will select a row
		PackedCollection position = new PackedCollection(shape(2)); // [row, 0]
		position.setMem(0, 0.0);  // Start with row 0
		position.setMem(1, 0.0);

		// Create dynamic producer for position
		DynamicCollectionProducer dynamicPos =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create subset computation to extract one row
		CollectionProducer subsetComp = subset(shape(1, 3), c(p(data)), dynamicPos);
		Evaluable<PackedCollection> eval = subsetComp.get();

		// Test with row 0
		position.setMem(0, 0.0);
		PackedCollection row0 = eval.evaluate();
		log("\nPosition = row 0:");
		log("  Result: [" + row0.toDouble(0) + ", " + row0.toDouble(1) + ", " + row0.toDouble(2) + "]");
		log("  Expected: [1, 2, 3]");

		// Test with row 2
		position.setMem(0, 2.0);
		PackedCollection row2 = eval.evaluate();
		log("\nPosition = row 2:");
		log("  Result: [" + row2.toDouble(0) + ", " + row2.toDouble(1) + ", " + row2.toDouble(2) + "]");
		log("  Expected: [7, 8, 9]");

		// Check if results are different
		boolean different = Math.abs(row0.toDouble(0) - row2.toDouble(0)) > 0.001;

		if (different) {
			log("\nSUCCESS: Subset with DynamicCollectionProducer works at runtime!");
		} else {
			log("\nFAILURE: Subset position is NOT read at runtime!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("Subset with DynamicCollectionProducer should work at runtime", different);
	}

	@Test
	public void testDynamicPositionInLayer() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/dynamic_layer_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  DYNAMIC POSITION IN LAYER TEST");
		log("=".repeat(70) + "\n");

		// Create a simple layer that uses position to index into RoPE frequencies
		// Similar to how ropeRotation uses position

		int headSize = 8;  // Small for testing
		int freqDim = headSize / 2;  // 4
		int seqLen = 10;

		// Create simple frequency matrix: each row is [pos*1, pos*2, pos*3, pos*4] * 2 (for cos/sin)
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				// Just use pos * (i+1) for cos, pos * (i+1) * 2 for sin
				freqCis.setMem(pos * freqDim * 2 + i * 2, pos * (i + 1));
				freqCis.setMem(pos * freqDim * 2 + i * 2 + 1, pos * (i + 1) * 2);
			}
		}

		log("Freq tensor row 0: [" +
			freqCis.toDouble(0) + ", " + freqCis.toDouble(1) + ", " +
			freqCis.toDouble(2) + ", " + freqCis.toDouble(3) + "...]");
		log("Freq tensor row 3: [" +
			freqCis.toDouble(3 * freqDim * 2) + ", " + freqCis.toDouble(3 * freqDim * 2 + 1) + ", " +
			freqCis.toDouble(3 * freqDim * 2 + 2) + ", " + freqCis.toDouble(3 * freqDim * 2 + 3) + "...]");

		// Create position
		PackedCollection position = new PackedCollection(1);

		// Create dynamic producer
		DynamicCollectionProducer dynamicPos =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Build a layer that extracts a row from freqCis based on position
		// This mimics what ropeRotation does: subset(shape(1, headSize, 2), c(p(weights)), pad(shape(3), position, 0))
		CollectionProducer padded = pad(shape(3), dynamicPos, 0);
		CollectionProducer subset = subset(shape(1, freqDim, 2), c(p(freqCis)), padded);

		// Get and test
		Evaluable<PackedCollection> eval = subset.get();

		// Test at position 0
		position.setMem(0, 0.0);
		PackedCollection row0 = eval.evaluate();
		log("\nPosition = 0:");
		log("  First 4 values: [" + row0.toDouble(0) + ", " + row0.toDouble(1) + ", " +
			row0.toDouble(2) + ", " + row0.toDouble(3) + "]");
		log("  Expected: [0, 0, 0, 0] (row 0)");

		// Test at position 3
		position.setMem(0, 3.0);
		PackedCollection row3 = eval.evaluate();
		log("\nPosition = 3:");
		log("  First 4 values: [" + row3.toDouble(0) + ", " + row3.toDouble(1) + ", " +
			row3.toDouble(2) + ", " + row3.toDouble(3) + "]");
		log("  Expected: [3, 6, 6, 12] (row 3)");

		// Check if results are different
		boolean different = Math.abs(row0.toDouble(0) - row3.toDouble(0)) > 0.001 ||
			Math.abs(row0.toDouble(2) - row3.toDouble(2)) > 0.001;

		if (different) {
			log("\nSUCCESS: Layer with dynamic position works!");
		} else {
			log("\nFAILURE: Layer position is NOT read at runtime!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("Layer with dynamic position should work at runtime", different);
	}

	@Test
	public void testDynamicPositionInCompiledModel() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/dynamic_compiled_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  DYNAMIC POSITION IN COMPILED MODEL TEST");
		log("=".repeat(70) + "\n");

		// Create a simple model that uses position to scale input
		int dim = 4;
		int seqLen = 10;

		// Create scale factors: position-dependent scaling
		PackedCollection scaleFactors = new PackedCollection(shape(seqLen, dim));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int d = 0; d < dim; d++) {
				scaleFactors.setMem(pos * dim + d, (pos + 1) * (d + 1));
			}
		}
		log("Scale factors row 0: [1, 2, 3, 4]");
		log("Scale factors row 2: [3, 6, 9, 12]");

		// Create position
		PackedCollection position = new PackedCollection(1);

		// Create dynamic producer
		Producer<PackedCollection> dynamicPos =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Build a layer that extracts a scale row based on position
		// and multiplies input by it
		CellularLayer scaleLayer = layer("positionScale", shape(dim), shape(dim), input -> {
			CollectionProducer padded = pad(shape(2), dynamicPos, 0);
			CollectionProducer scaleRow = subset(shape(1, dim), c(p(scaleFactors)), padded).reshape(dim);
			return c(input).multiply(scaleRow);
		});

		// Build model
		Model model = new Model(shape(dim));
		model.add(scaleLayer);

		// Compile
		CompiledModel compiled = model.compile();

		// Create input [1, 1, 1, 1]
		PackedCollection input = new PackedCollection(shape(dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 1.0);
		}

		// Test at position 0
		position.setMem(0, 0.0);
		PackedCollection result0 = compiled.forward(input);
		// Save values immediately (buffer might be reused)
		double r0v0 = result0.toDouble(0);
		double r0v1 = result0.toDouble(1);
		double r0v2 = result0.toDouble(2);
		double r0v3 = result0.toDouble(3);
		log("\nPosition = 0, input = [1,1,1,1]:");
		log("  Result: [" + r0v0 + ", " + r0v1 + ", " + r0v2 + ", " + r0v3 + "]");
		log("  Expected: [1, 2, 3, 4]");

		// Test at position 2
		position.setMem(0, 2.0);
		PackedCollection result2 = compiled.forward(input);
		double r2v0 = result2.toDouble(0);
		double r2v1 = result2.toDouble(1);
		log("\nPosition = 2, input = [1,1,1,1]:");
		log("  Result: [" + r2v0 + ", " + r2v1 + ", " + result2.toDouble(2) + ", " + result2.toDouble(3) + "]");
		log("  Expected: [3, 6, 9, 12]");

		// Check if results are different
		log("\nComparing: pos0[0]=" + r0v0 + " vs pos2[0]=" + r2v0);
		boolean different = Math.abs(r0v0 - r2v0) > 0.001;

		if (different) {
			log("\nSUCCESS: CompiledModel with dynamic position works!");
		} else {
			log("\nFAILURE: CompiledModel position is NOT read at runtime!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("CompiledModel with dynamic position should work at runtime", different);
	}
}
