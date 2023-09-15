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

public class Exponent extends Expression<Double> {
	public Exponent(Expression<Double> base, Expression<Double> exponent) {
		super(Double.class, base, exponent);
	}

	@Override
	public String getExpression() {
		return "pow(" + getChildren().get(0).getExpression() + ", " + getChildren().get(1).getExpression() + ")";
	}

	@Override
	public String getWrappedExpression() { return getExpression(); }

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new Exponent((Expression<Double>) children.get(0), (Expression<Double>) children.get(1));
	}

	@Override
	public Expression<Double> simplify() {
		Expression<?> flat = super.simplify();
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
}
