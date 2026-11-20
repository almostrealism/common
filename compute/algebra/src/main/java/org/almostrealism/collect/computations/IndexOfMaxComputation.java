/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that finds, for each group of a {@link PackedCollection}, the index of its
 * largest element. The groups are the items of the input at its traversal axis, and the
 * result has one element for each of them, holding the position of the first occurrence of
 * the maximum within the group.
 *
 * <pre>{@code
 * CollectionProducer rows = c(shape(2, 3),
 *     1.0, 8.0, 3.0,
 *     4.0, 2.0, 6.0).traverse(1);
 *
 * CollectionProducer index = indexOfMax(rows);
 * // Produces [1.0, 2.0]
 * }</pre>
 *
 * <p>The index is replaced on each iteration in which the element at the iteration's position
 * exceeds the element at the current index; see {@link IndexOfExtremumComputation} for the
 * shared search.</p>
 *
 * @see org.almostrealism.collect.AggregationFeatures#indexOfMax(Producer)
 * @see IndexOfExtremumComputation
 * @see CollectionMaxComputation
 *
 * @author Michael Murray
 */
public class IndexOfMaxComputation extends IndexOfExtremumComputation {
	/**
	 * Constructs a computation of the index of the maximum of each group of the input.
	 *
	 * @param input the {@link Producer} of the collection to search, traversed at the axis
	 *              whose items are the groups
	 */
	public IndexOfMaxComputation(Producer<PackedCollection> input) {
		this(CollectionFeatures.getInstance().shape(input), input);
	}

	/**
	 * Constructs a computation of the index of the maximum of each group of an input of
	 * the given shape.
	 *
	 * @param shape the {@link TraversalPolicy} of the input
	 * @param input the {@link Producer} of the collection to search
	 */
	protected IndexOfMaxComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("indexOfMax", shape, input);
	}

	/**
	 * A candidate replaces the current maximum when it is strictly greater.
	 *
	 * @param candidate the element at the position being considered
	 * @param current   the element at the current maximum index
	 * @return {@code candidate > current}
	 */
	@Override
	protected Expression<Boolean> isReplacement(Expression<?> candidate, Expression<?> current) {
		return candidate.greaterThan(current);
	}

	/**
	 * Generates a new index of the maximum with the input at {@code children.get(1)}.
	 *
	 * @param children the child {@link Process} instances, where index 1 is the input
	 * @return a new {@link IndexOfMaxComputation} for the child input
	 */
	@Override
	public IndexOfMaxComputation generate(List<Process<?, ?>> children) {
		return new IndexOfMaxComputation((Producer<PackedCollection>) children.get(1));
	}
}
