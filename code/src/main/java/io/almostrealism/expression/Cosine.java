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
import java.util.OptionalInt;

public class Cosine extends Expression<Double> {
	public Cosine(Expression<Double> input) {
		super(Double.class, input);
	}

	public String getExpression(LanguageOperations lang) {
		return "cos(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		return OptionalInt.of(1);
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.cos(children[0].doubleValue());
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Cosine((Expression<Double>) children.get(0));
	}
}
