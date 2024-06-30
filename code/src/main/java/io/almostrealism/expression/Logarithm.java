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
import java.util.OptionalLong;

public class Logarithm extends Expression<Double> {

	protected Logarithm(Expression<Double> input) {
		super(Double.class, input);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return "log(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return OptionalLong.of(1);
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.log(children[0].doubleValue());
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Logarithm((Expression<Double>) children.get(0));
	}

	public static <T> Expression<T> of(Expression<Double> input) {
		if (input instanceof Exp) {
			return (Expression<T>) input.getChildren().get(0);
		}

		return (Expression<T>) new Logarithm(input);
	}
}
