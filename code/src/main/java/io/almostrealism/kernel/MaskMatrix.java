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

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;

import java.util.Optional;
import java.util.OptionalInt;

public class MaskMatrix<T> extends ExpressionMatrix<T> {
	private ExpressionMatrix<?> mask;
	private ExpressionMatrix<T> expression;

	public MaskMatrix(Index row, Index col,
					  ExpressionMatrix<?> mask,
					  ExpressionMatrix<T> expression) {
		super(row, col);
		this.mask = mask;
		this.expression = expression;
	}

	@Override
	public Expression<T> valueAt(int i, int j) {
		Expression m = mask.valueAt(i, j);

		Optional<Boolean> b = m.booleanValue();
		if (b.isPresent()) {
			return b.get() ? expression.valueAt(i, j) : (Expression) new IntegerConstant(0);
		}

		OptionalInt v = m.intValue();
		if (v.isPresent()) {
			switch (v.getAsInt()) {
				case 0:
					return (Expression) new IntegerConstant(0);
				case 1:
					return expression.valueAt(i, j);
				default:
					throw new IllegalArgumentException();
			}
		}

		return Mask.of(m, expression.valueAt(i, j));
	}
}
