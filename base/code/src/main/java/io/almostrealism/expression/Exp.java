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
 * An exponential expression that computes {@code e^x} for a single operand.
 *
 * <p>Generates code of the form {@code exp(input)}. The derivative of {@code exp(x)}
 * is {@code exp(x)} itself, so the delta pass returns a product of the operand's delta
 * and this expression.</p>
 */
public class Exp extends UnaryMathFunction {
	/**
	 * Constructs an exponential expression for the given operand.
	 *
	 * @param input the exponent operand
	 */
	protected Exp(Expression<Double> input) {
		super(input);
	}

	/** {@inheritDoc} Returns 15, reflecting the cost of the {@code exp()} library call. */
	@Override
	public int getComputeCost() { return 15; }

	@Override
	protected String getFunctionName() { return "exp"; }

	@Override
	protected double applyToDouble(double operand) { return Math.exp(operand); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Rebuilds through {@link #of(Expression)} so that a constant operand folds, matching
	 * the factory rather than merely reconstructing the node.</p>
	 */
	@Override
	protected Expression<Double> createNode(Expression<?> input) {
		return Exp.<Double>of(input);
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	protected OptionalLong computeUpperBound(KernelStructureContext context) {
		OptionalLong v = getChildren().get(0).upperBound(context);
		if (v.isPresent()) {
			return OptionalLong.of((long) Math.ceil(Math.exp(v.getAsLong())));
		}

		return OptionalLong.empty();
	}

	@Override
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		CollectionExpression<?> delta = getChildren().get(0).delta(target);
		CollectionExpression<?> exp = new ConstantCollectionExpression(target.getShape(), this);
		return product(target.getShape(), List.of(delta, exp));
	}

	/**
	 * Creates an exponential expression for the given operand.
	 *
	 * <p>Folds constants where possible. Non-FP operands are widened with
	 * {@link Expression#toDouble()} so the emitted {@code exp()} call always
	 * has a floating-point argument — Metal's {@code exp()} overloads are
	 * FP-only and an integer operand triggers a compile ambiguity.</p>
	 *
	 * @param input the exponent operand
	 * @param <T>   the result type (always {@link Double})
	 * @return a constant if the input has a known double value, otherwise a new {@link Exp}
	 */
	public static <T> Expression<T> of(Expression input) {
		return (Expression<T>) of(Math::exp, (Expression<Double>) input, Exp::new);
	}
}
