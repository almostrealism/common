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

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;
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
 * <p>The index starts at zero and is replaced on each iteration in which the element at the
 * iteration's position exceeds the element at the current index. Each output element locates
 * its group by its own position, never by the kernel index, because when the computation is
 * embedded in a kernel of a different size the kernel index identifies an element of that
 * kernel rather than of this computation.</p>
 *
 * @see org.almostrealism.collect.AggregationFeatures#indexOfMax(Producer)
 * @see CollectionMaxComputation
 *
 * @author Michael Murray
 */
public class IndexOfMaxComputation extends TraversableRepeatedProducerComputation {
	/** The number of elements in each group of the input. */
	private final int size;

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
		super("indexOfMax", shape.replace(new TraversalPolicy(1)), shape.getSize(),
				(args, index) -> new IntegerConstant(0), input);
		this.size = shape.getSize();
	}

	/**
	 * Computes the index of the maximum of the group of the output element at the given
	 * position, by comparing each element of the group with the one at the current index.
	 *
	 * @param index the position of the output element
	 * @return the expression for the index of the maximum of that element's group
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversableExpression[] args = getTraversableArguments(index);

		Expression value = initial.apply(args, e(0));

		for (int i = 0; i < count; i++) {
			value = step(args, index, value, e(i));
			value = value.generate(value.flatten());
		}

		return value;
	}

	/**
	 * Computes one iteration of the search in a kernel of its own, where the current index
	 * of each output element is held in the output itself.
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		CollectionVariable variable = (CollectionVariable) args[0];
		Expression<?> position = new KernelIndex().multiply(variable.length());
		return step(args, position, variable.reference(position), localIndex);
	}

	/**
	 * Returns the index after comparing one element of a group with the element at the
	 * current index: the element's own position if it is larger, otherwise the current index.
	 *
	 * @param args     the traversable arguments, where {@code args[1]} is the input
	 * @param position the position of the output element whose group is searched
	 * @param current  the current index of the maximum
	 * @param element  the position within the group of the element to compare
	 * @return the index of the maximum after the comparison
	 */
	private Expression<?> step(TraversableExpression[] args, Expression<?> position,
							   Expression<?> current, Expression<?> element) {
		Expression<?> group = position.multiply(size);
		return conditional(args[1].getValueAt(group.add(element))
						.greaterThan(args[1].getValueAt(group.add(current))),
				element, current);
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
