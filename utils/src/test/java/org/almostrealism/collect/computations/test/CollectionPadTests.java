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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class CollectionPadTests implements TestFeatures {
	/**
	 * Tests 2D padding with asymmetric padding (0 on left, 1 on right).
	 * Input: 2x3 collection → Output: 2x5 collection
	 * Padding pattern: [original data][1 column of zeros]
	 */
	@Test
	public void pad2d1() {
		// Create a 2x3 input collection with random data
		PackedCollection<?> data = new PackedCollection<>(2, 3).randFill();
		
		// Apply padding: 0 zeros before, 1 zero after in dimension 1
		// This creates a 2x5 output (2x3 original + 0+1 padding in dim 1)
		PackedCollection<?> out = cp(data).pad(0, 1).traverse(1).evaluate();
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
	 * Input: 2x3 collection → Padded: 2x5 → Delta: 2x5x2x3 gradient tensor
	 */
	@Test
	public void pad2d1Delta() {
		// Create input data and compute its padded version with delta
		PackedCollection<?> data = new PackedCollection<>(2, 3).randFill();
		PackedCollection<?> out = cp(data).pad(0, 1)
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
	 * Input: 2x3 collection → Output: 4x5 collection  
	 * Padding pattern: 1 unit on all sides creates a "border" of zeros around the data
	 */
	@Test
	public void pad2d2() {
		// Create a 2x3 input collection
		PackedCollection<?> data = new PackedCollection<>(2, 3).randFill();
		
		// Apply symmetric padding: 1 unit on all sides of both dimensions
		// This creates a 4x5 output (2+2 x 3+2 = 4x5)
		PackedCollection<?> out = cp(data).pad(1, 1).traverse(1).evaluate();
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

	@Test
	public void pad3d() {
		int n = 2;

		PackedCollection<?> data = new PackedCollection<>(n, 2, 3).randFill();
		PackedCollection<?> out = cp(data).pad(0, 1, 1).traverse(1).evaluate();
		out.traverse(2).print();

		Assert.assertEquals(n, out.getShape().length(0));
		Assert.assertEquals(4, out.getShape().length(1));
		Assert.assertEquals(5, out.getShape().length(2));

		for (int np = 0; np < n; np++) {
			for (int i = 0; i < out.getShape().length(1); i++) {
				for (int j = 0; j < out.getShape().length(2); j++) {
					if (i == 0 || i == 3 || j == 0 || j == 4) {
						assertEquals(0, out.valueAt(np, i, j));
					} else {
						assertEquals(data.valueAt(np, i - 1, j - 1), out.valueAt(np, i, j));
					}
				}
			}
		}
	}

	@Test
	public void pad3dDelta() {
		int n = 2;

		PackedCollection<?> data = new PackedCollection<>(n, 2, 3).randFill();
		PackedCollection<?> out = cp(data)
								.pad(0, 1, 1).delta(cp(data))
								.evaluate();
		log(out.getShape());
		out.traverse(4).print();

		Assert.assertEquals(n, out.getShape().length(0));
		Assert.assertEquals(4, out.getShape().length(1));
		Assert.assertEquals(5, out.getShape().length(2));
		Assert.assertEquals(n, out.getShape().length(3));
		Assert.assertEquals(2, out.getShape().length(4));
		Assert.assertEquals(3, out.getShape().length(5));

		out.getShape().stream().forEach(pos -> {
			int nOut = pos[0]; int iOut = pos[1]; int jOut = pos[2];
			int nIn = pos[3]; int iIn = pos[4]; int jIn = pos[5];

			if (nIn == nOut && iOut == iIn + 1 && jOut == jIn + 1) {
				if (iOut != 0 && iOut != 3 && jOut != 0 && jOut != 4) {
					assertEquals(1.0, out.valueAt(pos));
				} else {
					assertEquals(0.0, out.valueAt(pos));
				}
			} else {
				assertEquals(0.0, out.valueAt(pos));
			}
		});
	}

	@Test
	public void pad4d() {
		int n = 2;
		int c = 4;

		PackedCollection<?> data = new PackedCollection<>(n, c, 2, 3).randFill();
		PackedCollection<?> out = cp(data).pad(0, 0, 1, 1).traverse(1).evaluate();
		out.print();

		Assert.assertEquals(n, out.getShape().length(0));
		Assert.assertEquals(c, out.getShape().length(1));
		Assert.assertEquals(4, out.getShape().length(2));
		Assert.assertEquals(5, out.getShape().length(3));


		for (int np = 0; np < n; np++) {
			for (int cp = 0; cp < c; cp++) {
				for (int i = 0; i < out.getShape().length(2); i++) {
					for (int j = 0; j < out.getShape().length(3); j++) {
						if (i == 0 || i == 3 || j == 0 || j == 4) {
							assertEquals(0, out.valueAt(np, cp, i, j));
						} else {
							assertEquals(data.valueAt(np, cp, i - 1, j - 1), out.valueAt(np, cp, i, j));
						}
					}
				}
			}
		}
	}
}
