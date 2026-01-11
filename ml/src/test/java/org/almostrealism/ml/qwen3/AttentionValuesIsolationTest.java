package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Isolate exactly what attentionValues does to break position handling.
 */
public class AttentionValuesIsolationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	/**
	 * Test 1: Just attentionValues with no RoPE, no mask.
	 * The output should be the same regardless of position since nothing depends on position.
	 */
	@Test
	public void test1_JustAttentionValues() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/attention_values_isolation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Test 1: Just attentionValues (no position dependency) ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int dim = heads * headSize;

		PackedCollection valueCache = new PackedCollection(shape(seqLen, heads, headSize));
		valueCache.fill(0.1);

		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(attentionShape);
		SequentialBlock main = new SequentialBlock(attentionShape);
		main.add(attentionValues(attentionShape, p(valueCache)));

		model.add(main);
		CompiledModel compiled = model.compile();

		// Test - since nothing uses position, outputs should be same
		PackedCollection input = new PackedCollection(attentionShape);
		for (int i = 0; i < attentionShape.getTotalSize(); i++) {
			input.setMem(i, 0.1);
		}

		PackedCollection result = compiled.forward(input);
		log("Output: " + formatOutput(result, 8));

		log("[INFO] No position used, output expected to be same");
	}

	/**
	 * Test 2: Softmax + attentionValues (no position dependency).
	 */
	@Test
	public void test2_SoftmaxPlusAttentionValues() throws Exception {
		log("\n=== Test 2: Softmax + attentionValues (no position dependency) ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int dim = heads * headSize;

		PackedCollection valueCache = new PackedCollection(shape(seqLen, heads, headSize));
		valueCache.fill(0.1);

		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(attentionShape);
		SequentialBlock main = new SequentialBlock(attentionShape);
		main.add(softmax(attentionShape, true));
		main.add(attentionValues(attentionShape, p(valueCache)));

		model.add(main);
		CompiledModel compiled = model.compile();

		PackedCollection input = new PackedCollection(attentionShape);
		for (int i = 0; i < attentionShape.getTotalSize(); i++) {
			input.setMem(i, 0.1);
		}

		PackedCollection result = compiled.forward(input);
		log("Output: " + formatOutput(result, 8));
		log("[INFO] No position used, output expected to be same");
	}

	/**
	 * Test 3: Causal mask + softmax + attentionValues (WITH position dependency).
	 * This should produce different outputs at different positions.
	 */
	@Test
	public void test3_MaskSoftmaxAttentionValues() throws Exception {
		log("\n=== Test 3: Mask + Softmax + attentionValues (position SHOULD matter) ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int dim = heads * headSize;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		PackedCollection valueCache = new PackedCollection(shape(seqLen, heads, headSize));
		valueCache.fill(0.1);

		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(attentionShape);
		SequentialBlock main = new SequentialBlock(attentionShape);

		// Causal mask
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		main.add(softmax(attentionShape, true));
		main.add(attentionValues(attentionShape, p(valueCache)));

		model.add(main);
		log("Compiling...");
		CompiledModel compiled = model.compile();

		testPositions(compiled, position, dim);
	}

	/**
	 * Test 4: Just causal mask + softmax (no attentionValues) - CONTROL.
	 */
	@Test
	public void test4_JustMaskSoftmax() throws Exception {
		log("\n=== Test 4: Just mask + softmax (no attentionValues) - CONTROL ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(attentionShape);
		SequentialBlock main = new SequentialBlock(attentionShape);

		// Causal mask
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		main.add(softmax(attentionShape, true));

		model.add(main);
		log("Compiling...");
		CompiledModel compiled = model.compile();

		testPositions(compiled, position, heads * seqLen);
	}

	/**
	 * Test 5: Create varying valueCache content - if position affects mask,
	 * different positions should attend to different values.
	 */
	@Test
	public void test5_VaryingValueCache() throws Exception {
		log("\n=== Test 5: Varying value cache content ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int dim = heads * headSize;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create value cache with DIFFERENT values at each position
		PackedCollection valueCache = new PackedCollection(shape(seqLen, heads, headSize));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int h = 0; h < heads; h++) {
				for (int d = 0; d < headSize; d++) {
					// Value is based on position so we can see which positions contribute
					int idx = (pos * heads + h) * headSize + d;
					valueCache.setMem(idx, pos * 0.1);  // Position 0 = 0.0, Position 1 = 0.1, etc.
				}
			}
		}

		log("ValueCache content:");
		log("  Position 0: " + valueCache.toDouble(0));
		log("  Position 1: " + valueCache.toDouble(heads * headSize));
		log("  Position 5: " + valueCache.toDouble(5 * heads * headSize));

		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(attentionShape);
		SequentialBlock main = new SequentialBlock(attentionShape);

		// Causal mask
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		main.add(softmax(attentionShape, true));
		main.add(attentionValues(attentionShape, p(valueCache)));

		model.add(main);
		log("Compiling...");
		CompiledModel compiled = model.compile();

		testPositions(compiled, position, dim);
	}

	private void testPositions(CompiledModel compiled, PackedCollection position, int outputSize) {
		PackedCollection input = new PackedCollection(compiled.getInputShape());
		for (int i = 0; i < input.getShape().getTotalSize(); i++) {
			input.setMem(i, 0.1);  // Uniform input
		}

		double[][] outputs = new double[5][outputSize];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			PackedCollection result = compiled.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append("Pos ").append(pos).append(": [");
			for (int i = 0; i < Math.min(4, outputSize); i++) {
				if (i > 0) sb.append(", ");
				outputs[pos][i] = result.toDouble(i);
				sb.append(String.format("%.6f", outputs[pos][i]));
			}
			if (outputSize > 4) sb.append(", ...");
			sb.append("]");
			log(sb.toString());
		}

		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			for (int i = 0; i < outputSize; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 1e-6) {
					allSame = false;
					break;
				}
			}
		}

		if (allSame) {
			log("[FAIL] Outputs identical - position NOT working");
		} else {
			log("[PASS] Outputs differ - position IS working");
		}
	}

	private String formatOutput(PackedCollection c, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(count, c.getShape().getTotalSize()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.6f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
