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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A {@link PrefixAccumulationComputation} that produces the cumulative (prefix) product of a
 * collection: element i is the product of input elements 0 through i, or, in the exclusive
 * form ({@code pad == true}), element 0 is 1.0 and element i is the product of input elements
 * 0 through i - 1.
 *
 * @see PrefixAccumulationComputation
 * @see org.almostrealism.collect.AggregationFeatures#cumulativeProduct(Producer, boolean)
 *
 * @author Michael Murray
 */
public class CumulativeProductComputation extends PrefixAccumulationComputation {

	/**
	 * Creates a cumulative product computation over the given input.
	 *
	 * @param shape The output {@link TraversalPolicy}, matching the input shape with each
	 *              element traversed by its own kernel thread
	 * @param pad   If true, produce the exclusive form (1.0 prepended); otherwise element i
	 *              includes input element i in its product
	 * @param input The collection to scan
	 */
	public CumulativeProductComputation(TraversalPolicy shape, boolean pad, Producer<PackedCollection> input) {
		super("cumulativeProduct", shape, pad, input);
	}

	/**
	 * Returns the multiplicative identity, 1.0.
	 *
	 * @return An {@link Expression} for 1.0
	 */
	@Override
	protected Expression<?> identity() { return e(1.0); }

	/**
	 * Returns the accumulator multiplied by the factor.
	 *
	 * @param accumulator The accumulated product so far
	 * @param factor      The factor to absorb
	 * @return An {@link Expression} for the product
	 */
	@Override
	protected Expression<?> combine(Expression<?> accumulator, Expression<?> factor) {
		return accumulator.multiply(factor);
	}

	/**
	 * Returns the input element at the given position.
	 *
	 * @param input    The traversable input collection
	 * @param position The input element index
	 * @return An {@link Expression} for the input value at that position
	 */
	@Override
	protected Expression<?> term(TraversableExpression input, Expression<?> position) {
		return input.getValueAt(position);
	}

	/**
	 * Generates a new instance of this computation with updated child processes,
	 * preserving the {@code pad} flag.
	 *
	 * @param children The child processes, where the first element is the output destination
	 * @return A new {@link CumulativeProductComputation} over the remaining child as input
	 */
	@Override
	public CumulativeProductComputation generate(List<Process<?, ?>> children) {
		return new CumulativeProductComputation(getShape(), isPad(),
				children.stream().skip(1).toArray(Producer[]::new)[0]);
	}
}
