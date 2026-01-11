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
 * Test that isolates position usage in main path only (no branches).
 * This mimics how attention() uses position for queries + causal mask.
 */
public class MainPathPositionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testMainPathWithRopeAndMask() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/main_path_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  MAIN PATH POSITION TEST (RoPE + Causal Mask - SEPARATE)");
		log("=".repeat(70) + "\n");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;
		int dim = heads * headSize;

		// Create position
		PackedCollection position = new PackedCollection(1);

		// Create dynamic position
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create RoPE frequencies
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

		log("Building model with just RoPE (uses position)...");

		// Build model with just RoPE
		var ropeShape = shape(heads, freqDim, 2);

		Model model = new Model(ropeShape);
		CellularLayer ropeLayer = ropeRotation(ropeShape, freqCis, dynamicPosition);
		model.add(ropeLayer);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Create input (1+0i complex for all heads)
		PackedCollection input = new PackedCollection(ropeShape);
		for (int h = 0; h < heads; h++) {
			for (int f = 0; f < freqDim; f++) {
				input.setMem((h * freqDim + f) * 2, 1.0);
				input.setMem((h * freqDim + f) * 2 + 1, 0.0);
			}
		}

		log("Input: [" + input.toDouble(0) + ", " + input.toDouble(1) + ", ...]");

		// Test at different positions
		int outputSize = heads * freqDim * 2;
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

		// Compare outputs
		log("\n--- Comparing Outputs ---");
		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < outputSize; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 0.001) allSame = false;
			}
			log(String.format("  Position 0 vs %d: max diff = %.6f", pos, maxDiff));
		}

		if (allSame) {
			log("\n[FAIL] RoPE outputs identical - POSITION BAKED IN!");
		} else {
			log("\n[PASS] RoPE outputs differ - position is dynamic!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertFalse("Position should affect RoPE output", allSame);
	}

	/**
	 * Test that mimics the attention() method structure:
	 * 1. Create SequentialBlock
	 * 2. Create branches (which don't return values to main path)
	 * 3. Add position-dependent layer to main block AFTER branching
	 *
	 * This is exactly what attention() does - it creates branches for K and V,
	 * then continues to process Q on the main block using position.
	 */
	@Test
	public void testBranchThenMainPathWithPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/branch_then_main_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  BRANCH THEN MAIN PATH WITH POSITION");
		log("  (Mimics attention() method structure)");
		log("=".repeat(70) + "\n");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;

		// Create position
		PackedCollection position = new PackedCollection(1);

		// Create dynamic position
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create RoPE frequencies
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

		log("Building model with branching THEN position (like attention())...");

		// Build exactly like attention() does:
		var ropeShape = shape(heads, freqDim, 2);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Create branches first (like attention() creates keys/values branches)
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("branch1", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("branch2", ropeShape, ropeShape, input -> multiply(input, scalar(3.0))));

		// NOW add position-dependent layer to main (like attention() adds queries processing)
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);

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

		log("Input: [" + input.toDouble(0) + ", " + input.toDouble(1) + ", ...]");

		// Test at different positions
		int outputSize = heads * freqDim * 2;
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

		// Compare outputs
		log("\n--- Comparing Outputs ---");
		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < outputSize; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 0.001) allSame = false;
			}
			log(String.format("  Position 0 vs %d: max diff = %.6f", pos, maxDiff));
		}

		if (allSame) {
			log("\n[FAIL] Branch-then-main outputs identical - POSITION BAKED IN!");
		} else {
			log("\n[PASS] Branch-then-main outputs differ!");
		}

		log("\n" + "=".repeat(70));

		Assert.assertFalse("Position should affect output after branching", allSame);
	}
}
