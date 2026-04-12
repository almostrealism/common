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

package org.almostrealism.collect;

import io.almostrealism.collect.ComparisonExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.GreaterThanCollection;
import org.almostrealism.collect.computations.LessThanCollection;

/**
 * Mixin interface providing element-wise comparison operations on
 * {@link CollectionProducer} instances. Extends {@link CollectionFeatures}
 * so all {@code compute}, {@code c}, and {@code shape} helpers are available.
 *
 * <p>{@link org.almostrealism.algebra.AlgebraFeatures} extends this interface
 * so comparison methods are reachable from any {@link CollectionProducer}.</p>
 *
 * @author  Michael Murray
 * @see CollectionFeatures
 * @see CollectionProducer
 */
public interface CollectionComparisonFeatures extends CollectionFeatures {

	/**
	 * Performs element-wise greater-than comparison between two collections with custom return values.
	 * This method compares corresponding elements and returns specified values based on the comparison result.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @param trueValue the value to return when the first element is greater than the second
	 * @param falseValue the value to return when the first element is not greater than the second
	 * @return a {@link CollectionProducer} that generates comparison results
	 *
	 * @see org.almostrealism.collect.computations.CollectionComparisonComputation
	 */
	@Override
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
										   Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return greaterThan(a, b, trueValue, falseValue, false);
	}

	/**
	 * Performs element-wise greater-than comparison between two collections with custom return values.
	 * This method compares corresponding elements and returns specified values based on the comparison result.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @param trueValue the value to return when the first element is greater than the second
	 * @param falseValue the value to return when the first element is not greater than the second
	 * @param includeEqual whether to treat elements which are equal as meeting the comparison condition
	 * @return a {@link CollectionProducer} that generates comparison results
	 *
	 * @see org.almostrealism.collect.computations.CollectionComparisonComputation
	 */
	@Override
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
										   Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
										   boolean includeEqual) {
		return compute((shape, args) ->
						new GreaterThanCollection(shape,
								args.get(0), args.get(1), args.get(2), args.get(3), includeEqual),
				null,
				a, b, trueValue, falseValue);
	}

	/**
	 * Returns {@code trueValue} where elements of {@code a} are strictly greater than
	 * corresponding elements of {@code b}, and {@code falseValue} otherwise.
	 * Equivalent to calling {@link #greaterThanConditional(Producer, Producer, Producer, Producer, boolean)}
	 * with {@code includeEqual = false}.
	 *
	 * @param a          the collection producer for the left-hand operand
	 * @param b          the collection producer for the right-hand operand
	 * @param trueValue  the producer supplying values where the condition is true
	 * @param falseValue the producer supplying values where the condition is false
	 * @return a {@link CollectionProducer} selecting between {@code trueValue} and {@code falseValue}
	 */
	@Override
	default CollectionProducer greaterThanConditional(Producer<PackedCollection> a, Producer<PackedCollection> b,
													  Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return greaterThanConditional(a, b, trueValue, falseValue, false);
	}

	/**
	 * Returns {@code trueValue} where elements of {@code a} are greater than corresponding
	 * elements of {@code b} (optionally including equality), and {@code falseValue} otherwise.
	 * When the two inputs have equal total sizes the output retains that shape; otherwise
	 * the output is scalar.
	 *
	 * @param a            the collection producer for the left-hand operand
	 * @param b            the collection producer for the right-hand operand
	 * @param trueValue    the producer supplying values where the condition is true
	 * @param falseValue   the producer supplying values where the condition is false
	 * @param includeEqual {@code true} to treat equality as satisfying the greater-than condition
	 * @return a {@link CollectionProducer} selecting between {@code trueValue} and {@code falseValue}
	 */
	@Override
	default CollectionProducer greaterThanConditional(Producer<PackedCollection> a, Producer<PackedCollection> b,
													  Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
													  boolean includeEqual) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("greaterThan", shape,
				args -> new ComparisonExpression("greaterThan", shape,
						(l, r) -> greater(l, r, includeEqual),
						args[1], args[2], args[3], args[4]),
				a, b, trueValue, falseValue);
	}

	/**
	 * Returns {@code trueValue} where elements of {@code a} are strictly less than
	 * corresponding elements of {@code b}, and {@code falseValue} otherwise.
	 * Equivalent to calling {@link #lessThan(Producer, Producer, Producer, Producer, boolean)}
	 * with {@code includeEqual = false}.
	 *
	 * @param a          the collection producer for the left-hand operand
	 * @param b          the collection producer for the right-hand operand
	 * @param trueValue  the producer supplying values where the condition is true
	 * @param falseValue the producer supplying values where the condition is false
	 * @return a {@link CollectionProducer} selecting between {@code trueValue} and {@code falseValue}
	 */
	@Override
	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
										Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return lessThan(a, b, trueValue, falseValue, false);
	}

	/**
	 * Returns {@code trueValue} where elements of {@code a} are less than corresponding
	 * elements of {@code b} (optionally including equality), and {@code falseValue} otherwise.
	 * Traversal axes are aligned across inputs before comparison.
	 *
	 * @param a            the collection producer for the left-hand operand
	 * @param b            the collection producer for the right-hand operand
	 * @param trueValue    the producer supplying values where the condition is true
	 * @param falseValue   the producer supplying values where the condition is false
	 * @param includeEqual {@code true} to treat equality as satisfying the less-than condition
	 * @return a {@link CollectionProducer} selecting between {@code trueValue} and {@code falseValue}
	 */
	@Override
	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
										Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
										boolean includeEqual) {
		return compute((shape, args) ->
						new LessThanCollection(shape,
								args.get(0), args.get(1), args.get(2), args.get(3), includeEqual),
				null,
				a, b, trueValue, falseValue);
	}

	/**
	 * Performs element-wise greater-than comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a > b, 0.0 otherwise
	 */
	@Override
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return greaterThan(a, b, c(1.0), c(0.0));
	}

	/**
	 * Performs element-wise greater-than-or-equal comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a >= b, 0.0 otherwise
	 */
	@Override
	default CollectionProducer greaterThanOrEqual(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return greaterThan(a, b, c(1.0), c(0.0), true);
	}

	/**
	 * Performs element-wise less-than comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a &lt; b, 0.0 otherwise
	 */
	@Override
	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return lessThan(a, b, c(1.0), c(0.0), false);
	}

	/**
	 * Performs element-wise less-than-or-equal comparison between two collections, returning 1.0 for true
	 * and 0.0 for false. This is a convenience method for generating binary comparison values
	 * suitable for logical operations.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a &lt;= b, 0.0 otherwise
	 */
	@Override
	default CollectionProducer lessThanOrEqual(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return lessThan(a, b, c(1.0), c(0.0), true);
	}
}
