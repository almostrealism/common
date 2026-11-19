/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.color.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for RGB color mathematics operations including addition and conditional logic.
 * These tests validate color arithmetic and comparison operations used in rendering pipelines.
 */
public class ColorMathTest extends TestSuiteBase implements RGBFeatures {

	/**
	 * Tests basic color addition of black and white RGB values.
	 * Validates that black (0,0,0) + white (1,1,1) = white (1,1,1).
	 *
	 * <p>This test verifies that the add operation correctly sums corresponding
	 * RGB components across color values.</p>
	 */
	@Test(timeout = 30000)
	public void fixedSum() {
		verboseLog(() -> {
			Producer<PackedCollection> p1 = black();
			Producer<PackedCollection> p2 = white();
			Producer<PackedCollection> sum = add(p1, p2);

			PackedCollection result = sum.get().evaluate();
			assertEquals(1.0, result.toDouble(0));
			assertEquals(1.0, result.toDouble(1));
			assertEquals(1.0, result.toDouble(2));
		});
	}

	/**
	 * Tests conditional color selection based on scalar comparison.
	 * Compares a scalar value (0.1) against 0.0 and returns either a custom RGB value or black.
	 *
	 * <p>Operation: if arg0 > 0.0 then return arg1 (0.0, 1.0, 0.0) else return black (0,0,0)</p>
	 * <p>Since 0.1 > 0.0, the result should be the green-only color (0.0, 1.0, 0.0).</p>
	 *
	 * <p>This test validates the greaterThan operation with custom return values for
	 * true and false conditions.</p>
	 */
	@Test(timeout = 30000)
	public void greaterThan() {
		verboseLog(() -> {
			Producer<PackedCollection> arg0 = v(shape(1), 0);
			Producer<PackedCollection> arg1 = v(RGB.shape(), 1);

			CollectionProducer greater =
					greaterThan(arg0, c(0.0), arg1, black());
			RGB result = new RGB(greater.get().evaluate(pack(0.1), new RGB(0.0, 1.0, 0.0)), 0);
			assertEquals(0.0, result.toDouble(0));
			assertEquals(1.0, result.toDouble(1));
			assertEquals(0.0, result.toDouble(2));
		});
	}

	/**
	 * Tests batched conditional color selection using hardware-accelerated kernel operations.
	 * Processes a batch of 5 scalar values and returns white or black for each based on comparison to 0.0.
	 *
	 * <p>Operation: For each scalar in the input batch:
	 * <ul>
	 *   <li>if value > 0.0, return white (1,1,1)</li>
	 *   <li>if value <= 0.0, return black (0,0,0)</li>
	 * </ul></p>
	 *
	 * <p>Input values: [0.0, -1.0, 1.0, -0.1, 0.1]</p>
	 * <p>Expected results: [black, black, white, black, white]</p>
	 *
	 * <p>The condition is one value per row (a variable-count {@code (-1, 1)} input) while the
	 * true and false branches ({@code white()} and {@code black()}) are a three-element vector.
	 * The selection broadcasts each row's single condition across all three of that row's output
	 * channels; reading the condition at the output element index instead would walk across
	 * neighbouring rows with the branches' stride. The per-row broadcast is handled by the
	 * comparison-operand index projection shared by the conditional selection computations.</p>
	 *
	 * @see #greaterThan() for a working single-value comparison example
	 */
	@Test(timeout = 30000)
	public void greaterThanKernel() {

		verboseLog(() -> {
			Producer<PackedCollection> arg0 = v(shape(-1, 1), 0);

			PackedCollection result = RGB.bank(5);
			PackedCollection input = new PackedCollection(5, 1).traverse(1);
			input.set(0, 0.0);
			input.set(1, -1.0);
			input.set(2, 1.0);
			input.set(3, -0.1);
			input.set(4, 0.1);

			input.print();

			CollectionProducer greater =
					greaterThan(arg0, c(0.0), white(), black());
			greater.get().into(result.each()).evaluate(input);  // Use .each() for batched processing
			result.print();

			assertEquals(0.0, result.get(0).toDouble(1));
			assertEquals(0.0, result.get(1).toDouble(1));
			assertEquals(1.0, result.get(2).toDouble(1));
			assertEquals(0.0, result.get(3).toDouble(1));
			assertEquals(1.0, result.get(4).toDouble(1));
		});
	}
}
