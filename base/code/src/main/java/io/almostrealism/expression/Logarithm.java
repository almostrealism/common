/*
 * Copyright 2026 Michael Murray
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
import java.util.OptionalLong;

/**
 * A natural logarithm expression that computes {@code log(input)}.
 *
 * <p>Generates code of the form {@code log(input)}. The factory method cancels
 * {@code log(exp(x))} back to {@code x}. The derivative is {@code 1 / input}.</p>
 */
public class Logarithm extends Expression<Double> {

	/**
	 * Constructs a natural logarithm expression for the given operand.
	 *
	 * @param input the operand whose logarithm is computed
	 */
	protected Logarithm(Expression<Double> input) {
		super(Double.class, input);
	}

	/** {@inheritDoc} Returns 15, reflecting the cost of the {@code log()} library call. */
	@Override
	public int getComputeCost() { return 15; }

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
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Logarithm((Expression<Double>) children.get(0));
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		Expression<?> in = getChildren().get(0);
		CollectionExpression delta = in.delta(target);
		CollectionExpression u = new ConstantCollectionExpression(target.getShape(), in);
		return quotient(target.getShape(), delta, u);
	}

	/**
	 * Creates a natural logarithm expression, cancelling {@code log(exp(x))} to {@code x}.
	 *
	 * @param input the operand
	 * @param <T>   the result type (always {@link Double})
	 * @return the simplified expression or a new {@link Logarithm}
	 */
	public static <T> Expression<T> of(Expression input) {
		if (input instanceof Exp) {
			return (Expression<T>) input.getChildren().get(0);
		}

		return (Expression<T>) new Logarithm(input);
	}
}
