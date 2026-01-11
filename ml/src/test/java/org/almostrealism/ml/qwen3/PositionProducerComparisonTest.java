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
 * Compare p(position) vs DynamicCollectionProducer for position handling.
 * This test determines if there's a functional difference between the two approaches.
 */
public class PositionProducerComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	/**
	 * Test p(position) in a simple causal mask scenario.
	 */
	@Test
	public void testPPositionCausalMask() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/position_producer_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Test: p(position) vs DynamicCollectionProducer ===\n");

		int heads = 2;
		int seqLen = 10;

		// Test 1: Using p(position)
		log("Test 1: Using p(position)...");
		PackedCollection position1 = new PackedCollection(1);
		Producer<PackedCollection> producer1 = p(position1);

		CompiledModel model1 = buildCausalMaskModel(heads, seqLen, producer1);
		double[][] outputs1 = runPositions(model1, position1, heads * seqLen);

		// Test 2: Using DynamicCollectionProducer
		log("\nTest 2: Using DynamicCollectionProducer...");
		PackedCollection position2 = new PackedCollection(1);
		Producer<PackedCollection> producer2 =
			new DynamicCollectionProducer(position2.getShape(), args -> position2);

		CompiledModel model2 = buildCausalMaskModel(heads, seqLen, producer2);
		double[][] outputs2 = runPositions(model2, position2, heads * seqLen);

		// Compare results
		log("\n=== Comparison ===");
		boolean p_works = !allSame(outputs1);
		boolean dynamic_works = !allSame(outputs2);

		log("p(position) produces different outputs: " + p_works);
		log("DynamicCollectionProducer produces different outputs: " + dynamic_works);

		// Compare actual values
		log("\nPosition 0 outputs:");
		log("  p(position): [" + formatOutput(outputs1[0], 4) + "]");
		log("  Dynamic:     [" + formatOutput(outputs2[0], 4) + "]");

		log("\nPosition 1 outputs:");
		log("  p(position): [" + formatOutput(outputs1[1], 4) + "]");
		log("  Dynamic:     [" + formatOutput(outputs2[1], 4) + "]");

		if (p_works) {
			log("\n[PASS] p(position) works correctly!");
		} else {
			log("\n[FAIL] p(position) produces identical outputs - position NOT dynamic!");
		}

		if (dynamic_works) {
			log("[PASS] DynamicCollectionProducer works correctly!");
		} else {
			log("[FAIL] DynamicCollectionProducer produces identical outputs - UNEXPECTED!");
		}

		Assert.assertTrue("DynamicCollectionProducer should work", dynamic_works);
	}

	/**
	 * Test p(position) in the full attention() method.
	 */
	@Test
	public void testPPositionInAttention() throws Exception {
		log("\n=== Test: p(position) in attention() method ===\n");

		int dim = 8;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;
		int seqLen = 10;

		PackedCollection rmsAttWeight = ones(dim);
		PackedCollection wq = createIdentity(dim, dim);
		PackedCollection wk = createIdentity(dim, dim);
		PackedCollection wv = createIdentity(dim, dim);
		PackedCollection wo = createIdentity(dim, dim);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		// Test 1: Using p(position)
		log("Test 1: Using p(position) with attention()...");
		PackedCollection position1 = new PackedCollection(1);

		var block1 = attention(heads, kvHeads,
			rmsAttWeight, wk, wv, wq, wo,
			null, null, null, null, null,
			freqCis, p(position1), 1e-6);

		Model model1 = new Model(shape(1, dim));
		model1.add(block1);
		CompiledModel compiled1 = model1.compile();

		// Run with DIFFERENT inputs at each position
		PackedCollection input1 = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input1.setMem(i, 0.1 * (i + 1));
		}

		double[][] outputs1 = new double[5][dim];
		for (int pos = 0; pos < 5; pos++) {
			position1.setMem(0, (double) pos);
			// Different input at each position
			for (int i = 0; i < dim; i++) {
				input1.setMem(i, 0.1 * (i + 1) + pos * 0.01);
			}
			PackedCollection result = compiled1.forward(input1);
			for (int i = 0; i < dim; i++) {
				outputs1[pos][i] = result.toDouble(i);
			}
			log("p(position) @ pos " + pos + ": [" + formatOutput(outputs1[pos], 4) + "]");
		}

		// Test 2: Using DynamicCollectionProducer
		log("\nTest 2: Using DynamicCollectionProducer with attention()...");
		PackedCollection position2 = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position2.getShape(), args -> position2);

		// Create fresh weights for second model
		PackedCollection rms2 = ones(dim);
		PackedCollection wq2 = createIdentity(dim, dim);
		PackedCollection wk2 = createIdentity(dim, dim);
		PackedCollection wv2 = createIdentity(dim, dim);
		PackedCollection wo2 = createIdentity(dim, dim);
		PackedCollection freq2 = createFreqCis(seqLen, headSize);

		var block2 = attention(heads, kvHeads,
			rms2, wk2, wv2, wq2, wo2,
			null, null, null, null, null,
			freq2, dynamicPosition, 1e-6);

		Model model2 = new Model(shape(1, dim));
		model2.add(block2);
		CompiledModel compiled2 = model2.compile();

		PackedCollection input2 = new PackedCollection(shape(1, dim));
		double[][] outputs2 = new double[5][dim];
		for (int pos = 0; pos < 5; pos++) {
			position2.setMem(0, (double) pos);
			// Same different input pattern
			for (int i = 0; i < dim; i++) {
				input2.setMem(i, 0.1 * (i + 1) + pos * 0.01);
			}
			PackedCollection result = compiled2.forward(input2);
			for (int i = 0; i < dim; i++) {
				outputs2[pos][i] = result.toDouble(i);
			}
			log("Dynamic @ pos " + pos + ": [" + formatOutput(outputs2[pos], 4) + "]");
		}

		// Compare
		log("\n=== Comparison ===");
		boolean p_works = !allSame(outputs1);
		boolean dynamic_works = !allSame(outputs2);

		log("p(position) produces different outputs: " + p_works);
		log("DynamicCollectionProducer produces different outputs: " + dynamic_works);

		if (p_works == dynamic_works) {
			log("\n[INFO] Both approaches produce same behavior");
		} else {
			log("\n[INFO] Different behavior detected!");
		}
	}

	private CompiledModel buildCausalMaskModel(int heads, int seqLen,
											   Producer<PackedCollection> position) {
		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(attentionShape);
		SequentialBlock main = new SequentialBlock(attentionShape);

		// Causal mask using position
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, position, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));
		main.add(softmax(attentionShape, true));

		model.add(main);
		return model.compile();
	}

	private double[][] runPositions(CompiledModel model, PackedCollection position, int outputSize) {
		PackedCollection input = new PackedCollection(model.getInputShape());
		for (int i = 0; i < input.getShape().getTotalSize(); i++) {
			input.setMem(i, 0.1);
		}

		double[][] outputs = new double[5][outputSize];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			PackedCollection result = model.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append("Pos ").append(pos).append(": [");
			for (int i = 0; i < Math.min(4, outputSize); i++) {
				if (i > 0) sb.append(", ");
				outputs[pos][i] = result.toDouble(i);
				sb.append(String.format("%.4f", outputs[pos][i]));
			}
			sb.append(", ...]");
			log(sb.toString());
		}

		return outputs;
	}

	private boolean allSame(double[][] outputs) {
		for (int pos = 1; pos < outputs.length; pos++) {
			for (int i = 0; i < outputs[pos].length; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 1e-6) {
					return false;
				}
			}
		}
		return true;
	}

	private String formatOutput(double[] arr, int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(count, arr.length); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[i]));
		}
		return sb.toString();
	}

	private PackedCollection ones(int size) {
		PackedCollection c = new PackedCollection(size);
		for (int i = 0; i < size; i++) c.setMem(i, 1.0);
		return c;
	}

	private PackedCollection createIdentity(int rows, int cols) {
		PackedCollection c = new PackedCollection(shape(rows, cols));
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < cols; j++) {
				c.setMem(i * cols + j, (i == j) ? 0.5 : 0.01);
			}
		}
		return c;
	}

	private PackedCollection createFreqCis(int seqLen, int headSize) {
		double theta = 1000000.0;
		int freqDim = headSize / 2;
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
				double angle = pos * freq;
				freqCis.setMem((pos * freqDim + i) * 2, Math.cos(angle));
				freqCis.setMem((pos * freqDim + i) * 2 + 1, Math.sin(angle));
			}
		}
		return freqCis;
	}
}
