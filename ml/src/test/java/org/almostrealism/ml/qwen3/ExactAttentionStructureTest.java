package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test that exactly replicates the attention() method structure step by step
 * to find where position handling breaks down.
 */
public class ExactAttentionStructureTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testStep1_RopeInMainAfterBranches() throws Exception {
		log("\n=== Step 1: RoPE in main path after creating branches ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;
		int dim = heads * headSize;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		PackedCollection freqCis = createFreqCis(seqLen, freqDim, headSize);

		var ropeShape = shape(heads, freqDim, 2);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Create branches (like K and V in attention) - must add layers!
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("branch1", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("branch2", ropeShape, ropeShape, input -> multiply(input, scalar(3.0))));

		// Add RoPE to main path (like Q processing)
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);
		CompiledModel compiled = model.compile();

		boolean passed = testPositionDynamics(compiled, position, ropeShape);
		Assert.assertTrue("Step 1 should pass", passed);
	}

	@Test
	public void testStep2_RopeWithLayerBeforeBranch() throws Exception {
		log("\n=== Step 2: Layer before branching, then RoPE in main path ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		PackedCollection freqCis = createFreqCis(seqLen, freqDim, headSize);

		var ropeShape = shape(heads, freqDim, 2);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Add a layer BEFORE branching (like rmsnorm in attention)
		main.add(layer("pre_branch", ropeShape, ropeShape,
			input -> multiply(input, scalar(2.0))));

		// Create branches - must add layers!
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("branch1", ropeShape, ropeShape, input -> multiply(input, scalar(1.5))));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("branch2", ropeShape, ropeShape, input -> multiply(input, scalar(1.5))));

		// Add RoPE to main path
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);
		CompiledModel compiled = model.compile();

		boolean passed = testPositionDynamics(compiled, position, ropeShape);
		Assert.assertTrue("Step 2 should pass", passed);
	}

	@Test
	public void testStep3_BranchesWithRopeAndMain() throws Exception {
		log("\n=== Step 3: Branches have RoPE too (like K branch), main has RoPE (like Q) ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		PackedCollection freqCis = createFreqCis(seqLen, freqDim, headSize);

		var ropeShape = shape(heads, freqDim, 2);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Add layer before branching
		main.add(layer("pre_branch", ropeShape, ropeShape,
			input -> multiply(input, scalar(2.0))));

		// Create branches - branch1 has its own RoPE (like K in attention)
		SequentialBlock branch1 = main.branch();
		branch1.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("branch2", ropeShape, ropeShape, input -> multiply(input, scalar(1.5))));

		// Main also has RoPE (like Q in attention)
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);
		CompiledModel compiled = model.compile();

		boolean passed = testPositionDynamics(compiled, position, ropeShape);
		Assert.assertTrue("Step 3 should pass", passed);
	}

	@Test
	public void testStep4_BranchesWithCacheReceptors() throws Exception {
		log("\n=== Step 4: Branches have cache receptors (like K/V cache writes) ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;
		int dim = heads * headSize;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		PackedCollection freqCis = createFreqCis(seqLen, freqDim, headSize);

		// Create caches
		PackedCollection cache1 = new PackedCollection(shape(seqLen, dim));
		PackedCollection cache2 = new PackedCollection(shape(seqLen, dim));
		cache1.clear();
		cache2.clear();

		var ropeShape = shape(heads, freqDim, 2);
		var flatShape = shape(dim);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Add layer before branching
		main.add(layer("pre_branch", ropeShape, ropeShape,
			input -> multiply(input, scalar(2.0))));

		// Create branches with RoPE and cache receptors
		SequentialBlock branch1 = main.branch();
		branch1.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		branch1.add(reshape(ropeShape, flatShape));
		branch1.andThen(into(cache1.reshape(shape(seqLen, dim)), dynamicPosition));

		SequentialBlock branch2 = main.branch();
		branch2.add(reshape(ropeShape, flatShape));
		branch2.andThen(into(cache2.reshape(shape(seqLen, dim)), dynamicPosition));

		// Main also has RoPE
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);
		log("Compiling model...");
		CompiledModel compiled = model.compile();

		boolean passed = testPositionDynamics(compiled, position, ropeShape);

		// Also check cache
		log("Cache1 row 0: [" + cache1.toDouble(0) + ", " + cache1.toDouble(1) + ", ...]");

		Assert.assertTrue("Step 4 should pass", passed);
	}

	@Test
	public void testStep5_AddCausalMaskToMain() throws Exception {
		log("\n=== Step 5: Add causal mask after RoPE in main path ===");

		int heads = 2;
		int seqLen = 10;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Use an input shape that can be reshaped to attention shape
		var inputShape = shape(heads, 1, seqLen);
		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(inputShape);
		SequentialBlock main = new SequentialBlock(inputShape);

		// Create branches with layers
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("branch1", inputShape, inputShape, input -> multiply(input, scalar(1.5))));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("branch2", inputShape, inputShape, input -> multiply(input, scalar(1.5))));

		// Add causal mask (uses position) to main
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		main.add(layer("causal_mask", attentionShape, attentionShape,
			input -> add(input, causalMask).reshape(attentionShape)));

		model.add(main);
		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Test
		PackedCollection input = new PackedCollection(inputShape);
		for (int i = 0; i < inputShape.getTotalSize(); i++) {
			input.setMem(i, 1.0);
		}

		double[][] outputs = new double[5][heads * seqLen];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			PackedCollection result = compiled.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append("Position ").append(pos).append(": [");
			for (int i = 0; i < Math.min(6, result.getShape().getTotalSize()); i++) {
				if (i > 0) sb.append(", ");
				outputs[pos][i] = result.toDouble(i);
				sb.append(String.format("%.2f", outputs[pos][i]));
			}
			sb.append(", ...]");
			log(sb.toString());
		}

		boolean allSame = compareOutputs(outputs, 5, Math.min(heads * seqLen, 20));
		if (allSame) {
			log("[FAIL] Step 5 - outputs identical");
		} else {
			log("[PASS] Step 5 - outputs differ");
		}
		Assert.assertFalse("Step 5 should pass", allSame);
	}

	private PackedCollection createFreqCis(int seqLen, int freqDim, int headSize) {
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
		return freqCis;
	}

	private PackedCollection createInput(io.almostrealism.collect.TraversalPolicy shape) {
		PackedCollection input = new PackedCollection(shape);
		int size = shape.getTotalSize();
		for (int i = 0; i < size; i += 2) {
			input.setMem(i, 1.0);
			input.setMem(i + 1, 0.0);
		}
		return input;
	}

	private boolean testPositionDynamics(CompiledModel compiled, PackedCollection position,
										  io.almostrealism.collect.TraversalPolicy inputShape) {
		PackedCollection input = createInput(inputShape);
		int outputSize = inputShape.getTotalSize();
		double[][] outputs = new double[5][outputSize];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			PackedCollection result = compiled.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append("Position ").append(pos).append(": [");
			for (int i = 0; i < Math.min(4, outputSize); i++) {
				if (i > 0) sb.append(", ");
				outputs[pos][i] = result.toDouble(i);
				sb.append(String.format("%.4f", outputs[pos][i]));
			}
			sb.append(", ...]");
			log(sb.toString());
		}

		boolean allSame = compareOutputs(outputs, 5, outputSize);
		if (allSame) {
			log("[FAIL] Outputs identical - position baked in");
		} else {
			log("[PASS] Outputs differ - position is dynamic");
		}
		return !allSame;
	}

	private boolean compareOutputs(double[][] outputs, int numPositions, int size) {
		boolean allSame = true;
		for (int pos = 1; pos < numPositions; pos++) {
			for (int i = 0; i < size; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 0.001) {
					allSame = false;
					break;
				}
			}
		}
		return allSame;
	}
}
