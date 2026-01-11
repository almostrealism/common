package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test to verify into() cache write with dynamic position.
 */
public class CachePositionTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testIntoCacheWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/cache_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  INTO CACHE WITH DYNAMIC POSITION TEST");
		log("=".repeat(70) + "\n");

		int seqLen = 10;
		int dim = 4;

		// Create a cache: (seqLen, dim) = (10, 4)
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();  // Zero initialize

		// Create position collection
		PackedCollection position = new PackedCollection(1);

		// Create dynamic position producer
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create the into receptor
		CollectionReceptor receptor = into(cache.reshape(shape(seqLen, dim)), dynamicPosition);

		// Create input data
		PackedCollection input = new PackedCollection(shape(dim));
		input.setMem(0, 1.0);
		input.setMem(1, 2.0);
		input.setMem(2, 3.0);
		input.setMem(3, 4.0);

		log("Input: [" + input.toDouble(0) + ", " + input.toDouble(1) + ", " +
			input.toDouble(2) + ", " + input.toDouble(3) + "]");

		// Write at position 0
		position.setMem(0, 0.0);
		OperationList op0 = new OperationList();
		op0.add(receptor.push(p(input)));
		op0.get().run();

		log("\nAfter write at position 0:");
		log("  Cache row 0: [" + cache.toDouble(0) + ", " + cache.toDouble(1) + ", " +
			cache.toDouble(2) + ", " + cache.toDouble(3) + "]");
		log("  Cache row 1: [" + cache.toDouble(dim) + ", " + cache.toDouble(dim+1) + ", " +
			cache.toDouble(dim+2) + ", " + cache.toDouble(dim+3) + "]");

		// Change input for second write
		input.setMem(0, 5.0);
		input.setMem(1, 6.0);
		input.setMem(2, 7.0);
		input.setMem(3, 8.0);

		log("\nNew input: [" + input.toDouble(0) + ", " + input.toDouble(1) + ", " +
			input.toDouble(2) + ", " + input.toDouble(3) + "]");

		// Write at position 2
		position.setMem(0, 2.0);
		OperationList op2 = new OperationList();
		op2.add(receptor.push(p(input)));
		op2.get().run();

		log("\nAfter write at position 2:");
		log("  Cache row 0: [" + cache.toDouble(0) + ", " + cache.toDouble(1) + ", " +
			cache.toDouble(2) + ", " + cache.toDouble(3) + "]");
		log("  Cache row 1: [" + cache.toDouble(dim) + ", " + cache.toDouble(dim+1) + ", " +
			cache.toDouble(dim+2) + ", " + cache.toDouble(dim+3) + "]");
		log("  Cache row 2: [" + cache.toDouble(2*dim) + ", " + cache.toDouble(2*dim+1) + ", " +
			cache.toDouble(2*dim+2) + ", " + cache.toDouble(2*dim+3) + "]");

		// Verify: row 0 should be [1,2,3,4], row 2 should be [5,6,7,8], row 1 should be zeros
		boolean row0Correct = Math.abs(cache.toDouble(0) - 1.0) < 0.001;
		boolean row1Empty = Math.abs(cache.toDouble(dim)) < 0.001;
		boolean row2Correct = Math.abs(cache.toDouble(2*dim) - 5.0) < 0.001;

		if (row0Correct && row1Empty && row2Correct) {
			log("\nSUCCESS: Cache write with dynamic position works correctly!");
		} else {
			log("\nFAILURE: Cache write is not respecting dynamic position!");
			log("  Row 0 correct (should be 1): " + row0Correct + " actual=" + cache.toDouble(0));
			log("  Row 1 empty (should be 0): " + row1Empty + " actual=" + cache.toDouble(dim));
			log("  Row 2 correct (should be 5): " + row2Correct + " actual=" + cache.toDouble(2*dim));
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("Row 0 should contain first input", row0Correct);
		Assert.assertTrue("Row 1 should be empty", row1Empty);
		Assert.assertTrue("Row 2 should contain second input", row2Correct);
	}
}
