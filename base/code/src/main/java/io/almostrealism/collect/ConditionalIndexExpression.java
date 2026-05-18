/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.collect;

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.sequence.Index;

import java.util.Optional;
import java.util.OptionalInt;

// TODO  Perhaps this should extend UniformCollectionExpression
// TODO  (it is a uniform translation of the input expression)
/**
 * A collection expression that evaluates its input at an index bounded by the collection size.
 *
 * <p>The raw output index is reduced modulo the collection's total size before querying the
 * input operand. When the reduced index can be resolved to a compile-time constant, the input
 * is accessed directly. Otherwise a chain of {@link Conditional} expressions is generated,
 * one branch per possible index value, so that the access can be resolved at runtime in the
 * generated code.</p>
 */
public class ConditionalIndexExpression extends OperandCollectionExpression {
	/**
	 * Creates a conditional index expression with the given shape and input operand.
	 *
	 * @param shape the output shape (also defines the modulus for index reduction)
	 * @param in    the input operand to evaluate at the bounded index
	 */
	public ConditionalIndexExpression(TraversalPolicy shape, TraversableExpression in) {
		super(null, shape, in);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Reduces the index modulo the collection size and returns the value from the input
	 * at that position. If the index is a compile-time constant, the input is accessed directly.
	 * Otherwise a chain of {@link Conditional} nodes covers all possible index values.</p>
	 */
	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		TraversableExpression<Double> value = getOperands().get(0);

		index = index.toInt().imod(getShape().getTotalSize());

		OptionalInt i = index.intValue();

		if (i.isPresent()) {
			return value.getValueAt(index);
		} else {
			Expression v = value.getValueAt(new IntegerConstant(0));

			for (int j = 1; j < getShape().getTotalSize(); j++) {
				v = Conditional.of(index.eq(new IntegerConstant(j)), value.getValueAt(new IntegerConstant(j)), v);
			}

			return v;
		}
	}

	/** {@inheritDoc} Delegates to the first operand. */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return getOperands().get(0).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	/** {@inheritDoc} Delegates to the first operand. */
	@Override
	public Optional<Boolean> containsIndex(int index) {
		return getOperands().get(0).containsIndex(index);
	}
}
