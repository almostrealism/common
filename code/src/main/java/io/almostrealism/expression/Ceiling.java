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

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Ceiling extends Expression<Double> {
	public Ceiling(Expression<Double> input) {
		super(Double.class, input);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		OptionalDouble v = getChildren().get(0).doubleValue();
		return v.isPresent() ? "ceil(" + v.getAsDouble() + ")" : "ceil(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble v = getChildren().get(0).doubleValue();
		if (v.isPresent()) return OptionalDouble.of(Math.ceil(v.getAsDouble()));
		return OptionalDouble.empty();
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalDouble v = doubleValue();
		if (v.isPresent()) return OptionalLong.of((long) Math.ceil(v.getAsDouble()));

		OptionalLong u = getChildren().get(0).upperBound(context);
		if (u.isPresent()) return OptionalLong.of((long) Math.ceil(u.getAsLong()));

		return OptionalLong.empty();
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.ceil(children[0].doubleValue());
	}

	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Ceiling((Expression<Double>) children.get(0));
	}
}
