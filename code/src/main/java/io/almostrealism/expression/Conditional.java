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

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Conditional extends Expression<Double> {
	public Conditional(Expression<Boolean> condition, Expression<Double> positive, Expression<Double> negative) {
		super(Double.class, condition, positive, negative);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return "(" + getChildren().get(0).getExpression(lang) + ") ? (" + getChildren().get(1).getExpression(lang) +
				") : (" + getChildren().get(2).getExpression(lang) + ")";
	}

	@Override
	public Expression simplify() {
		Expression<Boolean> condition = (Expression<Boolean>) getChildren().get(0).simplify();
		Expression<Double> positive = (Expression<Double>) getChildren().get(1).simplify();
		Expression<Double> negative = (Expression<Double>) getChildren().get(2).simplify();

		Optional<Boolean> cond = condition.booleanValue();
		if (cond.isPresent()) {
			if (cond.get()) {
				return positive;
			} else {
				return negative;
			}
		}

		OptionalInt li = positive.intValue();
		OptionalInt ri = negative.intValue();
		if (li.isPresent() && ri.isPresent() && li.getAsInt() == ri.getAsInt())
			return new IntegerConstant(li.getAsInt());

		OptionalDouble ld = positive.doubleValue();
		OptionalDouble rd = negative.doubleValue();
		if (ld.isPresent() && rd.isPresent() && ld.getAsDouble() == rd.getAsDouble())
			return new DoubleConstant(ld.getAsDouble());

		return new Conditional(condition, positive, negative);
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 3) throw new UnsupportedOperationException();
		return new Conditional((Expression<Boolean>) children.get(0),
				(Expression<Double>) children.get(1),
				(Expression<Double>) children.get(2));
	}
}
