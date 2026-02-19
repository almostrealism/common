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
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;

import java.util.ArrayList;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** The ExpressionList class. */
public class ExpressionList<T> extends ArrayList<Expression<T>> {

	// @Override
	/** Performs the getValue operation. */
	public Expression<T> getValue(int pos) {
		return get(pos);
	}

	/** Performs the minus operation. */
	public ExpressionList<T> minus() {
		return stream().map(Expression::minus).collect(collector());
	}

	/** Performs the multiply operation. */
	public ExpressionList<T> multiply(ExpressionList<T> operands) {
		if (this.size() != operands.size()) {
			throw new IllegalArgumentException("Cannot multiply lists of different sizes");
		}

		ExpressionList result = new ExpressionList<>();

		for (int i = 0; i < operands.size(); i++) {
			result.add(Product.of((Expression) get(i), (Expression) operands.get(i)));
		}

		return result;
	}

	/** Performs the sum operation. */
	public Expression<T> sum() {
		return Sum.of(toArray(Expression[]::new));
	}

	/** Performs the max operation. */
	public Expression<T> max() {
		if (size() <= 0) {
			throw new IllegalArgumentException("Maximum of zero expressions is undefined");
		}

		Expression max = get(0);
		for (int i = 1; i < size(); i++) {
			max = Max.of(max, (Expression) get(i));
		}

		return max;
	}

	/** Performs the exp operation. */
	public ExpressionList<T> exp() {
		ExpressionList result = new ExpressionList();
		for (Expression<T> e : this) result.add(e.exp());
		return result;
	}

	/** Performs the collector operation. */
	public static Collector<Expression, ?, ExpressionList> collector() {
		return Collectors.toCollection(() -> new ExpressionList());
	}
}
