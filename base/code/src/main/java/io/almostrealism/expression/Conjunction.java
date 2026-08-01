/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.expression;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A boolean conjunction ({@code &}) of two or more sub-expressions.
 *
 * <p>Generates code of the form {@code a & b & c}. Constant-true operands are
 * pruned during construction; if any operand is statically false the entire
 * conjunction collapses to {@code false}.</p>
 */
public class Conjunction extends NAryExpression<Boolean> {
	/**
	 * Constructs a conjunction from the given list of boolean sub-expressions.
	 *
	 * @param values the operands joined by the {@code &} operator
	 */
	protected Conjunction(List<Expression<?>> values) { super(Boolean.class, "&", values); }

	@Override
	public Number evaluate(Number... children) {
		for (Number child : children) {
			if (child.doubleValue() == 0) return 0;
		}

		return 1;
	}

	@Override
	public Expression<Boolean> recreate(List<Expression<?>> children) {
		return Conjunction.of(children);
	}

	/**
	 * Creates a conjunction of the given boolean expressions.
	 *
	 * @param values the operands to conjoin
	 * @return a simplified expression representing the logical AND
	 */
	public static Expression<Boolean> of(Expression<Boolean>... values) {
		return of(List.of(values));
	}

	/**
	 * Creates a conjunction from a list of expressions, pruning constant-true
	 * operands and collapsing to a constant if any operand is statically false.
	 *
	 * @param values the list of operands to conjoin
	 * @return a simplified expression representing the logical AND
	 */
	public static Expression<Boolean> of(List<Expression<?>> values) {
		values = values.stream().filter(e -> !e.booleanValue()
							.orElse(false)).collect(Collectors.toList());

		if (values.isEmpty()) {
			return new BooleanConstant(true);
		} else if (values.size() == 1) {
			return (Expression<Boolean>) values.get(0);
		} else if (values.stream().anyMatch(e -> !e.booleanValue().orElse(true))) {
			return new BooleanConstant(false);
		}

		return new Conjunction(values);
	}
}
