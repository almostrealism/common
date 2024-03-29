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

import java.util.Optional;

public class Mask<T extends Number> extends Conditional<T> {
	protected Mask(Expression<Boolean> mask, Expression<T> value) {
		super(value.getType(), mask, (Expression) value, (Expression) new IntegerConstant(0));
	}

	public Expression<Boolean> getMask() { return (Expression<Boolean>) getChildren().get(0); }
	public Expression<Double> getMaskedValue() { return (Expression<Double>) getChildren().get(1); }

	@Override
	public boolean isMasked() { return true; }

	public static Expression<?> of(Expression<Boolean> mask, Expression<?> value) {
		Optional<Boolean> b = mask.booleanValue();

		if (b.isPresent()) {
			if (b.get()) {
				return value;
			} else {
				return new IntegerConstant(0);
			}
		} else {
			return new Mask(mask, value);
		}
	}
}
