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
 * Test to verify if multiple uses of the same position Producer causes issues.
 *
 * Hypothesis: When a position Producer is used multiple times in the same model,
 * only the first use might be evaluated dynamically while subsequent uses get
 * a cached/baked-in value.
 */
public class MultiplePositionUsesTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testSinglePositionUse() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/multiple_position_single.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Single Position Use Test");
		log("===================================================\n");

		PackedCollection data = createData(10, 4);
		PackedCollection position = new PackedCollection(2);

		Model model = new Model(shape(4));
		model.add(layer("singleUse", shape(4), shape(4),
				input -> {
					// Single use of position: subset
					CollectionProducer row = subset(shape(1, 4), c(p(data)), p(position)).reshape(4);
					return add(input, row);
				}));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		PackedCollection input = createOnesInput(4);

		log("\n=== Testing Single Position Use ===\n");
		boolean allSame = testPositions(compiled, input, position, 5, 4);

		if (allSame) {
			log("\n[FAIL] Single position use: outputs identical");
		} else {
			log("\n[PASS] Single position use: outputs differ");
		}

		log("\n=== Test Complete ===");
	}

	@Test
	public void testTwoPositionUses() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/multiple_position_two.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Two Position Uses Test (Same Producer)");
		log("===================================================\n");

		PackedCollection data1 = createData(10, 4);
		PackedCollection data2 = createData(10, 4);
		// Make data2 different
		for (int i = 0; i < data2.getShape().getTotalSize(); i++) {
			data2.setMem(i, data2.toDouble(i) * 2);
		}

		PackedCollection position = new PackedCollection(2);

		Model model = new Model(shape(4));
		model.add(layer("twoUses", shape(4), shape(4),
				input -> {
					// TWO uses of the same position Producer
					CollectionProducer row1 = subset(shape(1, 4), c(p(data1)), p(position)).reshape(4);
					CollectionProducer row2 = subset(shape(1, 4), c(p(data2)), p(position)).reshape(4);
					return add(add(input, row1), row2);
				}));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		PackedCollection input = createOnesInput(4);

		log("\n=== Testing Two Position Uses (Same Producer) ===\n");
		boolean allSame = testPositions(compiled, input, position, 5, 4);

		if (allSame) {
			log("\n[FAIL] Two position uses: outputs identical - POSITION BAKED IN!");
		} else {
			log("\n[PASS] Two position uses: outputs differ");
		}

		log("\n=== Test Complete ===");
	}

	@Test
	public void testSequentialLayers() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/multiple_position_sequential.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Sequential Layers Using Same Position");
		log("===================================================\n");

		PackedCollection data1 = createData(10, 4);
		PackedCollection data2 = createData(10, 4);
		for (int i = 0; i < data2.getShape().getTotalSize(); i++) {
			data2.setMem(i, data2.toDouble(i) * 2);
		}

		PackedCollection position = new PackedCollection(2);

		Model model = new Model(shape(4));

		// First layer uses position
		model.add(layer("layer1", shape(4), shape(4),
				input -> {
					CollectionProducer row = subset(shape(1, 4), c(p(data1)), p(position)).reshape(4);
					return add(input, row);
				}));

		// Second layer ALSO uses same position
		model.add(layer("layer2", shape(4), shape(4),
				input -> {
					CollectionProducer row = subset(shape(1, 4), c(p(data2)), p(position)).reshape(4);
					return add(input, row);
				}));

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		PackedCollection input = createOnesInput(4);

		log("\n=== Testing Sequential Layers (Same Position) ===\n");
		boolean allSame = testPositions(compiled, input, position, 5, 4);

		if (allSame) {
			log("\n[FAIL] Sequential layers: outputs identical - POSITION BAKED IN!");
		} else {
			log("\n[PASS] Sequential layers: outputs differ");
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

		// Check if all outputs are identical
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
