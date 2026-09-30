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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionPermute;
import org.almostrealism.collect.computations.CollectionSubsetComputation;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins how the shape-dependent collection computations (repeat, enumerate,
 * subset, permute) treat their input: a shaped input determines the output
 * shape, and an input that is not a {@link io.almostrealism.collect.Shape}
 * is rejected with an {@link IllegalArgumentException} naming the operation.
 */
public class ShapeRequirementTests extends TestSuiteBase {

	/** Returns a producer that does not implement {@link io.almostrealism.collect.Shape}. */
	private static Producer<PackedCollection> shapeless() {
		return () -> null;
	}

	/**
	 * Asserts that {@code construction} throws an {@link IllegalArgumentException}
	 * carrying {@code expectedMessage}.
	 */
	private static void assertRejected(String expectedMessage, Runnable construction) {
		try {
			construction.run();
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(expectedMessage, e.getMessage());
			return;
		}

		Assert.fail("Expected IllegalArgumentException: " + expectedMessage);
	}

	/** Repeat rejects a shapeless input through both constructors. */
	@Test(timeout = 30000)
	public void repeatRequiresShape() {
		String msg = "Repeat cannot be performed without a TraversalPolicy";
		assertRejected(msg, () -> new PackedCollectionRepeat(2, shapeless()));
		assertRejected(msg, () -> new PackedCollectionRepeat(shape(3), 2, shapeless()));
	}

	/** Enumerate rejects a shapeless input through each constructor. */
	@Test(timeout = 30000)
	public void enumerateRequiresShape() {
		String msg = "Enumerate cannot be performed without a TraversalPolicy";
		assertRejected(msg, () -> new PackedCollectionEnumerate(shape(2), shapeless()));
		assertRejected(msg, () -> new PackedCollectionEnumerate(shape(2), shape(2), shapeless()));
		assertRejected(msg, () -> new PackedCollectionEnumerate(shape(2), shape(2), shapeless(), 0));
	}

	/** Both subset computations reject a shapeless input. */
	@Test(timeout = 30000)
	public void subsetRequiresShape() {
		String msg = "Subset cannot be performed without a TraversalPolicy";
		assertRejected(msg, () -> new PackedCollectionSubset(shape(2), shapeless(), new IntegerConstant(0)));
		assertRejected(msg, () -> new PackedCollectionSubset(shape(2), shapeless(), cp(new PackedCollection(1))));
		assertRejected(msg, () -> new CollectionSubsetComputation(shape(2), shapeless(),
				new Expression<?>[] { new IntegerConstant(0) }));
	}

	/** Permute rejects a shapeless input, naming the operation like its siblings. */
	@Test(timeout = 30000)
	public void permuteRequiresShape() {
		assertRejected("Permute cannot be performed without a TraversalPolicy",
				() -> new CollectionPermute(shapeless(), 1, 0));
	}

	/** A shaped input determines the output shape of each computation. */
	@Test(timeout = 30000)
	public void shapedInputsDetermineOutputShape() {
		Producer<PackedCollection> input = cp(new PackedCollection(shape(4, 6)));

		assertShape(shape(2, 4, 6), new PackedCollectionRepeat(2, input).getShape());
		assertShape(shape(3, 4, 6), new PackedCollectionRepeat(shape(4, 6), 3, input).getShape());
		assertShape(shape(6, 4), new CollectionPermute(input, 1, 0).getShape());
		assertShape(shape(2, 3), new PackedCollectionSubset(shape(2, 3), input,
				new IntegerConstant(1), new IntegerConstant(2)).getShape());
		assertShape(shape(2, 3), new CollectionSubsetComputation(shape(2, 3), input,
				new Expression<?>[] { new IntegerConstant(1), new IntegerConstant(2) }).getShape());
		assertShape(shape(3, 4, 2), new PackedCollectionEnumerate(shape(4, 2), input).getShape());
	}

	/** {@link Shape#asShape} returns the very same instance when the value is a {@link Shape}. */
	@Test(timeout = 30000)
	public void asShapeReturnsSameInstance() {
		PackedCollection value = new PackedCollection(shape(4, 6));
		Assert.assertSame(value, Shape.asShape("Test", value));
	}

	/** {@link Shape#requireShape} returns the shape of a {@link Shape} value. */
	@Test(timeout = 30000)
	public void requireShapeReturnsShapeOfValue() {
		PackedCollection value = new PackedCollection(shape(4, 6));
		assertShape(shape(4, 6), Shape.requireShape("Test", value));
		assertShape(shape(4, 6), Shape.requireShape("Test", cp(value)));
	}

	/**
	 * Both {@link Shape#asShape} and {@link Shape#requireShape} reject a value
	 * that is not a {@link Shape}, including {@code null}, naming the operation
	 * given as the first argument.
	 */
	@Test(timeout = 30000)
	public void asShapeRejectsNonShapeValues() {
		assertRejected("Custom cannot be performed without a TraversalPolicy",
				() -> Shape.asShape("Custom", "not a shape"));
		assertRejected("Custom cannot be performed without a TraversalPolicy",
				() -> Shape.requireShape("Custom", shapeless()));
		assertRejected("Custom cannot be performed without a TraversalPolicy",
				() -> Shape.asShape("Custom", null));
		assertRejected("Custom cannot be performed without a TraversalPolicy",
				() -> Shape.requireShape("Custom", null));
	}

	/** Asserts that {@code actual} has the same dimensions as {@code expected}. */
	private static void assertShape(TraversalPolicy expected, TraversalPolicy actual) {
		Assert.assertArrayEquals(expected.extent(), actual.extent());
	}
}
