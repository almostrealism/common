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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests a host-function producer ({@link DynamicCollectionProducer}) with a variable count used
 * as the condition operand of a batched conditional selection over three rows of three-element
 * vector branches.
 *
 * <p>With a variable count, the shape of a {@link DynamicCollectionProducer} is the shape of one
 * item and its function supplies one item per row. A per-row condition is therefore one value per
 * item, and every channel of a row selects by that row's single condition, exactly as it does for
 * a variable-shape pass-through input.</p>
 */
public class DynamicSelectionOperandTests extends TestSuiteBase {
	/** The per-row broadcast result for the row conditions {@code 1, -1, 0.5}. */
	private static final double[] PER_ROW = { 10, 11, 12, 23, 24, 25, 16, 17, 18 };

	/**
	 * Evaluates {@code greaterThan(condition, 0, trueBranch, falseBranch)} over three rows of a
	 * three-element vector, with the branches supplied as arguments, and returns the result.
	 *
	 * @param condition the condition operand
	 * @return the selected values
	 */
	protected PackedCollection select(Producer<PackedCollection> condition) {
		Producer<PackedCollection> trueBranch = v(shape(-1, 3), 0);
		Producer<PackedCollection> falseBranch = v(shape(-1, 3), 1);

		PackedCollection trueValues =
				pack(10, 11, 12, 13, 14, 15, 16, 17, 18).reshape(3, 3).traverse(1);
		PackedCollection falseValues =
				pack(20, 21, 22, 23, 24, 25, 26, 27, 28).reshape(3, 3).traverse(1);
		PackedCollection result = new PackedCollection(3, 3);

		greaterThan(condition, c(0.0), trueBranch, falseBranch).get()
				.into(result.each()).evaluate(trueValues, falseValues);
		return result;
	}

	/**
	 * Creates a variable-count host-function producer with the given item shape, whose function
	 * returns the given values.
	 *
	 * @param itemShape the shape of one item
	 * @param kernel    whether the producer evaluates its function once for the whole output
	 * @param values    the values the function returns
	 * @return the condition producer
	 */
	protected DynamicCollectionProducer condition(TraversalPolicy itemShape, boolean kernel, PackedCollection values) {
		return new DynamicCollectionProducer(itemShape, args -> values, kernel, false);
	}

	/**
	 * Asserts that every output cell holds the expected value.
	 *
	 * @param expected the expected values
	 * @param result   the selected values
	 */
	protected void assertSelected(double[] expected, PackedCollection result) {
		Assert.assertEquals(expected.length, result.getMemLength());
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("output " + i, expected[i], result.toDouble(i), 0.001);
		}
	}

	/**
	 * A per-row condition declared with a fixed item shape of one value, evaluated once for the
	 * whole output, governs every channel of its row. The operand has a fixed shape but a variable
	 * count, so it must be projected per row like a variable-shape input.
	 */
	@Test(timeout = 60000)
	public void kernelPerRowConditionBroadcasts() {
		assertSelected(PER_ROW, select(condition(shape(1), true, pack(1.0, -1.0, 0.5).reshape(3, 1))));
	}

	/**
	 * A per-row condition declared with a fixed item shape of one value, evaluated through its
	 * function, governs every channel of its row.
	 */
	@Test(timeout = 60000)
	public void functionPerRowConditionBroadcasts() {
		assertSelected(PER_ROW, select(condition(shape(1), false, pack(1.0, -1.0, 0.5).reshape(3, 1))));
	}

	/**
	 * A per-row condition declared with the variable shape {@code (-1, 1)} governs every channel
	 * of its row, matching the variable-shape pass-through input.
	 */
	@Test(timeout = 60000)
	public void variableShapePerRowConditionBroadcasts() {
		assertSelected(PER_ROW, select(condition(shape(-1, 1), false, pack(1.0, -1.0, 0.5).reshape(3, 1))));
	}

	/**
	 * A fixed-count condition as wide as the whole output is read element-wise: every channel
	 * selects by its own value rather than being broadcast per row. This is the counterpart to the
	 * variable-count cases above &mdash; a fixed count keeps the ordinary element-wise read, which
	 * is what {@code conditionIndex} short-circuits on through
	 * {@link io.almostrealism.relation.Countable#isFixedCount(Object)}, whereas a variable count is
	 * projected to one value per row.
	 */
	@Test(timeout = 60000)
	public void fixedCountConditionSelectsElementWise() {
		assertSelected(new double[] { 10, 21, 12, 23, 24, 25, 16, 17, 28 },
				select(cp(pack(1.0, -1.0, 1.0, -1.0, -1.0, -1.0, 1.0, 1.0, -1.0).reshape(3, 3)).traverse(1)));
	}

	/**
	 * A variable-count condition whose item is wider than a single value but narrower than the
	 * output item has no unambiguous per-row projection, so the selection is rejected rather than
	 * reading the operand past the end of its buffer. The item shape {@code (2)} supplies two
	 * values per row against a three-element output item. The rejection surfaces as an
	 * {@link IllegalArgumentException} raised while the kernel is compiled.
	 */
	@Test(timeout = 60000)
	public void narrowerItemConditionRejected() {
		try {
			select(condition(shape(2), false, pack(1.0, -1.0).reshape(2, 1)));
			Assert.fail("Expected the narrower variable-count operand to be rejected");
		} catch (RuntimeException e) {
			Throwable cause = e;
			while (cause != null && !(cause instanceof IllegalArgumentException)) {
				cause = cause.getCause();
			}
			Assert.assertNotNull("Expected an IllegalArgumentException in the cause chain", cause);
		}
	}
}
