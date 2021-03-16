/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.algebra.expressions;

import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.ExpressionArray;
import io.almostrealism.code.expressions.MultiExpression;

import java.util.function.IntFunction;

public class PairExpression extends ExpressionArray<Double> {
	public PairExpression() {
		super(2);
	}

	public static PairExpression from(MultiExpression exp) {
		return from((IntFunction<Expression<Double>>) exp::getValue);
	}

	public static PairExpression from(IntFunction<Expression<Double>> exp) {
		PairExpression res = new PairExpression();
		res.set(0, exp.apply(0));
		res.set(1, exp.apply(1));
		return res;
	}
}
