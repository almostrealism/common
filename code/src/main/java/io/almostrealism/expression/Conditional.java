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

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Conditional<T extends Number> extends Expression<T> {
	public static boolean enableSimplification = false;

	protected Conditional(Class<T> type, Expression<Boolean> condition, Expression<Double> positive, Expression<Double> negative) {
		super(type, condition, positive, negative);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return "(" + getChildren().get(0).getExpression(lang) + ") ? (" + getChildren().get(1).getExpression(lang) +
				") : (" + getChildren().get(2).getExpression(lang) + ")";
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		OptionalInt l = getChildren().get(1).upperBound(context);
		OptionalInt r = getChildren().get(2).upperBound(context);
		if (l.isPresent() && r.isPresent()) {
			return OptionalInt.of(Math.max(l.getAsInt(), r.getAsInt()));
		}

		return OptionalInt.empty();
	}

	@Override
	public Number evaluate(Number... children) {
		return children[0].doubleValue() != 0.0 ? children[1] : children[2];
	}

	@Override
	public Expression simplify(KernelStructureContext context) {
		Expression<?> flat = super.simplify(context);
		if (!(flat instanceof Conditional)) return flat;

		Expression<Boolean> condition = (Expression<Boolean>) flat.getChildren().get(0);
		Expression<Double> positive = (Expression<Double>) flat.getChildren().get(1);
		Expression<Double> negative = (Expression<Double>) flat.getChildren().get(2);

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

		if (!enableSimplification) return Conditional.of(condition, positive, negative);

		boolean replaceCondition = false;
		if (context.getSeriesProvider() != null && !flat.isSingleIndexMasked()) {
			int len = context.getSeriesProvider().getMaximumLength().orElse(0);
			OptionalInt max = condition.getIndices().stream()
					.mapToInt(id -> id.upperBound(context).orElse(Integer.MAX_VALUE))
					.findFirst();
			if (max.isPresent()) {
				replaceCondition = max.getAsInt() < len;
			}
		}

		if (replaceCondition) {
			Expression<?> exp = context.getSeriesProvider().getSeries(condition);
			Optional<Boolean> r = exp.booleanValue();

			if (r.isPresent()) {
				return r.get() ? positive : negative;
			} else if (exp instanceof Equals) {
				OptionalDouble d = ((Equals) exp).getRight().doubleValue();

				if (d.isPresent() && d.getAsDouble() == 1.0) {
					exp = ((Equals) exp).getLeft();

					if (rd.isPresent() && rd.getAsDouble() == 0) {
						return exp.multiply(positive);
					} else if (ld.isPresent() && ld.getAsDouble() == 0) {
						return exp.add(1).imod(2).multiply(negative);
					} else {
						return exp.multiply(positive).add(exp.add(1).imod(2).multiply(negative));
					}
				}
			}
		}

		return Conditional.of(condition, positive, negative);
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		if (children.size() != 3) throw new UnsupportedOperationException();
		return Conditional.of((Expression<Boolean>) children.get(0),
				(Expression<Double>) children.get(1),
				(Expression<Double>) children.get(2));
	}

	public static Expression of(Expression<Boolean> condition, Expression<Double> positive, Expression<Double> negative) {
		OptionalDouble rd = negative.doubleValue();
		if (rd.isPresent() && rd.getAsDouble() == 0.0) {
			return Mask.of(condition, positive);
		}

		OptionalDouble ld = positive.doubleValue();
		if (ld.isPresent() && ld.getAsDouble() == 0.0) {
			return Mask.of(condition.not(), negative);
		}

		return new Conditional(Double.class, condition, positive, negative);
	}
}
