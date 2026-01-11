package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.hardware.OperationList;
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
 * Test to verify multiple components using the same dynamic position.
 * This tests if the issue is with sharing the same DynamicCollectionProducer.
 */
public class CombinedPositionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testRopeAndCacheWithSamePosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/combined_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  COMBINED ROPE + CACHE WITH SAME POSITION TEST");
		log("=".repeat(70) + "\n");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;
		int dim = heads * headSize;

		// Create RoPE frequency tensor
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(10000.0, (2.0 * i) / headSize);
				double angle = pos * freq;
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}

		// Create cache
		PackedCollection cache = new PackedCollection(shape(seqLen, dim));
		cache.clear();

		// Create position collection - SINGLE instance shared
		PackedCollection position = new PackedCollection(1);

		// Create SINGLE dynamic position producer - to be shared
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Build model with ropeRotation
		var ropeShape = shape(heads, freqDim, 2);
		CellularLayer ropeLayer = ropeRotation(ropeShape, freqCis, dynamicPosition);

		// Also add cache write using the SAME position
		CollectionReceptor cacheReceptor = into(cache.reshape(shape(seqLen, dim)), dynamicPosition);

		// Build a sequential block with ropeRotation and reshape
		// Then add the cache receptor
		Model model = new Model(ropeShape);
		model.add(ropeLayer);
		// Reshape to match cache input size
		model.add(reshape(ropeShape, shape(dim)));
		// Wrap the receptor in a cell-compatible way
		model.lastBlock().getForward().setReceptor(protein -> cacheReceptor.push(protein));

		CompiledModel compiled = model.compile();

		// Create input
		PackedCollection input = new PackedCollection(ropeShape);
		for (int h = 0; h < heads; h++) {
			for (int f = 0; f < freqDim; f++) {
				input.setMem((h * freqDim + f) * 2, 1.0);
				input.setMem((h * freqDim + f) * 2 + 1, 0.0);
			}
		}

		log("Input (all 1+0i complex):");
		log("  [" + input.toDouble(0) + ", " + input.toDouble(1) + ", " +
			input.toDouble(2) + ", " + input.toDouble(3) + ", ...]");

		// Test at position 0
		position.setMem(0, 0.0);
		PackedCollection result0 = compiled.forward(input);
		double[] r0 = new double[dim];
		for (int i = 0; i < dim; i++) r0[i] = result0.toDouble(i);

		log("\nPosition = 0:");
		log("  RoPE output: [" + r0[0] + ", " + r0[1] + ", " + r0[2] + ", " + r0[3] + ", ...]");
		log("  Cache row 0: [" + cache.toDouble(0) + ", " + cache.toDouble(1) + ", " +
			cache.toDouble(2) + ", " + cache.toDouble(3) + ", ...]");

		// Save result0 before next forward call
		double r0_0 = result0.toDouble(0);
		double r0_1 = result0.toDouble(1);
		double cache0_0 = cache.toDouble(0);

		// Clear cache before next test
		cache.clear();

		// Test at position 3
		position.setMem(0, 3.0);
		PackedCollection result3 = compiled.forward(input);
		double[] r3 = new double[dim];
		for (int i = 0; i < dim; i++) r3[i] = result3.toDouble(i);

		log("\nPosition = 3:");
		log("  RoPE output: [" + r3[0] + ", " + r3[1] + ", " + r3[2] + ", " + r3[3] + ", ...]");
		log("  Cache row 0: [" + cache.toDouble(0) + ", " + cache.toDouble(1) + ", ...]");
		log("  Cache row 3: [" + cache.toDouble(3*dim) + ", " + cache.toDouble(3*dim+1) + ", ...]");

		// Check RoPE outputs differ
		log("\nComparing RoPE outputs:");
		log("  r0[0]=" + r0_0 + " vs r3[0]=" + r3[0] + " diff=" + (r0_0 - r3[0]));
		log("  r0[1]=" + r0_1 + " vs r3[1]=" + r3[1] + " diff=" + (r0_1 - r3[1]));

		boolean ropeDifferent = Math.abs(r0_0 - r3[0]) > 0.001 || Math.abs(r0_1 - r3[1]) > 0.001;

		// Check cache positions - at pos=3, data should be in row 3
		boolean cacheRow0Empty = Math.abs(cache.toDouble(0)) < 0.001;
		boolean cacheRow3HasData = Math.abs(cache.toDouble(3*dim)) > 0.001;

		log("\nResults:");
		log("  RoPE produces different output: " + ropeDifferent);
		log("  Cache row 0 empty (should be): " + cacheRow0Empty);
		log("  Cache row 3 has data (should be): " + cacheRow3HasData);

		if (ropeDifferent && cacheRow0Empty && cacheRow3HasData) {
			log("\nSUCCESS: Both RoPE and cache respect dynamic position!");
		} else {
			log("\nFAILURE: Position not working correctly!");
			if (!ropeDifferent) log("  - RoPE output is the SAME at different positions");
			if (!cacheRow0Empty) log("  - Cache row 0 has data when it shouldn't");
			if (!cacheRow3HasData) log("  - Cache row 3 is empty when it should have data");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("RoPE should produce different outputs", ropeDifferent);
		Assert.assertTrue("Cache row 0 should be empty at position 3", cacheRow0Empty);
		Assert.assertTrue("Cache row 3 should have data at position 3", cacheRow3HasData);
	}
}
