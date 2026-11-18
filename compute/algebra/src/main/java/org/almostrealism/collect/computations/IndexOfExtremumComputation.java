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
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Abstract base for the computations that find, for each group of a {@link PackedCollection}, the
 * index of its extreme (largest or smallest) element. The groups are the items of the input at its
 * traversal axis, and the result has one element for each of them, holding the position of the
 * first occurrence of the extremum within the group.
 *
 * <p>The index starts at zero and is replaced on each iteration in which the element at the
 * iteration's position {@link #isReplacement is more extreme than} the element at the current
 * index. Each output element locates its group by its own position, never by the kernel index,
 * because when the computation is embedded in a kernel of a different size the kernel index
 * identifies an element of that kernel rather than of this computation.</p>
 *
 * <p>The only difference between the {@link IndexOfMaxComputation maximum} and the
 * {@link IndexOfMinComputation minimum} is the direction of the comparison that decides a
 * replacement; everything else — the search itself and the kernel it compiles to — is shared
 * here.</p>
 *
 * @see IndexOfMaxComputation
 * @see IndexOfMinComputation
 *
 * @author Michael Murray
 */
public abstract class IndexOfExtremumComputation extends TraversableRepeatedProducerComputation {
	/** The number of elements in each group of the input. */
	private final int size;

	/**
	 * Constructs a computation of the index of the extremum of each group of an input of
	 * the given shape.
	 *
	 * @param name  the operation identifier (e.g. {@code "indexOfMax"}, {@code "indexOfMin"})
	 * @param shape the {@link TraversalPolicy} of the input
	 * @param input the {@link Producer} of the collection to search
	 */
	protected IndexOfExtremumComputation(String name, TraversalPolicy shape, Producer<PackedCollection> input) {
		super(name, shape.replace(new TraversalPolicy(1)), shape.getSize(), input);
		this.size = shape.getSize();
	}

	/**
	 * Returns zero: the search starts at the first element of each group.
	 *
	 * @param args  the traversable arguments
	 * @param index the position within the output block
	 * @return the constant {@code 0}
	 */
	@Override
	protected Expression<?> getInitialValue(TraversableExpression[] args, Expression index) {
		return new IntegerConstant(0);
	}

	/**
	 * Returns whether a candidate element should replace the current extremum, which determines
	 * the direction of the search: {@code candidate > current} for a maximum and
	 * {@code candidate < current} for a minimum. The comparison is strict, so the first
	 * occurrence of the extremum within a group is the one whose index is returned.
	 *
	 * @param candidate the element at the position being considered
	 * @param current   the element at the current extremum index
	 * @return the condition under which {@code candidate} is the new extremum
	 */
	protected abstract Expression<Boolean> isReplacement(Expression<?> candidate, Expression<?> current);

	/**
	 * Computes the index of the extremum of the group of the output element at the given
	 * position, by comparing each element of the group with the one at the current index.
	 *
	 * @param index the position of the output element
	 * @return the expression for the index of the extremum of that element's group
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversableExpression[] args = getTraversableArguments(index);

		Expression value = getInitialValue(args, e(0));

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
	 * current index: the element's own position if it is {@link #isReplacement more extreme},
	 * otherwise the current index.
	 *
	 * @param args     the traversable arguments, where {@code args[1]} is the input
	 * @param position the position of the output element whose group is searched
	 * @param current  the current index of the extremum
	 * @param element  the position within the group of the element to compare
	 * @return the index of the extremum after the comparison
	 */
	private Expression<?> step(TraversableExpression[] args, Expression<?> position,
							   Expression<?> current, Expression<?> element) {
		Expression<?> group = position.multiply(size);
		return conditional(isReplacement(args[1].getValueAt(group.add(element)),
						args[1].getValueAt(group.add(current))),
				element, current);
	}
}
