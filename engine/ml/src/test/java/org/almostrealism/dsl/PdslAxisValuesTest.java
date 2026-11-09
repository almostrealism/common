/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests of {@link PdslAxisValues}, the tensor argument that holds one value per batch entry and
 * position of an axis of a stage's input. The values are handed to the stage only when they are
 * exactly {@code [batch, length(axis)]} for its input; an axis that is not a non-batch axis of the
 * input, and values of any other layout, including a transposed or flat tensor with the right
 * number of values, are rejected with a {@link PdslParseException}.
 */
public class PdslAxisValuesTest extends TestSuiteBase {

	/** The input of every case: a batch of two entries of three positions of four channels. */
	private static final TraversalPolicy INPUT = new TraversalPolicy(2, 3, 4);

	/** Values of exactly {@code [batch, length(axis)]} are handed out unchanged, along any non-batch axis. */
	@Test(timeout = 60000)
	public void forInputHandsOutValuesOfTheExactLayout() {
		CollectionProducer perChannel = values(2, 4);
		CollectionProducer perPosition = values(2, 3);
		Assert.assertSame(perChannel, new PdslAxisValues("test() values", perChannel, 2).forInput(INPUT));
		Assert.assertSame(perPosition, new PdslAxisValues("test() values", perPosition, 1).forInput(INPUT));
	}

	/**
	 * Values with the right count in another layout are rejected rather than reshaped: a transposed
	 * {@code [4, 2]}, a flat {@code [8]} and a three-axis {@code [2, 1, 4]} tensor all hold the eight
	 * values a {@code [2, 4]} tensor holds, in a layout the broadcast would misread.
	 */
	@Test(timeout = 60000)
	public void forInputRejectsAnotherLayoutOfTheSameCount() {
		assertRejected("transposed values", values(4, 2), 2);
		assertRejected("flat values", values(8), 2);
		assertRejected("three-axis values", values(2, 1, 4), 2);
	}

	/** Values for another batch size, or for another axis's length, are rejected. */
	@Test(timeout = 60000)
	public void forInputRejectsValuesOfTheWrongCount() {
		assertRejected("values for one batch entry", values(1, 4), 2);
		assertRejected("values for the length of another axis", values(2, 3), 2);
	}

	/** The batch axis, and an axis past the last one of the input, are rejected. */
	@Test(timeout = 60000)
	public void forInputRejectsAnAxisThatIsNotANonBatchAxisOfTheInput() {
		assertRejected("the batch axis", values(2, 2), 0);
		assertRejected("an axis past the last", values(2, 4), 3);
	}

	/**
	 * Returns a producer of zero values of the given shape.
	 *
	 * @param dims the shape of the values
	 * @return the producer
	 */
	private CollectionProducer values(int... dims) {
		return cp(new PackedCollection(shape(dims)));
	}

	/**
	 * Asserts that values along the given axis are rejected for {@link #INPUT}.
	 *
	 * @param description what is rejected, for the failure message
	 * @param values      the values
	 * @param axis        the axis the values claim to index
	 */
	private void assertRejected(String description, CollectionProducer values, int axis) {
		PdslAxisValues axisValues = new PdslAxisValues("test() values", values, axis);
		try {
			axisValues.forInput(INPUT);
			Assert.fail("forInput should reject " + description);
		} catch (PdslParseException expected) {
			log("rejected " + description + ": " + expected.getMessage());
		}
	}
}
