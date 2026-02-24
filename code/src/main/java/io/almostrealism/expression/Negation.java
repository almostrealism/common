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
import java.util.Optional;

public class Negation extends UnaryExpression<Boolean> {
	protected Negation(Expression<Boolean> value) {
		super(Boolean.class, "!", value);
	}

	@Override
	protected boolean isIncludeSpace() { return false; }

	@Override
	public Optional<Boolean> booleanValue() {
		Optional<Boolean> value = getChildren().get(0).booleanValue();
		if (value.isEmpty()) return value;
		return Optional.of(!value.get());
	}

	@Override
	public Number evaluate(Number... children) {
		return children[0].doubleValue() == 0 ? 1 : 0;
	}

	@Override
	public Expression<Boolean> recreate(List<Expression<?>> children) {
		return (Expression) Negation.of(children.get(0));
	}

	public static Expression<?> of(Expression<?> value) {
		Optional<Boolean> c = value.booleanValue();
		if (c.isPresent()) {
			return new BooleanConstant(!c.get());
		} else if (value instanceof Negation) {
			return value.getChildren().get(0);
		}

		return new Negation((Expression) value);
	}
}
