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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;

import java.util.ArrayList;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class ExpressionList<T> extends ArrayList<Expression<T>> implements MultiExpression<T> {

	@Override
	public Expression<T> getValue(int pos) {
		return get(pos);
	}

	public ExpressionList<T> minus() {
		return stream().map(Expression::minus).collect(collector());
	}

	public ExpressionList<T> multiply(ExpressionList<T> operands) {
		if (this.size() != operands.size()) {
			throw new IllegalArgumentException("Cannot multiply lists of different sizes");
		}

		ExpressionList result = new ExpressionList<>();

		for (int i = 0; i < operands.size(); i++) {
			result.add(new Product((Expression) get(i), (Expression) operands.get(i)));
		}

		return result;
	}

	public Expression<T> sum() {
		return (Expression<T>) new Sum(toArray(Expression[]::new));
	}

	public Expression<T> max() {
		if (size() <= 0) {
			throw new IllegalArgumentException("Maximum of zero expressions is undefined");
		}

		Expression max = get(0);
		for (int i = 1; i < size(); i++) {
			max = new Max(max, (Expression) get(i));
		}

		return max;
	}

	public ExpressionList<T> exp() {
		ExpressionList result = new ExpressionList();
		for (Expression<T> e : this) result.add(e.exp());
		return result;
	}

	public static Collector<Expression, ?, ExpressionList> collector() {
		return Collectors.toCollection(() -> new ExpressionList());
	}
}
