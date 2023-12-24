/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Equals extends Comparison {
	public Equals(Expression<?> left, Expression<?> right) {
		super(left, right);
	}

	public String getExpression(LanguageOperations lang) {
		return "(" + getChildren().get(0).getExpression(lang) + ") == (" + getChildren().get(1).getExpression(lang) + ")";
	}

	@Override
	protected boolean compare(Number left, Number right) {
		return left.doubleValue() == right.doubleValue();
	}

	@Override
	protected int[] checkSingle(Expression left, Expression right, int len) {
		if (left instanceof KernelIndex) {
			OptionalInt i = right.intValue();
			OptionalDouble d = right.doubleValue();

			if (i.isPresent()) {
				int val = i.getAsInt();
				if (val >= 0 && val < len) {
					int seq[] = new int[len];
					seq[val] = 1;
					return seq;
				}
			} else if (d.isPresent()) {
				double val = d.getAsDouble();
				if (val == Math.floor(val)) {
					int seq[] = new int[len];
					seq[(int) val] = 1;
				}
			}
		}

		return null;
	}

	@Override
	public Expression<Boolean> generate(List<Expression<?>> children) {
		if (children.size() != 2) throw new UnsupportedOperationException();
		return new Equals(children.get(0), children.get(1));
	}
}
