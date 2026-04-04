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

import java.util.OptionalInt;

/**
 * A collection expression that selects from multiple operand expressions based on
 * a runtime choice value.
 *
 * <p>For each output index the {@code choice} expression is evaluated and its integer
 * value (modulo the number of operands) determines which operand is used. When the
 * choice can be resolved at compile time to a constant the corresponding operand is
 * used directly; otherwise a chain of {@link Conditional} expressions is built so that
 * the selection is performed at runtime in generated code.</p>
 */
public class ConditionalExpressionBase extends OperandCollectionExpression {
	/** The expression that selects which operand to use at each index. */
	private TraversableExpression<Double> choice;

	/**
	 * Creates a conditional expression with the given shape, choice selector, and candidate operands.
	 *
	 * @param shape   the output shape
	 * @param choice  the expression that selects the operand index (modulo number of operands)
	 * @param choices the operand expressions to choose from
	 */
	public ConditionalExpressionBase(TraversalPolicy shape,
									 TraversableExpression choice,
									 TraversableExpression... choices) {
		super(null, shape, choices);
		this.choice = choice;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Evaluates the choice selector and returns the value from the selected operand.
	 * If the choice is a compile-time constant, the appropriate operand is accessed directly;
	 * otherwise a chain of {@link Conditional} expressions is generated.</p>
	 */
	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		Expression ch = choice.getValueAt(index).toInt().imod(getOperands().size());

		OptionalInt i = ch.intValue();

		if (i.isPresent()) {
			return getOperands().get(i.getAsInt()).getValueAt(index);
		}

		Expression v = getOperands().get(0).getValueAt(index);

		for (int j = 1; j < getOperands().size(); j++) {
			v = Conditional.of(ch.eq(e(j)),
					getOperands().get(j).getValueAt(index), v);
		}

		return v;
	}

	/** {@inheritDoc} */
	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		// TODO
		return super.containsIndex(index);
	}
}
