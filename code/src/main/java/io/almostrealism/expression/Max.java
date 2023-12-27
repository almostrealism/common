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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;

public class Max extends BinaryExpression<Double> {
	public Max(Expression<Double> a, Expression<Double> b) {
		super(Double.class, a, b);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.max(
				getChildren().get(0).getExpression(lang),
				getChildren().get(1).getExpression(lang));
	}

	@Override
	public OptionalInt upperBound() {
		OptionalInt l = getChildren().get(0).upperBound();
		OptionalInt r = getChildren().get(1).upperBound();
		if (l.isPresent() && r.isPresent()) {
			return OptionalInt.of(Math.max(l.getAsInt(), r.getAsInt()));
		}

		return OptionalInt.empty();
	}

	@Override
	public CollectionExpression delta(TraversalPolicy shape, Function<Expression, Predicate<Expression>> target) {
		return CollectionExpression.conditional(shape,
				getLeft().greaterThan(getRight()),
				getLeft().delta(shape, target),
				getRight().delta(shape, target));
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new Max((Expression<Double>) children.get(0), (Expression<Double>) children.get(1));
	}

	public static Expression<Double> of(Expression<Double>... values) {
		Expression<Double> result = values[0];
		for (int i = 1; i < values.length; i++) {
			result = new Max(result, values[i]);
		}

		return result;
	}
}
