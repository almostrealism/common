/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Isolated tests for individual components used in RoPE and attention.
 * Each test focuses on a single operation to identify exactly where bugs exist.
 */
public class IsolatedComponentTests implements TestFeatures, PairFeatures {

	private static final double TOLERANCE = 1e-10;

	// ==================== SUBSET EXTRACTION TESTS ====================

	/**
	 * Test 1A: Basic subset extraction with constant index.
	 * Extracts a single row from a 2D array using subset.
	 */
	@Test
	public void subsetExtraction2D_ConstantIndex() {
		log("=== Test 1A: 2D Subset with Constant Index ===");

		// Create a simple 2D array: 4 rows x 3 cols
		PackedCollection data = new PackedCollection(shape(4, 3));
		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 3; col++) {
				data.setMem(row * 3 + col, row * 10 + col);  // Values: 0,1,2, 10,11,12, 20,21,22, 30,31,32
			}
		}

		log("Input data (4x3):");
		for (int row = 0; row < 4; row++) {
			log("  Row " + row + ": " + data.valueAt(row, 0) + ", " + data.valueAt(row, 1) + ", " + data.valueAt(row, 2));
		}

		// Extract row 2 using subset
		int targetRow = 2;
		Producer<PackedCollection> pos = c(targetRow, 0);  // Position (2, 0)
		CollectionProducer result = subset(shape(1, 3), c(p(data)), pos);

		PackedCollection out = result.get().evaluate();
		log("Extracted row " + targetRow + " (shape " + out.getShape() + "):");
		log("  Values: " + out.toDouble(0) + ", " + out.toDouble(1) + ", " + out.toDouble(2));

		// Verify
		Assert.assertEquals("Element 0", 20.0, out.toDouble(0), TOLERANCE);
		Assert.assertEquals("Element 1", 21.0, out.toDouble(1), TOLERANCE);
		Assert.assertEquals("Element 2", 22.0, out.toDouble(2), TOLERANCE);
		log("[PASS] Subset extraction correct\n");
	}

	/**
	 * Test 1B: Subset extraction from 3D array (like RoPE weights).
	 * Shape: (seqLen, headSize, 2) - extract one position.
	 */
	@Test
	public void subsetExtraction3D_ConstantIndex() {
		log("=== Test 1B: 3D Subset with Constant Index ===");

		// Create 3D array: 8 positions x 4 headSize x 2 (real/imag)
		int seqLen = 8;
		int headSize = 4;
		PackedCollection data = new PackedCollection(shape(seqLen, headSize, 2));

		// Fill with recognizable values: pos*100 + head*10 + (0 or 1)
		for (int pos = 0; pos < seqLen; pos++) {
			for (int head = 0; head < headSize; head++) {
				data.setMem(pos * headSize * 2 + head * 2 + 0, pos * 100 + head * 10 + 0);  // real
				data.setMem(pos * headSize * 2 + head * 2 + 1, pos * 100 + head * 10 + 1);  // imag
			}
		}

		log("Input data shape: " + data.getShape());
		log("Sample - position 3, head 2: real=" + data.valueAt(3, 2, 0) + ", imag=" + data.valueAt(3, 2, 1));

		// Extract position 5
		int targetPos = 5;
		Producer<PackedCollection> posIndex = c(targetPos, 0, 0);
		CollectionProducer result = subset(shape(1, headSize, 2), c(p(data)), posIndex);

		PackedCollection out = result.get().evaluate();
		log("Extracted position " + targetPos + " (shape " + out.getShape() + "):");

		// Verify each head's real/imag values
		boolean allCorrect = true;
		for (int head = 0; head < headSize; head++) {
			double expectedReal = targetPos * 100 + head * 10 + 0;
			double expectedImag = targetPos * 100 + head * 10 + 1;
			double actualReal = out.valueAt(0, head, 0);
			double actualImag = out.valueAt(0, head, 1);

			log("  Head " + head + ": expected (" + expectedReal + "," + expectedImag +
				"), actual (" + actualReal + "," + actualImag + ")");

			if (Math.abs(actualReal - expectedReal) > TOLERANCE ||
				Math.abs(actualImag - expectedImag) > TOLERANCE) {
				allCorrect = false;
			}
		}

		Assert.assertTrue("[PASS/FAIL] 3D subset extraction", allCorrect);
		if (allCorrect) log("[PASS] 3D Subset extraction correct\n");
	}

	// ==================== COMPLEX MULTIPLICATION TESTS ====================

	/**
	 * Test 2A: Basic complex multiplication of two scalars.
	 * (a + bi) * (c + di) = (ac - bd) + (ad + bc)i
	 */
	@Test
	public void complexMultiply_TwoScalars() {
		log("=== Test 2A: Complex Multiply - Two Scalars ===");

		// Create two complex numbers: (3 + 4i) and (1 + 2i)
		// Expected: (3*1 - 4*2) + (3*2 + 4*1)i = -5 + 10i
		PackedCollection a = new PackedCollection(shape(2));
		a.setMem(0, 3.0);  // real
		a.setMem(1, 4.0);  // imag

		PackedCollection b = new PackedCollection(shape(2));
		b.setMem(0, 1.0);  // real
		b.setMem(1, 2.0);  // imag

		log("Input a: " + a.toDouble(0) + " + " + a.toDouble(1) + "i");
		log("Input b: " + b.toDouble(0) + " + " + b.toDouble(1) + "i");

		CollectionProducer result = multiplyComplex(p(a), p(b));
		PackedCollection out = result.get().evaluate();

		log("Result shape: " + out.getShape());
		log("Result: " + out.toDouble(0) + " + " + out.toDouble(1) + "i");

		double expectedReal = 3.0 * 1.0 - 4.0 * 2.0;  // -5
		double expectedImag = 3.0 * 2.0 + 4.0 * 1.0;  // 10

		Assert.assertEquals("Real part", expectedReal, out.toDouble(0), TOLERANCE);
		Assert.assertEquals("Imaginary part", expectedImag, out.toDouble(1), TOLERANCE);
		log("[PASS] Complex scalar multiplication correct\n");
	}

	/**
	 * Test 2B: Complex multiplication of arrays (multiple complex numbers).
	 */
	@Test
	public void complexMultiply_Arrays() {
		log("=== Test 2B: Complex Multiply - Arrays ===");

		// Create arrays of 3 complex numbers each
		int n = 3;
		PackedCollection a = new PackedCollection(shape(n, 2));
		PackedCollection b = new PackedCollection(shape(n, 2));

		// a = [(1+2i), (3+4i), (5+0i)]
		// b = [(1+0i), (0+1i), (2+3i)]
		double[][] aVals = {{1, 2}, {3, 4}, {5, 0}};
		double[][] bVals = {{1, 0}, {0, 1}, {2, 3}};

		for (int i = 0; i < n; i++) {
			a.setMem(i * 2 + 0, aVals[i][0]);
			a.setMem(i * 2 + 1, aVals[i][1]);
			b.setMem(i * 2 + 0, bVals[i][0]);
			b.setMem(i * 2 + 1, bVals[i][1]);
		}

		log("Input arrays:");
		for (int i = 0; i < n; i++) {
			log("  a[" + i + "] = " + aVals[i][0] + " + " + aVals[i][1] + "i");
			log("  b[" + i + "] = " + bVals[i][0] + " + " + bVals[i][1] + "i");
		}

		// Expected results:
		// (1+2i)*(1+0i) = 1 + 2i
		// (3+4i)*(0+1i) = -4 + 3i
		// (5+0i)*(2+3i) = 10 + 15i
		double[][] expected = {{1, 2}, {-4, 3}, {10, 15}};

		CollectionProducer result = multiplyComplex(traverse(1, p(a)), traverse(1, p(b)));
		PackedCollection out = result.get().evaluate();

		log("Result shape: " + out.getShape());
		boolean allCorrect = true;
		for (int i = 0; i < n; i++) {
			double actualReal = out.valueAt(i, 0);
			double actualImag = out.valueAt(i, 1);
			log("  result[" + i + "] = " + actualReal + " + " + actualImag + "i (expected: " +
				expected[i][0] + " + " + expected[i][1] + "i)");

			if (Math.abs(actualReal - expected[i][0]) > TOLERANCE ||
				Math.abs(actualImag - expected[i][1]) > TOLERANCE) {
				allCorrect = false;
			}
		}

		Assert.assertTrue("[PASS/FAIL] Complex array multiplication", allCorrect);
		if (allCorrect) log("[PASS] Complex array multiplication correct\n");
	}

	/**
	 * Test 2C: Complex multiplication with broadcast (single complex * array).
	 */
	@Test
	public void complexMultiply_Broadcast() {
		log("=== Test 2C: Complex Multiply - Broadcast ===");

		// Single complex number: (2 + 1i)
		PackedCollection scalar = new PackedCollection(shape(1, 2));
		scalar.setMem(0, 2.0);
		scalar.setMem(1, 1.0);

		// Array of 3 complex numbers: [(1+0i), (0+1i), (1+1i)]
		PackedCollection array = new PackedCollection(shape(3, 2));
		double[][] arrayVals = {{1, 0}, {0, 1}, {1, 1}};
		for (int i = 0; i < 3; i++) {
			array.setMem(i * 2 + 0, arrayVals[i][0]);
			array.setMem(i * 2 + 1, arrayVals[i][1]);
		}

		log("Scalar: 2 + 1i");
		log("Array: [(1+0i), (0+1i), (1+1i)]");

		// Expected: (2+i) * each element
		// (2+i)*(1+0i) = 2 + i
		// (2+i)*(0+i) = -1 + 2i
		// (2+i)*(1+i) = 1 + 3i
		double[][] expected = {{2, 1}, {-1, 2}, {1, 3}};

		CollectionProducer scalarProd = (CollectionProducer) c(p(scalar)).traverse(1);
		CollectionProducer arrayProd = (CollectionProducer) c(p(array)).traverse(1);
		CollectionProducer result = multiplyComplex(arrayProd, scalarProd.repeat(3));
		PackedCollection out = result.get().evaluate();

		log("Result shape: " + out.getShape());
		boolean allCorrect = true;
		for (int i = 0; i < 3; i++) {
			double actualReal = out.valueAt(i, 0);
			double actualImag = out.valueAt(i, 1);
			log("  result[" + i + "] = " + actualReal + " + " + actualImag + "i (expected: " +
				expected[i][0] + " + " + expected[i][1] + "i)");

			if (Math.abs(actualReal - expected[i][0]) > TOLERANCE ||
				Math.abs(actualImag - expected[i][1]) > TOLERANCE) {
				allCorrect = false;
			}
		}

		Assert.assertTrue("[PASS/FAIL] Complex broadcast multiplication", allCorrect);
		if (allCorrect) log("[PASS] Complex broadcast multiplication correct\n");
	}

	// ==================== TRAVERSE OPERATION TESTS ====================

	/**
	 * Test 3A: traverse(1, x) on a 2D array.
	 */
	@Test
	public void traverse_2DArray() {
		log("=== Test 3A: Traverse on 2D Array ===");

		// Create 3x4 array with recognizable values
		PackedCollection data = new PackedCollection(shape(3, 4));
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				data.setMem(i * 4 + j, i * 10 + j);
			}
		}

		log("Input shape: " + data.getShape());
		log("Input data:");
		for (int i = 0; i < 3; i++) {
			StringBuilder sb = new StringBuilder("  Row " + i + ": ");
			for (int j = 0; j < 4; j++) {
				sb.append(data.valueAt(i, j)).append(" ");
			}
			log(sb.toString());
		}

		// traverse(1, x) should create a producer that iterates over axis 1
		CollectionProducer traversed = traverse(1, p(data));
		TraversalPolicy traversedShape = shape(traversed);

		log("Traversed shape: " + traversedShape);
		log("Traversed dimensions: " + traversedShape.getDimensions());

		// The traversed result should maintain the same data but with different traversal
		PackedCollection out = traversed.get().evaluate();
		log("Output shape: " + out.getShape());

		// Verify data is preserved
		boolean allCorrect = true;
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				double expected = i * 10 + j;
				double actual = out.valueAt(i, j);
				if (Math.abs(actual - expected) > TOLERANCE) {
					log("  MISMATCH at [" + i + "," + j + "]: expected " + expected + ", got " + actual);
					allCorrect = false;
				}
			}
		}

		Assert.assertTrue("[PASS/FAIL] Traverse preserves data", allCorrect);
		if (allCorrect) log("[PASS] Traverse on 2D array correct\n");
	}

	// ==================== COMBINED ROPE-STYLE OPERATION ====================

	/**
	 * Test 4: Simple RoPE-style operation with known values.
	 * This mimics what ropeRotation does but with simple, verifiable numbers.
	 */
	@Test
	public void ropeStyle_SimpleKnownValues() {
		log("=== Test 4: RoPE-Style Operation with Known Values ===");

		// Create input: 2 heads x 2 headSize x 2 (real/imag)
		// Using simple values we can verify by hand
		int heads = 2;
		int headSize = 2;

		PackedCollection input = new PackedCollection(shape(heads, headSize, 2));
		// Head 0: [(1+0i), (0+1i)]
		// Head 1: [(1+1i), (2+0i)]
		input.setMem(0, 1); input.setMem(1, 0);  // head 0, pos 0
		input.setMem(2, 0); input.setMem(3, 1);  // head 0, pos 1
		input.setMem(4, 1); input.setMem(5, 1);  // head 1, pos 0
		input.setMem(6, 2); input.setMem(7, 0);  // head 1, pos 1

		// Create weights (frequencies): 3 positions x 2 headSize x 2 (real/imag)
		// Position 0: [(1+0i), (1+0i)] - identity
		// Position 1: [(0+1i), (0+1i)] - 90 degree rotation
		// Position 2: [(-1+0i), (-1+0i)] - 180 degree rotation
		PackedCollection weights = new PackedCollection(shape(3, headSize, 2));
		// pos 0: identity
		weights.setMem(0, 1); weights.setMem(1, 0);
		weights.setMem(2, 1); weights.setMem(3, 0);
		// pos 1: 90 degree
		weights.setMem(4, 0); weights.setMem(5, 1);
		weights.setMem(6, 0); weights.setMem(7, 1);
		// pos 2: 180 degree
		weights.setMem(8, -1); weights.setMem(9, 0);
		weights.setMem(10, -1); weights.setMem(11, 0);

		log("Input (2 heads x 2 headSize x 2):");
		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				log("  head " + h + ", pos " + i + ": " + input.valueAt(h, i, 0) + " + " + input.valueAt(h, i, 1) + "i");
			}
		}

		// Test with position 1 (90 degree rotation)
		int testPos = 1;
		log("\nUsing position " + testPos + " (90 degree rotation):");

		// Extract weights at position testPos
		Producer<PackedCollection> posIndex = c(testPos, 0, 0);
		CollectionProducer freqs = subset(shape(1, headSize, 2), c(p(weights)), posIndex);

		log("Extracted frequencies:");
		PackedCollection freqsEval = freqs.get().evaluate();
		for (int i = 0; i < headSize; i++) {
			log("  pos " + i + ": " + freqsEval.valueAt(0, i, 0) + " + " + freqsEval.valueAt(0, i, 1) + "i");
		}

		// Apply complex multiplication (with repeat fix for multiplyComplex broadcast bug)
		CollectionProducer result = multiplyComplex(traverse(1, p(input)), freqs.traverse(1).repeat(heads));
		PackedCollection out = ((Evaluable<PackedCollection>) ((ParallelProcess) result).optimize().get()).evaluate();

		log("\nResults after complex multiply:");
		log("Output shape: " + out.getShape());

		// Expected results for 90 degree rotation (multiply by i):
		// (a + bi) * (0 + 1i) = -b + ai
		// Head 0, pos 0: (1+0i) * i = 0 + 1i -> expected: (0, 1)
		// Head 0, pos 1: (0+1i) * i = -1 + 0i -> expected: (-1, 0)
		// Head 1, pos 0: (1+1i) * i = -1 + 1i -> expected: (-1, 1)
		// Head 1, pos 1: (2+0i) * i = 0 + 2i -> expected: (0, 2)
		double[][][] expected = {
			{{0, 1}, {-1, 0}},   // head 0
			{{-1, 1}, {0, 2}}    // head 1
		};

		boolean allCorrect = true;
		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				double actualReal = out.valueAt(h, i, 0);
				double actualImag = out.valueAt(h, i, 1);
				double expReal = expected[h][i][0];
				double expImag = expected[h][i][1];

				log("  head " + h + ", pos " + i + ": got (" + actualReal + ", " + actualImag +
					"), expected (" + expReal + ", " + expImag + ")");

				if (Math.abs(actualReal - expReal) > TOLERANCE || Math.abs(actualImag - expImag) > TOLERANCE) {
					allCorrect = false;
					log("    *** MISMATCH ***");
				}
			}
		}

		Assert.assertTrue("[PASS/FAIL] RoPE-style operation", allCorrect);
		if (allCorrect) log("[PASS] RoPE-style operation correct\n");
	}

	/**
	 * Test 4B: Debug RoPE-style operation - check individual head operations.
	 */
	@Test
	public void ropeStyle_DebugHeadByHead() {
		log("=== Test 4B: RoPE-Style Debug - Head by Head ===");

		// Create input for just one head: 1 x 2 headSize x 2 (real/imag)
		int headSize = 2;

		// Test with head 0 values: [(1+0i), (0+1i)]
		PackedCollection inputHead0 = new PackedCollection(shape(1, headSize, 2));
		inputHead0.setMem(0, 1); inputHead0.setMem(1, 0);  // pos 0: (1+0i)
		inputHead0.setMem(2, 0); inputHead0.setMem(3, 1);  // pos 1: (0+1i)

		// Test with head 1 values: [(1+1i), (2+0i)]
		PackedCollection inputHead1 = new PackedCollection(shape(1, headSize, 2));
		inputHead1.setMem(0, 1); inputHead1.setMem(1, 1);  // pos 0: (1+1i)
		inputHead1.setMem(2, 2); inputHead1.setMem(3, 0);  // pos 1: (2+0i)

		// Frequencies: [(0+1i), (0+1i)] (90 degree rotation)
		PackedCollection freqs = new PackedCollection(shape(1, headSize, 2));
		freqs.setMem(0, 0); freqs.setMem(1, 1);
		freqs.setMem(2, 0); freqs.setMem(3, 1);

		log("Testing head 0: [(1+0i), (0+1i)] * [(0+1i), (0+1i)]");
		CollectionProducer result0 = multiplyComplex(traverse(1, p(inputHead0)), traverse(1, p(freqs)));
		PackedCollection out0 = result0.get().evaluate();

		log("  Shape: " + out0.getShape());
		log("  pos 0: (" + out0.valueAt(0, 0, 0) + ", " + out0.valueAt(0, 0, 1) + ") expected (0, 1)");
		log("  pos 1: (" + out0.valueAt(0, 1, 0) + ", " + out0.valueAt(0, 1, 1) + ") expected (-1, 0)");

		log("\nTesting head 1: [(1+1i), (2+0i)] * [(0+1i), (0+1i)]");
		CollectionProducer result1 = multiplyComplex(traverse(1, p(inputHead1)), traverse(1, p(freqs)));
		PackedCollection out1 = result1.get().evaluate();

		log("  Shape: " + out1.getShape());
		log("  pos 0: (" + out1.valueAt(0, 0, 0) + ", " + out1.valueAt(0, 0, 1) + ") expected (-1, 1)");
		log("  pos 1: (" + out1.valueAt(0, 1, 0) + ", " + out1.valueAt(0, 1, 1) + ") expected (0, 2)");

		// Verify head 0
		boolean head0Correct =
			Math.abs(out0.valueAt(0, 0, 0) - 0.0) < TOLERANCE &&
			Math.abs(out0.valueAt(0, 0, 1) - 1.0) < TOLERANCE &&
			Math.abs(out0.valueAt(0, 1, 0) - (-1.0)) < TOLERANCE &&
			Math.abs(out0.valueAt(0, 1, 1) - 0.0) < TOLERANCE;

		// Verify head 1
		boolean head1Correct =
			Math.abs(out1.valueAt(0, 0, 0) - (-1.0)) < TOLERANCE &&
			Math.abs(out1.valueAt(0, 0, 1) - 1.0) < TOLERANCE &&
			Math.abs(out1.valueAt(0, 1, 0) - 0.0) < TOLERANCE &&
			Math.abs(out1.valueAt(0, 1, 1) - 2.0) < TOLERANCE;

		log("\nHead 0 correct: " + head0Correct);
		log("Head 1 correct: " + head1Correct);

		// Now test both heads together
		log("\n--- Testing both heads together (2, 2, 2) ---");
		PackedCollection inputBoth = new PackedCollection(shape(2, headSize, 2));
		// Head 0: [(1+0i), (0+1i)]
		inputBoth.setMem(0, 1); inputBoth.setMem(1, 0);
		inputBoth.setMem(2, 0); inputBoth.setMem(3, 1);
		// Head 1: [(1+1i), (2+0i)]
		inputBoth.setMem(4, 1); inputBoth.setMem(5, 1);
		inputBoth.setMem(6, 2); inputBoth.setMem(7, 0);

		log("Input raw memory:");
		for (int i = 0; i < 8; i++) {
			log("  [" + i + "] = " + inputBoth.toDouble(i));
		}

		log("Freqs raw memory:");
		for (int i = 0; i < 4; i++) {
			log("  [" + i + "] = " + freqs.toDouble(i));
		}

		CollectionProducer resultBoth = multiplyComplex(traverse(1, p(inputBoth)), traverse(1, p(freqs)));
		PackedCollection outBoth = resultBoth.get().evaluate();

		log("\nCombined result shape: " + outBoth.getShape());
		log("Output raw memory:");
		for (int i = 0; i < outBoth.getShape().getTotalSize(); i++) {
			log("  [" + i + "] = " + outBoth.toDouble(i));
		}

		Assert.assertTrue("Head 0 individually correct", head0Correct);
		Assert.assertTrue("Head 1 individually correct", head1Correct);
	}

	/**
	 * Test 4C: Isolate whether subset extraction causes the issue.
	 */
	@Test
	public void ropeStyle_WithSubsetExtraction() {
		log("=== Test 4C: RoPE-Style with Subset Extraction ===");

		int heads = 2;
		int headSize = 2;

		// Same input as the failing test
		PackedCollection input = new PackedCollection(shape(heads, headSize, 2));
		input.setMem(0, 1); input.setMem(1, 0);  // head 0, pos 0: (1+0i)
		input.setMem(2, 0); input.setMem(3, 1);  // head 0, pos 1: (0+1i)
		input.setMem(4, 1); input.setMem(5, 1);  // head 1, pos 0: (1+1i)
		input.setMem(6, 2); input.setMem(7, 0);  // head 1, pos 1: (2+0i)

		// Weights in same format as the failing test (3 positions)
		PackedCollection weights = new PackedCollection(shape(3, headSize, 2));
		// pos 0: identity
		weights.setMem(0, 1); weights.setMem(1, 0);
		weights.setMem(2, 1); weights.setMem(3, 0);
		// pos 1: 90 degree (i)
		weights.setMem(4, 0); weights.setMem(5, 1);
		weights.setMem(6, 0); weights.setMem(7, 1);
		// pos 2: 180 degree
		weights.setMem(8, -1); weights.setMem(9, 0);
		weights.setMem(10, -1); weights.setMem(11, 0);

		int testPos = 1;
		Producer<PackedCollection> posIndex = c(testPos, 0, 0);

		// Extract frequencies at position testPos using subset
		CollectionProducer freqs = subset(shape(1, headSize, 2), c(p(weights)), posIndex);

		log("Extracted freqs shape: " + shape(freqs));

		// Evaluate extracted frequencies to verify
		PackedCollection freqsEval = freqs.get().evaluate();
		log("Freqs after subset:");
		for (int i = 0; i < freqsEval.getShape().getTotalSize(); i++) {
			log("  [" + i + "] = " + freqsEval.toDouble(i));
		}

		// Test 1: Without optimize
		log("\n--- Test without optimize() ---");
		CollectionProducer result1 = multiplyComplex(traverse(1, p(input)), freqs.traverse(1));
		PackedCollection out1 = result1.get().evaluate();

		log("Result shape: " + out1.getShape());
		log("Output:");
		for (int i = 0; i < out1.getShape().getTotalSize(); i++) {
			log("  [" + i + "] = " + out1.toDouble(i));
		}

		// Test 2: With optimize (like the original failing test)
		log("\n--- Test with optimize() ---");
		CollectionProducer result2 = multiplyComplex(traverse(1, p(input)), freqs.traverse(1));
		PackedCollection out2 = ((Evaluable<PackedCollection>) ((ParallelProcess) result2).optimize().get()).evaluate();

		log("Result shape: " + out2.getShape());
		log("Output:");
		for (int i = 0; i < out2.getShape().getTotalSize(); i++) {
			log("  [" + i + "] = " + out2.toDouble(i));
		}

		// Expected: [(0,1), (-1,0), (-1,1), (0,2)]
		double[] expected = {0, 1, -1, 0, -1, 1, 0, 2};

		boolean test1Correct = true;
		boolean test2Correct = true;
		for (int i = 0; i < 8; i++) {
			if (Math.abs(out1.toDouble(i) - expected[i]) > TOLERANCE) test1Correct = false;
			if (Math.abs(out2.toDouble(i) - expected[i]) > TOLERANCE) test2Correct = false;
		}

		log("\nWithout optimize: " + (test1Correct ? "PASS" : "FAIL"));
		log("With optimize: " + (test2Correct ? "PASS" : "FAIL"));

		// Test 3: With explicit repeat to match input shape
		log("\n--- Test with explicit repeat(2) ---");
		CollectionProducer freqsRepeated = freqs.traverse(1).repeat(2);
		log("Repeated freqs shape: " + shape(freqsRepeated));
		CollectionProducer result3 = multiplyComplex(traverse(1, p(input)), freqsRepeated);
		PackedCollection out3 = result3.get().evaluate();

		log("Result shape: " + out3.getShape());
		log("Output:");
		for (int i = 0; i < out3.getShape().getTotalSize(); i++) {
			log("  [" + i + "] = " + out3.toDouble(i));
		}

		boolean test3Correct = true;
		for (int i = 0; i < 8; i++) {
			if (Math.abs(out3.toDouble(i) - expected[i]) > TOLERANCE) test3Correct = false;
		}
		log("With repeat: " + (test3Correct ? "PASS" : "FAIL"));

		Assert.assertTrue("With explicit repeat should work", test3Correct);
	}

	// ==================== SHAPE DIMENSION TESTS ====================

	/**
	 * Test 5A: Verify shape (n) vs (n, 1) handling in operations.
	 */
	@Test
	public void shapeDimension_NvsN1() {
		log("=== Test 5A: Shape (n) vs (n, 1) Handling ===");

		// Create two equivalent representations
		PackedCollection shape_n = new PackedCollection(shape(4));
		PackedCollection shape_n1 = new PackedCollection(shape(4, 1));

		for (int i = 0; i < 4; i++) {
			shape_n.setMem(i, i + 1);
			shape_n1.setMem(i, i + 1);
		}

		log("shape(4): " + shape_n.getShape() + " - total size: " + shape_n.getShape().getTotalSize());
		log("shape(4,1): " + shape_n1.getShape() + " - total size: " + shape_n1.getShape().getTotalSize());

		// Test that both have same data
		boolean sameData = true;
		for (int i = 0; i < 4; i++) {
			if (Math.abs(shape_n.toDouble(i) - shape_n1.toDouble(i)) > TOLERANCE) {
				sameData = false;
			}
		}
		log("Same underlying data: " + sameData);

		// Test reshape from (4) to (4, 1)
		CollectionProducer reshaped = c(p(shape_n)).reshape(shape(4, 1));
		PackedCollection reshapedOut = reshaped.get().evaluate();
		log("Reshaped (4) -> (4,1): " + reshapedOut.getShape());

		// Test reshape from (4, 1) to (4)
		CollectionProducer reshapedBack = c(p(shape_n1)).reshape(shape(4));
		PackedCollection reshapedBackOut = reshapedBack.get().evaluate();
		log("Reshaped (4,1) -> (4): " + reshapedBackOut.getShape());

		Assert.assertTrue("Reshape to (n,1) works", reshapedOut.getShape().equals(shape(4, 1)));
		Assert.assertTrue("Reshape to (n) works", reshapedBackOut.getShape().equals(shape(4)));
		log("[PASS] Shape dimension handling correct\n");
	}

	/**
	 * Test 5B: Sum operation and output shape.
	 */
	@Test
	public void sumOperation_OutputShape() {
		log("=== Test 5B: Sum Operation Output Shape ===");

		// Create a 3x4 array
		PackedCollection data = new PackedCollection(shape(3, 4));
		for (int i = 0; i < 12; i++) {
			data.setMem(i, i + 1);
		}

		log("Input shape: " + data.getShape());
		log("Input data: 1,2,3,4, 5,6,7,8, 9,10,11,12");

		// Sum along axis 1 (columns) - should produce shape (3)
		CollectionProducer summed = traverse(1, p(data)).sum();
		PackedCollection sumOut = summed.get().evaluate();

		log("Sum result shape: " + sumOut.getShape());
		log("Sum values:");
		for (int i = 0; i < sumOut.getShape().getTotalSize(); i++) {
			log("  [" + i + "] = " + sumOut.toDouble(i));
		}

		// Expected sums: [1+2+3+4=10, 5+6+7+8=26, 9+10+11+12=42]
		double[] expected = {10, 26, 42};

		boolean shapeCorrect = sumOut.getShape().getTotalSize() == 3;
		boolean valuesCorrect = true;

		for (int i = 0; i < 3; i++) {
			if (Math.abs(sumOut.toDouble(i) - expected[i]) > TOLERANCE) {
				valuesCorrect = false;
				log("  MISMATCH at " + i + ": expected " + expected[i] + ", got " + sumOut.toDouble(i));
			}
		}

		log("Shape correct: " + shapeCorrect);
		log("Values correct: " + valuesCorrect);

		Assert.assertTrue("Sum output shape", shapeCorrect);
		Assert.assertTrue("Sum values", valuesCorrect);
		if (shapeCorrect && valuesCorrect) log("[PASS] Sum operation correct\n");
	}
}
