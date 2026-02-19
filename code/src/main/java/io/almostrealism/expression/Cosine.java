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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** The Cosine class. */
public class Cosine extends Expression<Double> {
	protected Cosine(Expression<Double> input) {
		super(Double.class, input);
	}

	/** Performs the getExpression operation. */
	public String getExpression(LanguageOperations lang) {
		return "cos(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return OptionalLong.of(1);
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.cos(children[0].doubleValue());
	}

	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Cosine((Expression<Double>) children.get(0));
	}

	/** Chain rule: d/dx[cos(f)] = -sin(f) * df/dx */
	@Override
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		CollectionExpression<?> childDelta = getChildren().get(0).delta(target);
		Expression<?> negSin = Minus.of(Sine.of((Expression<Double>) getChildren().get(0)));
		CollectionExpression<?> cofactor = new ConstantCollectionExpression(target.getShape(), negSin);
		return product(target.getShape(), List.of(childDelta, cofactor));
	}

	/** Performs the of operation. */
	public static Expression<Double> of(Expression<Double> input) {
		OptionalDouble d = input.doubleValue();

		if (d.isPresent()) {
			return new DoubleConstant(Math.cos(d.getAsDouble()));
		}

		return new Cosine(input);
	}
}
