/*
 * Copyright 2023 Michael Murray
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
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Equals extends Expression<Boolean> {
	public Equals(Expression<?> left, Expression<?> right) {
		super(Boolean.class, left, right);
	}

	public String getExpression() {
		return "(" + getChildren().get(0).getExpression() + ") == (" + getChildren().get(1).getExpression() + ")";
	}

	@Override
	public Expression<Boolean> simplify() {
		Expression<?> left = getChildren().get(0).simplify();
		Expression<?> right = getChildren().get(1).simplify();

		OptionalInt li = left.intValue();
		OptionalInt ri = right.intValue();
		if (li.isPresent() && ri.isPresent())
			return new BooleanConstant(li.getAsInt() == ri.getAsInt());

		OptionalDouble ld = left.doubleValue();
		OptionalDouble rd = right.doubleValue();
		if (ld.isPresent() && rd.isPresent())
			return new BooleanConstant(ld.getAsDouble() == rd.getAsDouble());

		return new Equals(left, right);
	}

	@Override
	public Expression<Boolean> generate(List<Expression<?>> children) {
		if (children.size() != 2) throw new UnsupportedOperationException();
		return new Equals(children.get(0), children.get(1));
	}
}
