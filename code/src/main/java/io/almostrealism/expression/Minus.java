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

package io.almostrealism.expression;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Minus extends UnaryExpression<Double> {
	public Minus(Expression<Double> value) {
		super(Double.class, "-", value);
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 1)  throw new UnsupportedOperationException();
		return new Minus((Expression<Double>) children.get(0));
	}

	@Override
	public OptionalInt intValue() {
		OptionalInt i = getChildren().get(0).intValue();
		if (i.isPresent()) return OptionalInt.of(i.getAsInt() * -1);
		return super.intValue();
	}

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble d = getChildren().get(0).doubleValue();
		if (d.isPresent()) return OptionalDouble.of(d.getAsDouble() * -1);
		return super.doubleValue();
	}
}
