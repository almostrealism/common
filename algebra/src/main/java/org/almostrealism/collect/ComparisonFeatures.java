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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.ComparisonExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.CollectionComparisonComputation;
import org.almostrealism.collect.computations.CollectionConjunctionComputation;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.GreaterThanCollection;
import org.almostrealism.collect.computations.LessThanCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Factory interface for comparison and logical operations on collections.
 * This interface provides methods for equals, greaterThan, lessThan, and, and related operations.
 *
 * @author Michael Murray
 * @see CollectionFeatures
 */
public interface ComparisonFeatures extends AggregationFeatures, ExpressionFeatures {

	/**
	 * Performs element-wise equality comparison between two collections with custom return values.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @param trueValue the value to return when elements are equal
	 * @param falseValue the value to return when elements are not equal
	 * @return a {@link CollectionProducer} that generates comparison results
	 */
	default CollectionProducer equals(Producer<PackedCollection> a, Producer<PackedCollection> b,
									  Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return compute((shape, args) ->
						new CollectionComparisonComputation("equals", shape,
								args.get(0), args.get(1), args.get(2), args.get(3)),
				null,
				a, b, trueValue, falseValue);
	}

	/**
	 * Performs element-wise greater-than comparison between two collections with custom return values.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @param trueValue the value to return when the first element is greater than the second
	 * @param falseValue the value to return when the first element is not greater than the second
	 * @return a {@link CollectionProducer} that generates comparison results
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
										   Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return greaterThan(a, b, trueValue, falseValue, false);
	}

	/**
	 * Performs element-wise greater-than comparison between two collections with custom return values.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @param trueValue the value to return when the first element is greater than the second
	 * @param falseValue the value to return when the first element is not greater than the second
	 * @param includeEqual whether to treat elements which are equal as meeting the comparison condition
	 * @return a {@link CollectionProducer} that generates comparison results
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
										   Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
										   boolean includeEqual) {
		return compute((shape, args) ->
						new GreaterThanCollection(shape,
								args.get(0), args.get(1), args.get(2), args.get(3), includeEqual),
				null,
				a, b, trueValue, falseValue);
	}

	default CollectionProducer greaterThanConditional(Producer<PackedCollection> a, Producer<PackedCollection> b,
													  Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return greaterThanConditional(a, b, trueValue, falseValue, false);
	}

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

	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b,
										Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return lessThan(a, b, trueValue, falseValue, false);
	}

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
	 * Performs element-wise greater-than comparison, returning 1.0 for true and 0.0 for false.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a > b, 0.0 otherwise
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return greaterThan(a, b, c(1.0), c(0.0));
	}

	/**
	 * Performs element-wise greater-than-or-equal comparison, returning 1.0 for true and 0.0 for false.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a >= b, 0.0 otherwise
	 */
	default CollectionProducer greaterThanOrEqual(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return greaterThan(a, b, c(1.0), c(0.0), true);
	}

	/**
	 * Performs element-wise less-than comparison, returning 1.0 for true and 0.0 for false.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a < b, 0.0 otherwise
	 */
	default CollectionProducer lessThan(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return lessThan(a, b, c(1.0), c(0.0), false);
	}

	/**
	 * Performs element-wise less-than-or-equal comparison, returning 1.0 for true and 0.0 for false.
	 *
	 * @param a the first collection to compare
	 * @param b the second collection to compare
	 * @return a {@link CollectionProducer} that generates 1.0 where a <= b, 0.0 otherwise
	 */
	default CollectionProducer lessThanOrEqual(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return lessThan(a, b, c(1.0), c(0.0), true);
	}

	/**
	 * Performs element-wise logical AND operation with custom return values.
	 *
	 * @param a the first operand (non-zero = true)
	 * @param b the second operand (non-zero = true)
	 * @param trueValue the value to return when both a AND b are non-zero
	 * @param falseValue the value to return otherwise
	 * @return a {@link CollectionProducer} that generates the logical AND result
	 */
	default CollectionProducer and(
			Producer<PackedCollection> a, Producer<PackedCollection> b,
			Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return compute((shape, args) ->
						new CollectionConjunctionComputation(shape,
								args.get(0), args.get(1), args.get(2), args.get(3)),
				null,
				a, b, trueValue, falseValue);
	}

	/**
	 * Performs element-wise logical AND operation, returning 1.0 for true and 0.0 for false.
	 *
	 * @param a the first operand (non-zero = true)
	 * @param b the second operand (non-zero = true)
	 * @return a {@link CollectionProducer} that generates 1.0 where both operands are non-zero, 0.0 otherwise
	 */
	default CollectionProducer and(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return and(a, b, c(1.0), c(0.0));
	}

	// Required for internal use
	<P extends Producer<PackedCollection>> CollectionProducer compute(BiFunction<TraversalPolicy, List<Producer<PackedCollection>>, P> processor,
																	  Function<List<String>, String> description, Producer<PackedCollection>... arguments);
}
