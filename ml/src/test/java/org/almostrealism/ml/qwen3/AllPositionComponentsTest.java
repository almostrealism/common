package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.graph.CollectionReceptor;
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
 * Test combining all position-dependent components:
 * - ropeRotation for Q and K
 * - into() for K and V caches
 * - greaterThan for causal mask
 */
public class AllPositionComponentsTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testAllPositionComponents() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/all_position_components_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  ALL POSITION-DEPENDENT COMPONENTS TEST");
		log("=".repeat(70) + "\n");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;
		int dim = heads * headSize;

		// Create position collection - shared by all components
		PackedCollection position = new PackedCollection(1);

		// CRITICAL: Use DynamicCollectionProducer
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create RoPE frequency tensor
		double theta = 10000.0;
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
				double angle = pos * freq;
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}

		// Create caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, dim));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, dim));
		keyCache.clear();
		valueCache.clear();

		log("Building model with all position components...");

		// Build model
		var ropeShape = shape(heads, freqDim, 2);
		var flatShape = shape(dim);
		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(ropeShape);

		// 1. RoPE rotation (uses position)
		CellularLayer ropeLayer = ropeRotation(ropeShape, freqCis, dynamicPosition);
		model.add(ropeLayer);

		// 2. Reshape for cache
		model.add(reshape(ropeShape, flatShape));

		// 3. Cache write (uses position) - using receptor
		CollectionReceptor cacheReceptor = into(keyCache.reshape(shape(seqLen, dim)), dynamicPosition);
		model.lastBlock().getForward().setReceptor(protein -> cacheReceptor.push(protein));

		// 4. Reshape to attention shape
		model.add(reshape(flatShape, shape(heads, 1, seqLen)));

		// 5. Causal mask (uses position)
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow = greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		model.add(layer("causal_mask", attentionShape, attentionShape,
			input -> add(input, causalMask).reshape(attentionShape)));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create input
		PackedCollection input = new PackedCollection(ropeShape);
		for (int h = 0; h < heads; h++) {
			for (int f = 0; f < freqDim; f++) {
				input.setMem((h * freqDim + f) * 2, 1.0);
				input.setMem((h * freqDim + f) * 2 + 1, 0.0);
			}
		}

		log("Input (1+0i): [" + input.toDouble(0) + ", " + input.toDouble(1) + ", ...]");

		// Test at position 0
		position.setMem(0, 0.0);
		PackedCollection result0 = compiled.forward(input);

		log("\n--- Position 0 ---");
		log("  RoPE output head 0: from cache row 0: [" +
			keyCache.toDouble(0) + ", " + keyCache.toDouble(1) + ", ...]");
		log("  Causal mask in output: [" + result0.toDouble(0) + ", " + result0.toDouble(1) + ", ...]");

		// Save values
		double cache0_0 = keyCache.toDouble(0);
		double mask0_1 = result0.toDouble(1);

		// Clear cache
		keyCache.clear();

		// Test at position 3
		position.setMem(0, 3.0);
		PackedCollection result3 = compiled.forward(input);

		log("\n--- Position 3 ---");
		log("  RoPE output head 0: from cache row 0: [" +
			keyCache.toDouble(0) + ", " + keyCache.toDouble(1) + ", ...]");
		log("  RoPE output head 0: from cache row 3: [" +
			keyCache.toDouble(3*dim) + ", " + keyCache.toDouble(3*dim+1) + ", ...]");
		log("  Causal mask in output: [" + result3.toDouble(0) + ", " + result3.toDouble(1) + ", ...]");

		// Check:
		// 1. Cache at pos 0 should be empty, pos 3 should have data
		boolean cacheRow0Empty = Math.abs(keyCache.toDouble(0)) < 0.001;
		boolean cacheRow3HasData = Math.abs(keyCache.toDouble(3*dim)) > 0.001;

		// 2. Causal mask should differ
		double mask3_1 = result3.toDouble(1);
		boolean maskDifferent = Math.abs(mask0_1 - mask3_1) > 0.001;

		log("\n--- Results ---");
		log("  Cache row 0 empty at pos 3: " + cacheRow0Empty);
		log("  Cache row 3 has data at pos 3: " + cacheRow3HasData);
		log("  Causal mask differs: " + maskDifferent + " (pos0=" + mask0_1 + ", pos3=" + mask3_1 + ")");

		boolean allPassed = cacheRow0Empty && cacheRow3HasData && maskDifferent;

		if (allPassed) {
			log("\nSUCCESS: All position-dependent components work correctly!");
		} else {
			log("\nFAILURE: Some components not respecting position!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("Cache should be written at correct position", cacheRow0Empty && cacheRow3HasData);
		Assert.assertTrue("Causal mask should differ between positions", maskDifferent);
	}
}
