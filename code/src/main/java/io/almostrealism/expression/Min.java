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

package io.almostrealism.expression;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ExpressionCache;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public class Min extends BinaryExpression<Double> {
	protected Min(Expression<Double> a, Expression<Double> b) {
		super(Double.class, a, b);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.min(
				getChildren().get(0).getExpression(lang),
				getChildren().get(1).getExpression(lang));
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong l = getChildren().get(0).upperBound(context);
		OptionalLong r = getChildren().get(1).upperBound(context);
		if (l.isPresent() && r.isPresent()) {
			return OptionalLong.of(Math.min(l.getAsLong(), r.getAsLong()));
		}

		return OptionalLong.empty();
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.min(children[0].doubleValue(), children[1].doubleValue());
	}

	@Override
	public Expression generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return Min.of(children.get(0), children.get(1));
	}

	public static Expression<Double> of(Expression<?> a, Expression<?> b) {
		// TODO
//		return ExpressionCache.match(create(a, b));
		return create(a, b);
	}

	public static Expression create(Expression<?> a, Expression<?> b) {
		OptionalDouble aVal = a.doubleValue();
		OptionalDouble bVal = b.doubleValue();

		if (aVal.isPresent() && bVal.isPresent()) {
			return Constant.of(Math.min(aVal.getAsDouble(), bVal.getAsDouble()));
		}

		return new Min((Expression<Double>) a, (Expression<Double>) b);
	}
}
