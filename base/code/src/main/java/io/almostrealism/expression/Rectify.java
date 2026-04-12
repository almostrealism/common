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

/**
 * A rectified-linear (ReLU) expression that clamps negative values to zero.
 *
 * <p>Equivalent to {@code max(input, 0)}, implemented as a specialisation of {@link Max}.
 * The zero operand is typed as {@code double} or {@code int} depending on whether the
 * input is floating-point.</p>
 */
public class Rectify extends Max {
	/**
	 * Constructs a rectify expression for the given input.
	 *
	 * @param input the expression whose negative values are clamped to zero
	 */
	protected Rectify(Expression<? extends Number> input) {
		super(input, input.isFP() ? Constant.of(0.0) : Constant.of(0));
	}

	/**
	 * Creates a rectify expression, folding constants where possible.
	 *
	 * @param input the expression to rectify
	 * @return a constant if the input is a known constant, otherwise a {@link Rectify}
	 */
	public static Expression of(Expression input) {
		if (input.doubleValue().isPresent()) {
			return new DoubleConstant(Math.max(input.doubleValue().getAsDouble(), 0));
		}

		return new Rectify(input);
	}
}
