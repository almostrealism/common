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

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Represents the two-argument arctangent function atan2(y, x).
 *
 * <p>The atan2 function computes the angle (in radians) between the positive x-axis
 * and the point (x, y). Unlike the single-argument atan function, atan2 correctly
 * handles all four quadrants and produces results in the range [-PI, PI].</p>
 *
 * <p>This is commonly used in signal processing to compute the phase angle of
 * complex numbers, where y is the imaginary part and x is the real part.</p>
 *
 * @see java.lang.Math#atan2(double, double)
 */
public class Atan2 extends BinaryExpression<Double> {

	/**
	 * Constructs an Atan2 expression.
	 *
	 * @param y the y-coordinate (imaginary part for complex phase)
	 * @param x the x-coordinate (real part for complex phase)
	 */
	public Atan2(Expression<Double> y, Expression<Double> x) {
		super(Double.class, y, x);
	}

	/**
	 * Returns the y-coordinate expression (first argument).
	 *
	 * @return the y expression
	 */
	public Expression<Double> getY() {
		return (Expression<Double>) getLeft();
	}

	/**
	 * Returns the x-coordinate expression (second argument).
	 *
	 * @return the x expression
	 */
	public Expression<Double> getX() {
		return (Expression<Double>) getRight();
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return "atan2(" + getY().getExpression(lang) + ", " + getX().getExpression(lang) + ")";
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		// atan2 returns values in the range [-PI, PI]
		return OptionalLong.of(4); // ceil(PI)
	}

	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		return OptionalLong.of(-4); // floor(-PI)
	}

	@Override
	public boolean isPossiblyNegative() {
		return true; // atan2 can return negative values
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.atan2(children[0].doubleValue(), children[1].doubleValue());
	}

	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new IllegalArgumentException("Atan2 requires exactly 2 children");
		}
		return new Atan2((Expression<Double>) children.get(0), (Expression<Double>) children.get(1));
	}

	/**
	 * Factory method to create an Atan2 expression with constant folding.
	 *
	 * @param y the y-coordinate expression
	 * @param x the x-coordinate expression
	 * @return an Atan2 expression or a constant if both inputs are constants
	 */
	public static Expression<Double> of(Expression<Double> y, Expression<Double> x) {
		OptionalDouble yVal = y.doubleValue();
		OptionalDouble xVal = x.doubleValue();

		if (yVal.isPresent() && xVal.isPresent()) {
			return new DoubleConstant(Math.atan2(yVal.getAsDouble(), xVal.getAsDouble()));
		}

		return new Atan2(y, x);
	}
}
