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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;

/**
 * A {@link ConditionalExpressionBase} that computes the element-wise product of two
 * complex number collections. Complex numbers are represented in interleaved format
 * where consecutive pairs of values represent (real, imaginary) components.
 *
 * <p>For complex numbers (p + qi) and (r + si), this expression computes:</p>
 * <ul>
 *   <li>Real part: pr - qs</li>
 *   <li>Imaginary part: ps + qr</li>
 * </ul>
 *
 * <p>The expression uses {@link UniformBinaryGroupExpression} to group pairs of values
 * and apply the appropriate complex multiplication formula based on whether the
 * target index corresponds to a real (even index) or imaginary (odd index) component.</p>
 *
 * @see ConditionalExpressionBase
 * @see UniformBinaryGroupExpression
 */
public class ComplexProductExpression extends ConditionalExpressionBase {

	/**
	 * Constructs a new complex product expression for the given shape and input expressions.
	 *
	 * @param shape the {@link TraversalPolicy} defining the shape of the result,
	 *              where the total size should be even (pairs of real/imaginary values)
	 * @param a     the first complex number collection (interleaved real/imaginary)
	 * @param b     the second complex number collection (interleaved real/imaginary)
	 */
	public ComplexProductExpression(TraversalPolicy shape,
									TraversableExpression a,
									TraversableExpression b) {
		super(shape, new ArithmeticSequenceExpression(shape),
				realPart(shape, a, b), imagPart(shape, a, b));
	}

	/**
	 * Creates an expression for computing the real part of complex multiplication.
	 * For inputs (p, q) from the first complex number and (r, s) from the second,
	 * computes pr - qs.
	 *
	 * @param shape the shape of the result collection
	 * @param a     the first complex number collection
	 * @param b     the second complex number collection
	 * @return a binary group expression that computes the real part
	 */
	private static UniformBinaryGroupExpression realPart(TraversalPolicy shape,
														 TraversableExpression a,
														 TraversableExpression b) {
		return new UniformBinaryGroupExpression("realPart", shape, a, b, (left, right) -> {
				Expression p = left[0]; Expression q = left[1];
				Expression r = right[0]; Expression s = right[1];
				return Sum.of(Product.of(p, r), Minus.of(Product.of(q, s)));
			},
				idx -> (Expression) idx.toInt().divide(2).multiply(2),
				idx -> (Expression) idx.toInt().divide(2).multiply(2).add(1));
	}

	/**
	 * Creates an expression for computing the imaginary part of complex multiplication.
	 * For inputs (p, q) from the first complex number and (r, s) from the second,
	 * computes ps + qr.
	 *
	 * @param shape the shape of the result collection
	 * @param a     the first complex number collection
	 * @param b     the second complex number collection
	 * @return a binary group expression that computes the imaginary part
	 */
	private static UniformBinaryGroupExpression imagPart(TraversalPolicy shape,
														 TraversableExpression a,
														 TraversableExpression b) {
		return new UniformBinaryGroupExpression("imagPart", shape, a, b, (left, right) -> {
				Expression p = left[0]; Expression q = left[1];
				Expression r = right[0]; Expression s = right[1];
				return Sum.of(Product.of(p, s), Product.of(q, r));
			},
				idx -> (Expression) idx.toInt().divide(2).multiply(2),
				idx -> (Expression) idx.toInt().divide(2).multiply(2).add(1));
	}
}
