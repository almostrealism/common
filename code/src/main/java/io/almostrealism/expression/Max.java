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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.stream.Stream;

public class Max extends BinaryExpression<Double> {
	protected Max(Expression<? extends Number> a, Expression<? extends Number> b) {
		super(Double.class, a, b);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.max(
				getChildren().get(0).getExpression(lang),
				getChildren().get(1).getExpression(lang));
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong l = getChildren().get(0).upperBound(context);
		OptionalLong r = getChildren().get(1).upperBound(context);
		if (l.isPresent() && r.isPresent()) {
			return OptionalLong.of(Math.max(l.getAsLong(), r.getAsLong()));
		}

		return OptionalLong.empty();
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.max(children[0].doubleValue(), children[1].doubleValue());
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return conditional(target.getShape(),
				getLeft().greaterThan(getRight()),
				getLeft().delta(target),
				getRight().delta(target));
	}

	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return Max.of((Expression<Double>) children.get(0), (Expression<Double>) children.get(1));
	}

	public static Expression<Double> of(Expression<Double>... values) {
		if (values.length == 0) {
			throw new IllegalArgumentException();
		}

		if (values.length == 2) {
			if (values[0].doubleValue().orElse(-1.0) == 0.0) {
				return Rectify.of(values[1]);
			} else if (values[1].doubleValue().orElse(-1.0) == 0.0) {
				return Rectify.of(values[0]);
			}
		}

		OptionalDouble v[] = Stream.of(values)
				.map(Expression::doubleValue)
				.toArray(OptionalDouble[]::new);

		if (Stream.of(v).anyMatch(OptionalDouble::isEmpty)) {
			Expression<Double> result = values[0];
			for (int i = 1; i < values.length; i++) {
				if (result.isFP() != values[i].isFP()) {
					// Ensure that comparison is of similar type
					result = result.isFP() ?
							new Max(result, values[i].toDouble()) :
							new Max(result.toDouble(), values[i]);
				} else {
					result = new Max(result, values[i]);
				}
			}

			return result;
		} else {
			double max = Stream.of(v)
					.mapToDouble(OptionalDouble::getAsDouble)
					.max().getAsDouble();
			return Constant.of(max);
		}
	}
}
