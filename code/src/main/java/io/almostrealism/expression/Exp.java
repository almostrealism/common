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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalInt;

public class Exp extends Expression<Double> {
	public Exp(Expression<Double> input) {
		super(Double.class, input);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return "exp(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		OptionalInt v = getChildren().get(0).upperBound(context);
		if (v.isPresent()) {
			return OptionalInt.of((int) Math.ceil(Math.exp(v.getAsInt())));
		}

		return OptionalInt.empty();
	}

	public Number evaluate(Number... children) {
		return Math.exp(children[0].doubleValue());
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Exp((Expression<Double>) children.get(0));
	}

	@Override
	public CollectionExpression delta(TraversalPolicy shape, IndexedExpressionMatcher target) {
		CollectionExpression delta = getChildren().get(0).delta(shape, target);
		CollectionExpression exp = CollectionExpression.create(shape, this);
		return CollectionExpression.product(shape, List.of(delta, exp));
	}
}
