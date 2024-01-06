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

public class Floor extends Expression<Double> {
	public Floor(Expression<Double> input) {
		super(Double.class, null, input);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		OptionalDouble v = getChildren().get(0).doubleValue();
		return v.isPresent() ? "floor(" + v.getAsDouble()+ ")" : "floor(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble v = getChildren().get(0).doubleValue();
		if (v.isPresent()) return OptionalDouble.of(Math.floor(v.getAsDouble()));
		return OptionalDouble.empty();
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		OptionalInt v = getChildren().get(0).upperBound(context);
		if (v.isPresent()) return v;
		return OptionalInt.empty();
	}

	@Override
	public boolean isKernelValue(IndexValues values) {
		return getChildren().get(0).isKernelValue(values);
	}

	@Override
	public Number kernelValue(IndexValues indexValues) {
		return Math.floor((double) getChildren().get(0).kernelValue(indexValues));
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.floor(children[0].doubleValue());
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Floor((Expression<Double>) children.get(0));
	}
}
