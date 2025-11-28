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
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Tests for RGB color mathematics operations including addition and conditional logic.
 * These tests validate color arithmetic and comparison operations used in rendering pipelines.
 */
public class ColorMathTest implements TestFeatures, RGBFeatures {

	/**
	 * Tests basic color addition of black and white RGB values.
	 * Validates that black (0,0,0) + white (1,1,1) = white (1,1,1).
	 *
	 * <p>This test verifies that the add operation correctly sums corresponding
	 * RGB components across color values.</p>
	 */
	@Test
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
	@Test
	public void greaterThan() {
		verboseLog(() -> {
			Producer<PackedCollection> arg0 = v(shape(1), 0);
			Producer<PackedCollection> arg1 = v(RGB.shape(), 1);

			CollectionProducer<PackedCollection> greater =
					greaterThan(arg0, c(0.0), (Producer) arg1, (Producer) black());
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
	 * <p><strong>Known Issue - Shape Inference with Constant RGB Producers:</strong></p>
	 * <p>This test currently fails due to a shape inference problem. When using constant RGB
	 * producers like {@code white()} and {@code black()} (shape 3) with batched scalar inputs
	 * (shape -1,1), the generated kernel incorrectly assumes the input has RGB stride (3) instead
	 * of scalar stride (1).</p>
	 *
	 * <p>The generated kernel reads:</p>
	 * <pre>
	 * _output[(id * 3) + 0] = (_input[(id * 3) + 0] > 0.0) ? 1.0 : 0;  // Wrong stride!
	 * </pre>
	 * <p>Instead of:</p>
	 * <pre>
	 * _output[(id * 3) + 0] = (_input[id] > 0.0) ? 1.0 : 0;  // Correct stride
	 * </pre>
	 *
	 * <p>This causes the kernel to read input values [0, 1, 2] for the first RGB and [3, 4, 5]
	 * for the second RGB, instead of reading scalar value 0 for the first RGB, scalar 1 for the
	 * second RGB, etc.</p>
	 *
	 * <p><strong>Root Cause:</strong> The framework's shape inference mechanism assumes that when
	 * trueValue and falseValue are both shape(3), the input comparison operands must also be
	 * shape(3) per element. This works correctly for single-value operations (see {@link #greaterThan()})
	 * where both scalar and RGB are passed as variable producers, but fails for batched operations
	 * using constants.</p>
	 *
	 * <p><strong>Potential Solutions:</strong></p>
	 * <ul>
	 *   <li>Use per-element evaluation instead of batched kernel (defeats batching purpose)</li>
	 *   <li>Implement explicit scalar-to-RGB expansion operation before conditional</li>
	 *   <li>Create specialized greaterThan variant for scalar-to-vector conversions</li>
	 *   <li>Enhance shape inference to handle dimension mismatches between comparison and result</li>
	 * </ul>
	 *
	 * <p>See {@code /workspace/project/common/utils/test_output/greaterThan_investigation.md}
	 * for detailed analysis and documentation recommendations.</p>
	 *
	 * @see #greaterThan() for a working single-value comparison example
	 */
	@Test
	public void greaterThanKernel() {
		if (skipKnownIssues) return;

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

			CollectionProducer<PackedCollection> greater =
					greaterThan(arg0, c(0.0), (Producer) white(), black());
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
