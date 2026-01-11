package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test to verify position works with branched execution (like attention uses).
 *
 * The attention block uses:
 * - attention.branch() to create keys/values branches
 * - andThen() with CollectionReceptor for cache writes
 * - accum() for residual connections
 */
public class BranchPositionTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testBranchWithPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/branch_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Branch Position Test");
		log("===================================================\n");

		int inputSize = 4;
		int outputSize = 4;

		PackedCollection data1 = createData(10, inputSize);
		PackedCollection data2 = createData(10, inputSize);
		for (int i = 0; i < data2.getShape().getTotalSize(); i++) {
			data2.setMem(i, data2.toDouble(i) * 2);
		}

		PackedCollection position = new PackedCollection(2);

		// CRITICAL: Use DynamicCollectionProducer instead of p(position)
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create model with branching structure like attention
		Model model = new Model(shape(inputSize));

		SequentialBlock main = new SequentialBlock(shape(inputSize));

		// Branch 1: processes input differently
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("branch1_process", shape(inputSize), shape(inputSize),
				input -> {
					CollectionProducer row = subset(shape(1, inputSize), c(p(data1)), dynamicPosition).reshape(inputSize);
					return add(input, row);
				}));

		// Branch 2: also uses position
		SequentialBlock branch2 = main.branch();
		branch2.add(layer("branch2_process", shape(inputSize), shape(inputSize),
				input -> {
					CollectionProducer row = subset(shape(1, inputSize), c(p(data2)), dynamicPosition).reshape(inputSize);
					return add(input, row);
				}));

		// Main ALSO uses position (this is key!)
		main.add(layer("main_combine", shape(inputSize), shape(inputSize),
				input -> {
					// Main branch uses position too
					CollectionProducer row = subset(shape(1, inputSize), c(p(data1)), dynamicPosition).reshape(inputSize);
					return add(multiply(input, scalar(0.5)), row);
				}));

		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		PackedCollection input = createOnesInput(inputSize);

		log("\n=== Testing Branch Pattern with Position (main also uses position) ===\n");
		boolean allSame = testPositions(compiled, input, position, 5, outputSize);

		if (allSame) {
			log("\n[FAIL] Branch with position: outputs identical - POSITION BAKED IN!");
		} else {
			log("\n[PASS] Branch with position: outputs differ");
		}

		log("\n=== Test Complete ===");

		Assert.assertFalse("Position should affect output in branched model", allSame);
	}

	@Test
	public void testBranchWithCacheWrite() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/branch_cache_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Branch with Cache Write Position Test");
		log("===================================================\n");

		int inputSize = 4;
		int seqLen = 10;

		// Create cache for branch outputs
		PackedCollection cache = new PackedCollection(shape(seqLen, inputSize));
		cache.fill(0.0);

		PackedCollection data = createData(seqLen, inputSize);
		PackedCollection position = new PackedCollection(2);

		// CRITICAL: Use DynamicCollectionProducer
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create model with branching + cache write (like attention)
		Model model = new Model(shape(inputSize));

		SequentialBlock main = new SequentialBlock(shape(inputSize));

		// Branch that uses position and writes to cache
		SequentialBlock branch = main.branch();
		branch.add(layer("branch_process", shape(inputSize), shape(inputSize),
				input -> {
					CollectionProducer row = subset(shape(1, inputSize), c(p(data)), dynamicPosition).reshape(inputSize);
					return add(input, row);
				}));

		// Add cache write via andThen (like attention does)
		branch.andThen(into(cache, dynamicPosition));

		// Main continues
		main.add(layer("main_process", shape(inputSize), shape(inputSize),
				input -> multiply(input, scalar(0.5))));

		model.add(main);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		PackedCollection input = createOnesInput(inputSize);

		log("\n=== Testing Branch + Cache Write with Position ===\n");
		boolean allSame = testPositions(compiled, input, position, 5, inputSize);

		log("\n=== Cache Contents After Test ===");
		for (int row = 0; row < seqLen; row++) {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Row %d: [", row));
			for (int col = 0; col < inputSize; col++) {
				if (col > 0) sb.append(", ");
				sb.append(String.format("%.1f", cache.toDouble(row * inputSize + col)));
			}
			sb.append("]");
			log(sb.toString());
		}

		if (allSame) {
			log("\n[FAIL] Branch + cache: outputs identical - POSITION BAKED IN!");
		} else {
			log("\n[PASS] Branch + cache: outputs differ");
		}

		log("\n=== Test Complete ===");
	}

	private PackedCollection createData(int rows, int cols) {
		PackedCollection data = new PackedCollection(shape(rows, cols));
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				data.setMem(row * cols + col, row * 10 + col);
			}
		}
		return data;
	}

	private PackedCollection createOnesInput(int size) {
		PackedCollection input = new PackedCollection(shape(size));
		for (int i = 0; i < size; i++) {
			input.setMem(i, 1.0);
		}
		return input;
	}

	private boolean testPositions(CompiledModel compiled, PackedCollection input,
								  PackedCollection position, int numPositions, int outputSize) {
		double[][] outputs = new double[numPositions][outputSize];

		for (int pos = 0; pos < numPositions; pos++) {
			position.setMem(0, (double) pos);
			position.setMem(1, 0.0);

			PackedCollection output = compiled.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Position %d: [", pos));
			for (int i = 0; i < outputSize; i++) {
				outputs[pos][i] = output.toDouble(i);
				if (i > 0) sb.append(", ");
				sb.append(String.format("%.1f", outputs[pos][i]));
			}
			sb.append("]");
			log(sb.toString());
		}

		boolean allSame = true;
		for (int pos = 1; pos < numPositions; pos++) {
			for (int i = 0; i < outputSize; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 0.001) {
					allSame = false;
					break;
				}
			}
		}

		return allSame;
	}
}
