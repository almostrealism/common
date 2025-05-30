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

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;

public class Tangent extends Expression<Double> {
	private boolean hyperbolic;

	protected Tangent(Expression<Double> input) {
		this(input, false);
	}

	protected Tangent(Expression<Double> input, boolean hyperbolic) {
		super(Double.class, input);
		this.hyperbolic = hyperbolic;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return (hyperbolic ? "tanh(" : "tan(") + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public Number evaluate(Number... children) {
		return hyperbolic ?
				Math.tanh(children[0].doubleValue()) :
				Math.tan(children[0].doubleValue());
	}

	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Tangent((Expression<Double>) children.get(0), hyperbolic);
	}

	@Override
	public boolean compare(Expression e) {
		return super.compare(e) && ((Tangent) e).hyperbolic == hyperbolic;
	}

	public static Expression<Double> of(Expression<Double> input) {
		return of(input, false);
	}

	public static Expression<Double> of(Expression<Double> input, boolean hyperbolic) {
		OptionalDouble d = input.doubleValue();

		if (d.isPresent()) {
			return new DoubleConstant(hyperbolic ? Math.tanh(d.getAsDouble()) : Math.tan(d.getAsDouble()));
		}

		return new Tangent(input, hyperbolic);
	}
}
