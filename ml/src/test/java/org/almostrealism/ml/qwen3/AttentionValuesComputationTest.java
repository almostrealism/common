package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Trace through attentionValues computation step by step with small matrices.
 *
 * Goal: Understand where the computation diverges from the expected formula:
 *   attn_output[h][d] = sum_pos(softmax[h][pos] * values[h][pos][d])
 */
public class AttentionValuesComputationTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void traceAttentionValuesComputation() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/attention_values_computation.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Attention Values Computation Trace");
		log("===================================================\n");

		// Use small dimensions for tracing
		int seqLength = 2;
		int heads = 2;
		int headSize = 3;
		int dim = heads * headSize;  // 6

		// Create softmax weights: softmax[h, pos]
		// Shape: [heads, seqLength] = [2, 2]
		PackedCollection softmax = new PackedCollection(shape(heads, seqLength));
		// Head 0: [0.6, 0.4] - attends more to position 0
		// Head 1: [0.2, 0.8] - attends more to position 1
		softmax.setMem(0, 0.6);  // [0,0]
		softmax.setMem(1, 0.4);  // [0,1]
		softmax.setMem(2, 0.2);  // [1,0]
		softmax.setMem(3, 0.8);  // [1,1]

		log("Softmax weights [heads, seqLength]:");
		log("  Head 0: [" + softmax.toDouble(0) + ", " + softmax.toDouble(1) + "]");
		log("  Head 1: [" + softmax.toDouble(2) + ", " + softmax.toDouble(3) + "]");

		// Create values: values[pos, h, d] stored as [seqLength, heads, headSize]
		// But attentionValues expects [seqLength, heads, headSize] -> need to match expandedValues
		PackedCollection values = new PackedCollection(shape(seqLength, heads, headSize));
		// Position 0:
		//   Head 0: [1, 2, 3]
		//   Head 1: [4, 5, 6]
		// Position 1:
		//   Head 0: [7, 8, 9]
		//   Head 1: [10, 11, 12]
		values.setMem(0, 1);   // [0,0,0]
		values.setMem(1, 2);   // [0,0,1]
		values.setMem(2, 3);   // [0,0,2]
		values.setMem(3, 4);   // [0,1,0]
		values.setMem(4, 5);   // [0,1,1]
		values.setMem(5, 6);   // [0,1,2]
		values.setMem(6, 7);   // [1,0,0]
		values.setMem(7, 8);   // [1,0,1]
		values.setMem(8, 9);   // [1,0,2]
		values.setMem(9, 10);  // [1,1,0]
		values.setMem(10, 11); // [1,1,1]
		values.setMem(11, 12); // [1,1,2]

		log("\nValues [seqLength, heads, headSize]:");
		log("  Position 0:");
		log("    Head 0: [1, 2, 3]");
		log("    Head 1: [4, 5, 6]");
		log("  Position 1:");
		log("    Head 0: [7, 8, 9]");
		log("    Head 1: [10, 11, 12]");

		// Expected output: attn_output[h][d] = sum_pos(softmax[h][pos] * values[pos][h][d])
		log("\n=== Expected Output ===");
		log("attn_output[h][d] = sum_pos(softmax[h][pos] * values[pos][h][d])");
		double[] expected = new double[dim];
		for (int h = 0; h < heads; h++) {
			log("  Head " + h + ":");
			StringBuilder sb = new StringBuilder("    [");
			for (int d = 0; d < headSize; d++) {
				double sum = 0;
				for (int pos = 0; pos < seqLength; pos++) {
					double sw = softmax.toDouble(h * seqLength + pos);
					double v = values.toDouble(pos * (heads * headSize) + h * headSize + d);
					sum += sw * v;
				}
				expected[h * headSize + d] = sum;
				if (d > 0) sb.append(", ");
				sb.append(String.format("%.2f", sum));
			}
			sb.append("]");
			log(sb.toString());
		}

		// Now trace through attentionValues computation
		log("\n=== Tracing attentionValues Implementation ===");

		// Step 1: reshape(shape(seqLength, dim), expandedValues)
		log("\nStep 1: v = reshape((seqLength, dim), values)");
		CollectionProducer valuesP = c(values);
		CollectionProducer v = valuesP.reshape(shape(seqLength, dim));
		log("  Shape: " + shape(v));
		PackedCollection vResult = v.get().evaluate();
		log("  Values (linear): " + formatArray(vResult, seqLength * dim));

		// Step 2: enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength))
		log("\nStep 2: v = enumerate(1, 1, v).reshape((heads, headSize, seqLength))");
		CollectionProducer enumV = enumerate(1, 1, v);
		log("  After enumerate(1, 1): shape = " + shape(enumV));
		PackedCollection enumResult = enumV.get().evaluate();
		log("  Values after enumerate: " + formatArray(enumResult, seqLength * dim));

		CollectionProducer reshapedV = enumV.reshape(shape(heads, headSize, seqLength));
		log("  After reshape: shape = " + shape(reshapedV));
		PackedCollection reshapedResult = reshapedV.get().evaluate();

		log("  Values [h, d, pos]:");
		for (int h = 0; h < heads; h++) {
			log("    Head " + h + ":");
			for (int d = 0; d < headSize; d++) {
				StringBuilder sb = new StringBuilder("      d=" + d + ": [");
				for (int pos = 0; pos < seqLength; pos++) {
					int idx = h * (headSize * seqLength) + d * seqLength + pos;
					if (pos > 0) sb.append(", ");
					sb.append(String.format("%.0f", reshapedResult.toDouble(idx)));
				}
				sb.append("]");
				log(sb.toString());
			}
		}

		// Step 3: a = traverse(1, input).repeat(headSize)
		log("\nStep 3: a = traverse(1, softmax).repeat(headSize)");
		CollectionProducer softmaxP = c(softmax);
		CollectionProducer aTraversed = traverse(1, softmaxP);
		log("  After traverse(1): shape = " + shape(aTraversed));

		CollectionProducer a = aTraversed.repeat(headSize);
		log("  After repeat(headSize): shape = " + shape(a));

		PackedCollection aResult = a.get().evaluate();
		log("  Values after repeat:");
		int aTotal = heads * seqLength * headSize;
		log("  " + formatArray(aResult, aTotal));

		// Step 4: multiply(traverseEach(a), traverseEach(v))
		log("\nStep 4: product = multiply(traverseEach(a), traverseEach(v))");
		CollectionProducer product = multiply(traverseEach(a), traverseEach(reshapedV));
		log("  Product shape: " + shape(product));

		PackedCollection productResult = product.get().evaluate();
		log("  Product values: " + formatArray(productResult, aTotal));

		// Step 5: traverse(2).sum()
		log("\nStep 5: o = product.traverse(2).sum()");
		CollectionProducer oTraversed = product.traverse(2);
		log("  After traverse(2): shape = " + shape(oTraversed));

		CollectionProducer o = oTraversed.sum();
		log("  After sum(): shape = " + shape(o));

		PackedCollection oResult = o.get().evaluate();
		log("  Sum values: " + formatArray(oResult, dim));

		// Step 6: reshape to output
		log("\nStep 6: output = o.reshape((1, dim))");
		CollectionProducer output = o.reshape(shape(1, dim).traverseEach());
		PackedCollection outputResult = output.get().evaluate();
		log("  Final output: " + formatArray(outputResult, dim));

		// Compare with expected
		log("\n=== Comparison ===");
		log("Expected: " + formatDoubleArray(expected, dim));
		log("Actual:   " + formatArray(outputResult, dim));

		double maxDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(outputResult.toDouble(i) - expected[i]);
			maxDiff = Math.max(maxDiff, diff);
		}
		log("Max difference: " + String.format("%.6f", maxDiff));

		if (maxDiff < 0.01) {
			log("\n[OK] attentionValues produces correct output!");
		} else {
			log("\n[MISMATCH] attentionValues produces incorrect output!");
		}

		log("\n=== Test Complete ===");
	}

	private String formatArray(PackedCollection c, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.2f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatDoubleArray(double[] arr, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.2f", arr[i]));
		}
		sb.append("]");
		return sb.toString();
	}
}
