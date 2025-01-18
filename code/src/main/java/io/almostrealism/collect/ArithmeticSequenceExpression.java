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

package io.almostrealism.collect;

import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;

public class ArithmeticSequenceExpression extends CollectionExpressionAdapter {
	private double initial;
	private double rate;


	public ArithmeticSequenceExpression(TraversalPolicy shape) {
		this(shape, 0, 1);
	}

	public ArithmeticSequenceExpression(TraversalPolicy shape, double rate) {
		this(shape, 0, rate);
	}

	public ArithmeticSequenceExpression(TraversalPolicy shape, double initial, double rate) {
		super(null, shape);
		this.initial = initial;
		this.rate = rate;
	}

	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		return (Expression) e(initial).add(e(rate).multiply(index));
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return null;
	}

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		if (initial == 0 && rate == 0)
			return new BooleanConstant(false);

		double n = -initial / rate;
		if (n == Math.floor(n)) {
			return index.eq(new IntegerConstant((int) n));
		}

		return new BooleanConstant(true);
	}
}
