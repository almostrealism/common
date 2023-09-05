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

public class Conditional extends Expression<Double> {
	public Conditional(Expression<Boolean> condition, Expression<Double> positive, Expression<Double> negative) {
		super(Double.class,
				"(" + condition.getExpression() + ") ? (" + positive.getExpression() +
				") : (" + negative.getExpression() + ")", condition, positive, negative);
	}

	@Override
	public String getExpression() {
		return "(" + getChildren().get(0).getExpression() + ") ? (" + getChildren().get(1).getExpression() +
				") : (" + getChildren().get(2).getExpression() + ")";
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 3) throw new UnsupportedOperationException();
		return new Conditional((Expression<Boolean>) children.get(0),
				(Expression<Double>) children.get(1),
				(Expression<Double>) children.get(2));
	}
}
