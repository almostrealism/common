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

import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Conditional extends Expression<Double> {
	public static boolean enableKernelSimplification = true;

	protected Conditional(Expression<Boolean> condition, Expression<Double> positive, Expression<Double> negative) {
		super(Double.class, condition, positive, negative);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return "(" + getChildren().get(0).getExpression(lang) + ") ? (" + getChildren().get(1).getExpression(lang) +
				") : (" + getChildren().get(2).getExpression(lang) + ")";
	}

	@Override
	public OptionalInt upperBound() {
		OptionalInt l = getChildren().get(1).upperBound();
		OptionalInt r = getChildren().get(2).upperBound();
		if (l.isPresent() && r.isPresent()) {
			return OptionalInt.of(Math.max(l.getAsInt(), r.getAsInt()));
		}

		return OptionalInt.empty();
	}

	@Override
	public Expression simplify(KernelSeriesProvider provider) {
		Expression<?> flat = super.simplify(provider);
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

		if (enableKernelSimplification && provider != null) {
			OptionalInt max = provider.getMaximumLength();
			int seq[] = max.isPresent() ? condition.booleanSeq(max.getAsInt()) : null;
			Expression exp = seq == null ? null : provider.getSeries(seq);

			if (exp != null) {
				if (rd.isPresent() && rd.getAsDouble() == 0) {
					return exp.multiply(positive);
				} else if (ld.isPresent() && ld.getAsDouble() == 0) {
					return exp.add(1).imod(2).multiply(negative);
				} else {
					return exp.multiply(positive).add(exp.add(1).imod(2).multiply(negative));
				}
			}
		}

		return Conditional.create(condition, positive, negative);
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 3) throw new UnsupportedOperationException();
		return Conditional.create((Expression<Boolean>) children.get(0),
				(Expression<Double>) children.get(1),
				(Expression<Double>) children.get(2));
	}

	public static Conditional create(Expression<Boolean> condition, Expression<Double> positive, Expression<Double> negative) {
		OptionalDouble rd = negative.doubleValue();
		if (rd.isPresent() && rd.getAsDouble() == 0.0) {
			return new Mask(condition, positive);
		}

		OptionalDouble ld = positive.doubleValue();
		if (ld.isPresent() && ld.getAsDouble() == 0.0) {
			return new Mask(condition.not(), negative);
		}

		return new Conditional(condition, positive, negative);
	}
}
