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

import io.almostrealism.expression.ExpressionArray;

public class PairBankExpression extends ExpressionArray<Double> {
	public PairBankExpression(int count) {
		super(2 * count);
	}

	public void set(int index, PairExpression exp) {
		set(index * 2, exp.get(0));
		set(index * 2 + 1, exp.get(1));
	}
}
