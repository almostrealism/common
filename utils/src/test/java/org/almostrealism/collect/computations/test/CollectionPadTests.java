/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.collect.computations.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class CollectionPadTests implements TestFeatures {
	/**
	 * Tests padding computation with subset operations.
	 * Creates a 6-element collection and performs subset operations followed by arithmetic.
	 * The test combines two subsets - one multiplied by 2 and added to the other,
	 * then pads the result with zeros to match the original shape.
	 */
	@Test
	public void padSubset() {
		Producer<PackedCollection> multiplier = func(shape(1), args -> pack(2.0));

		PackedCollection data = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
		CollectionProducer subset1 = c(data).subset(shape(3), 3);
		PackedCollection result = pad(shape(6), subset1.multiply(multiplier), 0).evaluate();
		result.print();

		assertEquals(6, result.getShape().length(0));
		assertEquals(4 * 2, result.valueAt(0));
		assertEquals(5 * 2, result.valueAt(1));
		assertEquals(6 * 2, result.valueAt(2));
		assertEquals(0.0, result.valueAt(3));
		assertEquals(0.0, result.valueAt(4));
		assertEquals(0.0, result.valueAt(5));
	}

	/**
	 * Tests 2D padding with asymmetric padding (0 on left, 1 on right).
	 * Input: 2x3 collection -> Output: 2x5 collection
	 * Padding pattern: [original data][1 column of zeros]
	 */
	@Test
	public void pad2d1() {
		// Create a 2x3 input collection with random data
		PackedCollection data = new PackedCollection(2, 3).randFill();
		
		// Apply padding: 0 zeros before, 1 zero after in dimension 1
		// This creates a 2x5 output (2x3 original + 0+1 padding in dim 1)
		PackedCollection out = cp(data).pad(0, 1).traverse(1).evaluate();
		out.print();

		// Verify output dimensions
		Assert.assertEquals(2, out.getShape().length(0)); // Height unchanged
		Assert.assertEquals(5, out.getShape().length(1)); // Width: 3 + 1 + 1 = 5

		// Verify padding pattern: zeros at the edges, original data in the middle
		for (int i = 0; i < out.getShape().length(0); i++) {
			for (int j = 0; j < out.getShape().length(1); j++) {
				if (j == 0 || j == 4) {
					// First and last columns should be zero (padding)
					assertEquals(0, out.valueAt(i, j));
				} else {
					// Middle columns should contain original data (shifted by offset)
					assertEquals(data.valueAt(i, j - 1), out.valueAt(i, j));
				}
			}
		}
	}

	/**
	 * Tests the gradient computation (delta) for 2D padding operations.
	 * This test demonstrates how padding operations propagate gradients during backpropagation.
	 * Input: 2x3 collection -> Padded: 2x5 -> Delta: 2x5x2x3 gradient tensor
	 */
	@Test
	public void pad2d1Delta() {
		// Create input data and compute its padded version with delta
		PackedCollection data = new PackedCollection(2, 3).randFill();
		PackedCollection out = cp(data).pad(0, 1)
								.delta(cp(data)).evaluate();
		log(out.getShape());
		out.traverse(2).print();

		// The delta output has shape [output_dims][input_dims] = [2,5][2,3]
		Assert.assertEquals(2, out.getShape().length(0)); // Original output height
		Assert.assertEquals(5, out.getShape().length(1)); // Original output width  
		Assert.assertEquals(2, out.getShape().length(2)); // Input height
		Assert.assertEquals(3, out.getShape().length(3)); // Input width

		// Verify gradient flow: should be 1.0 where output connects to input, 0.0 elsewhere
		out.getShape().stream().forEach(pos -> {
			int iOut = pos[0]; int jOut = pos[1]; // Output position indices
			int iIn = pos[2]; int jIn = pos[3];   // Input position indices

			// Gradient should be 1.0 when output position corresponds to input position
			if (iOut == iIn && jOut == jIn + 1) { // jOut offset by 1 due to padding
				if (jOut != 0 && jOut != 4) { // Not in padded regions
					assertEquals(1.0, out.valueAt(pos));
				} else {
					assertEquals(0.0, out.valueAt(pos)); // Padded regions have no gradient
				}
			} else {
				assertEquals(0.0, out.valueAt(pos)); // No connection = no gradient
			}
		});
	}

	/**
	 * Tests symmetric 2D padding where equal amounts are added to all sides.
	 * Input: 2x3 collection -> Output: 4x5 collection  
	 * Padding pattern: 1 unit on all sides creates a "border" of zeros around the data
	 */
	@Test
	public void pad2d2() {
		// Create a 2x3 input collection
		PackedCollection data = new PackedCollection(2, 3).randFill();
		
		// Apply symmetric padding: 1 unit on all sides of both dimensions
		// This creates a 4x5 output (2+2 x 3+2 = 4x5)
		PackedCollection out = cp(data).pad(1, 1).traverse(1).evaluate();
		out.print();

		// Verify output dimensions
		Assert.assertEquals(4, out.getShape().length(0)); // Height: 2 + 1+1 = 4
		Assert.assertEquals(5, out.getShape().length(1)); // Width: 3 + 1+1 = 5

		// Verify padding pattern: zeros around the border, original data in center
		for (int i = 0; i < out.getShape().length(0); i++) {
			for (int j = 0; j < out.getShape().length(1); j++) {
				if (i == 0 || i == 3 || j == 0 || j == 4) {
					// Border cells should be zero (padding)
					assertEquals(0, out.valueAt(i, j));
				} else {
					// Inner cells should contain original data (shifted by 1 in each dimension)
					assertEquals(data.valueAt(i - 1, j - 1), out.valueAt(i, j));
				}
			}
		}
	}

	/**
	 * Tests 3D padding with selective dimension padding.
	 * Input: 2x2x3 collection -> Output: 2x4x5 collection
	 * Demonstrates padding only certain dimensions while leaving others unchanged.
	 */
	@Test
	public void pad3d() {
		int n = 2;

		// Create a 3D input collection: 2x2x3
		PackedCollection data = new PackedCollection(n, 2, 3).randFill();
		
		// Apply padding: 0 in dim 0, 1 in dim 1, 1 in dim 2
		// This creates a 2x4x5 output (2+0, 2+2, 3+2)
		PackedCollection out = cp(data).pad(0, 1, 1).traverse(1).evaluate();
		out.traverse(2).print();

		// Verify output dimensions  
		Assert.assertEquals(n, out.getShape().length(0));     // Unchanged: 2
		Assert.assertEquals(4, out.getShape().length(1));     // Padded: 2 + 1+1 = 4
		Assert.assertEquals(5, out.getShape().length(2));     // Padded: 3 + 1+1 = 5

		// Verify 3D padding pattern across all dimensions
		for (int np = 0; np < n; np++) {
			for (int i = 0; i < out.getShape().length(1); i++) {
				for (int j = 0; j < out.getShape().length(2); j++) {
					if (i == 0 || i == 3 || j == 0 || j == 4) {
						// Border regions in padded dimensions should be zero
						assertEquals(0, out.valueAt(np, i, j));
					} else {
						// Inner regions should contain original data (offset by padding)
						assertEquals(data.valueAt(np, i - 1, j - 1), out.valueAt(np, i, j));
					}
				}
			}
		}
	}

	/**
	 * Tests gradient computation for 3D padding operations.
	 * This demonstrates how gradients flow through multi-dimensional padding.
	 * Input: 2x2x3 -> Padded: 2x4x5 -> Delta: 2x4x5x2x2x3 gradient tensor
	 */
	@Test
	public void pad3dDelta() {
		int n = 2;

		// Create 3D input and compute delta through padding operation
		PackedCollection data = new PackedCollection(n, 2, 3).randFill();
		PackedCollection out = cp(data)
								.pad(0, 1, 1).delta(cp(data))
								.evaluate();
		log(out.getShape());
		out.traverse(4).print();

		// Delta output combines output shape [2,4,5] with input shape [2,2,3]
		Assert.assertEquals(n, out.getShape().length(0)); // Output dim 0
		Assert.assertEquals(4, out.getShape().length(1));  // Output dim 1 (padded)
		Assert.assertEquals(5, out.getShape().length(2));  // Output dim 2 (padded) 
		Assert.assertEquals(n, out.getShape().length(3));  // Input dim 0
		Assert.assertEquals(2, out.getShape().length(4));  // Input dim 1
		Assert.assertEquals(3, out.getShape().length(5));  // Input dim 2

		// Verify 3D gradient flow relationships
		out.getShape().stream().forEach(pos -> {
			int nOut = pos[0]; int iOut = pos[1]; int jOut = pos[2]; // Output indices
			int nIn = pos[3]; int iIn = pos[4]; int jIn = pos[5];    // Input indices

			// Gradient is 1.0 when output position maps to input position
			if (nIn == nOut && iOut == iIn + 1 && jOut == jIn + 1) {
				if (iOut != 0 && iOut != 3 && jOut != 0 && jOut != 4) {
					// Non-padded regions have gradient of 1.0
					assertEquals(1.0, out.valueAt(pos));
				} else {
					// Padded regions have no gradient connection
					assertEquals(0.0, out.valueAt(pos));
				}
			} else {
				// No mapping = no gradient
				assertEquals(0.0, out.valueAt(pos));
			}
		});
	}

	/**
	 * Tests 4D padding commonly used in neural network scenarios.
	 * Input: 2x4x2x3 collection (batch x channels x height x width) -> Output: 2x4x4x5
	 * Demonstrates padding only spatial dimensions while preserving batch and channel dimensions.
	 * This pattern is typical in convolutional neural networks where spatial padding is applied
	 * but batch and channel dimensions remain unchanged.
	 */
	@Test
	public void pad4d() {
		int n = 2; // Batch size
		int c = 4; // Number of channels

		// Create 4D input: [batch, channels, height, width] = [2, 4, 2, 3]
		PackedCollection data = new PackedCollection(n, c, 2, 3).randFill();
		
		// Apply padding only to spatial dimensions (height and width)
		// Padding: [0, 0, 1, 1] means no padding for batch/channels, 1 unit padding for spatial dims
		PackedCollection out = cp(data).pad(0, 0, 1, 1).traverse(1).evaluate();
		out.print();

		// Verify output dimensions
		Assert.assertEquals(n, out.getShape().length(0)); // Batch unchanged: 2
		Assert.assertEquals(c, out.getShape().length(1)); // Channels unchanged: 4  
		Assert.assertEquals(4, out.getShape().length(2)); // Height padded: 2 + 1+1 = 4
		Assert.assertEquals(5, out.getShape().length(3)); // Width padded: 3 + 1+1 = 5

		// Verify 4D padding pattern: only spatial dimensions are padded
		for (int np = 0; np < n; np++) {       // Iterate through batches
			for (int cp = 0; cp < c; cp++) {   // Iterate through channels
				for (int i = 0; i < out.getShape().length(2); i++) { // Height
					for (int j = 0; j < out.getShape().length(3); j++) { // Width
						if (i == 0 || i == 3 || j == 0 || j == 4) {
							// Spatial borders should be zero (padding)
							assertEquals(0, out.valueAt(np, cp, i, j));
						} else {
							// Inner spatial regions contain original data
							assertEquals(data.valueAt(np, cp, i - 1, j - 1), out.valueAt(np, cp, i, j));
						}
					}
				}
			}
		}
	}


	@Test
	public void padSmallBatch() {
		// Test padding operation with a batch of scalars
		// We want to pad each scalar in the batch to position 0 of shape(2)
		Producer<PackedCollection> input = v(shape(-1, 1), 0);
		Producer<PackedCollection> padded = pad(shape(2), input, 0);

		// Create batch of 3 scalars
		PackedCollection scalars = new PackedCollection(shape(3, 1).traverse(1));
		scalars.setMem(0, 5.0);   // Batch 0
		scalars.setMem(1, 10.0);  // Batch 1
		scalars.setMem(2, 15.0);  // Batch 2

		// Pad to shape(2) - should give [5.0, 0.0], [10.0, 0.0], [15.0, 0.0]
		PackedCollection result = new PackedCollection(shape(3, 2).traverse(1));
		padded.get().into(result.each()).evaluate(scalars);

		System.out.println("Pad batch test:");
		System.out.println("  Batch 0: [" + result.valueAt(0, 0) + ", " + result.valueAt(0, 1) + "] (expected [5.0, 0.0])");
		System.out.println("  Batch 1: [" + result.valueAt(1, 0) + ", " + result.valueAt(1, 1) + "] (expected [10.0, 0.0])");
		System.out.println("  Batch 2: [" + result.valueAt(2, 0) + ", " + result.valueAt(2, 1) + "] (expected [15.0, 0.0])");

		Assert.assertEquals(5.0, result.valueAt(0, 0), 0.01);
		Assert.assertEquals(0.0, result.valueAt(0, 1), 0.01);
		Assert.assertEquals(10.0, result.valueAt(1, 0), 0.01);
		Assert.assertEquals(0.0, result.valueAt(1, 1), 0.01);
		Assert.assertEquals(15.0, result.valueAt(2, 0), 0.01);
		Assert.assertEquals(0.0, result.valueAt(2, 1), 0.01);
	}

	@Test
	public void concatSmallBatch() {
		// Test concat operation with batch of scalars
		// We'll create two separate values from a scalar input and concat them
		Producer<PackedCollection> input = v(shape(-1, 1), 0);
		Producer<PackedCollection> doubled = c(input).multiply(c(2.0));
		Producer<PackedCollection> concatenated = concat(shape(2), input, doubled);

		// Create batch of 3 scalars: [5, 10, 15]
		// Expected: concat([5, 10], [10, 20], [15, 30])
		PackedCollection scalars = new PackedCollection(shape(3, 1).traverse(1));
		scalars.setMem(0, 5.0);   // Batch 0: concat([5], [10]) -> [5, 10]
		scalars.setMem(1, 10.0);  // Batch 1: concat([10], [20]) -> [10, 20]
		scalars.setMem(2, 15.0);  // Batch 2: concat([15], [30]) -> [15, 30]

		PackedCollection result = new PackedCollection(shape(3, 2).traverse(1));
		concatenated.get().into(result.each()).evaluate(scalars);

		System.out.println("Concat batch test:");
		System.out.println("  Batch 0: [" + result.valueAt(0, 0) + ", " + result.valueAt(0, 1) + "] (expected [5.0, 10.0])");
		System.out.println("  Batch 1: [" + result.valueAt(1, 0) + ", " + result.valueAt(1, 1) + "] (expected [10.0, 20.0])");
		System.out.println("  Batch 2: [" + result.valueAt(2, 0) + ", " + result.valueAt(2, 1) + "] (expected [15.0, 30.0])");

		Assert.assertEquals(5.0, result.valueAt(0, 0), 0.01);
		Assert.assertEquals(10.0, result.valueAt(0, 1), 0.01);
		Assert.assertEquals(10.0, result.valueAt(1, 0), 0.01);
		Assert.assertEquals(20.0, result.valueAt(1, 1), 0.01);
		Assert.assertEquals(15.0, result.valueAt(2, 0), 0.01);
		Assert.assertEquals(30.0, result.valueAt(2, 1), 0.01);
	}

	@Test
	public void concatLargeBatch() {
		// Test concat with exactly 256 elements to check for batch size limit
		Producer<PackedCollection> input = v(shape(-1, 1), 0);
		Producer<PackedCollection> doubled = c(input).multiply(c(2.0));
		Producer<PackedCollection> concatenated = concat(shape(2), input, doubled);

		int batchSize = 256;
		PackedCollection scalars = new PackedCollection(shape(batchSize, 1).traverse(1));
		for (int i = 0; i < batchSize; i++) {
			scalars.setMem(i, (double) i);
		}

		PackedCollection result = new PackedCollection(shape(batchSize, 2).traverse(1));
		concatenated.get().into(result.each()).evaluate(scalars);

		System.out.println("Concat large batch test (size=" + batchSize + "):");
		System.out.println("  Element 0: [" + result.valueAt(0, 0) + ", " + result.valueAt(0, 1) + "] (expected [0.0, 0.0])");
		System.out.println("  Element 1: [" + result.valueAt(1, 0) + ", " + result.valueAt(1, 1) + "] (expected [1.0, 2.0])");
		System.out.println("  Element 100: [" + result.valueAt(100, 0) + ", " + result.valueAt(100, 1) + "] (expected [100.0, 200.0])");
		System.out.println("  Element 255: [" + result.valueAt(255, 0) + ", " + result.valueAt(255, 1) + "] (expected [255.0, 510.0])");

		// Check first few
		Assert.assertEquals(0.0, result.valueAt(0, 0), 0.01);
		Assert.assertEquals(0.0, result.valueAt(0, 1), 0.01);
		Assert.assertEquals(1.0, result.valueAt(1, 0), 0.01);
		Assert.assertEquals(2.0, result.valueAt(1, 1), 0.01);

		// Check middle
		Assert.assertEquals(100.0, result.valueAt(100, 0), 0.01);
		Assert.assertEquals(200.0, result.valueAt(100, 1), 0.01);

		// Check last element (index 255)
		Assert.assertEquals(255.0, result.valueAt(255, 0), 0.01);
		Assert.assertEquals(510.0, result.valueAt(255, 1), 0.01);
	}

	@Test
	public void concat2DTraversal() {
		// Test concat with 2D traversal
		Producer<PackedCollection> input = v(shape(-1, 1), 0);
		Producer<PackedCollection> doubled = c(input).multiply(c(2.0));
		Producer<PackedCollection> concatenated = concat(shape(2), input, doubled);

		// Use 16x16 grid (256 elements total) with .traverse(2)
		int h = 16;
		int w = 16;
		PackedCollection scalars = new PackedCollection(shape(h, w, 1).traverse(2));
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				scalars.setMem(scalars.getShape().index(y, x, 0), (double) (y * w + x));
			}
		}

		PackedCollection result = new PackedCollection(shape(h, w, 2).traverse(2));
		concatenated.get().into(result.each()).evaluate(scalars);

		System.out.println("Concat 2D traversal test (size=" + (h*w) + "):");
		System.out.println("  [0,0]: [" + result.valueAt(0, 0, 0) + ", " + result.valueAt(0, 0, 1) + "] (expected [0.0, 0.0])");
		System.out.println("  [0,1]: [" + result.valueAt(0, 1, 0) + ", " + result.valueAt(0, 1, 1) + "] (expected [1.0, 2.0])");
		System.out.println("  [8,8]: [" + result.valueAt(8, 8, 0) + ", " + result.valueAt(8, 8, 1) + "] (expected [136.0, 272.0])");
		System.out.println("  [15,15]: [" + result.valueAt(15, 15, 0) + ", " + result.valueAt(15, 15, 1) + "] (expected [255.0, 510.0])");

		// Check corners
		Assert.assertEquals(0.0, result.valueAt(0, 0, 0), 0.01);
		Assert.assertEquals(0.0, result.valueAt(0, 0, 1), 0.01);
		Assert.assertEquals(255.0, result.valueAt(15, 15, 0), 0.01);
		Assert.assertEquals(510.0, result.valueAt(15, 15, 1), 0.01);

		// Check middle
		Assert.assertEquals(136.0, result.valueAt(8, 8, 0), 0.01);
		Assert.assertEquals(272.0, result.valueAt(8, 8, 1), 0.01);
	}
}
