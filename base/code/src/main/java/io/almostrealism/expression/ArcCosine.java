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

import java.util.OptionalLong;

/**
 * An arc-cosine (inverse cosine) expression applied to a single operand.
 *
 * <p>Generates code of the form {@code acos(input)}. The operand is defined on
 * {@code [-1, 1]} and the result lies in {@code [0, pi]}, so the upper bound is 4
 * ({@code pi < 4}). If the operand is a constant, the value is folded at factory time.</p>
 */
public class ArcCosine extends UnaryMathFunction {
	/**
	 * Constructs an arc-cosine expression for the given operand.
	 *
	 * @param input the operand, defined on {@code [-1, 1]}
	 */
	protected ArcCosine(Expression<Double> input) {
		super(input);
	}

	/** {@inheritDoc} Returns 25, reflecting the cost of the {@code acos()} transcendental. */
	@Override
	public int getComputeCost() { return 25; }

	@Override
	protected String getFunctionName() { return "acos"; }

	@Override
	protected double applyToDouble(double operand) { return Math.acos(operand); }

	@Override
	protected Expression<Double> createNode(Expression<?> input) {
		return new ArcCosine((Expression<Double>) input);
	}

	@Override
	protected OptionalLong computeUpperBound(KernelStructureContext context) {
		return OptionalLong.of(4);
	}

	/** Chain rule: d/dx[acos(f)] = -df/dx / sqrt(1 - f*f) */
	@Override
	public CollectionExpression delta(CollectionExpression target) {
		Expression<Double> in = (Expression<Double>) getChildren().get(0);
		CollectionExpression childDelta = in.delta(target);

		Expression<Double> radicand = (Expression<Double>) new DoubleConstant(1.0)
				.subtract(in.multiply(in));
		Expression<?> negDenominator = Minus.of(radicand.pow(new DoubleConstant(0.5)));
		CollectionExpression denominator =
				new ConstantCollectionExpression(target.getShape(), negDenominator);

		return quotient(target.getShape(), childDelta, denominator);
	}

	/**
	 * Creates an arc-cosine expression, folding constants where possible.
	 *
	 * <p>Non-FP operands are widened with {@link Expression#toDouble()} so the emitted
	 * {@code acos()} call always has a floating-point argument — Metal's {@code acos()}
	 * overloads are FP-only and an integer operand triggers a compile ambiguity.</p>
	 *
	 * @param input the operand, defined on {@code [-1, 1]}
	 * @return a constant if the input is a known constant, otherwise an {@link ArcCosine}
	 */
	public static Expression<Double> of(Expression<Double> input) {
		return of(Math::acos, input, ArcCosine::new);
	}
}
