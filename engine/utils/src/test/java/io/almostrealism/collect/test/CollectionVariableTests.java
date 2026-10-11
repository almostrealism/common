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

package io.almostrealism.collect.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Verifies that a {@link CollectionVariable} whose producer is a delegating wrapper, such as
 * a {@link MemoryDataDestinationProducer}, takes its shape and its count from the wrapped
 * delegate rather than from the wrapper itself.
 */
public class CollectionVariableTests extends TestSuiteBase implements ExpressionFeatures {

	/** A single-element shape, for which only a fixed count pins every index to zero. */
	private static final TraversalPolicy SCALAR = new TraversalPolicy(1);

	/**
	 * Returns a {@link Countable} that is neither a {@link Shape} nor traversable, so that a
	 * variable produced by a wrapper around it falls back to the variable's own indexing.
	 *
	 * @param fixed whether the count is fixed
	 * @return a new countable with a count of one
	 */
	private static Countable countable(boolean fixed) {
		return new Countable() {
			@Override
			public long getCountLong() { return 1; }

			@Override
			public boolean isFixedCount() { return fixed; }
		};
	}

	/**
	 * Returns a scalar collection variable produced by a destination wrapper around a
	 * {@link #countable(boolean) countable} with the given kind of count.
	 *
	 * @param fixed whether the delegate's count is fixed
	 * @return a new collection variable
	 */
	private static CollectionVariable<?> scalarVariable(boolean fixed) {
		return new CollectionVariable("v", SCALAR, new MemoryDataDestinationProducer<>(countable(fixed)));
	}

	/**
	 * Returns the position of the {@link InstanceReference} in {@code exp}, which may be
	 * wrapped in a mask.
	 *
	 * @param exp the expression containing the reference
	 * @return the integer value of the referenced position
	 */
	private static OptionalInt referencedPosition(Expression<?> exp) {
		if (exp instanceof InstanceReference) {
			return exp.getChildren().get(0).intValue();
		}

		return exp.getChildren().stream()
				.map(CollectionVariableTests::referencedPosition)
				.filter(OptionalInt::isPresent)
				.findFirst().orElse(OptionalInt.empty());
	}

	/**
	 * The count of a variable produced by a delegating wrapper is the count of the
	 * delegate, both when asked directly and when indexing falls back to the variable's own
	 * references: a fixed-count scalar is always read at position zero, while a
	 * variable-count scalar is read at the requested position.
	 */
	@Test(timeout = 30000)
	public void wrappedProducerCountDecidesIndexing() {
		CollectionVariable<?> fixed = scalarVariable(true);
		CollectionVariable<?> variable = scalarVariable(false);

		Assert.assertTrue(fixed.isFixedCount());
		Assert.assertFalse(variable.isFixedCount());

		Assert.assertEquals(OptionalInt.of(0), referencedPosition(fixed.getValueAt(e(3))));
		Assert.assertEquals(OptionalInt.of(3), referencedPosition(variable.getValueAt(e(3))));
	}

	/**
	 * A variable is a {@link CollectionVariable} with the producer's shape when the producer,
	 * or the delegate it wraps, is a {@link Shape}, and a plain {@link ArrayVariable}
	 * otherwise.
	 */
	@Test(timeout = 30000)
	public void createTakesTheShapeOfTheProducerOrItsDelegate() {
		TraversalPolicy shape = new TraversalPolicy(4, 2);
		PassThroughProducer<?> input = new PassThroughProducer<>(shape, 0);

		Assert.assertEquals(shape, Shape.shapeOf(input));
		Assert.assertEquals(shape, Shape.shapeOf(new MemoryDataDestinationProducer<>(input)));
		Assert.assertNull(Shape.shapeOf(new MemoryDataDestinationProducer<>(countable(true))));
		Assert.assertNull(Shape.shapeOf(null));

		ArrayVariable<?> direct = CollectionVariable.create("direct", (Supplier) input);
		ArrayVariable<?> wrapped = CollectionVariable.create("wrapped",
				(Supplier) new MemoryDataDestinationProducer<>(input));
		ArrayVariable<?> plain = CollectionVariable.create("plain",
				(Supplier) new MemoryDataDestinationProducer<>(countable(true)));

		Assert.assertTrue(direct instanceof CollectionVariable);
		Assert.assertEquals(shape, ((CollectionVariable<?>) direct).getShape());
		Assert.assertTrue(wrapped instanceof CollectionVariable);
		Assert.assertEquals(shape, ((CollectionVariable<?>) wrapped).getShape());
		Assert.assertFalse(plain instanceof CollectionVariable);
	}
}
