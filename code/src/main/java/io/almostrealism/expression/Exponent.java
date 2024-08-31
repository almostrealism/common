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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ExpressionCache;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public class Exponent extends Expression<Double> {
	public Exponent(Expression<Double> base, Expression<Double> exponent) {
		super(Double.class, base, exponent);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.pow(
				getChildren().get(0).getExpression(lang),
				getChildren().get(1).getExpression(lang));
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong l = getChildren().get(0).upperBound(context);
		OptionalLong r = getChildren().get(1).upperBound(context);
		if (l.isPresent() && r.isPresent()) {
			return OptionalLong.of((long) Math.pow(l.getAsLong(), r.getAsLong()));
		}

		return OptionalLong.empty();
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.pow(children[0].doubleValue(), children[1].doubleValue());
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return Exponent.of((Expression<Double>) children.get(0), (Expression<Double>) children.get(1));
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		Expression base = getChildren().get(0);
		Expression exp = getChildren().get(1);

		TraversalPolicy ts = target.getShape();

		CollectionExpression baseDelta = base.delta(target);
		CollectionExpression expDelta = exp.delta(target);

		CollectionExpression self = new ConstantCollectionExpression(target.getShape(), this);
		CollectionExpression logBase = new ConstantCollectionExpression(target.getShape(), base.log());
		CollectionExpression ratio = new ConstantCollectionExpression(target.getShape(), exp.divide(base));

		CollectionExpression term1 = product(ts, baseDelta, ratio);
		CollectionExpression term2 = product(ts, expDelta, logBase);
		return product(ts, self, sum(ts, term1, term2));
	}

	@Override
	public Expression<Double> simplify(KernelStructureContext context, int depth) {
		Expression<?> flat = super.simplify(context, depth);
		if (!(flat instanceof Exponent)) return (Expression<Double>) flat;

		Expression base = flat.getChildren().get(0);
		Expression exponent = flat.getChildren().get(1);

		if (base.doubleValue().isPresent()) {
			if (base.doubleValue().getAsDouble() == 1.0) {
				return new DoubleConstant(1.0);
			} else if (base.doubleValue().getAsDouble() == 0.0) {
				return new DoubleConstant(0.0);
			} else if (exponent.doubleValue().isPresent()) {
				return new DoubleConstant(Math.pow(base.doubleValue().getAsDouble(), exponent.doubleValue().getAsDouble()));
			}
		} else if (exponent.doubleValue().isPresent()) {
			if (exponent.doubleValue().getAsDouble() == 1.0) {
				return base;
			} else if (exponent.doubleValue().getAsDouble() == 0.0) {
				return new DoubleConstant(1.0);
			}
		}

		return (Expression<Double>) flat;
	}

	public static Expression<Double> of(Expression<Double> base, Expression<Double> exponent) {
		return ExpressionCache.match(Exponent.create(base, exponent));
	}

	public static Expression<Double> create(Expression<Double> base, Expression<Double> exponent) {
		OptionalDouble exponentValue = exponent.doubleValue();
		if (exponentValue.isPresent()) {
			if (exponentValue.getAsDouble() == 0.0) {
				return new DoubleConstant(1.0);
			} else if (exponentValue.getAsDouble() == 1.0) {
				return base;
			}
		}

		return new Exponent(base, exponent);
	}
}
