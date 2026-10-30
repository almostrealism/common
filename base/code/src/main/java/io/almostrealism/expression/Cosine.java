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

import java.util.List;
import java.util.OptionalLong;

/**
 * A cosine expression applied to a single operand.
 *
 * <p>Generates code of the form {@code cos(input)}. The upper bound is always 1 since
 * {@code |cos(x)| <= 1}. If the operand is a constant, the value is folded at factory time.</p>
 */
public class Cosine extends UnaryMathFunction {
	/**
	 * Constructs a cosine expression for the given operand.
	 *
	 * @param input the angle operand
	 */
	protected Cosine(Expression<Double> input) {
		super(input);
	}

	/** {@inheritDoc} Returns 25, reflecting the cost of the {@code cos()} transcendental. */
	@Override
	public int getComputeCost() { return 25; }

	@Override
	protected String getFunctionName() { return "cos"; }

	@Override
	protected double applyToDouble(double operand) { return Math.cos(operand); }

	@Override
	protected Expression<Double> createNode(Expression<?> input) {
		return new Cosine((Expression<Double>) input);
	}

	@Override
	protected OptionalLong computeUpperBound(KernelStructureContext context) {
		return OptionalLong.of(1);
	}

	/** Chain rule: d/dx[cos(f)] = -sin(f) * df/dx */
	@Override
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		CollectionExpression<?> childDelta = getChildren().get(0).delta(target);
		Expression<?> negSin = Minus.of(Sine.of((Expression<Double>) getChildren().get(0)));
		CollectionExpression<?> cofactor = new ConstantCollectionExpression(target.getShape(), negSin);
		return product(target.getShape(), List.of(childDelta, cofactor));
	}

	/**
	 * Creates a cosine expression, folding constants where possible.
	 *
	 * <p>Non-FP operands are widened with {@link Expression#toDouble()} so the emitted
	 * {@code cos()} call always has a floating-point argument — Metal's {@code cos()}
	 * overloads are FP-only and an integer operand triggers a compile ambiguity.</p>
	 *
	 * @param input the angle operand
	 * @return a constant if the input is a known constant, otherwise a {@link Cosine}
	 */
	public static Expression<Double> of(Expression<Double> input) {
		return of(Math::cos, input, Cosine::new);
	}
}
